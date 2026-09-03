package io.jmix.cli.generator

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Project generation ported from Studio's ProjectCreator: evaluate `.globals`,
 * walk the template tree rendering paths and contents, generate messages_*
 * per locale, normalize gradlew, then optionally initialize and stage a Git repository.
 */
class ProjectGenerator(
    private val onWarning: (String) -> Unit = { System.err.println("Warning: $it") },
) {

    fun generate(templateRoot: Path, info: ProjectCreationInfo) {
        val binding = Bindings.createBinding(info)

        evaluateGlobals(templateRoot, binding)

        var failed = 0
        val allFiles = listTemplateFiles(templateRoot)
        for (file in allFiles) {
            if (isSkippedInMainPass(file)) continue
            if (!processFile(file, templateRoot, info.targetDir, binding)) failed++
        }

        failed += createLocaleMessages(allFiles, templateRoot, info, binding)

        if (failed > 0) {
            throw IOException("$failed file(s) could not be generated — the project is incomplete.")
        }

        setGradlewExecutable(info.projectDir)

        if (info.createGitRepository) {
            initGitRepository(info.projectDir)
        }
    }

    private fun evaluateGlobals(templateRoot: Path, binding: MutableMap<String, Any?>) {
        val globalsFile = templateRoot.resolve(GLOBALS)
        if (!Files.isRegularFile(globalsFile)) return
        val globalsMap = HashMap<String, Any?>()
        binding["globals"] = globalsMap
        // Rendered output is discarded; the script mutates the globals map.
        TemplateEngine.render(readUtf8(globalsFile), binding)
        binding.putAll(globalsMap)
    }

    private fun listTemplateFiles(templateRoot: Path): List<Path> =
        Files.walk(templateRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().toList()
        }

    private fun isSkippedInMainPass(file: Path): Boolean {
        val name = file.fileName.toString()
        return name == TEMPLATE_JSON || name == GLOBALS || name.startsWith(MESSAGES_PREFIX)
    }

    private fun processFile(
        file: Path,
        templateRoot: Path,
        targetDir: Path,
        binding: Map<String, Any?>,
    ): Boolean {
        try {
            // Paths are Groovy templates too (e.g. src/main/java/${project_rootPath}/...).
            val relativePath = relativeSlashPath(file, templateRoot)
            val processedName = TemplateEngine.render(relativePath, binding)
            val targetFile = targetDir.resolve(processedName)
            Files.createDirectories(targetFile.parent)

            if (isTemplateFile(relativePath)) {
                val content = TemplateEngine.render(readUtf8(file), binding)
                Files.writeString(targetFile, content)
            } else {
                Files.copy(file, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                if (relativePath == GRADLEW) {
                    val text = TemplateEngine.convertLineSeparators(readUtf8(targetFile))
                    Files.writeString(targetFile, text)
                }
            }
            return true
        } catch (e: IOException) {
            onWarning("Unable to process file $file: ${e.message}")
            return false
        }
    }

    /** Returns the number of locales whose messages file could not be generated. */
    private fun createLocaleMessages(
        allFiles: List<Path>,
        templateRoot: Path,
        info: ProjectCreationInfo,
        binding: MutableMap<String, Any?>,
    ): Int {
        val messagesTemplate = allFiles.lastOrNull {
            it.fileName.toString().startsWith(MESSAGES_PREFIX)
        } ?: return 0
        return info.locales.count { locale ->
            binding["current_locale"] = locale
            !processFile(messagesTemplate, templateRoot, info.targetDir, binding)
        }
    }

    private fun relativeSlashPath(file: Path, root: Path): String =
        root.relativize(file).toString().replace('\\', '/')

    // Studio's rule: a file is rendered unless its path ends with a skip-list entry.
    private fun isTemplateFile(path: String): Boolean =
        EXCLUDED_FROM_PROCESSING.none { path.endsWith(it) }

    private fun readUtf8(file: Path): String =
        String(Files.readAllBytes(file), StandardCharsets.UTF_8)

    private fun setGradlewExecutable(projectDir: Path) {
        val gradlew = projectDir.resolve(GRADLEW)
        if (!Files.exists(gradlew)) {
            onWarning("$gradlew does not exist")
            return
        }
        try {
            Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxrwxr-x"))
        } catch (e: IOException) {
            onWarning("Failed to set +x permission for $gradlew: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX file system (Windows) — nothing to do.
        }
    }

    private fun initGitRepository(projectDir: Path) {
        try {
            for (command in listOf(listOf("git", "init"), listOf("git", "add", "--all"))) {
                val process = ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    onWarning("Git command timed out in $projectDir: ${command.joinToString(" ")}")
                    return
                }
                if (process.exitValue() != 0) {
                    onWarning("Git command failed in $projectDir: ${command.joinToString(" ")}")
                    return
                }
            }
        } catch (e: Exception) {
            onWarning("Failed to prepare git repository in $projectDir: ${e.message}")
        }
    }

    companion object {
        const val TEMPLATE_JSON = "template.json"
        const val GLOBALS = ".globals"
        const val MESSAGES_PREFIX = "messages_"
        const val GRADLEW = "gradlew"

        // Studio's EXCLUDED_FROM_PROCESSING: copied verbatim, never rendered.
        val EXCLUDED_FROM_PROCESSING = listOf(
            ".jks", ".ico", ".png", ".jar", "gradlew", "gradlew.bat",
            "package.json", "package-lock.json", "webpack.config.js",
        )
    }
}

package io.jmix.cli.generator

import com.google.gson.Gson
import groovyjarjarasm.asm.AnnotationVisitor
import groovyjarjarasm.asm.ClassReader
import groovyjarjarasm.asm.ClassVisitor
import groovyjarjarasm.asm.Opcodes
import groovyjarjarasm.asm.Type
import io.jmix.cli.addon.AddonProjectProfile
import io.jmix.cli.env.EnvironmentCheck
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.util.PlatformVersions
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Configures selected add-ons before the generated project is staged in Git. */
object AddonInstaller {
    internal data class Artifact(val group: String, val name: String, val version: String, val file: String)
    internal data class Module(
        val className: String,
        val id: String,
        val dependsOn: List<String>,
        val artifact: String,
        val changelog: String?,
    )

    fun requireJavaHome(version: String): Path = EnvironmentCheck.checkJdk(version).compatible.firstOrNull()?.home
        ?: throw IOException("Installing add-ons requires a development JDK compatible with Jmix $version. " +
            "Install a supported JDK and set JAVA_HOME, or generate without --addons.")

    fun install(info: ProjectCreationInfo, buildFile: Path) {
        val javaHome = requireJavaHome(info.jmixVersion)
        appendDependencies(info, buildFile)
        val artifacts = try {
            resolveArtifacts(info.projectDir, buildFile.parent, javaHome)
        } catch (e: IOException) {
            val credentialsHint = if (info.addons.any { it.addon.commercial }) {
                "\nCommercial add-ons require premium repository access. Set premiumRepoUser and premiumRepoPass in " +
                    "~/.gradle/gradle.properties, or use ORG_GRADLE_PROJECT_premiumRepoUser and " +
                    "ORG_GRADLE_PROJECT_premiumRepoPass. See ${Bindings.PREMIUM_REPOSITORY_DOCS}"
            } else ""
            throw IOException("${e.message}$credentialsHint\nProject generation is incomplete; files remain at ${info.projectDir}. " +
                "Fix the error and regenerate in an empty directory, or use --force to overwrite the generated files.", e)
        }
        val modules = readModules(artifacts)
        if (Bindings.isAddonTemplate(info)) {
            configureModule(info, buildFile.parent, modules)
            configureTestSecurity(buildFile, modules)
        }
        configureChangelogs(info, buildFile.parent, modules, artifacts)
    }

    internal fun appendDependencies(info: ProjectCreationInfo, buildFile: Path) {
        val existing = AddonProjectProfile.dependencyCoordinates(Files.readString(buildFile))
        // Marketplace dependency versions come from the project's Jmix BOM, as in Studio.
        val additions = linkedMapOf<String, String>()
        for (addon in info.addons.filterNot { it.included }) {
            for (dependency in addon.dependencies) {
                if (dependency.coordinates in existing) continue
                val configuration = if (Bindings.isAddonTemplate(info) && dependency.group.startsWith("io.jmix")) "api"
                    else dependency.configuration
                additions.putIfAbsent(dependency.coordinates, configuration)
            }
        }
        if (additions.isEmpty()) return
        val declarations = additions.entries.joinToString("\n") { (coordinates, configuration) ->
            "$configuration '$coordinates'"
        }
        prependDependencies(buildFile, declarations)
    }

    private fun prependDependencies(buildFile: Path, declarations: String) {
        val text = Files.readString(buildFile)
        val block = Regex("(?m)^dependencies[ \\t]*\\{").find(text)
            ?: throw IOException("Cannot find the generated project's top-level dependencies block in $buildFile.")
        val offset = block.range.last + 1
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val insertion = newline + declarations.prependIndent("    ").replace("\n", newline) + newline
        Files.writeString(buildFile, text.replaceRange(offset, offset, insertion))
    }

    private fun resolveArtifacts(projectDir: Path, moduleDir: Path, javaHome: Path): List<Artifact> {
        val tempDir = Files.createTempDirectory("jmix-addons-")
        val script = tempDir.resolve("metadata.gradle")
        val output = tempDir.resolve("artifacts.json")
        val log = tempDir.resolve("gradle.log")
        try {
            AddonInstaller::class.java.getResourceAsStream("/io/jmix/cli/addon-metadata.gradle")!!.use { Files.copy(it, script) }
            val relative = projectDir.relativize(moduleDir).joinToString(":")
            val modulePath = if (relative.isEmpty()) ":" else ":$relative"
            val task = modulePath.trimEnd(':') + ":jmixCliResolveAddons"
            val command = ProjectLauncher.gradleCommand(task) + listOf("--console=plain", "--init-script", script.toString())
            val builder = ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            builder.environment().putAll(mapOf("JAVA_HOME" to javaHome.toString(), "JMIX_CLI_MODULE" to modulePath,
                "JMIX_CLI_METADATA" to output.toString()))
            val process = builder.start()
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
                throw IOException("Timed out resolving add-on dependencies. Check the project repositories and retry generation.")
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                val detail = Files.readAllLines(log).takeLast(25).joinToString("\n").take(3000)
                throw IOException("Cannot resolve selected add-ons with the generated Gradle wrapper. " +
                    "Check repository access and JDK configuration.\n$detail")
            }
            return Gson().fromJson(Files.readString(output), Array<Artifact>::class.java).toList()
        } finally {
            Files.walk(tempDir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    internal fun readModules(artifacts: List<Artifact>): List<Module> {
        val modules = mutableListOf<Module>()
        for (artifact in artifacts.filter { it.file.endsWith(".jar") }) {
            JarFile(artifact.file).use { jar ->
                for (entry in jar.entries()) {
                    if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/versions/")) continue
                    val reader = jar.getInputStream(entry).use { ClassReader(it) }
                    var module = false
                    var id = ""
                    val dependsOn = mutableListOf<String>()
                    reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                            if (descriptor != "Lio/jmix/core/annotation/JmixModule;") return null
                            module = true
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(name: String?, value: Any?) {
                                    if (name == "id") id = value as String
                                }
                                override fun visitArray(name: String): AnnotationVisitor? = if (name == "dependsOn") {
                                    object : AnnotationVisitor(Opcodes.ASM9) {
                                        override fun visit(name: String?, value: Any?) {
                                            if (value is Type) dependsOn.add(value.className)
                                        }
                                    }
                                } else null
                            }
                        }
                    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    if (module) {
                        val className = reader.className.replace('/', '.')
                        val moduleId = id.ifEmpty { className.substringBeforeLast('.') }
                        val root = moduleId.replace('.', '/') + "/liquibase/changelog.xml"
                        modules.add(Module(className, moduleId, dependsOn, "${artifact.group}:${artifact.name}",
                            root.takeIf { jar.getJarEntry(it) != null }))
                    }
                }
            }
        }
        return sortModules(modules.distinctBy { it.className })
    }

    internal fun sortModules(modules: List<Module>): List<Module> {
        val byClass = modules.associateBy { it.className }
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val result = mutableListOf<Module>()
        fun visit(module: Module) {
            if (module.className in visited) return
            if (!visiting.add(module.className)) throw IOException("Cyclic Jmix module dependency: ${module.className}")
            module.dependsOn.mapNotNull(byClass::get).forEach(::visit)
            visiting.remove(module.className)
            visited.add(module.className)
            result.add(module)
        }
        modules.sortedWith(compareBy({ !it.id.startsWith("io.jmix.") }, { it.id })).forEach(::visit)
        return result
    }

    internal fun configureModule(info: ProjectCreationInfo, moduleDir: Path, modules: List<Module>) {
        val selectedArtifacts = info.addons.filterNot { it.included }.flatMap { it.dependencies }
            .flatMap { listOf(it.coordinates, "${it.group}:${it.name.removeSuffix("-starter")}") }.toSet()
        val selected = modules.filter { it.artifact in selectedArtifacts }
        val redundant = selected.flatMap { it.dependsOn }.toSet()
        val required = selected.filterNot { it.className in redundant }.map { it.className }
        if (required.isEmpty()) return
        val source = moduleDir.resolve("src/main")
        val configs = Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
                .filter { Files.readString(it).contains("@JmixModule") }.toList()
        }
        if (configs.size != 1) throw IOException("Cannot identify the generated add-on's @JmixModule configuration.")
        val file = configs.single()
        val text = Files.readString(file)
        val kotlin = file.toString().endsWith(".kt")
        val annotation = Regex("@JmixModule\\s*\\(([^)]*)\\)").find(text)
            ?: throw IOException("Cannot update @JmixModule in $file")
        val args = annotation.groupValues[1]
        val depends = Regex("dependsOn\\s*=\\s*(\\{[^}]*}|\\[[^]]*]|[^,]+)").find(args)
            ?: throw IOException("Cannot update @JmixModule dependsOn in $file")
        val existing = depends.groupValues[1].trim().removeSurrounding("{", "}").removeSurrounding("[", "]")
        val additions = required.filterNot { name ->
            Regex("(?<![A-Za-z0-9_])${Regex.escape(name.substringAfterLast('.'))}\\s*(?:\\.class|::class)").containsMatchIn(existing)
        }.map { it + if (kotlin) "::class" else ".class" }
        if (additions.isEmpty()) return
        val values = (listOf(existing.trim()).filter(String::isNotEmpty) + additions).joinToString(", ")
        val replacement = "dependsOn = " + if (kotlin) "[$values]" else "{$values}"
        val updatedArgs = args.replaceRange(depends.range, replacement)
        Files.writeString(file, text.replaceRange(annotation.range, "@JmixModule($updatedArgs)"))
    }

    /** Add-ons such as Reports expect host security; generated tests have no application user repository. */
    internal fun configureTestSecurity(buildFile: Path, modules: List<Module>) {
        if (modules.none { it.className == "io.jmix.security.SecurityConfiguration" }) return
        val source = buildFile.parent.resolve("src/test")
        if (!Files.isDirectory(source)) return
        val files = Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
                .toList()
        }
        val config = files.singleOrNull { Files.readString(it).contains("@SpringBootConfiguration") }
            ?: throw IOException("Cannot identify the generated add-on's test configuration for Jmix Security.")
        val starter = "io.jmix.security:jmix-security-starter"
        if (starter !in AddonProjectProfile.dependencyCoordinates(Files.readString(buildFile))) {
            prependDependencies(buildFile, "// Security bootstrap for generated add-on tests\n" +
                "testImplementation '$starter'")
        }
        if (files.any { Files.readString(it).contains("UserRepository") }) return
        val text = Files.readString(config)
        val end = text.lastIndexOf('}')
        if (end < 0) throw IOException("Cannot add the test user repository to $config")
        val bean = if (config.toString().endsWith(".kt")) """

            @org.springframework.context.annotation.Bean
            open fun addonTestUserRepository(): io.jmix.core.security.UserRepository =
                io.jmix.core.security.InMemoryUserRepository()
        """.trimIndent() else """

            @org.springframework.context.annotation.Bean
            io.jmix.core.security.UserRepository addonTestUserRepository() {
                return new io.jmix.core.security.InMemoryUserRepository();
            }
        """.trimIndent()
        Files.writeString(config, text.substring(0, end) + "\n" + bean.prependIndent("    ") + "\n" + text.substring(end))
    }

    internal fun configureChangelogs(info: ProjectCreationInfo, moduleDir: Path, modules: List<Module>, artifacts: List<Artifact>) {
        if (PlatformVersions.compare(info.jmixVersion, "1.5.0") < 0) return
        val roots = modules.mapNotNull { it.changelog }.distinct()
        if (roots.isEmpty()) return
        val resourceCache = mutableMapOf<String, ByteArray?>()
        fun resource(path: String): ByteArray? = resourceCache.getOrPut(path) {
            artifacts.asSequence().filter { it.file.endsWith(".jar") }.mapNotNull { artifact ->
                JarFile(artifact.file).use { jar -> jar.getJarEntry(path)?.let { entry -> jar.getInputStream(entry).use { it.readBytes() } } }
            }.firstOrNull()
        }
        fun includes(path: String): List<Include> {
            if (!path.endsWith(".xml")) return emptyList()
            return resource(path)?.let { bytes ->
                try { xmlIncludes(bytes) } catch (e: IOException) {
                    throw IOException("Cannot read add-on changelog '$path': ${e.message}", e)
                }
            }.orEmpty()
        }
        for (test in listOf(false, true)) {
            val file = moduleDir.resolve("src/${if (test) "test" else "main"}/resources/${info.rootPath}/liquibase/changelog.xml")
            if (!Files.isRegularFile(file)) continue
            val text = Files.readString(file)
            val included = xmlIncludes(text.toByteArray())
            val addonMain = Bindings.isAddonTemplate(info) && !test
            fun Include.active(): Boolean = context.isBlank() ||
                if (addonMain) context == "@jmix-addon" else !context.contains('@')
            fun Include.pathFrom(parent: String): String = if (relative)
                Path.of(parent).parent.resolve(this.file).normalize().toString().replace('\\', '/')
                else this.file.trimStart('/')
            val covered = mutableSetOf<String>()
            fun collect(path: String, result: MutableSet<String>) {
                if (!result.add(path)) return
                includes(path).filter { it.active() }.forEach { child ->
                    collect(child.pathFrom(path), result)
                }
            }
            included.filter { it.active() }.forEach {
                collect(it.pathFrom("${info.rootPath}/liquibase/changelog.xml"), covered)
            }
            // A dependency root can include another module's root itself.
            val transitive = mutableSetOf<String>()
            for (root in roots) {
                includes(root).filter { it.active() }.forEach { child ->
                    collect(child.pathFrom(root), transitive)
                }
            }
            val additions = roots.filterNot { it in covered || it in transitive }
            if (additions.isEmpty()) continue
            val context = if (addonMain && PlatformVersions.compare(info.jmixVersion, "2.2.0") >= 0)
                " contextFilter=\"@jmix-addon\"" else ""
            val lines = additions.joinToString("\n", postfix = "\n\n") {
                val path = it.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                "    <include file=\"/$path\"$context/>"
            }
            val anchor = Regex("(?m)^\\s*<includeAll\\b").find(text)?.range?.first ?: text.indexOf("</databaseChangeLog>")
            if (anchor < 0) throw IOException("Cannot update Liquibase root $file")
            Files.writeString(file, text.substring(0, anchor) + "\n" + lines + text.substring(anchor))
        }
    }

    private data class Include(val file: String, val relative: Boolean, val context: String)

    private fun xmlIncludes(bytes: ByteArray): List<Include> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: SAXParseException) = throw e
            override fun fatalError(e: SAXParseException) = throw e
        })
        val document = try {
            builder.parse(ByteArrayInputStream(bytes))
        } catch (e: SAXException) {
            throw IOException("Invalid add-on Liquibase changelog: ${e.message}", e)
        }
        val nodes = document.getElementsByTagNameNS("*", "include")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            Include(element.getAttribute("file"), element.getAttribute("relativeToChangelogFile") == "true",
                element.getAttribute("contextFilter").ifEmpty { element.getAttribute("context") }.trim())
        }
    }
}

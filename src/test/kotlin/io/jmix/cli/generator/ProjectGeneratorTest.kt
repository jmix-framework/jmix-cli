package io.jmix.cli.generator

import io.jmix.cli.env.EnvironmentCheck
import io.jmix.cli.addon.AddonCatalog
import io.jmix.cli.addon.AddonCatalogTest
import io.jmix.cli.addon.AddonProjectProfile
import io.jmix.cli.template.TemplateMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProjectGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun writeTemplate(root: Path, relativePath: String, content: String) {
        val file = root.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun buildFixtureTemplate(): Path {
        val root = tempDir.resolve("template")
        writeTemplate(root, "template.json", """{"version":1,"name":"Fixture","order":1}""")
        writeTemplate(
            root, ".globals",
            "<%\nglobals[\"userTable\"] = (project_idPrefix == null || project_idPrefix.isEmpty()) " +
                "? \"USER_\" : project_idPrefix.toUpperCase() + \"_USER\"\n%>",
        )
        writeTemplate(root, "settings.gradle", "rootProject.name = '\${project_name}'\n")
        writeTemplate(root, "\${gitignore}", ".gradle\nbuild\n")
        writeTemplate(
            root,
            "src/main/java/\${project_rootPath}/\${project_classPrefix}App.java",
            "package \${project_rootPackage};\nclass \${project_classPrefix}App {} // table \${userTable}\n",
        )
        writeTemplate(
            root,
            "src/main/resources/\${project_rootPath}/messages_\${current_locale.code}.properties",
            "app.title=\${project_projectPrintableName}\n",
        )
        // Skip-list file: content must be copied verbatim, path still rendered.
        writeTemplate(root, "gradlew", "#!/bin/sh\r\necho \${not_a_binding}\r\n")
        return root
    }

    private fun generate(
        locales: List<JmixLocale> = listOf(JmixLocale("en", "English", true)),
        createGitRepository: Boolean = false,
        onStatus: (String) -> Unit = {},
        onWarning: (String) -> Unit = {},
        overwriteExisting: Boolean = true,
        installToolkit: (Path) -> Unit = {},
    ): Path {
        val target = tempDir.resolve("out")
        val info = ProjectCreationInfo(
            name = "jmix-project",
            targetDir = target,
            rootPackage = "com.company.jmixproject",
            projectId = "shp",
            locales = locales,
            jmixVersion = "3.0.1",
            templateMetadata = TemplateMetadata(),
            createGitRepository = createGitRepository,
        )
        ProjectGenerator(onWarning, onStatus).generate(buildFixtureTemplate(), info, overwriteExisting) {
            installToolkit(target)
        }
        return target
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `commercial resolution uses the selected build file and JDK before Git staging`(useSuppliedProfile: Boolean) {
        val details = listOf("Unknown property 'premiumRepoUser'", "Received status code 401: Unauthorized",
            "Received status code 403: Forbidden", "Could not resolve host global.repo.jmix.io")
        val template = buildFixtureTemplate()
        writeTemplate(template, "build.gradle", "repositories {\n" +
            "<% project_additionalRepositories.each { repository -> %>\${repository}\n<% } %>}\n" +
            "dependencies { implementation 'io.jmix.core:jmix-core-starter' }\n")
        val addonProfile = if (useSuppliedProfile) {
            val profile = checkNotNull(AddonProjectProfile.from(template))
            // This module would sort first if the generator scanned the template again.
            writeTemplate(template, "00.gradle", "dependencies { implementation 'io.jmix.core:jmix-core-starter' }\n")
            profile
        } else null
        writeTemplate(template, "gradlew",
            "#!/bin/sh\nprintf '%s\\n' \"\$JAVA_HOME\" \"${details.joinToString("\n")}\"\nexit 1\n")
        writeTemplate(template, "gradlew.bat", "@echo off\r\n" +
            "echo \"%JAVA_HOME%\"\r\n" + details.joinToString("\r\n") { "echo $it" } + "\r\nexit /b 1\r\n")
        val target = tempDir.resolve("failed-project")
        val info = ProjectCreationInfo(name = "demo", targetDir = target, rootPackage = "com.example.demo",
            jmixVersion = "3.0.1", templateMetadata = TemplateMetadata(), createGitRepository = true,
            addons = AddonCatalog.parse(AddonCatalogTest.CATALOG_JSON)
                .select(listOf("paid"), "3.0.1", AddonProjectProfile("build.gradle", emptySet())))
        val phases = mutableListOf<String>()
        val javaHome = tempDir.resolve("selected JDK")

        val error = assertThrows(IOException::class.java) {
            ProjectGenerator(onStatus = phases::add).generate(
                template, info, addonJavaHome = javaHome, addonProfile = addonProfile,
            )
        }

        val message = error.message!!
        assertTrue(message.contains(javaHome.toString()), message)
        details.forEach { assertTrue(message.contains(it), message) }
        assertTrue(message.contains("premiumRepoUser and premiumRepoPass"), message)
        assertTrue(message.contains("ORG_GRADLE_PROJECT_premiumRepoUser"), message)
        assertTrue(message.contains("https://docs.jmix.io/jmix/studio/subscription.html"), message)
        assertTrue(message.contains("generation is incomplete"), message)
        assertTrue(message.contains("--force"), message)
        assertFalse(Files.exists(target.resolve(".git")))
        assertFalse(phases.contains("Initializing the Git repository"))
        val build = Files.readString(target.resolve("build.gradle"))
        assertTrue(build.contains("// Commercial Jmix add-ons require a subscription, see " +
            "https://docs.jmix.io/jmix/studio/subscription.html#enterprise-subscription"))
        assertTrue(build.contains("url = 'https://global.repo.jmix.io/repository/premium'"))
        assertTrue(build.contains("username = rootProject['premiumRepoUser']"))
        assertTrue(build.contains("implementation 'demo:paid-starter'"))
        assertFalse(build.contains("demo:paid-starter:"))
    }

    @Test
    fun `renders a complete project with Studio bindings locales and executable wrapper`() {
        val phases = mutableListOf<String>()
        val target = generate(listOf(JmixLocale("en", "English", true), JmixLocale("de", "German")), onStatus = phases::add)

        assertEquals(listOf("Rendering the project files"), phases)
        assertEquals("rootProject.name = 'jmix-project'\n", Files.readString(target.resolve("settings.gradle")))
        val app = Files.readString(target.resolve("src/main/java/com/company/jmixproject/JmixProjectApp.java"))
        assertEquals("package com.company.jmixproject;\nclass JmixProjectApp {} // table SHP_USER\n", app)
        assertTrue(Files.exists(target.resolve(".gitignore")))
        assertFalse(Files.exists(target.resolve("\${gitignore}")))
        assertFalse(Files.exists(target.resolve("template.json")))
        assertFalse(Files.exists(target.resolve(".globals")))

        val dir = target.resolve("src/main/resources/com/company/jmixproject")
        assertEquals("app.title=Jmix Project\n", Files.readString(dir.resolve("messages_en.properties")))
        assertEquals("app.title=Jmix Project\n", Files.readString(dir.resolve("messages_de.properties")))

        val gradlew = target.resolve("gradlew")
        // Content NOT rendered (unknown binding untouched), but CRLF normalized to LF.
        assertEquals("#!/bin/sh\necho \${not_a_binding}\n", Files.readString(gradlew))
        if (isPosix(gradlew)) {
            assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(gradlew))
        }
    }

    @Test
    fun `git repository stages generated files and toolkit files together`() {
        Assumptions.assumeTrue(EnvironmentCheck.isGitAvailable())
        val phases = mutableListOf<String>()
        val target = generate(createGitRepository = true, onStatus = phases::add) { projectDir ->
            assertTrue(Files.isRegularFile(projectDir.resolve("settings.gradle")))
            assertFalse(Files.exists(projectDir.resolve(".git")))
            phases.add("Installing the Agent Toolkit")
            writeTemplate(projectDir, "AGENTS.md", "Project guidelines\n")
            writeTemplate(projectDir, ".agents/skills/jmix/SKILL.md", "Jmix skill\n")
        }
        assertEquals(listOf("Rendering the project files", "Installing the Agent Toolkit", "Initializing the Git repository"), phases)

        val process = ProcessBuilder("git", "diff", "--cached", "--name-only")
            .directory(target.toFile())
            .redirectErrorStream(true)
            .start()
        val staged = process.inputStream.bufferedReader().readLines().toSet()
        assertEquals(0, process.waitFor())

        val generated = Files.walk(target).use { files ->
            files.filter { Files.isRegularFile(it) && !it.startsWith(target.resolve(".git")) }
                .map { target.relativize(it).toString().replace('\\', '/') }
                .toList()
                .toSet()
        }
        assertEquals(generated, staged)
    }

    @Test
    fun `generation into an existing repository preserves its index`() {
        Assumptions.assumeTrue(EnvironmentCheck.isGitAvailable())
        val target = Files.createDirectories(tempDir.resolve("out"))
        git(target, "init", "--quiet")
        val notes = target.resolve("notes.txt")
        Files.writeString(notes, "staged\n")
        git(target, "add", "notes.txt")
        Files.writeString(notes, "edited\n")
        val warnings = mutableListOf<String>()

        generate(createGitRepository = true, onWarning = warnings::add)

        assertEquals("staged\n", git(target, "show", ":notes.txt"))
        assertEquals("edited\n", Files.readString(notes))
        assertEquals("notes.txt\n", git(target, "diff", "--cached", "--name-only"))
        assertTrue(warnings.any { it.contains("Skipping Git setup") })
        assertTrue(Files.isRegularFile(target.resolve("settings.gradle")))
    }

    @Test
    fun `generation inside a Git work tree skips nested Git setup`() {
        Assumptions.assumeTrue(EnvironmentCheck.isGitAvailable())
        git(tempDir, "init", "--quiet")
        val warnings = mutableListOf<String>()

        val target = generate(createGitRepository = true, onWarning = warnings::add)

        assertFalse(Files.exists(target.resolve(".git")))
        assertEquals("", git(tempDir, "diff", "--cached", "--name-only"))
        assertTrue(warnings.any { it.contains("inside an existing Git repository") })
    }

    @Test
    fun `generation rechecks a destination before writing when overwrite is not approved`() {
        val target = Files.createDirectories(tempDir.resolve("out"))
        val settings = Files.writeString(target.resolve("settings.gradle"), "original\n")

        val error = assertThrows(IOException::class.java) { generate(overwriteExisting = false) }

        assertTrue(error.message.orEmpty().contains("not empty"))
        assertEquals("original\n", Files.readString(settings))
        assertFalse(Files.exists(target.resolve("src")))
    }

    @Test
    fun `output validation requires paths within the destination`() {
        val target = tempDir.resolve("out")
        val output = target.resolve("src/Example.java")

        assertEquals(output, checkedProjectOutput(target, output))
        assertThrows(IOException::class.java) { checkedProjectOutput(target, tempDir.resolve("another-project")) }
    }

    @Test
    fun `output validation rejects linked directories even inside the destination`() {
        Assumptions.assumeTrue(isPosix(tempDir))
        val target = Files.createDirectories(tempDir.resolve("out"))
        val actual = Files.createDirectory(target.resolve("source"))
        val link = Files.createSymbolicLink(target.resolve("src"), actual)

        assertThrows(IOException::class.java) { checkedProjectOutput(target, link.resolve("Example.java")) }
    }

    @Test
    fun `toolkit files are generated when Git is disabled`() {
        val target = generate { projectDir -> writeTemplate(projectDir, "AGENTS.md", "Project guidelines\n") }

        assertTrue(Files.isRegularFile(target.resolve("AGENTS.md")))
        assertFalse(Files.exists(target.resolve(".git")))
    }

    @Test
    fun `generation fails when files cannot be written`() {
        // The read-only trap needs POSIX permissions; on Windows a read-only
        // directory does not prevent file creation.
        Assumptions.assumeTrue(isPosix(tempDir))
        val target = tempDir.resolve("readonly-out")
        Files.createDirectories(target)
        Files.setPosixFilePermissions(
            target,
            java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"),
        )
        val info = ProjectCreationInfo(
            name = "jmix-project",
            targetDir = target,
            rootPackage = "com.company.jmixproject",
            jmixVersion = "3.0.1",
            templateMetadata = TemplateMetadata(),
            createGitRepository = false,
        )
        try {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
                ProjectGenerator(onWarning = {}).generate(buildFixtureTemplate(), info)
            }
        } finally {
            Files.setPosixFilePermissions(
                target,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
            )
        }
    }

    private fun isPosix(path: Path): Boolean =
        "posix" in path.fileSystem.supportedFileAttributeViews()

    private fun git(projectDir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
        return output
    }
}

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
        ProjectGenerator(onWarning = {}, onStatus = onStatus).generate(buildFixtureTemplate(), info)
        return target
    }

    @Test
    fun `generation names its phases before each starts`() {
        val phases = mutableListOf<String>()
        generate(onStatus = phases::add)
        assertEquals(listOf("Rendering the project files"), phases)

        Assumptions.assumeTrue(EnvironmentCheck.isGitAvailable())
        phases.clear()
        generate(createGitRepository = true, onStatus = phases::add)
        assertEquals(listOf("Rendering the project files", "Initializing the Git repository"), phases)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Unknown property 'premiumRepoUser'", "Received status code 401: Unauthorized",
        "Received status code 403: Forbidden", "Could not resolve host global.repo.jmix.io"])
    fun `commercial resolution failures leave rendered configuration and stop before Git staging`(detail: String) {
        val template = buildFixtureTemplate()
        writeTemplate(template, "build.gradle", "repositories {\n" +
            "<% project_additionalRepositories.each { repository -> %>\${repository}\n<% } %>}\n" +
            "dependencies { implementation 'io.jmix.core:jmix-core-starter' }\n")
        writeTemplate(template, "gradlew", "#!/bin/sh\nprintf '%s\\n' \"$detail\"\nexit 1\n")
        writeTemplate(template, "gradlew.bat", "@echo off\r\necho $detail\r\nexit /b 1\r\n")
        val target = tempDir.resolve("failed-project")
        val info = ProjectCreationInfo(name = "demo", targetDir = target, rootPackage = "com.example.demo",
            jmixVersion = "3.0.1", templateMetadata = TemplateMetadata(), createGitRepository = true,
            addons = AddonCatalog.parse(AddonCatalogTest.CATALOG_JSON)
                .select(listOf("paid"), "3.0.1", AddonProjectProfile("build.gradle", emptySet())))
        val phases = mutableListOf<String>()

        val error = assertThrows(IOException::class.java) {
            ProjectGenerator(onStatus = phases::add).generate(template, info)
        }

        val message = error.message!!
        assertTrue(message.contains(detail), message)
        assertTrue(message.contains("premiumRepoUser and premiumRepoPass"), message)
        assertTrue(message.contains("ORG_GRADLE_PROJECT_premiumRepoUser"), message)
        assertTrue(message.contains("https://docs.jmix.io/jmix/studio/subscription.html"), message)
        assertTrue(message.contains("generation is incomplete"), message)
        assertTrue(message.contains("--force"), message)
        assertFalse(Files.exists(target.resolve(".git")))
        assertFalse(phases.contains("Initializing the Git repository"))
        val build = Files.readString(target.resolve("build.gradle"))
        assertTrue(build.contains("// Commercial Jmix add-ons require a license"))
        assertTrue(build.contains("url = 'https://global.repo.jmix.io/repository/premium'"))
        assertTrue(build.contains("username = rootProject['premiumRepoUser']"))
        assertTrue(build.contains("implementation 'demo:paid-starter'"))
        assertFalse(build.contains("demo:paid-starter:"))
    }

    @Test
    fun `renders paths and contents, honors globals`() {
        val target = generate()

        assertEquals("rootProject.name = 'jmix-project'\n", Files.readString(target.resolve("settings.gradle")))
        val app = Files.readString(target.resolve("src/main/java/com/company/jmixproject/JmixProjectApp.java"))
        assertEquals("package com.company.jmixproject;\nclass JmixProjectApp {} // table SHP_USER\n", app)
    }

    @Test
    fun `gitignore path binding produces dotfile`() {
        val target = generate()
        assertTrue(Files.exists(target.resolve(".gitignore")))
        assertFalse(Files.exists(target.resolve("\${gitignore}")))
    }

    @Test
    fun `git repository stages every generated file`() {
        Assumptions.assumeTrue(EnvironmentCheck.isGitAvailable())
        val target = generate(createGitRepository = true)

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
    fun `template json and globals are not emitted`() {
        val target = generate()
        assertFalse(Files.exists(target.resolve("template.json")))
        assertFalse(Files.exists(target.resolve(".globals")))
    }

    @Test
    fun `messages file generated once per locale`() {
        val target = generate(
            listOf(JmixLocale("en", "English", true), JmixLocale("de", "German")),
        )
        val dir = target.resolve("src/main/resources/com/company/jmixproject")
        assertEquals("app.title=Jmix Project\n", Files.readString(dir.resolve("messages_en.properties")))
        assertEquals("app.title=Jmix Project\n", Files.readString(dir.resolve("messages_de.properties")))
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

    @Test
    fun `skip-list files copied verbatim except gradlew line endings, gradlew is executable`() {
        val target = generate()
        val gradlew = target.resolve("gradlew")
        // Content NOT rendered (unknown binding untouched), but CRLF normalized to LF.
        assertEquals("#!/bin/sh\necho \${not_a_binding}\n", Files.readString(gradlew))
        // The generator sets the executable bit only on POSIX file systems.
        if (isPosix(gradlew)) {
            assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(gradlew))
        }
    }

    private fun isPosix(path: Path): Boolean =
        "posix" in path.fileSystem.supportedFileAttributeViews()
}

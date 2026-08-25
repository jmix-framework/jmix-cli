package io.jmix.cli.generator

import io.jmix.cli.template.TemplateMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    private fun generate(locales: List<JmixLocale> = listOf(JmixLocale("en", "English", true))): Path {
        val target = tempDir.resolve("out")
        val info = ProjectCreationInfo(
            name = "jmix-project",
            targetDir = target,
            rootPackage = "com.company.jmixproject",
            projectId = "shp",
            locales = locales,
            jmixVersion = "3.0.1",
            templateMetadata = TemplateMetadata(),
            createGitRepository = false,
        )
        ProjectGenerator(onWarning = {}).generate(buildFixtureTemplate(), info)
        return target
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

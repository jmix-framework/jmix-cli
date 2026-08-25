package io.jmix.cli.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectLauncherTest {

    private val projectDir = Path.of("/work/demo")

    @Test
    fun `gradle command uses batch wrapper on windows`() {
        assertEquals(
            listOf("cmd", "/c", "gradlew.bat", "bootRun"),
            ProjectLauncher.gradleCommand("bootRun", os = "Windows 11"),
        )
    }

    @Test
    fun `gradle command uses shell wrapper elsewhere`() {
        assertEquals(listOf("./gradlew", "bootRun"), ProjectLauncher.gradleCommand("bootRun", os = "Linux"))
        assertEquals(listOf("./gradlew", "build"), ProjectLauncher.gradleCommand("build", os = "Mac OS X"))
    }

    @Test
    fun `file manager command depends on os`() {
        assertEquals(listOf("open", "$projectDir"), ProjectLauncher.fileManagerCommand(projectDir, os = "Mac OS X"))
        assertEquals(listOf("explorer.exe", "$projectDir"), ProjectLauncher.fileManagerCommand(projectDir, os = "Windows 11"))
        assertEquals(listOf("xdg-open", "$projectDir"), ProjectLauncher.fileManagerCommand(projectDir, os = "Linux"))
    }

    @Test
    fun `browser command depends on os`() {
        val url = "http://localhost:8080"
        assertEquals(listOf("open", url), ProjectLauncher.browserCommand(url, os = "Mac OS X"))
        assertEquals(
            listOf("rundll32", "url.dll,FileProtocolHandler", url),
            ProjectLauncher.browserCommand(url, os = "Windows 11"),
        )
        assertEquals(listOf("xdg-open", url), ProjectLauncher.browserCommand(url, os = "Linux"))
    }

    @Test
    fun `server port read from application properties`(@TempDir tempDir: Path) {
        val resources = Files.createDirectories(tempDir.resolve("src/main/resources"))
        Files.writeString(
            resources.resolve("application.properties"),
            "#server.port = 1111\nmain.datasource.username=sa\nserver.port = 9090\n",
        )

        assertEquals(9090, ProjectLauncher.serverPort(tempDir))
    }

    @Test
    fun `server port defaults to 8080 without the property`(@TempDir tempDir: Path) {
        assertEquals(8080, ProjectLauncher.serverPort(tempDir))

        val resources = Files.createDirectories(tempDir.resolve("src/main/resources"))
        Files.writeString(resources.resolve("application.properties"), "main.datasource.username=sa\n")
        assertEquals(8080, ProjectLauncher.serverPort(tempDir))
    }

    @Test
    fun `finds idea launcher on path`(@TempDir tempDir: Path) {
        val launcher = tempDir.resolve("idea")
        Files.createFile(launcher)
        launcher.toFile().setExecutable(true)

        assertEquals(launcher, ProjectLauncher.ideaLauncherOnPath(os = "Linux", pathEnv = tempDir.toString()))
    }

    @Test
    fun `finds windows idea launcher by extension`(@TempDir tempDir: Path) {
        val launcher = tempDir.resolve("idea.cmd")
        Files.createFile(launcher)
        launcher.toFile().setExecutable(true)

        assertEquals(launcher, ProjectLauncher.ideaLauncherOnPath(os = "Windows 11", pathEnv = tempDir.toString()))
    }

    @Test
    fun `returns null when path has no idea launcher`(@TempDir tempDir: Path) {
        assertNull(ProjectLauncher.ideaLauncherOnPath(os = "Linux", pathEnv = tempDir.toString()))
        assertNull(ProjectLauncher.ideaLauncherOnPath(os = "Linux", pathEnv = null))
    }

    @Test
    fun `finds mac idea application bundle`(@TempDir tempDir: Path) {
        val app = tempDir.resolve("IntelliJ IDEA CE.app")
        Files.createDirectory(app)

        assertEquals(app, ProjectLauncher.macIdeaApp(listOf(tempDir)))

        val open = ProjectLauncher.openCommand(projectDir, os = "Mac OS X", pathEnv = "", macAppDirs = listOf(tempDir))
        assertEquals(ProjectLauncher.Opener.IDEA, open.opener)
        assertEquals(listOf("open", "-a", "$app", "$projectDir"), open.command)
    }

    @Test
    fun `falls back to file manager when idea is not found`(@TempDir tempDir: Path) {
        val open = ProjectLauncher.openCommand(projectDir, os = "Linux", pathEnv = tempDir.toString(), macAppDirs = emptyList())
        assertEquals(ProjectLauncher.Opener.FILE_MANAGER, open.opener)
        assertEquals(listOf("xdg-open", "$projectDir"), open.command)
    }
}

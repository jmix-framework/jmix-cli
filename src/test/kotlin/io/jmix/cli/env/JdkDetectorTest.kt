package io.jmix.cli.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

@EnabledOnOs(OS.LINUX, OS.MAC)
class JdkDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = ["21.0.1", "1.8.0_392"])
    fun `launcher resolves to the reported development JDK`(version: String, @TempDir dir: Path) {
        val home = Files.createDirectory(dir.resolve("real jdk"))
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"$version\"")
        val runtimeHome = if (version.startsWith("1.8")) Files.createDirectory(home.resolve("jre")) else home
        val launcherHome = launcher(dir, """
            cat <<'SETTINGS'
                java.home = $runtimeHome
            openjdk version "$version"
            SETTINGS
        """.trimIndent())

        assertEquals(Jdk(JdkDetector.parseMajorVersion(version)!!, home.toRealPath()), JdkDetector.detectJdk(launcherHome))
        assertEquals(JdkDetector.detectJdk(home), JdkDetector.detectJdk(launcherHome))
    }

    @Test
    @Timeout(15)
    fun `unresponsive launcher cannot block JDK detection`(@TempDir dir: Path) {
        val launcherHome = launcher(dir, "exec sleep 60")

        assertNull(JdkDetector.detectJdk(launcherHome))
    }

    private fun launcher(dir: Path, body: String): Path {
        val home = Files.createDirectory(dir.resolve("launcher"))
        val executable = Files.createDirectories(home.resolve("bin")).resolve("java")
        Files.writeString(executable, "#!/bin/sh\n$body\n")
        check(executable.toFile().setExecutable(true))
        return home
    }
}

package io.jmix.cli.env

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/**
 * Downloads a real Temurin JDK (~200 MB) from the Adoptium API.
 * Needs network; enable with JMIX_CLI_IT=true (set in CI).
 */
@EnabledIfEnvironmentVariable(named = "JMIX_CLI_IT", matches = "true")
class JdkInstallerIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `installs a verified temurin jdk and detects its version`() {
        var progressed = false

        val home = JdkInstaller.install(25, jdksDir = tempDir) { done, total ->
            if (done > 0 && total > 0) progressed = true
        }

        assertTrue(progressed, "download progress must be reported")
        assertEquals(25, JdkDetector.majorVersionOf(home), "installed JDK must report the requested version")

        // A second install of the same release must reuse the existing JDK.
        var downloadedAgain = false
        val reused = JdkInstaller.install(25, jdksDir = tempDir) { _, _ -> downloadedAgain = true }
        assertEquals(home, reused)
        assertTrue(!downloadedAgain, "existing installation must be reused without downloading")
    }
}

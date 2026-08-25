package io.jmix.cli.env

import java.util.concurrent.TimeUnit

data class JdkCheckResult(
    val compatible: List<Jdk>,
    val all: List<Jdk>,
    val supportedVersions: Set<Int>,
)

/**
 * Environment checks for the generated project (not for the CLI itself):
 * a compatible JDK and, optionally, git for `git init`.
 */
object EnvironmentCheck {

    fun checkJdk(jmixVersion: String): JdkCheckResult {
        val supported = JdkVersions.supportedJdks(jmixVersion)
        val all = JdkDetector.detectJdks()
        return JdkCheckResult(
            compatible = all.filter { it.majorVersion in supported },
            all = all,
            supportedVersions = supported,
        )
    }

    // Message format ported from Studio's PmBundle
    // "CubaModuleWizard.settingsStep.validation.jdk.incompatible".
    fun jdkMismatchMessage(jmixMajorMinor: String, supported: Set<Int>): String =
        "Note that Jmix $jmixMajorMinor supports only these JDK versions: " +
            supported.joinToString(", ") { "JDK $it" } + "."

    fun installHint(supported: Set<Int>): String {
        val newest = supported.max()
        return "Install a compatible JDK, e.g. with SDKMAN (sdk install java $newest-tem) " +
            "or from https://adoptium.net/temurin/releases/?version=$newest"
    }

    fun isGitAvailable(): Boolean = try {
        val process = ProcessBuilder("git", "--version")
            .redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: Exception) {
        false
    }
}

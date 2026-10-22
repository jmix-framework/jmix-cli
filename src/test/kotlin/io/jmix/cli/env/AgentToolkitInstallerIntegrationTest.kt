package io.jmix.cli.env

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Downloads the real Agent Toolkit installer and runs its non-interactive
 * subcommands against a temporary project directory. Everything it writes is
 * project-local, so the test never touches global agent directories.
 * Needs network; enable with JMIX_CLI_IT=true (set in CI).
 */
@EnabledIfEnvironmentVariable(named = "JMIX_CLI_IT", matches = "true")
class AgentToolkitInstallerIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @ParameterizedTest
    @ValueSource(strings = ["2.8.3", "3.0.1"])
    fun `installs guidelines and local skills into the project`(jmixVersion: String) {
        AgentToolkitInstaller.installGuidelinesAndSkills(tempDir, jmixVersion)

        assertTrue(Files.isRegularFile(tempDir.resolve("CLAUDE.md")), "Claude guidelines must exist")
        assertEquals("@AGENTS.md\n", Files.readString(tempDir.resolve("CLAUDE.md")))
        assertTrue(Files.isRegularFile(tempDir.resolve("AGENTS.md")), "Codex/OpenCode/Junie guidelines must exist")
        assertFalse(Files.exists(tempDir.resolve(".junie/guidelines.md"), NOFOLLOW_LINKS), "Junie must read the shared AGENTS.md")
        assertFalse(Files.exists(tempDir.resolve(".junie/skills"), NOFOLLOW_LINKS), "Junie-specific skills must not be installed")
        assertFalse(Files.exists(tempDir.resolve(".skills"), NOFOLLOW_LINKS), "legacy skills store must not be created")
        val claudeSkills = tempDir.resolve(".claude/skills")
        assertTrue(Files.isDirectory(claudeSkills), "claude skills dir must exist")
        assertTrue(
            Files.list(claudeSkills).use { entries -> entries.anyMatch { it.fileName.toString().startsWith("jmix-") } },
            "jmix skills must be linked for claude",
        )
        val sharedSkills = tempDir.resolve(".agents/skills")
        assertTrue(Files.isDirectory(sharedSkills, NOFOLLOW_LINKS), "shared skills store must be a real directory")
        assertTrue(Files.isDirectory(sharedSkills.resolve("jmix"), NOFOLLOW_LINKS), "stored skills must be real directories")
        assertTrue(Files.isSameFile(sharedSkills.resolve("jmix"), claudeSkills.resolve("jmix")), "Claude must use the shared store")
        assertTrue(
            Files.list(sharedSkills).use { entries -> entries.anyMatch { it.fileName.toString().startsWith("jmix-") } },
            "jmix skills must be installed in the shared skills directory",
        )
    }
}

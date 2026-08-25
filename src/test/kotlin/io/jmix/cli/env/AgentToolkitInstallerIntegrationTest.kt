package io.jmix.cli.env

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

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

    @Test
    fun `installs guidelines and local skills into the project`() {
        AgentToolkitInstaller.installGuidelinesAndSkills(tempDir, "3.0.1")

        assertTrue(Files.isRegularFile(tempDir.resolve("CLAUDE.md")), "Claude guidelines must exist")
        assertTrue(Files.isRegularFile(tempDir.resolve("AGENTS.md")), "Codex/OpenCode guidelines must exist")
        assertTrue(Files.isRegularFile(tempDir.resolve(".junie/guidelines.md")), "Junie guidelines must exist")
        assertTrue(Files.isDirectory(tempDir.resolve(".skills")), "local skills store must exist")
        val claudeSkills = tempDir.resolve(".claude/skills")
        assertTrue(Files.isDirectory(claudeSkills), "claude skills dir must exist")
        assertTrue(
            Files.list(claudeSkills).use { entries -> entries.anyMatch { it.fileName.toString().startsWith("jmix-") } },
            "jmix skills must be linked for claude",
        )
    }
}

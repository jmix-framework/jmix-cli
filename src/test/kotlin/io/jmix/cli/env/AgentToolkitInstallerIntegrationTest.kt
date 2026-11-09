package io.jmix.cli.env

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    fun `installs guidelines skills and MCP into the project`(jmixVersion: String) {
        AgentToolkitInstaller.install(tempDir, jmixVersion)

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

        val claude = servers(".mcp.json", "mcpServers")
        val opencode = servers("opencode.json", "mcp")
        val junie = servers(".junie/mcp/mcp.json", "mcpServers")
        val expectedServers = setOf("jetbrains", "jmix-docs", "playwright")
        assertEquals(expectedServers, claude.keySet())
        assertEquals(expectedServers, opencode.keySet())
        assertEquals(setOf("jmix-docs", "playwright"), junie.keySet())

        val docsUrl = "https://docs-mcp.jmix.io/mcp"
        assertEquals(docsUrl, claude.getAsJsonObject("jmix-docs").get("url").asString)
        assertEquals(docsUrl, opencode.getAsJsonObject("jmix-docs").get("url").asString)
        val junieDocs = junie.getAsJsonObject("jmix-docs")
        assertEquals("npx", junieDocs.get("command").asString)
        assertEquals(listOf("-y", "mcp-remote", docsUrl), junieDocs.getAsJsonArray("args").map { it.asString })

        val claudeJetBrains = claude.getAsJsonObject("jetbrains")
        val jetBrainsUrl = claudeJetBrains.get("url").asString
        assertTrue(jetBrainsUrl.endsWith("/stream") || jetBrainsUrl.endsWith("/sse"), jetBrainsUrl)
        assertEquals(if (jetBrainsUrl.endsWith("/sse")) "sse" else "http", claudeJetBrains.get("type").asString)
        val opencodeJetBrains = opencode.getAsJsonObject("jetbrains")
        assertEquals("remote", opencodeJetBrains.get("type").asString)
        assertEquals(jetBrainsUrl, opencodeJetBrains.get("url").asString)
        assertEquals("stdio", claude.getAsJsonObject("playwright").get("type").asString)
        assertEquals("local", opencode.getAsJsonObject("playwright").get("type").asString)

        val codex = Files.readString(tempDir.resolve(".codex/config.toml"))
        val codexServers = codex.lineSequence()
            .filter { it.startsWith("[mcp_servers.") }
            .map { it.removePrefix("[mcp_servers.").removeSuffix("]") }
            .toSet()
        assertEquals(expectedServers, codexServers)
        assertTrue(codex.contains(docsUrl))
        assertTrue(codex.contains(jetBrainsUrl))
        if (jetBrainsUrl.endsWith("/sse")) assertTrue(codex.contains("mcp-remote"))
    }

    private fun servers(config: String, key: String): JsonObject =
        JsonParser.parseString(Files.readString(tempDir.resolve(config))).asJsonObject.getAsJsonObject(key)
}

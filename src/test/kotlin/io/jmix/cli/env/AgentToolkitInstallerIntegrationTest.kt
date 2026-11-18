package io.jmix.cli.env

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        // The lists are given, not taken from the live metadata: the expectations below are fixed.
        val result = AgentToolkitInstaller.install(tempDir, jmixVersion, servers = mcpServers, agents = mcpAgents)
        assertProjectRelative(result.changes)

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

    @Test
    fun `the MCP step takes its servers and agents from the toolkit metadata by default`() {
        val result = AgentToolkitInstaller.install(tempDir, "3.0.1", steps = setOf(ToolkitStep.MCP))
        assertProjectRelative(result.changes)
    }

    @Test
    fun `a failed step stops the run with the installer's error`() {
        Files.createDirectory(tempDir.resolve("AGENTS.md"))
        val error = assertThrows(IOException::class.java) {
            AgentToolkitInstaller.install(
                tempDir, "3.0.1", steps = setOf(ToolkitStep.GUIDELINES, ToolkitStep.MCP), servers = mcpServers, agents = mcpAgents,
            )
        }
        val message = error.message.orEmpty()
        assertTrue(message.startsWith("Agent Toolkit guidelines step failed: error: "), message)
        assertEquals(listOf("AGENTS.md"), Files.list(tempDir).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `runs only the selected step, keeps own guidelines and reports the changes`() {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Own rules\n")
        val result = AgentToolkitInstaller.install(tempDir, "3.0.1", steps = setOf(ToolkitStep.GUIDELINES))
        assertEquals(
            listOf("backed-up" to "AGENTS.md.bak", "updated" to "AGENTS.md", "created" to "CLAUDE.md"),
            result.changes.map { it.action to it.path },
        )
        assertTrue(Files.readString(tempDir.resolve("AGENTS.md")).contains("# Own rules"))
        assertFalse(Files.exists(tempDir.resolve(".agents"), NOFOLLOW_LINKS))
        assertFalse(Files.exists(tempDir.resolve(".mcp.json"), NOFOLLOW_LINKS))
        assertEquals(emptyList<ToolkitChange>(), AgentToolkitInstaller.install(tempDir, "3.0.1", steps = setOf(ToolkitStep.GUIDELINES)).changes)
    }

    @Test
    fun `configures only the selected MCP servers and agents and relays the toolkit's notes`() {
        val options = AgentToolkitMetadata.mcpOptions("v3")
        val server = options.servers.last().value
        val agent = options.agents.first().value
        val result = AgentToolkitInstaller.install(tempDir, "3.0.1", steps = setOf(ToolkitStep.MCP), servers = listOf(server), agents = listOf(agent))
        val written = result.changes.single()
        assertEquals("created", written.action)
        assertTrue(Files.readString(tempDir.resolve(written.path)).contains(server))
        assertEquals(listOf(written.path), Files.list(tempDir).use { files -> files.map { it.fileName.toString() }.toList() })
        assertTrue(result.notes.isNotEmpty())
        assertTrue(result.notes.none { it.isBlank() || it.startsWith("[") || it.startsWith("@@") || it != it.trim() })
    }

    @Test
    fun `a fresh install is up to date against the toolkit's own files`() {
        // More patient than the production limit of a hint, which a slow runner would turn into a null state.
        val fetch = { path: String -> AgentToolkitMetadata.fetch("v3", path, Duration.ofSeconds(30)) }
        assertEquals(
            AgentToolkitStatus.Status(AgentToolkitStatus.State.NOT_INSTALLED, AgentToolkitStatus.State.NOT_INSTALLED),
            AgentToolkitStatus.detect(tempDir, "v3", fetch),
        )
        val result = AgentToolkitInstaller.install(tempDir, "3.0.1", steps = setOf(ToolkitStep.SKILLS, ToolkitStep.GUIDELINES))
        assertEquals(
            listOf("created" to ".agents/skills", "created" to ".claude/skills", "created" to "AGENTS.md", "created" to "CLAUDE.md"),
            result.changes.map { it.action to it.path },
        )
        assertEquals(
            AgentToolkitStatus.Status(AgentToolkitStatus.State.UP_TO_DATE, AgentToolkitStatus.State.UP_TO_DATE),
            AgentToolkitStatus.detect(tempDir, "v3", fetch),
        )
    }

    @Test
    fun `a Jmix major without a toolkit branch fails with its name`() {
        val error = assertThrows(IOException::class.java) { AgentToolkitInstaller.install(tempDir, "99.0.0") }
        assertEquals("the toolkit has no v99 branch for Jmix 99 yet", error.message)
    }

    private val mcpServers = listOf("jetbrains", "jmix-docs", "playwright")
    private val mcpAgents = listOf("claude", "codex", "opencode", "junie")

    private fun assertProjectRelative(changes: List<ToolkitChange>) {
        assertTrue(changes.isNotEmpty())
        for (change in changes) {
            assertFalse(Path.of(change.path).isAbsolute, change.path)
            assertTrue(Files.exists(tempDir.resolve(change.path), NOFOLLOW_LINKS), change.path)
        }
    }

    private fun servers(config: String, key: String): JsonObject =
        JsonParser.parseString(Files.readString(tempDir.resolve(config))).asJsonObject.getAsJsonObject(key)
}

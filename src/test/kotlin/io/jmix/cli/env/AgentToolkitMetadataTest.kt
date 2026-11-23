package io.jmix.cli.env

import io.jmix.cli.FakeServer
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AgentToolkitMetadataTest {

    private val sample = """
        {"install-flow":{"steps":[
          {"id":"skills","inputs":[{"id":"mcpServers","type":"options","choices":[{"value":"wrong-step","default":true}]}]},
          {"id":"mcp","inputs":[
            {"id":"mcpScope","type":"options","choices":[{"value":"local","label":"Local","default":true},{"value":"global","label":"Global"}]},
            {"id":"mcpServers","type":"options","multi":true,"choices":[
              {"value":"alpha","label":"Al\u001Bpha","default":true},
              {"value":"keyed","label":"Keyed","default":true},
              {"value":"key-ed","label":"Hyphen","default":true},
              {"value":"my_docs","label":"Underscore","default":true},
              {"value":"beta","default":true},
              {"value":"off","label":"Off","default":false},
              {"value":"bad value","label":"Bad","default":true},
              {"value":"--flag","label":"Flag","default":true}]},
            {"id":"mcpAgents","type":"options","multi":true,"choices":[
              {"value":"one","label":"One","default":true},
              {"value":"two","label":"Two"},
              {"value":"three","label":" ","default":true}]},
            {"id":"KeyedToken","type":"userInput","default":"skip"},
            {"id":"keyEdSecret","type":"userInput"},
            {"id":"my_docsKey","type":"userInput"},
            {"id":"alphaNote","type":"options","choices":[]}]}]}}
    """.trimIndent()

    @Test
    fun `offers the default choices and drops servers that need a text input`() {
        val options = AgentToolkitMetadata.parseMcpOptions(sample)
        assertEquals(listOf(ToolkitChoice("alpha", "Alpha"), ToolkitChoice("beta", "beta")), options.servers)
        assertEquals(listOf(ToolkitChoice("one", "One"), ToolkitChoice("three", "three")), options.agents)
    }

    @Test
    fun `the toolkit v3 metadata gives the lists jmix new used to hardcode`() {
        val json = checkNotNull(javaClass.getResource("/toolkit/studio-meta-data-v3.json")).readText()
        val options = AgentToolkitMetadata.parseMcpOptions(json)
        assertEquals(listOf("jetbrains", "jmix-docs", "playwright"), options.servers.map { it.value })
        assertEquals(listOf("JetBrains", "Jmix Docs", "Playwright"), options.servers.map { it.label })
        assertEquals(listOf("claude", "codex", "opencode", "junie"), options.agents.map { it.value })
        assertEquals(listOf("Claude Code", "Codex", "OpenCode", "Junie"), options.agents.map { it.label })
        assertEquals(listOf(".mcp.json", ".codex/config.toml", "opencode.json", ".junie/mcp/mcp.json"), options.agents.map { it.projectConfig })
        assertEquals(listOf(emptyList(), emptyList(), emptyList(), listOf("jetbrains")), options.agents.map { it.skipServers })
    }

    @Test
    fun `an agent's skipped servers are the strings of its skipServers array`() {
        fun agent(value: String, skip: String) = """{"value":"$value","default":true,"skipServers":$skip}"""
        val agents = listOf(agent("list", """["jetbrains", 5, null, "x"]"""), agent("text", "\"jetbrains\""), """{"value":"none","default":true}""")
        val servers = """{"id":"mcpServers","type":"options","choices":[{"value":"s","default":true}]}"""
        val json = """{"install-flow":{"steps":[{"id":"mcp","inputs":[$servers,{"id":"mcpAgents","type":"options","choices":[${agents.joinToString(",")}]}]}]}}"""
        val skips = AgentToolkitMetadata.parseMcpOptions(json).agents.associate { it.value to it.skipServers }
        assertEquals(mapOf("list" to listOf("jetbrains", "x"), "text" to emptyList(), "none" to emptyList()), skips)
    }

    @Test
    fun `an agent's project config is kept only as a plain relative path`() {
        fun agent(value: String, config: String) = """{"value":"$value","default":true,"projectConfig":$config}"""
        val agents = listOf(
            agent("plain", "\".mcp.json\""), agent("nested", "\".junie/mcp/mcp.json\""), """{"value":"none","default":true}""",
            agent("absolute", "\"/etc/mcp.json\""), agent("parent", "\"a/../../mcp.json\""), agent("dot", "\"./mcp.json\""),
            agent("backslash", "\"a\\\\b.json\""), agent("empty", "\"\""), agent("number", "5"),
        )
        val servers = """{"id":"mcpServers","type":"options","choices":[{"value":"s","default":true}]}"""
        val json = """{"install-flow":{"steps":[{"id":"mcp","inputs":[$servers,{"id":"mcpAgents","type":"options","choices":[${agents.joinToString(",")}]}]}]}}"""
        val configs = AgentToolkitMetadata.parseMcpOptions(json).agents.associate { it.value to it.projectConfig }
        assertEquals(mapOf("plain" to ".mcp.json", "nested" to ".junie/mcp/mcp.json"), configs.filterValues { it != null })
        assertEquals(9, configs.size)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "not json",
        "[]",
        "{}",
        """{"install-flow":{"steps":[1]}}""",
        """{"install-flow":{"steps":[{"id":"mcp","inputs":[null]}]}}""",
        """{"install-flow":{"steps":[{"id":"skills","inputs":[]}]}}""",
        """{"install-flow":{"steps":[{"id":"mcp","inputs":[{"id":"mcpServers","type":"options","choices":[{"value":"a","default":true}]}]}]}}""",
        """{"install-flow":{"steps":[{"id":"mcp","inputs":[{"id":"mcpServers","type":"options","choices":[{"value":"a","default":false}]},{"id":"mcpAgents","type":"options","choices":[{"value":"b","default":true}]}]}]}}""",
        """{"install-flow":{"steps":[{"id":"mcp","inputs":[{"id":"mcpServers","type":"options","choices":"a"},{"id":"mcpAgents","type":"options","choices":[{"value":"b","default":true}]}]}]}}""",
    ])
    fun `unexpected metadata is rejected`(json: String) {
        val error = assertThrows(IOException::class.java) { AgentToolkitMetadata.parseMcpOptions(json) }
        assertEquals("Unexpected Agent Toolkit metadata format.", error.message)
    }

    @Test
    fun `a choice with a value that is no text or a default that is no boolean is not offered`() {
        fun input(id: String, vararg choices: String) = """{"id":"$id","type":"options","choices":[${choices.joinToString(",")}]}"""
        val ok = """{"value":"ok","default":true}"""
        val servers = input("mcpServers", """{"value":5,"default":"true"}""", """{"value":5,"default":true}""", """{"value":"text","default":"true"}""", ok)
        val options = AgentToolkitMetadata.parseMcpOptions("""{"install-flow":{"steps":[{"id":"mcp","inputs":[$servers,${input("mcpAgents", ok)}]}]}}""")
        assertEquals(listOf(ToolkitChoice("ok", "ok")), options.servers)
    }

    @Test
    fun `fetch reads a toolkit file and names the one it cannot read`() {
        FakeServer().use { server ->
            server.on("GET", "/v3/.studio/studio-meta-data.json") { FakeServer.Response(200, sample.toByteArray()) }
            server.on("GET", "/v3/big.json") { FakeServer.Response(200, ByteArray(256 * 1024 + 1)) }
            server.on("GET", "/v3/broken.json") { FakeServer.Response(500) }
            val limit = Duration.ofSeconds(5)
            assertEquals(sample, AgentToolkitMetadata.fetch("v3", ".studio/studio-meta-data.json", limit, base = server.url))
            assertEquals(listOf("alpha", "beta"), AgentToolkitMetadata.mcpOptions("v3", limit, base = server.url).servers.map { it.value })
            val missing = assertThrows(FileNotFoundException::class.java) { AgentToolkitMetadata.fetch("v3", "missing.json", limit, base = server.url) }
            assertEquals("Cannot load missing.json from Agent Toolkit v3: HTTP 404", missing.message)
            val broken = assertThrows(IOException::class.java) { AgentToolkitMetadata.fetch("v3", "broken.json", limit, base = server.url) }
            assertEquals("Cannot load broken.json from Agent Toolkit v3: HTTP 500", broken.message)
            assertFalse(broken is FileNotFoundException)
            val big = assertThrows(IOException::class.java) { AgentToolkitMetadata.fetch("v3", "big.json", limit, base = server.url) }
            assertTrue(big.message.orEmpty().startsWith("Cannot load big.json from Agent Toolkit v3: "), big.message)
            assertFalse(big is FileNotFoundException)
        }
    }

    @Test
    fun `fetch gives up at its timeout`() {
        FakeServer().use { server ->
            server.on("GET", "/v3/slow.json") { Thread.sleep(3_000); FakeServer.Response(200, "late".toByteArray()) }
            val error = assertTimeoutPreemptively(Duration.ofMillis(2_500)) {
                assertThrows(IOException::class.java) { AgentToolkitMetadata.fetch("v3", "slow.json", Duration.ofSeconds(1), base = server.url) }
            }
            assertEquals("Cannot load slow.json from Agent Toolkit v3: cannot reach 127.0.0.1", error.message)
        }
    }

    @Test
    fun `fetch names the file when the toolkit host does not answer`() {
        val port = ServerSocket(0).use { it.localPort }
        val error = assertThrows(IOException::class.java) {
            AgentToolkitMetadata.fetch("v3", "any.json", Duration.ofSeconds(5), base = "http://127.0.0.1:$port")
        }
        assertEquals("Cannot load any.json from Agent Toolkit v3: cannot reach 127.0.0.1", error.message)
    }
}

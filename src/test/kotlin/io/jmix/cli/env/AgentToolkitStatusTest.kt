package io.jmix.cli.env

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AgentToolkitStatusTest {
    @TempDir lateinit var dir: Path

    private val manifestPath = ".studio/skills-manifest.json"
    private val blockPath = "content/guidelines-block.md"
    private val sha = "10ac7c357bf35ade8fbc86f552bf92eddd77adfb118d225dd50c87627a36535d"
    private val block = "<!-- BEGIN block -->\n## Jmix\n\nRead the skill.\n<!-- END block -->\n"

    private fun manifest(hash: String = sha, store: String = "store/skills") =
        """{"schemaVersion":1,"sha256":"$hash","skills":["jmix","jmix-b","jmix-missing"],"store":{"global":".g","local":"$store"}}"""

    private fun skill(name: String, vararg files: Pair<String, String>) = files.forEach { (path, text) ->
        val file = dir.resolve("store/skills").resolve(name).resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun fixture() {
        skill("jmix", "SKILL.md" to "a\n")
        skill("jmix-b", "SKILL.md" to "b\n", "ref/x.txt" to "x")
    }

    private val metadataPath = ".studio/studio-meta-data.json"
    private val metadataV3 = checkNotNull(javaClass.getResource("/toolkit/studio-meta-data-v3.json")).readText()

    private fun detect(
        manifest: String? = manifest(),
        block: String? = this.block,
        metadata: String? = metadataV3,
    ): AgentToolkitStatus.Status {
        // The files are fetched at once, from several threads.
        val asked = ConcurrentLinkedQueue<String>()
        val status = AgentToolkitStatus.detect(dir, "v3") { path ->
            asked += path
            when (path) { manifestPath -> manifest; blockPath -> block; metadataPath -> metadata; else -> error(path) }
        }
        assertEquals(setOf(manifestPath, blockPath, metadataPath), asked.toSet())
        return status
    }

    private fun config(path: String, text: String) {
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun agentsMd(text: String) = Files.writeString(dir.resolve("AGENTS.md"), text)

    @Test
    fun `skills hash matches the toolkit manifest algorithm`() {
        fixture()
        assertEquals(sha, AgentToolkitStatus.skillsHash(dir.resolve("store/skills"), listOf("jmix-missing", "jmix-b", "jmix")))
    }

    @Test
    fun `skills are missing, up to date or outdated against the manifest`() {
        assertEquals(AgentToolkitStatus.State.NOT_INSTALLED, detect().skills)
        fixture()
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().skills)
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect(manifest("0".repeat(64))).skills)
    }

    @Test
    fun `guidelines are missing, up to date or outdated against the block`() {
        assertEquals(AgentToolkitStatus.State.NOT_INSTALLED, detect().guidelines)
        agentsMd("# Own rules\n")
        assertEquals(AgentToolkitStatus.State.NOT_INSTALLED, detect().guidelines)
        agentsMd("Never write <!-- BEGIN block --> by hand.\n")
        assertEquals(AgentToolkitStatus.State.NOT_INSTALLED, detect().guidelines)
        agentsMd("# Own rules\r\n\r\n" + block.replace("\n", "\r\n"))
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().guidelines)
        agentsMd("\uFEFF" + block.replace("Read the skill.", "Old text."))
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect().guidelines)
    }

    @Test
    fun `MCP is set up when one agent's project config names every offered server`() {
        assertEquals(AgentToolkitStatus.State.NOT_INSTALLED, detect().mcp)
        // A config of the project's own, with no server of the toolkit.
        config(".mcp.json", """{"mcpServers":{"mine":{"url":"http://mine"}}}""")
        assertEquals(AgentToolkitStatus.State.NOT_INSTALLED, detect().mcp)
        // Two of the three servers, one of them only as a value.
        config(".mcp.json", """{"mcpServers":{"jetbrains":{},"jmix-docs":{"url":"playwright"}}}""")
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect().mcp)
        // The third one in another agent's config: together they name all three, but each agent misses one.
        config("opencode.json", """{"mcp":{"playwright":{}}}""")
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect().mcp)
        // A TOML config that names all three.
        config(".codex/config.toml", "[mcp_servers.jetbrains]\n[mcp_servers.\"jmix-docs\"]\n  [mcp_servers.playwright]\ncommand = \"npx\"\n")
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().mcp)
        // The JSON config alone, once complete.
        Files.delete(dir.resolve(".codex/config.toml"))
        config(".mcp.json", """{"mcpServers":{"jetbrains":{},"jmix-docs":{},"playwright" : {}}}""")
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().mcp)
    }

    @Test
    fun `a Junie config is complete without the servers the toolkit never gives Junie`() {
        config(".junie/mcp/mcp.json", """{"mcpServers":{"jmix-docs":{}}}""")
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect().mcp)
        // What the installers write for Junie: every server but JetBrains.
        config(".junie/mcp/mcp.json", """{"mcpServers":{"jmix-docs":{},"playwright":{}}}""")
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().mcp)
        // Without the toolkit's exception, the JetBrains server would be missing.
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect(metadata = metadataV3.replace("skipServers", "otherKey")).mcp)
    }

    @Test
    fun `TOML table headers count as the installers read them`() {
        config(".codex/config.toml", "﻿[mcp_servers.jetbrains]\n[ mcp_servers . 'jmix-docs' ]\r\n[\"mcp_servers\".\"playwright\"]\n")
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().mcp)
        // A commented-out table is not a server.
        config(".codex/config.toml", "[mcp_servers.jetbrains]\n[mcp_servers.jmix-docs]\n# [mcp_servers.playwright]\n")
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect().mcp)
    }

    @Test
    fun `MCP has no state without agent project configs or with unreadable metadata`() {
        config(".mcp.json", """{"mcpServers":{"jetbrains":{},"jmix-docs":{},"playwright":{}}}""")
        assertNull(detect(metadata = metadataV3.replace("projectConfig", "otherKey")).mcp)
        assertNull(detect(metadata = "not json").mcp)
        assertNull(detect(metadata = null).mcp)
    }

    @Test
    fun `the three files are requested at once`() {
        val started = CountDownLatch(3)
        val waitedAlone = AtomicBoolean(false)
        AgentToolkitStatus.detect(dir, "v3") { path ->
            started.countDown()
            // One request after another, each would wait here alone.
            if (!started.await(5, TimeUnit.SECONDS)) waitedAlone.set(true)
            when (path) { manifestPath -> manifest(); blockPath -> block; else -> metadataV3 }
        }
        assertFalse(waitedAlone.get())
    }

    @Test
    fun `the MCP choices come with the status`() {
        assertEquals(AgentToolkitMetadata.parseMcpOptions(metadataV3), detect().mcpOptions)
        assertNull(detect(metadata = "not json").mcpOptions)
    }

    @Test
    fun `a config that is not UTF-8 is still read`() {
        val names = """{"mcpServers":{"jetbrains":{},"jmix-docs":{},"playwright":{}},"note":""""
        Files.write(dir.resolve(".mcp.json"), names.toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray())
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().mcp)
    }

    @Test
    fun `a config that cannot be read leaves the state to the others`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val locked = dir.resolve(".mcp.json")
        Files.writeString(locked, """{"mcpServers":{"jetbrains":{}}}""")
        Files.setPosixFilePermissions(locked, emptySet())
        assumeFalse(Files.isReadable(locked), "this user reads every file")
        assertNull(detect().mcp)
        config("opencode.json", """{"mcp":{"jetbrains":{}}}""")
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect().mcp)
        config("opencode.json", """{"mcp":{"jetbrains":{},"jmix-docs":{},"playwright":{}}}""")
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().mcp)
    }

    @Test
    fun `an MCP config over the size limit is not read`() {
        config(".mcp.json", """{"mcpServers":{"jetbrains":{},"jmix-docs":{},"playwright":{}}}""")
        // Sparse: the length is set without writing 4 MiB.
        RandomAccessFile(dir.resolve(".mcp.json").toFile(), "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
        assertNull(detect().mcp)
        RandomAccessFile(dir.resolve(".mcp.json").toFile(), "rw").use { it.setLength(4L * 1024 * 1024) }
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().mcp)
    }

    @Test
    fun `an MCP config linked outside the project is not read`(@TempDir outside: Path) {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val target = Files.writeString(outside.resolve("mcp.json"), """{"jetbrains":{},"jmix-docs":{},"playwright":{}}""")
        Files.createSymbolicLink(dir.resolve(".mcp.json"), target)
        assertEquals(AgentToolkitStatus.State.NOT_INSTALLED, detect().mcp)
    }

    @Test
    fun `hints are omitted when the toolkit cannot be read`() {
        fixture()
        assertEquals(AgentToolkitStatus.Status(null, null), detect(manifest = null, block = null, metadata = null))
        assertEquals(AgentToolkitStatus.Status(null, null), detect(manifest = "not json", block = " \n", metadata = "{}"))
        assertNull(detect(manifest = """{"sha256":"$sha","skills":"jmix","store":{"local":"store/skills"}}""").skills)
        assertEquals(
            AgentToolkitStatus.Status(null, null),
            AgentToolkitStatus.detect(dir, "v3") { throw IOException("offline") },
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["../outside", "/abs/skills", "store/../../outside", ""])
    fun `a manifest that points outside the project is ignored`(store: String) {
        fixture()
        assertNull(detect(manifest(store = store)).skills)
    }

    @Test
    fun `a guidelines file over the size limit is not read`() {
        agentsMd(block)
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().guidelines)
        // Sparse: the length is set without writing 4 MiB.
        RandomAccessFile(dir.resolve("AGENTS.md").toFile(), "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
        assertNull(detect().guidelines)
        RandomAccessFile(dir.resolve("AGENTS.md").toFile(), "rw").use { it.setLength(4L * 1024 * 1024) }
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().guidelines)
    }

    @Test
    fun `a skills store linked outside the project is not read`(@TempDir outside: Path) {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        fixture()
        Files.move(dir.resolve("store"), outside.resolve("store"))
        Files.createSymbolicLink(dir.resolve("store"), outside.resolve("store"))
        assertNull(detect().skills)
        Files.delete(dir.resolve("store"))
        Files.move(outside.resolve("store"), dir.resolve("real"))
        Files.createSymbolicLink(dir.resolve("store"), dir.resolve("real"))
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().skills)
    }

    @Test
    fun `a guidelines file that is not UTF-8 is still read`() {
        // The installers keep the user's own bytes, here Latin-1, below the block they add.
        Files.write(dir.resolve("AGENTS.md"), block.toByteArray() + "\n# Caf".toByteArray() + byteArrayOf(0xE9.toByte(), '\n'.code.toByte()))
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect().guidelines)
    }

    @Test
    fun `a guidelines file linked outside the project is not read`(@TempDir outside: Path) {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        Files.createSymbolicLink(dir.resolve("AGENTS.md"), Files.writeString(outside.resolve("AGENTS.md"), block))
        assertNull(detect().guidelines)
    }

    @Test
    fun `a link in a skill folder is never read, so a replaced toolkit file counts as changed`(@TempDir outside: Path) {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        fixture()
        val skill = dir.resolve("store/skills/jmix/SKILL.md")
        Files.move(skill, outside.resolve("SKILL.md"))
        Files.createSymbolicLink(skill, outside.resolve("SKILL.md"))
        assertEquals(AgentToolkitStatus.State.UPDATE_AVAILABLE, detect().skills)
    }

    @Test
    fun `skill names that are not plain names are not followed`() {
        fixture()
        Files.writeString(Files.createDirectories(dir.resolve("store/secret")).resolve("f"), "s")
        val manifest = """{"sha256":"$sha","skills":["jmix","jmix-b","../secret"],"store":{"local":"store/skills"}}"""
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect(manifest).skills)
    }
}

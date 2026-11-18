package io.jmix.cli.env

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun detect(manifest: String? = manifest(), block: String? = this.block): AgentToolkitStatus.Status {
        val asked = mutableListOf<String>()
        val status = AgentToolkitStatus.detect(dir, "v3") { path ->
            asked += path
            when (path) { manifestPath -> manifest; blockPath -> block; else -> error(path) }
        }
        assertEquals(setOf(manifestPath, blockPath), asked.toSet())
        return status
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
    fun `hints are omitted when the toolkit cannot be read`() {
        fixture()
        assertEquals(AgentToolkitStatus.Status(null, null), detect(manifest = null, block = null))
        assertEquals(AgentToolkitStatus.Status(null, null), detect(manifest = "not json", block = " \n"))
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
    fun `skill names that are not plain names are not followed`() {
        fixture()
        Files.writeString(Files.createDirectories(dir.resolve("store/secret")).resolve("f"), "s")
        val manifest = """{"sha256":"$sha","skills":["jmix","jmix-b","../secret"],"store":{"local":"store/skills"}}"""
        assertEquals(AgentToolkitStatus.State.UP_TO_DATE, detect(manifest).skills)
    }
}

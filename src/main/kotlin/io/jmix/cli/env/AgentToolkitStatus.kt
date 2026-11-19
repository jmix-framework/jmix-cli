package io.jmix.cli.env

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.jmix.cli.util.string
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.Arrays
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.text.Charsets.UTF_8

/**
 * Compares the skills, the guidelines and the MCP configs of a project with the files the Jmix Agent Toolkit
 * publishes for that purpose (Jmix Studio reads the same ones). The skills folder, the skill names, the
 * guidelines marker, the servers and the agents' config files come from those files; none are named here.
 */
object AgentToolkitStatus {
    enum class State { NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE }

    /**
     * A null state means the toolkit file it needs could not be read or understood. [mcpOptions] are the MCP
     * choices of the metadata the MCP state comes from, so the questions that follow need not load it again.
     */
    data class Status(
        val skills: State?,
        val guidelines: State?,
        val mcp: State? = null,
        val mcpOptions: ToolkitMcpOptions? = null,
    )

    /** [fetch] returns the text of a toolkit file of [ref] by its repository path, or null. */
    fun detect(
        projectDir: Path,
        ref: String,
        fetch: (path: String) -> String? = { AgentToolkitMetadata.fetch(ref, it, FETCH_TIMEOUT) },
    ): Status {
        // All at once: each request may take its whole timeout.
        val (manifest, block, metadata) = Executors.newVirtualThreadPerTaskExecutor().use { requests ->
            listOf(MANIFEST_PATH, BLOCK_PATH, AgentToolkitMetadata.METADATA_PATH)
                .map { path -> requests.submit(Callable { orNull { fetch(path) } }) }
                .map { it.get() }
        }
        val mcpOptions = orNull { metadata?.let(AgentToolkitMetadata::parseMcpOptions) }
        return Status(
            orNull { skills(projectDir, manifest) },
            orNull { guidelines(projectDir, block) },
            orNull { mcpOptions?.let { mcp(projectDir, it) } },
            mcpOptions,
        )
    }

    /**
     * Byte-identical to the toolkit's `.studio/gen_skills_manifest.py`: every regular file below a listed skill
     * directory is its path relative to [skillsDir] with `/` separators as UTF-8, a zero byte and the file's
     * bytes; the entries are hashed in the order of their path bytes, compared as unsigned values.
     */
    internal fun skillsHash(skillsDir: Path, skillNames: List<String>): String {
        val files = skillNames.map(skillsDir::resolve).filter { Files.isDirectory(it) }.flatMap { skill ->
            Files.walk(skill).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .map { skillsDir.relativize(it).joinToString("/").toByteArray(UTF_8) to it }
                    .toList()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((path, file) in files.sortedWith { a, b -> Arrays.compareUnsigned(a.first, b.first) }) {
            digest.update(path)
            digest.update(0)
            Files.newInputStream(file).use { it.transferTo(DigestOutputStream(OutputStream.nullOutputStream(), digest)) }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun skills(projectDir: Path, manifest: String?): State? {
        val root = manifest?.let { JsonParser.parseString(it) as? JsonObject } ?: return null
        val sha256 = root.string("sha256") ?: return null
        val listed = root.get("skills") as? JsonArray ?: return null
        val local = (root.get("store") as? JsonObject)?.string("local") ?: return null
        val store = inProject(projectDir, local) ?: return null
        // The manifest is external input: a name is followed only when it is one plain path element.
        val names = listed.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.asString }
            .filter(SKILL_NAME::matches)
        return when {
            names.none { Files.isDirectory(store.resolve(it)) } -> State.NOT_INSTALLED
            skillsHash(store, names) == sha256 -> State.UP_TO_DATE
            else -> State.UPDATE_AVAILABLE
        }
    }

    /**
     * The project configs are the ones the toolkit names for its default agents. Up to date when one of them
     * names every offered server the toolkit configures for its agent; only the names are compared. Not
     * installed when none of them exists or names an offered server.
     */
    private fun mcp(projectDir: Path, options: ToolkitMcpOptions): State? {
        val servers = options.servers.map { it.value }
        val configs = options.agents.mapNotNull { agent ->
            val file = agent.projectConfig?.let { inProject(projectDir, it) } ?: return@mapNotNull null
            file to servers - agent.skipServers.toSet()
        }
        if (configs.isEmpty()) return null
        // The offered servers each existing config names; null for one that cannot be read.
        val found = configs.filter { (file, _) -> Files.isRegularFile(file) }
            .map { (file, expected) -> expected to readOrNull(file)?.let { namedServers(it, servers) } }
        return when {
            found.any { (expected, named) -> expected.isNotEmpty() && named?.containsAll(expected) == true } -> State.UP_TO_DATE
            found.any { (_, named) -> !named.isNullOrEmpty() } -> State.UPDATE_AVAILABLE
            found.any { (_, named) -> named == null } -> null
            else -> State.NOT_INSTALLED
        }
    }

    /**
     * The [servers] that [text] names as a JSON key (`"id":`) or as the last key of a TOML table header
     * (`[mcp_servers.id]`). Headers are read as the installers read them: spaces, quotes and a BOM do not count.
     */
    private fun namedServers(text: String, servers: List<String>): Set<String> {
        val tables = text.lineSequence()
            .map { it.trimStart(' ', '\t', BOM.single()) }
            .filter { it.startsWith("[") }
            .map { line -> line.trimStart('[').substringBefore(']').filterNot { it in TOML_IGNORED } }
            .toSet()
        return servers.filterTo(mutableSetOf()) { id ->
            tables.any { it == id || it.endsWith(".$id") } || Regex("\"${Regex.escape(id)}\"\\s*:").containsMatchIn(text)
        }
    }

    /** The text of [file]; null when it is too large or cannot be read. Bytes that are not UTF-8 are replaced. */
    private fun readOrNull(file: Path): String? = try {
        if (Files.size(file) > MAX_FILE_BYTES) null else String(Files.readAllBytes(file), UTF_8)
    } catch (e: IOException) {
        null
    }

    /**
     * [relative] inside the project, or null when it leads out of it or is the project itself. A link can lead
     * out of the project too; nothing out there is read.
     */
    private fun inProject(projectDir: Path, relative: String): Path? {
        val project = projectDir.toAbsolutePath().normalize()
        val path = project.resolve(relative).normalize()
        if (Path.of(relative).isAbsolute || path == project || !path.startsWith(project)) return null
        if (Files.exists(path) && !path.toRealPath().startsWith(project.toRealPath())) return null
        return path
    }

    private fun guidelines(projectDir: Path, block: String?): State? {
        val expected = block?.let(::unixText)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val file = projectDir.resolve(GUIDELINES_FILE)
        if (!Files.isRegularFile(file)) return State.NOT_INSTALLED
        if (Files.size(file) > MAX_FILE_BYTES) return null
        val text = unixText(Files.readString(file))
        return when {
            expected in text -> State.UP_TO_DATE
            // The first line of the block is the toolkit's marker of its own section.
            expected.lineSequence().first() in text.lineSequence() -> State.UPDATE_AVAILABLE
            else -> State.NOT_INSTALLED
        }
    }

    private fun unixText(text: String) = text.removePrefix(BOM).replace("\r\n", "\n")

    /** A hint is an extra: whatever prevents one leaves it out. */
    private fun <T> orNull(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        null
    }

    private const val MANIFEST_PATH = ".studio/skills-manifest.json"
    private const val BLOCK_PATH = "content/guidelines-block.md"
    private const val GUIDELINES_FILE = "AGENTS.md"
    private const val BOM = "\uFEFF"
    private const val TOML_IGNORED = " \t\"'"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private val FETCH_TIMEOUT: Duration = Duration.ofSeconds(3)
    private val SKILL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}

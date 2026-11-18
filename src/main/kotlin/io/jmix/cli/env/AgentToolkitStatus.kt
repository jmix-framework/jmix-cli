package io.jmix.cli.env

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.jmix.cli.util.string
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.Arrays
import java.util.HexFormat
import kotlin.text.Charsets.UTF_8

/**
 * Compares the skills and the guidelines of a project with the files the Jmix Agent Toolkit publishes for
 * that purpose (Jmix Studio reads the same ones). The skills folder, the skill names and the guidelines
 * marker come from those files; none are named here.
 */
object AgentToolkitStatus {
    enum class State { NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE }

    /** A null state means the toolkit file it needs could not be read or understood. */
    data class Status(val skills: State?, val guidelines: State?)

    /** [fetch] returns the text of a toolkit file of [ref] by its repository path, or null. */
    fun detect(
        projectDir: Path,
        ref: String,
        fetch: (path: String) -> String? = { AgentToolkitMetadata.fetch(ref, it, FETCH_TIMEOUT) },
    ): Status {
        val manifest = orNull { fetch(MANIFEST_PATH) }
        val block = orNull { fetch(BLOCK_PATH) }
        return Status(orNull { skills(projectDir, manifest) }, orNull { guidelines(projectDir, block) })
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
        val project = projectDir.toAbsolutePath().normalize()
        val store = project.resolve(local).normalize()
        if (Path.of(local).isAbsolute || store == project || !store.startsWith(project)) return null
        // A link can lead the store out of the project; nothing out there is read.
        if (Files.exists(store) && !store.toRealPath().startsWith(project.toRealPath())) return null
        // The manifest is external input: a name is followed only when it is one plain path element.
        val names = listed.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.asString }
            .filter(SKILL_NAME::matches)
        return when {
            names.none { Files.isDirectory(store.resolve(it)) } -> State.NOT_INSTALLED
            skillsHash(store, names) == sha256 -> State.UP_TO_DATE
            else -> State.UPDATE_AVAILABLE
        }
    }

    private fun guidelines(projectDir: Path, block: String?): State? {
        val expected = block?.let(::unixText)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val file = projectDir.resolve(GUIDELINES_FILE)
        if (!Files.isRegularFile(file)) return State.NOT_INSTALLED
        if (Files.size(file) > MAX_GUIDELINES_BYTES) return null
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
    private const val MAX_GUIDELINES_BYTES = 4L * 1024 * 1024
    private val FETCH_TIMEOUT: Duration = Duration.ofSeconds(3)
    private val SKILL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}

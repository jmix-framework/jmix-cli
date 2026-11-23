package io.jmix.cli.env

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.jmix.cli.util.Http
import io.jmix.cli.util.describeIoError
import io.jmix.cli.util.hostOf
import io.jmix.cli.util.string
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Duration
import kotlin.text.Charsets.UTF_8

/**
 * A server or an agent the toolkit offers. For an agent, [projectConfig] is the project MCP config file the
 * toolkit writes for it, relative to the project root (null when the toolkit names none), and [skipServers]
 * are the servers the toolkit never configures for it.
 */
data class ToolkitChoice(
    val value: String,
    val label: String,
    val projectConfig: String? = null,
    val skipServers: List<String> = emptyList(),
)

/** The MCP servers and AI agents the CLI offers, in the toolkit's order. */
data class ToolkitMcpOptions(val servers: List<ToolkitChoice>, val agents: List<ToolkitChoice>)

/**
 * Reads the files the Jmix Agent Toolkit publishes for its consumers (Jmix Studio reads the same ones),
 * so the CLI keeps no copy of the toolkit's servers and agents.
 */
object AgentToolkitMetadata {

    /**
     * Text of [path] on toolkit ref [ref] (a branch or HEAD). Throws FileNotFoundException for HTTP 404,
     * and another IOException when the file is larger than 256 KiB or unreachable; every message names the file.
     */
    fun fetch(ref: String, path: String, timeout: Duration): String =
        fetch(ref, path, timeout, AgentToolkitInstaller.RAW_CONTENT_BASE)

    internal fun fetch(ref: String, path: String, timeout: Duration, base: String): String {
        val failure = "Cannot load $path from Agent Toolkit $ref"
        val response = try {
            // Http.send bounds the whole exchange by the request timeout.
            Http.sendBytes(Http.client, Http.request("$base/$ref/$path", timeout).GET().build(), MAX_FILE_BYTES)
        } catch (e: IOException) {
            throw IOException("$failure: ${describeIoError(e, hostOf(base))}", e)
        }
        val status = response.statusCode()
        if (status != 200) {
            throw if (status == 404) FileNotFoundException("$failure: HTTP $status") else IOException("$failure: HTTP $status")
        }
        return String(response.body(), UTF_8)
    }

    /** Loads `.studio/studio-meta-data.json` of [ref] and applies [parseMcpOptions]. */
    fun mcpOptions(ref: String, timeout: Duration = Duration.ofSeconds(15)): ToolkitMcpOptions =
        mcpOptions(ref, timeout, AgentToolkitInstaller.RAW_CONTENT_BASE)

    internal fun mcpOptions(ref: String, timeout: Duration, base: String): ToolkitMcpOptions =
        parseMcpOptions(fetch(ref, METADATA_PATH, timeout, base))

    /**
     * The choices the toolkit enables by default, without the servers its step asks a text input for:
     * the installer prompts for that text, and the CLI never lets it prompt.
     */
    internal fun parseMcpOptions(json: String): ToolkitMcpOptions {
        val root = try {
            JsonParser.parseString(json) as? JsonObject
        } catch (e: JsonParseException) {
            throw unexpectedFormat()
        }
        val steps = objects((root?.get("install-flow") as? JsonObject)?.get("steps"))
        val inputs = objects(steps.firstOrNull { it.string("id") == MCP_STEP }?.get("inputs"))
        val textInputs = inputs.filter { it.string("type") == TEXT_INPUT_TYPE }.mapNotNull { it.string("id") }
        val servers = choices(inputs, SERVERS_INPUT).filterNot { server ->
            // Letters and digits alone: an input id has no `-` or `.`, and `_` may be kept or dropped.
            val prefix = server.value.filter(Char::isLetterOrDigit)
            textInputs.any { it.filter(Char::isLetterOrDigit).startsWith(prefix, ignoreCase = true) }
        }
        val agents = choices(inputs, AGENTS_INPUT)
        if (servers.isEmpty() || agents.isEmpty()) throw unexpectedFormat()
        return ToolkitMcpOptions(servers, agents)
    }

    private fun choices(inputs: List<JsonObject>, inputId: String): List<ToolkitChoice> =
        objects(inputs.firstOrNull { it.string("id") == inputId }?.get("choices")).mapNotNull { choice ->
            val value = choice.string("value")?.takeIf { CHOICE_VALUE.matches(it) } ?: return@mapNotNull null
            val isDefault = (choice.get("default") as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean == true
            if (!isDefault) return@mapNotNull null
            val label = choice.string("label")?.filterNot(Char::isISOControl)?.takeIf { it.isNotBlank() }
            // Only compared with server ids, never passed on: any text will do.
            val skipServers = (choice.get("skipServers") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.asString }.orEmpty()
            ToolkitChoice(value, label ?: value, choice.string("projectConfig")?.takeIf(::isProjectPath), skipServers)
        }

    /** A path the toolkit may name inside a project: relative, plain elements, no `.` or `..`. */
    private fun isProjectPath(path: String): Boolean =
        PROJECT_PATH.matches(path) && path.split('/').none { it == "." || it == ".." }

    /** [element] as an array of objects; anything else is not the toolkit's format. */
    private fun objects(element: JsonElement?): List<JsonObject> =
        (element as? JsonArray)?.map { it as? JsonObject ?: throw unexpectedFormat() } ?: throw unexpectedFormat()

    // Gson's own message is dropped: it quotes the document.
    private fun unexpectedFormat() = IOException("Unexpected Agent Toolkit metadata format.")

    internal const val METADATA_PATH = ".studio/studio-meta-data.json"
    private const val MCP_STEP = "mcp"
    private const val SERVERS_INPUT = "mcpServers"
    private const val AGENTS_INPUT = "mcpAgents"
    private const val TEXT_INPUT_TYPE = "userInput"
    private const val MAX_FILE_BYTES = 256 * 1024

    // Safe as one element of a comma-separated installer argument, and never an option.
    private val CHOICE_VALUE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val PROJECT_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
}

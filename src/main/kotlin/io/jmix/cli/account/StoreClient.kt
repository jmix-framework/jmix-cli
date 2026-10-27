package io.jmix.cli.account

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.jmix.cli.util.Http
import io.jmix.cli.util.string
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month

/** A subscription of the account. Only a trial has a [trialDownloadUrl], which must never be printed or logged. */
internal data class Subscription(val endDate: LocalDate?, val trialDownloadUrl: String?) {

    /** Hides the download link, so a subscription that reaches a message or a log does not leak it. */
    override fun toString(): String = "Subscription(endDate=$endDate, trialDownloadUrl=<hidden>)"
}

/** What the store said when a trial was started; either part can be missing. [link] must never be logged. */
internal data class StartedTrial(val link: String?, val expiryDate: LocalDate?) {

    /** Hides the download link, so a value that reaches a message or a log does not leak it. */
    override fun toString(): String = "StartedTrial(link=<hidden>, expiryDate=$expiryDate)"
}

/**
 * The trial endpoints of the Jmix Store, called as the signed-in user. The bearer token goes to [storeApiUrl] only.
 * The store can answer with anything, so bodies are read through checked casts and never quoted in a message.
 */
internal class StoreClient(
    private val storeApiUrl: String,
    private val tokens: AccessTokens,
    private val http: HttpClient = Http.client,
) {

    /** All subscriptions of the account. Entries that are not JSON objects are skipped. */
    fun subscriptions(): List<Subscription> = send("GET", "/subscriptions") { body ->
        (body as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::subscriptionOf) }
    }

    /** Whether the account can start a trial now; false unless the store says so. */
    fun trialAvailable(): Boolean = send("GET", "/trial/addons/check") { body ->
        ((body as? JsonObject)?.get("available") as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean ?: false
    }

    /** Starts the trial. The store does not always say where to download it: what it leaves out is null. */
    fun startTrial(): StartedTrial = send("POST", "/trial/addons/generate") { body ->
        val started = body as? JsonObject
        StartedTrial(started?.nonBlank("link"), parseStoreDate(started?.get("expiryDate")))
    }

    /**
     * Calls the store and passes the JSON of a successful answer to [read]; null from [read] means the body has
     * the wrong shape. A rejected access token is replaced once and the call is repeated once.
     */
    private fun <T : Any> send(method: String, path: String, read: (JsonElement) -> T?): T {
        var response = exchange(method, path, tokens.current())
        if (response.statusCode() == 401) {
            response = exchange(method, path, tokens.refresh())
            if (response.statusCode() == 401) throw IOException("the Jmix Store rejected the sign-in (HTTP 401)")
        }
        val status = response.statusCode()
        val json = parse(response.body())
        val failure = failureOf(status, json as? JsonObject)
        if (failure != null) throw failure
        return json?.let(read) ?: throw unexpectedResponse(status)
    }

    private fun exchange(method: String, path: String, accessToken: String): HttpResponse<String> {
        val builder = Http.request(storeApiUrl + path).header("Authorization", "Bearer $accessToken")
        // The generate call takes no body, so it sends no Content-Type either.
        val request = if (method == "POST") builder.POST(HttpRequest.BodyPublishers.noBody()) else builder.GET()
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}

/**
 * Reads a store date: a Jackson `LocalDate` object (`year`, `month` name or `monthValue`, `dayOfMonth`) or an
 * ISO date string. Anything else, and a date that does not exist, is null.
 */
internal fun parseStoreDate(element: JsonElement?): LocalDate? = try {
    when (element) {
        is JsonObject -> dateOf(element)
        is JsonPrimitive -> if (element.isString) LocalDate.parse(element.asString) else null
        else -> null
    }
} catch (e: DateTimeException) {
    null // Dropped on purpose: a date that cannot be read is no reason to fail.
} catch (e: IllegalArgumentException) {
    null // An unknown month name or a number that does not fit.
}

private fun dateOf(date: JsonObject): LocalDate? {
    val year = date.int("year") ?: return null
    val month = date.int("monthValue") ?: date.string("month")?.let { Month.valueOf(it).value } ?: return null
    val day = date.int("dayOfMonth") ?: return null
    return LocalDate.of(year, month, day)
}

private fun subscriptionOf(item: JsonObject) =
    Subscription(parseStoreDate(item["endDate"]), item.nonBlank("trialDownloadUrl"))

/** The failure a response describes, or null when it describes none. */
private fun failureOf(status: Int, fields: JsonObject?): IOException? {
    val error = fields?.nonBlank("error")
    val reason = fields?.nonBlank("error_description") ?: error
    return when {
        // Failures come in two shapes: OAuth-style error fields, and the store's own {errorCode, errorFields, message}.
        status !in 200..299 ->
            IOException(reason ?: fields?.nonBlank("message") ?: "the Jmix Store returned HTTP $status")
        error != null -> IOException(reason)
        else -> null
    }
}

/** The body as JSON, or null when it is not JSON. An empty body is JSON null. */
private fun parse(body: String): JsonElement? = try {
    JsonParser.parseString(body)
} catch (e: JsonParseException) {
    // Dropped on purpose: Gson's message quotes the body, which can hold download links.
    null
}

// Never add the body: it can hold download links.
private fun unexpectedResponse(status: Int) =
    IOException("the Jmix Store returned an unexpected response (HTTP $status)")

private fun JsonObject.nonBlank(name: String): String? = string(name)?.takeIf { it.isNotBlank() }

private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt

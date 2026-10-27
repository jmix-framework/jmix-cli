package io.jmix.cli.util

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * The member [name] when it is a JSON string; null when it is missing, null or any other kind of value.
 * Blank strings are returned as they are, so callers decide whether they matter.
 *
 * Reads only through checked casts: Gson's own conversions throw exceptions whose messages quote the whole
 * document, and a response body can hold tokens or download links.
 */
internal fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString

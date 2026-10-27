package io.jmix.cli

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Charsets.UTF_8

/**
 * In-process HTTP server on a loopback port, for tests that play a remote service.
 * Requests are routed by exact method and path, so a query string does not matter;
 * anything without a route gets 404. Every request is recorded, routed or not.
 */
class FakeServer : AutoCloseable {

    class Request(val method: String, val path: String, val query: String?, val headers: Headers, val body: String) {

        /** Fields of an `application/x-www-form-urlencoded` body. */
        fun form(): Map<String, String> = decodeFields(body)

        /** Fields of the query string. */
        fun query(): Map<String, String> = decodeFields(query.orEmpty())
    }

    class Response(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val handlers = ConcurrentHashMap<String, (Request) -> Response>()
    private val recorded = Collections.synchronizedList(mutableListOf<Request>())

    val url: String = "http://127.0.0.1:${server.address.port}"

    /** Snapshot of the requests received so far, oldest first. */
    val requests: List<Request> get() = synchronized(recorded) { recorded.toList() }

    init {
        server.createContext("/") { exchange -> exchange.use { answer(it) } }
        server.start()
    }

    /** Answers [method] requests to exactly [path] with whatever [handler] returns. */
    fun on(method: String, path: String, handler: (Request) -> Response) {
        handlers["$method $path"] = handler
    }

    override fun close() = server.stop(0)

    private fun answer(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val request = Request(
            method = exchange.requestMethod,
            path = uri.rawPath,
            query = uri.rawQuery,
            headers = exchange.requestHeaders,
            body = exchange.requestBody.readAllBytes().toString(UTF_8),
        )
        recorded.add(request)
        val response = handlers["${request.method} ${request.path}"]?.invoke(request) ?: Response(404)
        response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        if (response.body.isEmpty()) {
            exchange.sendResponseHeaders(response.status, NO_BODY)
        } else {
            exchange.sendResponseHeaders(response.status, response.body.size.toLong())
            exchange.responseBody.write(response.body)
        }
    }

    companion object {
        // sendResponseHeaders: -1 announces a response without a body.
        private const val NO_BODY = -1L

        fun json(status: Int, text: String): Response =
            Response(status, text.toByteArray(UTF_8), mapOf("Content-Type" to "application/json"))

        private fun decodeFields(fields: String): Map<String, String> =
            fields.split("&").filter { it.isNotEmpty() }.associate { field ->
                decode(field.substringBefore("=")) to decode(field.substringAfter("=", ""))
            }

        private fun decode(text: String): String = URLDecoder.decode(text, UTF_8)
    }
}

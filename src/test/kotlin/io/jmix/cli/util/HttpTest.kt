package io.jmix.cli.util

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively

class HttpTest {

    @Test
    fun `requests identify the CLI`() {
        val agent = Http.request("http://127.0.0.1:1/").build().headers().firstValue("User-Agent").orElseThrow()
        assertTrue(agent.startsWith("jmix-cli/"), agent)
    }

    @Test
    fun `account request deadline includes the response body`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sent = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            exchange.use {
                it.sendResponseHeaders(200, 100L)
                it.responseBody.write('{'.code)
                it.responseBody.flush()
                sent.countDown()
                release.await(10, SECONDS)
            }
        }
        server.start()
        try {
            val request = Http.request("http://127.0.0.1:${server.address.port}", Duration.ofSeconds(1)).build()
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                assertThrows<HttpTimeoutException> { Http.sendText(Http.accountClient, request) }
                assertTrue(sent.count == 0L)
            }
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}

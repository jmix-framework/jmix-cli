package io.jmix.cli.repo

import com.sun.net.httpserver.HttpServer
import io.jmix.cli.addon.AddonCatalogTest
import io.jmix.cli.addon.AddonProjectProfile
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

class AddonRepositoryTest {
    @TempDir lateinit var cacheDir: Path

    @Test
    @EnabledIfEnvironmentVariable(named = "JMIX_CLI_IT", matches = "true")
    fun `Studio live catalog resolves a free add-on and template-included Data Tools`() {
        val catalog = AddonRepository(cacheDir = cacheDir).catalog()
        val profile = AddonProjectProfile("build.gradle", setOf("io.jmix.flowui:jmix-flowui-starter",
            "io.jmix.datatools:jmix-datatools-starter", "io.jmix.datatools:jmix-datatools-flowui-starter"))
        val available = catalog.available("3.0.1", profile)
        assertTrue(available.any { it.id == "quartz" })
        assertTrue(available.any { it.id == "data-tools" && it.included })
        assertTrue(available.none { it.addon.commercial })
    }

    @Test
    fun `malformed response preserves validated cache and offline fallback`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = AddonCatalogTest.CATALOG_JSON
        server.createContext("/catalog") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val repo = AddonRepository("http://127.0.0.1:${server.address.port}/catalog", cacheDir)
        try {
            assertEquals(2, repo.catalog().addons.size)
            val good = Files.readString(repo.cacheFile)
            body = "<html>proxy error</html>"
            Files.setLastModifiedTime(repo.cacheFile, FileTime.from(Instant.EPOCH))
            assertEquals(2, repo.catalog().addons.size)
            assertEquals(good, Files.readString(repo.cacheFile))
        } finally {
            server.stop(0)
        }
        assertEquals(2, repo.catalog().addons.size)
    }

    @Test
    fun `catalog cache is isolated by full source URL and corrupt cache is rejected`() {
        val first = AddonRepository("http://127.0.0.1:1/a-b", cacheDir)
        Files.writeString(first.cacheFile, AddonCatalogTest.CATALOG_JSON)
        assertEquals(2, first.catalog().addons.size)
        val second = AddonRepository("http://127.0.0.1:1/ab", cacheDir)
        assertNotEquals(first.cacheFile, second.cacheFile)
        assertThrows(IOException::class.java) { second.catalog() }
        Files.writeString(first.cacheFile, "corrupt")
        assertThrows(IOException::class.java) { first.catalog() }
    }
}

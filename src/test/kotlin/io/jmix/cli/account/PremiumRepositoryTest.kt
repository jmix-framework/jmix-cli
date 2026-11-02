package io.jmix.cli.account

import io.jmix.cli.FakeServer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PremiumRepositoryTest {
    @TempDir lateinit var dir: Path
    private val file get() = dir.resolve("gradle/gradle.properties")
    private val sessions get() = SessionStore(dir.resolve("account/session.json"))

    private fun write(text: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun properties() = Properties().apply { Files.newInputStream(file).use(::load) }

    private fun prepare(server: FakeServer, sid: String = "repo-secret"): Pair<String, String> {
        server.issuesTokens()
        server.on("GET", "/app/rest/v3/studio/license/check") {
            FakeServer.json(200, com.google.gson.Gson().toJson(mapOf("activeLicense" to true, "sid" to sid)))
        }
        sessions.save(Session("global", server.url, server.url, "r0"))
        return PremiumRepository(sessions, file, { null }).prepare()
    }

    @Test
    fun `saves account key outside the project with owner-only permissions`() {
        FakeServer().use { server ->
            assertEquals("premiumRepoUser" to "premiumRepoPass", prepare(server))
            assertEquals("repo", properties().getProperty("premiumRepoUser"))
            assertEquals("secret", properties().getProperty("premiumRepoPass"))
            if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
            }
            val before = Files.readAllBytes(file)
            val requests = server.requests.size
            PremiumRepository(sessions, file, { null }).prepare()
            assertArrayEquals(before, Files.readAllBytes(file))
            assertEquals(requests, server.requests.size)
        }
    }

    @Test
    fun `configured Gradle and CI credentials need no account or file changes`() {
        val original = "# My settings\r\npremiumRepoUser=local\r\npremiumRepoPass=secret\r\n"
        write(original)
        assertEquals("premiumRepoUser" to "premiumRepoPass", PremiumRepository(sessions, file, { null }).prepare())
        assertEquals(original, Files.readString(file))
        Files.delete(file)
        val env = mapOf("ORG_GRADLE_PROJECT_premiumRepoUser" to "ci", "ORG_GRADLE_PROJECT_premiumRepoPass" to "secret")
        PremiumRepository(sessions, file, env::get).prepare()
        assertFalse(Files.exists(file))
    }

    @Test
    fun `conflicts preserve old values and reuse matching suffixed properties`() {
        val original = "# Keep these\r\norg.gradle.jvmargs=-Xmx2g\r\npremiumRepoUser=old\r\n" +
            "premiumRepoUser.0=repo\r\npremiumRepoPass.0=other\r\npremiumRepoPass.1=secret"
        write(original)
        FakeServer().use { server ->
            assertEquals("premiumRepoUser.0" to "premiumRepoPass.1", prepare(server))
            assertEquals(original, Files.readString(file))
        }
        write("# Keep these\r\npremiumRepoUser=old\r\n")
        FakeServer().use { server ->
            assertEquals("premiumRepoUser.0" to "premiumRepoPass", prepare(server))
            assertTrue(Files.readString(file).startsWith("# Keep these\r\npremiumRepoUser=old\r\n"))
            assertEquals("old", properties().getProperty("premiumRepoUser"))
            assertEquals("repo", properties().getProperty("premiumRepoUser.0"))
            assertEquals("secret", properties().getProperty("premiumRepoPass"))
        }
    }

    @Test
    fun `standard properties escaping preserves credentials and trailing continuations`() {
        write("# Keep this\norg.gradle.jvmargs=-Xmx2g\\")
        FakeServer().use { server ->
            prepare(server, "répo:1-p\\a=ss!#")
            assertEquals("répo:1", properties().getProperty("premiumRepoUser"))
            assertEquals("p\\a=ss!#", properties().getProperty("premiumRepoPass"))
            assertEquals("-Xmx2g", properties().getProperty("org.gradle.jvmargs"))
        }
    }

    @Test
    fun `environment conflicts cannot shadow newly saved credentials`() {
        val env = mapOf("ORG_GRADLE_PROJECT_premiumRepoUser" to "old", "ORG_GRADLE_PROJECT_premiumRepoUser.0" to "other")
        FakeServer().use { server ->
            prepare(server)
            Files.delete(file)
            assertEquals("premiumRepoUser.1" to "premiumRepoPass", PremiumRepository(sessions, file, env::get).prepare())
            write("premiumRepoUser=repo\npremiumRepoUser.1=conflict\n")
            assertEquals("premiumRepoUser.2" to "premiumRepoPass", PremiumRepository(sessions, file, env::get).prepare())
        }
    }

    @Test
    fun `symbolic link is preserved and a failed save leaves no credential copy`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val original = dir.resolve("original.properties")
        Files.writeString(original, "# Preserve me\n")
        Files.createDirectories(file.parent)
        Files.createSymbolicLink(file, original)
        FakeServer().use { server ->
            assertThrows<IOException> { prepare(server) }
            assertTrue(Files.isSymbolicLink(file))
            assertEquals("# Preserve me\n", Files.readString(original))
            Files.list(file.parent).use { assertEquals(listOf(file), it.toList()) }
        }
    }

    @Test
    fun `missing account or license and malformed keys never modify properties`() {
        write("# Preserve me\n")
        assertTrue(assertThrows<IOException> { PremiumRepository(sessions, file, { null }).prepare() }
            .message!!.contains("jmix login"))
        FakeServer().use { server ->
            for (key in listOf("PRIVATE", "PRIVATE-", "-PRIVATE", "a-b-PRIVATE", "a-PRIVATE\nvalue")) {
                val error = assertThrows<IOException> { prepare(server, key) }
                assertFalse(error.message!!.contains("PRIVATE"))
                assertEquals("# Preserve me\n", Files.readString(file))
            }
            server.on("GET", "/app/rest/v3/studio/license/check") {
                FakeServer.json(200, """{"activeLicense":false,"sid":"PRIVATE"}""")
            }
            assertThrows<IOException> { PremiumRepository(sessions, file, { null }).prepare() }
            assertEquals("# Preserve me\n", Files.readString(file))
        }
    }

    @Test
    fun `properties changed during the account request are preserved`() {
        write("# Original\n")
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/studio/license/check") {
                write("# Updated by another process\n")
                FakeServer.json(200, """{"activeLicense":true,"sid":"repo-secret"}""")
            }
            sessions.save(Session("global", server.url, server.url, "r0"))
            assertThrows<IOException> { PremiumRepository(sessions, file, { null }).prepare() }
            assertEquals("# Updated by another process\n", Files.readString(file))
            Files.list(file.parent).use { assertEquals(listOf(file), it.toList()) }
        }
    }

    @Test
    fun `Gradle user home environment selects the properties file`() {
        assertEquals(dir.resolve("custom/gradle.properties"), PremiumRepository.defaultFile(
            { if (it == "GRADLE_USER_HOME") dir.resolve("custom").toString() else null }, dir))
        assertEquals(dir.resolve(".gradle/gradle.properties"), PremiumRepository.defaultFile({ null }, dir))
    }
}

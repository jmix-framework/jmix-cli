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

    private fun prepare(
        server: FakeServer,
        sid: String = "repo-secret",
        action: PremiumConflictAction = PremiumConflictAction.KEEP,
        onSaved: (Path) -> Unit = {},
    ): Pair<String, String> {
        server.issuesTokens()
        server.on("GET", "/app/rest/v3/studio/license/check") {
            FakeServer.json(200, com.google.gson.Gson().toJson(mapOf("activeLicense" to true, "sid" to sid)))
        }
        sessions.save(Session("global", server.url, server.url, "r0"))
        return PremiumRepository(sessions, file, { null }).prepare(onConflict = { _, _ -> action }, onSaved = onSaved)
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
            PremiumRepository(sessions, file, { null }).prepare(onSaved = { fail("Nothing was saved") })
            assertArrayEquals(before, Files.readAllBytes(file))
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
    fun `system properties can override a complete environment pair`() {
        val original = "systemProp.org.gradle.project.premiumRepoUser=old\npremiumRepoUser=local\n"
        write(original)
        val env = mapOf("ORG_GRADLE_PROJECT_premiumRepoUser" to "repo", "ORG_GRADLE_PROJECT_premiumRepoPass" to "secret")
        FakeServer().use { server ->
            prepare(server)
            val requests = server.requests.size
            val repository = PremiumRepository(sessions, file, env::get)
            assertEquals("premiumRepoUser" to "premiumRepoPass", repository.prepare(
                onConflict = { _, canReplace -> assertTrue(canReplace); PremiumConflictAction.KEEP },
                onSaved = { fail("Nothing was saved") },
            ))
            assertTrue(server.requests.drop(requests).any { it.path.endsWith("/studio/license/check") })
            assertEquals(original, Files.readString(file))

            write("premiumRepoUser=local\npremiumRepoPass=local-pass\n")
            val before = server.requests.size
            repository.prepare(onConflict = { _, _ -> fail("Environment credentials take precedence") })
            assertEquals(before, server.requests.size)
        }
    }

    @Test
    fun `blank higher-priority properties do not fall back to lower-priority credentials`() {
        val original = "premiumRepoUser=local\npremiumRepoPass=secret\n"
        val userEnv = "ORG_GRADLE_PROJECT_premiumRepoUser"
        val passEnv = "ORG_GRADLE_PROJECT_premiumRepoPass"
        for ((contents, env) in listOf(
            original to mapOf(userEnv to "", passEnv to "secret"),
            (original + "systemProp.org.gradle.project.premiumRepoUser=\n") to mapOf(userEnv to "ci", passEnv to "secret"),
        )) {
            write(contents)
            val error = assertThrows<IOException> { PremiumRepository(sessions, file, env::get).prepare() }
            assertTrue(error.message!!.contains("jmix login"))
            assertEquals(contents, Files.readString(file))
        }
    }

    @Test
    fun `conflicts preserve old values and reuse matching suffixed properties`() {
        val original = "# Keep these\r\norg.gradle.jvmargs=-Xmx2g\r\npremiumRepoUser=old\r\n" +
            "premiumRepoUser.0=repo\r\npremiumRepoPass.0=other\r\npremiumRepoPass.1=secret"
        write(original)
        FakeServer().use { server ->
            assertEquals("premiumRepoUser.0" to "premiumRepoPass.1", prepare(server, action = PremiumConflictAction.SEPARATE))
            assertEquals(original, Files.readString(file))
        }
        write("# Keep these\r\npremiumRepoUser=old\r\n")
        FakeServer().use { server ->
            assertEquals("premiumRepoUser.0" to "premiumRepoPass", prepare(server, action = PremiumConflictAction.SEPARATE))
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
            assertEquals("premiumRepoUser.1" to "premiumRepoPass", PremiumRepository(sessions, file, env::get)
                .prepare(onConflict = { _, canReplace -> assertFalse(canReplace); PremiumConflictAction.SEPARATE }))
            write("premiumRepoUser=repo\npremiumRepoUser.1=conflict\n")
            assertEquals("premiumRepoUser.2" to "premiumRepoPass", PremiumRepository(sessions, file, env::get)
                .prepare(onConflict = { _, _ -> PremiumConflictAction.SEPARATE }))
        }
    }

    @Test
    fun `complete conflicting credentials require a decision and keep is the default`() {
        val original = "# Existing account\npremiumRepoUser=old\npremiumRepoPass=oldpass\n"
        write(original)
        FakeServer().use { server ->
            assertEquals("premiumRepoUser" to "premiumRepoPass", prepare(server, onSaved = { fail("Nothing was saved") }))
            assertEquals(original, Files.readString(file))
            var conflicts = 0
            assertEquals("premiumRepoUser.0" to "premiumRepoPass.0", PremiumRepository(sessions, file, { null }).prepare(
                onConflict = { path, canReplace ->
                    assertEquals(file, path)
                    assertTrue(canReplace)
                    conflicts++
                    PremiumConflictAction.SEPARATE
                },
            ))
            assertEquals(1, conflicts)
            assertTrue(Files.readString(file).startsWith(original))
        }
    }

    @Test
    fun `configured credentials remain usable when the account key is unavailable`() {
        val original = "premiumRepoUser=old\npremiumRepoPass=oldpass\n"
        write(original)
        FakeServer().use { server ->
            prepare(server)
            for ((status, body) in listOf(
                200 to """{"activeLicense":false}""",
                503 to """{"activeLicense":false}""",
                200 to """{"activeLicense":true,"sid":"malformed"}""",
            )) {
                server.on("GET", "/app/rest/v3/studio/license/check") {
                    FakeServer.json(status, body)
                }
                assertEquals("premiumRepoUser" to "premiumRepoPass", PremiumRepository(sessions, file, { null })
                    .prepare(onSaved = { fail("Nothing was saved") }))
                assertEquals(original, Files.readString(file))
            }
        }
    }

    @Test
    fun `overwrite changes only credential records and reports the saved file`() {
        write("# Keep this comment with a trailing slash \\\r\n" +
            "org.gradle.jvmargs=-Xmx2g\r\npremiumRepoUser:old\r\npremiumRepoUser=duplicate\r\n" +
            "premiumRepoP\\ass=old\\\r\n    password\r\n" +
            "systemProp.org.gradle.project.premiumRepoUser=also-old\r\n! Footer\r\n")
        val notifications = mutableListOf<Path>()
        FakeServer().use { server ->
            assertEquals("premiumRepoUser" to "premiumRepoPass", prepare(server,
                action = PremiumConflictAction.REPLACE, onSaved = notifications::add))
        }
        assertEquals(listOf(file), notifications)
        assertEquals("repo", properties().getProperty("premiumRepoUser"))
        assertEquals("secret", properties().getProperty("premiumRepoPass"))
        assertEquals("-Xmx2g", properties().getProperty("org.gradle.jvmargs"))
        val text = Files.readString(file)
        assertTrue(text.startsWith("# Keep this comment with a trailing slash \\\r\norg.gradle.jvmargs=-Xmx2g\r\n! Footer\r\n"))
        assertFalse(text.contains("old") || text.contains("duplicate") || text.contains("password"))
        assertEquals(1, text.lineSequence().count { it.startsWith("premiumRepoUser=") })
    }

    @Test
    fun `partial conflicts remain untouched when no conflict handler is supplied`() {
        val original = "premiumRepoUser=old\n"
        write(original)
        FakeServer().use { server ->
            prepare(server)
            assertEquals("premiumRepoUser" to "premiumRepoPass", PremiumRepository(sessions, file, { null }).prepare(
                onSaved = { fail("Nothing was saved") },
            ))
            assertEquals(original, Files.readString(file))
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
        val error = assertThrows<IOException> {
            PremiumRepository.defaultFile({ if (it == "GRADLE_USER_HOME") "relative-gradle" else null }, dir)
        }
        assertTrue(error.message!!.contains("Set GRADLE_USER_HOME to an absolute path"))
    }
}

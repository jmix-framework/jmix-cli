package io.jmix.cli.account

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SessionStoreTest {

    @Test
    fun `save makes an existing account directory private`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val account = Files.createDirectory(dir.resolve("account"))
        Files.setPosixFilePermissions(account, PosixFilePermissions.fromString("rwxr-xr-x"))
        SessionStore(account.resolve("session.json")).save(session)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(account)))
    }

    @TempDir lateinit var dir: Path
    private val session = Session("global", "https://sso.jmix.io", "https://store-admin.jmix.io", "r1")

    @Test
    fun `saved session loads back`() {
        val store = SessionStore(dir.resolve("account/session.json"))
        store.save(session)
        assertEquals(session, store.load())
    }

    @Test
    fun `saved identity loads back and old sessions need no identity`() {
        val store = SessionStore(dir.resolve("session.json"))
        val named = session.copy(identity = Identity("Ann Lee", "ann@example.com"))
        store.save(named)
        assertEquals(named, store.load())
        Files.writeString(store.file, """{"region":"global","ssoUrl":"sso","storeUrl":"store","refreshToken":"r1"}""")
        assertEquals(Identity(null, null), store.load()?.identity)
    }

    @Test
    fun `saved identity fields are checked and cannot inject terminal controls`() {
        val store = SessionStore(dir.resolve("session.json"))
        Files.writeString(store.file, """{"region":"global","ssoUrl":"sso","storeUrl":"store","refreshToken":"r1","identity":{"name":"Ann\u001b\n","email":5}}""")
        assertEquals(Identity("Ann", null), store.load()?.identity)
    }

    @Test
    fun `missing, corrupt or incomplete files load as signed out`() {
        val file = dir.resolve("session.json")
        assertNull(SessionStore(file).load())
        Files.writeString(file, "{")
        assertNull(SessionStore(file).load())
        Files.writeString(file, """{"region":"global","ssoUrl":"https://sso.jmix.io"}""")
        assertNull(SessionStore(file).load())
        for (text in listOf(
            "", "[]",
            """{"region":"global","ssoUrl":"a","storeUrl":"b","refreshToken":{}}""",
            """{"region":"cn","ssoUrl":"a","storeUrl":"b","refreshToken":"r"}""",
        )) {
            Files.writeString(file, text)
            assertNull(SessionStore(file).load(), text)
        }
    }

    @Test
    fun `blank and non-string values load as signed out`() {
        val file = dir.resolve("session.json")
        fun withToken(json: String) =
            Files.writeString(file, """{"region":"global","ssoUrl":"a","storeUrl":"b","refreshToken":$json}""")
        withToken("\"r\"")
        assertEquals(Session("global", "a", "b", "r"), SessionStore(file).load())
        for (token in listOf("\"\"", "\" \"", "5", "true", "null")) {
            withToken(token)
            assertNull(SessionStore(file).load(), token)
        }
    }

    @Test
    fun `session file is private to the user`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val file = dir.resolve("account/session.json")
        SessionStore(file).save(session)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
    }

    @Test
    fun `save replaces the previous session and delete reports whether one existed`() {
        val store = SessionStore(dir.resolve("session.json"))
        store.save(session)
        store.save(session.copy(refreshToken = "r2"))
        assertEquals("r2", store.load()?.refreshToken)
        assertTrue(store.delete())
        assertFalse(store.delete())
        assertNull(store.load())
    }

    @Test
    fun `session text hides the refresh token`() {
        assertFalse(session.copy(refreshToken = "secret-r1").toString().contains("secret-r1"))
    }

    @Test
    fun `a failed save leaves no copy of the token behind`() {
        val file = dir.resolve("session.json")
        Files.createDirectory(file) // a directory in the way makes the final move fail
        assertThrows<IOException> { SessionStore(file).save(session) }
        Files.list(dir).use { assertEquals(listOf(file), it.toList()) }
    }
}

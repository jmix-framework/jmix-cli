package io.jmix.cli.account

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.jmix.cli.util.string
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/** A saved session and its display identity. Access tokens are requested when needed and never persisted. */
internal data class Session(
    val region: String,
    val ssoUrl: String,
    val storeUrl: String,
    val refreshToken: String,
    val identity: Identity = Identity(null, null),
) {

    /** Hides the refresh token, so a session that reaches a message or a log does not leak it. */
    override fun toString(): String =
        "Session(region=$region, ssoUrl=$ssoUrl, storeUrl=$storeUrl, refreshToken=<hidden>)"
}

/** Keeps the signed-in [Session] in one JSON file that only its owner can read. */
internal class SessionStore(val file: Path = defaultFile()) {

    /** The saved session, or null when signed out. A missing or damaged file reads as signed out. */
    fun load(): Session? = try {
        parse(Files.readString(file))
    } catch (e: Exception) {
        // Dropped on purpose: Gson's message quotes the file, which holds the refresh token.
        null
    }

    /** Replaces the saved session atomically; a failed save keeps the old one and leaves no copy of the token. */
    fun save(session: Session) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir, *ownerOnly("rwx------"))
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        val tmp = Files.createTempFile(dir, "session", ".tmp", *ownerOnly("rw-------"))
        try {
            Files.writeString(tmp, Gson().toJson(session))
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Nothing is left to delete after a successful move.
            Files.deleteIfExists(tmp)
        }
    }

    /** Signs out. Returns whether a session was saved. */
    fun delete(): Boolean = Files.deleteIfExists(file)

    private fun parse(text: String): Session? {
        val json = JsonParser.parseString(text) as? JsonObject ?: return null
        fun nonBlank(name: String) = json.string(name)?.takeIf { it.isNotBlank() }
        val session = Session(
            region = nonBlank("region") ?: return null,
            ssoUrl = nonBlank("ssoUrl") ?: return null,
            storeUrl = nonBlank("storeUrl") ?: return null,
            refreshToken = nonBlank("refreshToken") ?: return null,
            identity = Identity.fromClaims(json["identity"] as? JsonObject),
        )
        AccountRegion.of(session.region) // An unknown id throws, which load reads as signed out.
        return session
    }

    /** [permissions] as an initial attribute. Windows has no POSIX view and rejects it, so it gets none. */
    private fun ownerOnly(permissions: String): Array<FileAttribute<*>> =
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions)))
        } else {
            emptyArray()
        }

    companion object {
        fun defaultFile(): Path = Path.of(System.getProperty("user.home"), ".jmix", "account", "session.json")
    }
}

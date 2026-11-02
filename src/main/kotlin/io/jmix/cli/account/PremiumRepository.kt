package io.jmix.cli.account

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

internal enum class PremiumConflictAction { KEEP, REPLACE, SEPARATE }

/** Saves account credentials in Gradle's user home, keeping secrets out of generated projects. */
internal class PremiumRepository(
    private val sessions: SessionStore = SessionStore(),
    private val propertiesFile: Path = defaultFile(),
    private val env: (String) -> String? = System::getenv,
) {
    /** Property names to reference in the build script; values never leave this method. */
    fun prepare(
        onConflict: (Path, Boolean) -> PremiumConflictAction = { _, _ -> PremiumConflictAction.KEEP },
        onSaved: (Path) -> Unit = {},
    ): Pair<String, String> {
        val bytes = if (Files.exists(propertiesFile)) Files.readAllBytes(propertiesFile) else byteArrayOf()
        val properties = Properties()
        try {
            bytes.inputStream().use(properties::load)
        } catch (e: IllegalArgumentException) {
            throw IOException("Cannot read Gradle properties at $propertiesFile. Correct the properties file and retry.")
        }
        fun values(name: String): List<String> = listOfNotNull(
            env("ORG_GRADLE_PROJECT_$name"), properties.getProperty("systemProp.org.gradle.project.$name"),
            properties.getProperty(name),
        )

        val configured = values(USER).any(String::isNotBlank) && values(PASS).any(String::isNotBlank)
        if (configured && env("ORG_GRADLE_PROJECT_$USER") != null && env("ORG_GRADLE_PROJECT_$PASS") != null) return USER to PASS

        val setupHint = "Configure premiumRepoUser and premiumRepoPass in $propertiesFile, or use " +
            "ORG_GRADLE_PROJECT_premiumRepoUser and ORG_GRADLE_PROJECT_premiumRepoPass."
        val session = sessions.load() ?: if (configured) return USER to PASS
            else throw IOException("Commercial add-ons require premium repository access. Run 'jmix login' to use your account license. $setupHint")
        val key = try {
            StoreClient(AccountEndpoints(session.ssoUrl, session.storeUrl).storeApiUrl, AccessTokens(sessions, session))
                .premiumLicenseKey()
        } catch (e: IOException) {
            if (configured) return USER to PASS
            throw e
        } ?: if (configured) return USER to PASS
            else throw IOException("Your Jmix account has no available commercial license key. $setupHint")
        val parts = key.split('-').map(String::trim)
        if (parts.size != 2 || parts.any { it.isEmpty() || it.any(Char::isISOControl) }) {
            throw IOException("The Jmix Store returned an invalid commercial license key. $setupHint")
        }

        val credentials = mapOf(USER to parts[0], PASS to parts[1])
        if (credentials.any { (name, credential) -> values(name).any { it != credential } }) {
            val canReplace = credentials.all { (name, credential) ->
                env("ORG_GRADLE_PROJECT_$name")?.let { it == credential } ?: true
            }
            when (onConflict(propertiesFile, canReplace)) {
                PremiumConflictAction.KEEP -> return USER to PASS
                PremiumConflictAction.REPLACE -> {
                    if (!canReplace) throw IOException("Premium credentials from environment variables cannot be replaced. Update the environment or save account credentials separately.")
                    save(bytes, credentials, replace = true)
                    onSaved(propertiesFile)
                    return USER to PASS
                }
                PremiumConflictAction.SEPARATE -> {}
            }
        }

        val additions = linkedMapOf<String, String>()
        fun property(base: String, credential: String): String {
            val names = listOf(base) + (0..100).map { "$base.$it" }
            return names.firstOrNull { values(it).isNotEmpty() && values(it).all { value -> value == credential } }
                ?: names.firstOrNull { values(it).isEmpty() }?.also { additions[it] = credential }
                ?: throw IOException("Cannot find a free premium repository property name in $propertiesFile.")
        }
        val names = property(USER, parts[0]) to property(PASS, parts[1])
        if (additions.isNotEmpty()) {
            save(bytes, additions)
            onSaved(propertiesFile)
        }
        return names
    }

    private fun save(original: ByteArray, additions: Map<String, String>, replace: Boolean = false) {
        val file = propertiesFile.toAbsolutePath()
        if (Files.isSymbolicLink(file)) throw IOException("Cannot save premium credentials through a symbolic link: $file")
        val current = if (Files.exists(file)) Files.readAllBytes(file) else byteArrayOf()
        if (!current.contentEquals(original)) throw IOException("Gradle properties changed while checking the license. Retry generation.")
        Files.createDirectories(file.parent)
        val attributes = if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else emptyArray()
        val tmp = Files.createTempFile(file.parent, "premium-", ".tmp", *attributes)
        try {
            val originalText = original.toString(Charsets.ISO_8859_1)
            val text = if (replace) withoutCredentials(originalText) else originalText
            val newline = if ("\r\n" in text) "\r\n" else "\n"
            val encoded = ByteArrayOutputStream().also { output ->
                Properties().apply { additions.forEach { (key, value) -> setProperty(key, value) } }.store(output, null)
            }.toString(Charsets.ISO_8859_1)
                .lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.joinToString(newline, postfix = newline)
            // A blank line terminates a trailing continuation without rewriting unrelated properties.
            val separator = if (text.isEmpty()) "" else if (text.endsWith('\n')) newline else newline + newline
            Files.write(tmp, (text + separator + encoded).toByteArray(Charsets.ISO_8859_1))
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Remove whole property records, including escaped keys, duplicates and continued values. */
    private fun withoutCredentials(text: String): String = buildString {
        val keys = setOf(USER, PASS, "systemProp.org.gradle.project.$USER", "systemProp.org.gradle.project.$PASS")
        var record = ""
        fun appendRecord() {
            val properties = Properties().apply { load(record.reader()) }
            if (properties.keys.none { it in keys }) append(record)
            record = ""
        }
        for (line in text.splitToSequence(Regex("(?<=\n)|(?<=\r)(?!\n)"))) {
            val comment = record.isEmpty() && line.trimStart(' ', '\t', '\u000c').firstOrNull() in listOf('#', '!')
            record += line
            val trailingSlashes = line.trimEnd('\r', '\n').takeLastWhile { it == '\\' }.length
            if (comment || trailingSlashes % 2 == 0) appendRecord()
        }
        if (record.isNotEmpty()) appendRecord()
    }

    companion object {
        const val USER = "premiumRepoUser"
        const val PASS = "premiumRepoPass"

        fun defaultFile(
            env: (String) -> String? = System::getenv,
            home: Path = Path.of(System.getProperty("user.home")),
        ): Path = (env("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".gradle"))
            .resolve("gradle.properties")
    }
}

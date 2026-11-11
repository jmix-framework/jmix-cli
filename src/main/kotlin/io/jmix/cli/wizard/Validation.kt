package io.jmix.cli.wizard

import java.net.URI
import java.net.URISyntaxException
import javax.lang.model.SourceVersion

/**
 * Input validation for the project wizard. The project field patterns retain
 * Studio's original ranges (StudioRegex.java, MainSettingsStep.kt, ProjectCreator.java).
 */
object Validation {

    private val PROJECT_NAME_PATTERN = Regex("[A-z]+[A-z0-9_-]+")
    private val PACKAGE_PATTERN = Regex("[a-z0-9_.]+")
    private val PROJECT_ID_PATTERN = Regex("[A-z]+[A-z0-9]+$|^$")
    private val INVALID_NAMESPACE = Regex("[^a-z0-9]|(^\\d+)")

    const val MAX_PROJECT_ID_LENGTH = 7

    /** Returns an error message or null when valid. */
    fun validateProjectName(value: String): String? = when {
        value.isBlank() -> "Project name cannot be empty."
        !PROJECT_NAME_PATTERN.matches(value) ->
            "Project name should start with a letter and contain only letters, digits, dash or underscore symbols."
        SourceVersion.isKeyword(value.lowercase()) -> "Project name cannot be a java keyword."
        else -> null
    }

    fun validateRootPackage(value: String): String? = when {
        value.isBlank() -> "Root package cannot be empty"
        !PACKAGE_PATTERN.matches(value) ->
            "Root package should contain only lowercase letters, digits, underscores, and dots."
        !SourceVersion.isName(value) ->
            "Invalid Java package name. Each segment must be a Java identifier without keywords."
        else -> null
    }

    fun validateProjectId(value: String, mandatory: Boolean): String? = when {
        value.length > MAX_PROJECT_ID_LENGTH -> "Project id cannot be longer than 7 symbols"
        mandatory && value.isBlank() -> "Project id must be specified."
        !PROJECT_ID_PATTERN.matches(value) ->
            "Project id should start with a letter and contain letters and digits only."
        else -> null
    }

    /** Validates the base URL before appending Maven artifact paths. */
    fun validateRepositoryUrl(value: String): String? {
        if (value.isBlank()) return "Repository URL cannot be empty."
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return "Repository URL must start with http:// or https://."
        }
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            null
        }
        return when {
            uri?.host == null -> "Repository URL must be a valid HTTP(S) URL with a host."
            uri.port != -1 && uri.port !in 1..65535 -> "Repository URL port must be between 1 and 65535."
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
                "Use a repository base URL without credentials, a query, or a fragment."
            else -> null
        }
    }

    /** Studio's ProjectCreator.transformProjectNamespace. */
    fun transformProjectNamespace(src: String): String {
        val result = INVALID_NAMESPACE.replace(src.lowercase(), "")
        return result.ifBlank { "myproject" }
    }
}

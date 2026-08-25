package io.jmix.cli.wizard

import javax.lang.model.SourceVersion

/**
 * Input validation ported from Studio (StudioRegex.java, MainSettingsStep.kt,
 * ProjectCreator.java). Regexes are verbatim — including the [A-z] ranges —
 * so the CLI accepts exactly what Studio accepts.
 */
object Validation {

    private val PROJECT_NAME_PATTERN = Regex("[A-z]+[A-z0-9_-]+")
    private val PACKAGE_PATTERN = Regex("([a-z0-9_]+[.]?)+")
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
            "Root package should contain only digits, lowercase letters or underscores"
        value.substringAfterLast('.') != sanitizeJavaIdentifier(value.substringAfterLast('.')) ->
            "Invalid java package name"
        else -> null
    }

    fun validateProjectId(value: String, mandatory: Boolean): String? = when {
        value.length > MAX_PROJECT_ID_LENGTH -> "Project id cannot be longer than 7 symbols"
        mandatory && value.isBlank() -> "Project id must be specified."
        !PROJECT_ID_PATTERN.matches(value) ->
            "Project id should start with a letter and contain letters and digits only."
        else -> null
    }

    /** Studio's ProjectCreator.transformProjectNamespace. */
    fun transformProjectNamespace(src: String): String {
        val result = INVALID_NAMESPACE.replace(src.lowercase(), "")
        return result.ifBlank { "myproject" }
    }

    /** IntelliJ StringUtil.sanitizeJavaIdentifier equivalent. */
    fun sanitizeJavaIdentifier(name: String): String {
        val kept = name.filter { Character.isJavaIdentifierPart(it) }
        if (kept.isEmpty()) return kept
        return if (!Character.isJavaIdentifierStart(kept.first())) "_$kept" else kept
    }
}

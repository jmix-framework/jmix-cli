package io.jmix.cli.template

/**
 * Gson model of template.json inside the jmix-studio-templates artifact.
 * Mirrors Studio's TemplateMetadata (JmixTemplateSelectStep.kt), including
 * Gson's absent-field values: fields are nullable/zero/false when missing,
 * Kotlin defaults do NOT apply (Gson instantiates via Unsafe).
 */
data class TemplateMetadata(
    val version: Int = 0,
    val name: String? = null,
    val order: Int = 0,
    val addon: Boolean = false,
    val description: String? = null,
    val params: List<TemplateParam>? = null,
    val hideForSubproject: Boolean = false,
    val subFolder: String? = null,
    val tags: String? = "",
) {
    fun param(name: String): TemplateParam? = params?.find { it.name == name }

    /** Studio semantics: a param absent from template.json counts as mandatory. */
    fun isMandatoryParam(name: String): Boolean = param(name)?.mandatory ?: true

    fun isVisibleParam(name: String, defaultIfNotPresent: Boolean = true): Boolean =
        param(name)?.let { !it.hidden } ?: defaultIfNotPresent
}

data class TemplateParam(
    val name: String? = null,
    val mandatory: Boolean = false,
    val hidden: Boolean = false,
    val defaultValue: String? = null,
)

/** A template found in the templates jar: its directory name plus parsed metadata. */
data class Template(
    val id: String,
    val metadata: TemplateMetadata,
) {
    val displayName: String get() = metadata.name ?: id
}

/** Param name constants (wizard model property names). */
object TemplateParams {
    const val PROJECT_ID = "projectId"
    const val PROJECT_THEME = "projectTheme"
    const val ROOT_PACKAGE = "rootPackage"
    const val LOCALES = "locales"
}

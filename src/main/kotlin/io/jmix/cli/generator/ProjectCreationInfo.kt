package io.jmix.cli.generator

import io.jmix.cli.template.TemplateMetadata
import java.nio.file.Path

/**
 * Locale of the generated project. Mirrors Studio's JmixLocale — templates
 * access `code`, `displayName` and `default` via Groovy property syntax
 * (e.g. `project_locales.collect { it.code }`).
 */
data class JmixLocale(
    val code: String,
    val displayName: String,
    val default: Boolean = false,
)

/** Maven repository added to the generated project's build script. */
data class Repository(
    val url: String,
    val user: String = "",
    val password: String = "",
)

/** All inputs for project generation. Mirrors Studio's JmixProjectCreationInfo. */
data class ProjectCreationInfo(
    val name: String,
    /** Directory chosen by the user; template files are generated relative to it. */
    val targetDir: Path,
    val rootPackage: String,
    val projectId: String = "",
    val projectTheme: String = "",
    val locales: List<JmixLocale> = listOf(JmixLocale("en", "English", default = true)),
    val repositories: List<Repository> = listOf(Repository(DEFAULT_REPOSITORY_URL)),
    val jmixVersion: String,
    val templateMetadata: TemplateMetadata,
    val createGitRepository: Boolean = true,
) {
    val rootPath: String get() = rootPackage.replace('.', '/')

    /**
     * Project root including the template's subFolder (Studio substitutes only
     * the literal `${project_name}` placeholder, without the template engine).
     */
    val projectDir: Path
        get() = templateMetadata.subFolder
            ?.replace("\${project_name}", name)
            ?.let { targetDir.resolve(it) }
            ?: targetDir

    companion object {
        const val DEFAULT_REPOSITORY_URL = "https://global.repo.jmix.io/repository/public"
    }
}

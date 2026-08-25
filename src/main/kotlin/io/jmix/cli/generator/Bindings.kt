package io.jmix.cli.generator

import io.jmix.cli.util.PlatformVersions
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Template binding map construction, ported from Studio's
 * ProjectGenerationBindingHelper.createBinding.
 */
object Bindings {

    private const val ADDON_PROJECT_NAME_POSTFIX = "-addon"

    // Studio's CubaConstants.LEGACY_ADDON_NAMES: 1.x template.json has no addon
    // flag, so addon templates are recognized by name for Jmix <= 1.3.999.
    private val LEGACY_ADDON_NAMES = setOf(
        "Single Module Add-On", "Single Module Add-On (Kotlin)", "single-module-addon",
    )
    private const val REPOSITORY_USER_GRADLE_PROP = "repoUser"
    private const val REPOSITORY_PASSWORD_GRADLE_PROP = "repoPass"

    /** Marker URL for the local Maven repository (rendered as `mavenLocal()`). */
    const val MAVEN_LOCAL_URL = "mavenLocal"

    // Studio's CUBA_REPO_SOURCE — a nested Groovy template for one maven { } block.
    private const val REPO_SOURCE =
        "    maven {\n" +
            "\${indent}    url = '\${config_repositoryUrl}'\n" +
            "<%if (isCredentialsToAdd) {%>\${indent}    credentials {\n" +
            "\${indent}        username(rootProject.hasProperty('\${Studio_REPOSITORY_USER_GRADLE_PROP}') ? rootProject['\${Studio_REPOSITORY_USER_GRADLE_PROP}'] : \${config_presentationRepositoryUser})\n" +
            "\${indent}        password(rootProject.hasProperty('\${Studio_REPOSITORY_PASSWORD_GRADLE_PROP}') ? rootProject['\${Studio_REPOSITORY_PASSWORD_GRADLE_PROP}'] : \${config_presentationRepositoryPassword})\n" +
            "\${indent}    }\n<%}%>" +
            "\${indent}}"

    fun createBinding(info: ProjectCreationInfo): MutableMap<String, Any?> {
        val binding = HashMap<String, Any?>()

        var projectName = info.name
        val isAddon = isAddonTemplate(info)
        if (isAddon) {
            binding["module_name"] = projectName.lowercase()
            projectName += ADDON_PROJECT_NAME_POSTFIX

            val rootPath = info.rootPath
            val autoConfigurationPath = rootPath.substringBeforeLast('/', "") +
                (if (rootPath.contains('/')) "/" else "") +
                "autoconfigure/" + rootPath.substringAfterLast('/')
            binding["project_autoConfigurationPath"] = autoConfigurationPath
            binding["project_autoConfigurationPackage"] = autoConfigurationPath.replace("/", ".")
        }
        binding["project_name"] = projectName
        binding["project_path"] = info.projectDir.toString()
        binding["project_rootPath"] = info.rootPath
        binding["project_rootPackage"] = info.rootPackage
        binding["project_baseProjectsGroup_version"] = "0.1.9"

        binding["project_classPrefix"] = projectName.rejoinBySplitter("-").rejoinBySplitter("_")
        binding["project_projectPrintableName"] = projectName.rejoinBySplitter("-", " ")

        binding["project_idPrefix"] = info.projectId
        binding["project_id"] = info.projectId
        binding["project_locales"] = info.locales
        binding["project_theme"] = info.projectTheme
        binding["project_version"] = "0.0.1-SNAPSHOT"
        binding["project_group"] = info.rootPackage.substringBeforeLast('.')

        val indent = if (isAddon) "        " else "    "
        val additionalRepositories = ArrayList<String>()
        for (repo in info.repositories) {
            if (repo.url == MAVEN_LOCAL_URL) {
                additionalRepositories.add(0, "    mavenLocal()")
            } else {
                additionalRepositories.add(renderRepository(repo, indent))
            }
        }
        binding["project_additionalRepositories"] = additionalRepositories
        binding["Studio_GRADLE_VERSION"] = "6.5"

        val random = ThreadLocalRandom.current()
        binding["anonymousSessionId"] = UUID(random.nextLong(), random.nextLong()).toString()

        binding["gitignore"] = ".gitignore"

        binding["groovyEscapeHelper"] = GroovyEscapeHelper
        binding["propertyEscapeHelper"] = PropertyEscapeHelper

        // Added by Studio's ProjectCreator right after binding creation.
        binding["includeExtraGradle"] = true

        return binding
    }

    // Studio's ProjectGenerationBindingHelper.isAddonTemplate.
    private fun isAddonTemplate(info: ProjectCreationInfo): Boolean =
        if (PlatformVersions.compare(info.jmixVersion, "1.3.999") <= 0) {
            info.templateMetadata.name in LEGACY_ADDON_NAMES
        } else {
            info.templateMetadata.addon
        }

    private fun renderRepository(repo: Repository, indent: String): String {
        val hasCredentials = repo.user.isNotBlank() && repo.password.isNotBlank()
        val repoBinding = linkedMapOf<String, Any?>(
            "config_repositoryUrl" to repo.url,
            "isCredentialsToAdd" to hasCredentials,
            "Studio_REPOSITORY_USER_GRADLE_PROP" to REPOSITORY_USER_GRADLE_PROP,
            "Studio_REPOSITORY_PASSWORD_GRADLE_PROP" to REPOSITORY_PASSWORD_GRADLE_PROP,
            "config_presentationRepositoryUser" to "'${repo.user}'",
            "config_presentationRepositoryPassword" to "'${repo.password}'",
            "indent" to indent,
        )
        return TemplateEngine.render(REPO_SOURCE, repoBinding)
    }
}

/**
 * Studio's StudioUtils.rejoinBySplitter: split by [splitter], capitalize the
 * first character of every part, join with [joiner].
 */
fun String.rejoinBySplitter(splitter: String, joiner: String = ""): String =
    split(splitter).joinToString(joiner) { part ->
        if (part.isEmpty()) part else part.replaceFirst(part.take(1), part.take(1).uppercase())
    }

/**
 * Stand-in for IntelliJ's GrStringUtil.getLiteralTextByValue: wraps a value in
 * a single-quoted Groovy string literal. Not referenced by current templates.
 */
object GroovyEscapeHelper {
    @JvmStatic
    fun getLiteralTextByValue(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

/**
 * Stand-in for IntelliJ's PropertiesElementFactory.escapeValue: escapes a
 * java.util.Properties value. Not referenced by current templates.
 */
object PropertyEscapeHelper {
    @JvmStatic
    fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("=", "\\=").replace(":", "\\:")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}

package io.jmix.cli.template

import com.google.gson.Gson
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads project templates from the jmix-studio-templates jar. Project templates
 * live under the top-level `project/` directory; each has a template.json.
 */
class TemplateCatalog(jarPath: Path) : AutoCloseable {

    private val gson = Gson()
    private val fileSystem: FileSystem = FileSystems.newFileSystem(jarPath)

    /**
     * Project templates for `jmix new`, sorted by order then name (Studio's
     * TemplatesOrderComparator). Add-on templates are selectable too — the
     * addon flag changes generation bindings, not template availability.
     */
    fun projectTemplates(): List<Template> {
        val projectDir = fileSystem.getPath(PROJECT_TEMPLATES_DIR)
        if (!Files.isDirectory(projectDir)) return emptyList()
        return Files.list(projectDir).use { dirs ->
            dirs.filter { Files.isDirectory(it) }
                .map { dir -> Template(dir.fileName.toString().trimEnd('/'), readMetadata(dir)) }
                .toList()
        }
            .sortedWith(compareBy({ it.metadata.order }, { it.displayName }))
    }

    /** Root directory of one template inside the jar. */
    fun templateRoot(templateId: String): Path =
        fileSystem.getPath(PROJECT_TEMPLATES_DIR, templateId)

    private fun readMetadata(templateDir: Path): TemplateMetadata {
        val file = templateDir.resolve(TEMPLATE_JSON)
        val json = if (Files.isRegularFile(file)) Files.readString(file) else ""
        if (json.isBlank()) return legacyMetadata(templateDir)
        val parsed = gson.fromJson(json, TemplateMetadata::class.java) ?: return legacyMetadata(templateDir)
        return processMetadata(parsed)
    }

    // Studio's processTemplateMetadata: pre-1 addon metadata gets a hidden locales param.
    private fun processMetadata(metadata: TemplateMetadata): TemplateMetadata =
        if (metadata.version < 1 && metadata.addon) {
            metadata.copy(
                params = metadata.params.orEmpty() +
                    TemplateParam(TemplateParams.LOCALES, mandatory = false, hidden = true),
                tags = metadata.tags.orEmpty(),
            )
        } else {
            metadata
        }

    /**
     * Studio's legacy fallback for 1.0.x artifacts without template.json:
     * metadata synthesized from a per-directory table plus template.description.
     */
    private fun legacyMetadata(templateDir: Path): TemplateMetadata {
        val dirName = templateDir.fileName.toString().trimEnd('/')
        data class Legacy(val order: Int, val mandatoryProjectId: Boolean, val hiddenLocales: Boolean, val addon: Boolean)
        val legacy = when (dirName) {
            "single-module-application" -> Legacy(0, mandatoryProjectId = false, hiddenLocales = false, addon = false)
            "single-module-addon" -> Legacy(1, mandatoryProjectId = true, hiddenLocales = true, addon = true)
            "theme-addon" -> Legacy(2, mandatoryProjectId = false, hiddenLocales = false, addon = true)
            "widgets-addon" -> Legacy(3, mandatoryProjectId = false, hiddenLocales = false, addon = true)
            else -> Legacy(Int.MAX_VALUE, mandatoryProjectId = false, hiddenLocales = false, addon = false)
        }
        val descriptionFile = templateDir.resolve(LEGACY_DESCRIPTION)
        val description = if (Files.isRegularFile(descriptionFile)) Files.readString(descriptionFile) else ""
        return TemplateMetadata(
            version = 0,
            name = dirName,
            order = legacy.order,
            addon = legacy.addon,
            description = description,
            params = listOf(
                TemplateParam(TemplateParams.PROJECT_ID, mandatory = legacy.mandatoryProjectId, hidden = false),
                TemplateParam(TemplateParams.LOCALES, mandatory = false, hidden = legacy.hiddenLocales),
            ),
            hideForSubproject = false,
            subFolder = null,
            tags = "",
        )
    }

    override fun close() = fileSystem.close()

    companion object {
        const val PROJECT_TEMPLATES_DIR = "project"
        const val TEMPLATE_JSON = "template.json"
        const val LEGACY_DESCRIPTION = "template.description"
    }
}

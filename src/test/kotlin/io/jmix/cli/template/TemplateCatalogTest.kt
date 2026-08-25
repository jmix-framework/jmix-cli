package io.jmix.cli.template

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TemplateCatalogTest {

    @TempDir
    lateinit var tempDir: Path

    private fun buildJar(entries: Map<String, String>): Path {
        val jar = tempDir.resolve("templates.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `templates sorted by order then name`() {
        val jar = buildJar(
            mapOf(
                "project/b-template/template.json" to """{"version":1,"name":"B","order":200}""",
                "project/a-template/template.json" to """{"version":1,"name":"A","order":100}""",
            ),
        )
        TemplateCatalog(jar).use { catalog ->
            assertEquals(listOf("a-template", "b-template"), catalog.projectTemplates().map { it.id })
        }
    }

    @Test
    fun `legacy template without template json gets Studio's fallback metadata`() {
        val jar = buildJar(
            mapOf(
                "project/single-module-application/template.description" to "Legacy app",
                "project/single-module-addon/template.description" to "Legacy addon",
            ),
        )
        TemplateCatalog(jar).use { catalog ->
            val templates = catalog.projectTemplates()
            // Legacy orders: application=0, addon=1 — application first, not alphabetical.
            assertEquals(listOf("single-module-application", "single-module-addon"), templates.map { it.id })

            val app = templates[0].metadata
            assertFalse(app.isMandatoryParam(TemplateParams.PROJECT_ID))
            assertFalse(app.addon)
            assertEquals("Legacy app", app.description)

            val addon = templates[1].metadata
            assertTrue(addon.isMandatoryParam(TemplateParams.PROJECT_ID))
            assertTrue(addon.addon)
            assertFalse(addon.isVisibleParam(TemplateParams.LOCALES))
        }
    }

    @Test
    fun `blank template json falls back instead of crashing`() {
        val jar = buildJar(mapOf("project/broken/template.json" to "  "))
        TemplateCatalog(jar).use { catalog ->
            val template = catalog.projectTemplates().single()
            assertEquals("broken", template.id)
            assertEquals(Int.MAX_VALUE, template.metadata.order)
        }
    }

    @Test
    fun `pre-1 addon metadata gets hidden locales param`() {
        val jar = buildJar(
            mapOf("project/old-addon/template.json" to """{"version":0,"name":"Old","order":5,"addon":true}"""),
        )
        TemplateCatalog(jar).use { catalog ->
            val metadata = catalog.projectTemplates().single().metadata
            assertFalse(metadata.isVisibleParam(TemplateParams.LOCALES))
        }
    }
}

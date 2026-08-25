package io.jmix.cli.template

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TemplateMetadataTest {

    private val gson = Gson()

    @Test
    fun `parses real template json shape`() {
        val json = """
            {
              "version": 1,
              "name": "Add-On (Java)",
              "order": 130,
              "addon": true,
              "description": "<html>Jmix add-on.</html>",
              "params": [
                {"name": "projectId", "mandatory": true},
                {"name": "locales", "hidden": true}
              ]
            }
        """.trimIndent()
        val metadata = gson.fromJson(json, TemplateMetadata::class.java)

        assertEquals("Add-On (Java)", metadata.name)
        assertEquals(130, metadata.order)
        assertTrue(metadata.addon)
        assertTrue(metadata.isMandatoryParam(TemplateParams.PROJECT_ID))
        assertFalse(metadata.isVisibleParam(TemplateParams.LOCALES))
        assertTrue(metadata.isVisibleParam(TemplateParams.PROJECT_ID))
        assertNull(metadata.subFolder)
    }

    @Test
    fun `absent param counts as mandatory - Studio semantics`() {
        val metadata = gson.fromJson("""{"version":1,"name":"X","order":1}""", TemplateMetadata::class.java)
        assertTrue(metadata.isMandatoryParam(TemplateParams.PROJECT_ID))
        assertTrue(metadata.isVisibleParam(TemplateParams.PROJECT_ID))
        assertFalse(metadata.isVisibleParam(TemplateParams.PROJECT_THEME, defaultIfNotPresent = false))
    }

    @Test
    fun `subFolder parsed for composite templates`() {
        val json = """{"version":1,"name":"Composite","order":170,"subFolder":"${'$'}{project_name}-all","hideForSubproject":true}"""
        val metadata = gson.fromJson(json, TemplateMetadata::class.java)
        assertEquals("\${project_name}-all", metadata.subFolder)
        assertTrue(metadata.hideForSubproject)
    }
}

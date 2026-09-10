package io.jmix.cli.addon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AddonCatalogTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `catalog selects compatible variants and locks template dependencies`() {
        val catalog = AddonCatalog.parse(CATALOG_JSON)
        val metadata = catalog.addons.first()
        assertEquals(760, metadata.weight)
        assertEquals("Add-on", metadata.category)
        assertEquals(listOf("Maintenance", "Integration"), metadata.tags)
        assertEquals("Haulmont", metadata.vendor)
        val flowUi = AddonProjectProfile("build.gradle", setOf("io.jmix.flowui:jmix-flowui-starter"))
        val addon = catalog.select(listOf("sample"), "3.0.1", flowUi).single()
        assertEquals("3.0.1", addon.version)
        assertEquals(listOf("demo:sample-starter", "demo:sample-flowui-starter"), addon.dependencies.map { it.coordinates })
        assertFalse(addon.included)
        assertTrue(catalog.available("3.0.1", flowUi.copy(coordinates = flowUi.coordinates + addon.dependencies.map { it.coordinates }))
            .single().included)
        val backend = catalog.select(listOf("sample"), "3.0.1", AddonProjectProfile("build.gradle", emptySet())).single()
        assertEquals(listOf("sample-starter"), backend.dependencies.map { it.name })
    }

    @Test
    fun `picker groups marketplace tags once with Studio featured order within each group`() {
        val sample = AddonCatalog.parse(CATALOG_JSON).addons.first()
        fun addon(id: String, name: String, weight: Int, vararg tags: String, category: String = "Add-on") = sample.copy(
            id = id, name = name, weight = weight, category = category, tags = tags.toList(),
            dependencies = listOf(AddonDependency("demo", id)),
        )
        val catalog = AddonCatalog(listOf(
            addon("alpha", "Alpha", 10, "Features"),
            addon("translation", "Translation", 2000, "Security", category = "Translation"),
            addon("included", "Included", 0),
            addon("zulu", "Zulu", 1000, "Features"),
            addon("beta", "Beta", 10, "Features"),
            addon("charts", "Charts", 2000, "Features", "UI"),
            addon("quartz", "Quartz", 2000, "System", "Integration"),
            addon("saml", "SAML", 2000, "Integration", "Security"),
            addon("jmx", "JMX Console", 2000, "UI", "System"),
            addon("unknown", "Unknown", 2000, "New tag"),
            addon("untagged", "Untagged", 10),
            addon("paid", "Paid", 5000).copy(commercial = true),
        ))
        val available = catalog.available("3.0.1", AddonProjectProfile("build.gradle", setOf("demo:included")))
        assertEquals(listOf("included", "zulu", "alpha", "beta", "charts", "quartz", "saml", "jmx",
            "unknown", "untagged", "translation"), available.map { it.id })
        assertEquals(listOf("Features", "Features", "Features", "UI", "Integrations", "Security", "System",
            "Other", "Other", "Translations"), available.filterNot { it.included }.map { it.addon.group.title })
        assertEquals(Int.MAX_VALUE,
            AddonCatalog.parse(CATALOG_JSON.replace("\"weight\":760", "\"weight\":null")).addons.first().weight)
        assertEquals(Int.MAX_VALUE, AddonCatalog.parse(CATALOG_JSON).addons.last().weight)
    }

    @Test
    fun `translation defaults match compatible locales with exact region before language fallback`() {
        val sample = AddonCatalog.parse(CATALOG_JSON).addons.first()
        fun translation(code: String) = sample.copy(id = "translation-$code", category = "Translation",
            dependencies = listOf(AddonDependency("io.jmix.translations", "jmix-translations-$code")))
        val catalog = AddonCatalog(listOf(
            translation("de"), translation("fr"), translation("pt"), translation("pt-br"), translation("zh-cn"),
            translation("ru").copy(compatibility = listOf(AddonCompatibility("2.8", listOf("2.8.3")))),
            translation("es").copy(commercial = true),
            translation("it").copy(category = "Add-on"),
            translation("ar").copy(dependencies = listOf(AddonDependency("other", "jmix-translations-ar"))),
        ))
        val available = catalog.available("3.0.1", AddonProjectProfile("build.gradle", emptySet()))

        assertEquals(setOf("translation-de", "translation-fr", "translation-pt-br", "translation-zh-cn"),
            translationAddonIds(available, listOf("en", " de_DE ", "DE", "fr-CA", "pt_BR", "PT-br", "zh_CN")))
        assertEquals(setOf("translation-pt"), translationAddonIds(available, listOf("pt-PT")))
        assertTrue(translationAddonIds(available, listOf("ru", "es", "it", "ar", "pl", "zh-TW")).isEmpty())
        assertTrue(translationAddonIds(available, emptyList()).isEmpty())
    }

    @Test
    fun `commercial unknown and incompatible selections fail explicitly`() {
        val catalog = AddonCatalog.parse(CATALOG_JSON)
        val profile = AddonProjectProfile("build.gradle", emptySet())
        assertTrue(assertThrows(IOException::class.java) { catalog.select(listOf("paid"), "3.0.1", profile) }.message!!.contains("commercial"))
        assertThrows(IOException::class.java) { catalog.select(listOf("missing"), "3.0.1", profile) }
        assertThrows(IOException::class.java) { catalog.select(listOf("sample"), "2.8.3", profile) }
        assertEquals(1, catalog.select(listOf("sample", "sample"), "3.0.1", profile).size)
        val broken = AddonCatalog.parse(CATALOG_JSON.replace("\"demo\",\"name\":\"sample-starter\"",
            "\"io.jmix.gradle\",\"name\":\"jmix-gradle-plugin\""))
        assertTrue(broken.available("3.0.1", profile).isEmpty())
        assertTrue(assertThrows(IOException::class.java) { broken.select(listOf("sample"), "3.0.1", profile) }
            .message!!.contains("vendor"))
    }

    @Test
    fun `catalog can contain resources without installable dependencies`() {
        val catalog = AddonCatalog.parse("""{"appComponents":[
            {"id":"ui-kit","name":"UI Kit","commercial":false,"dependencies":[],"compatibilityList":[]}
        ]}""")
        assertTrue(catalog.available("3.0.1", AddonProjectProfile("build.gradle", emptySet())).isEmpty())
    }

    @Test
    fun `third party version is selected from compatibility rather than platform version`() {
        val catalog = AddonCatalog.parse(CATALOG_JSON.replace("[\"3.0.0\",\"3.0.1\",\"3.0.999-SNAPSHOT\"]", "[\"0.9.4\",\"0.9.5\",\"0.9.6-SNAPSHOT\"]"))
        val profile = AddonProjectProfile("build.gradle", emptySet())
        assertEquals("0.9.5", catalog.available("3.0.1", profile).single().version)
        assertEquals("0.9.6-SNAPSHOT", catalog.available("3.0.999-SNAPSHOT", profile).single().version)
    }

    @Test
    fun `Maven range boundaries and Studio UI exceptions are respected`() {
        val profile = AddonProjectProfile("build.gradle", emptySet())
        assertFalse(AddonDependency("demo", "test", versionRange = "(2.5.1,)").supports("2.5.1", profile))
        assertTrue(AddonDependency("demo", "test", versionRange = "[2.5.1,)").supports("2.5.1", profile))
        assertTrue(AddonDependency("demo", "swagger-ui-starter").supports("3.0.1", profile))
        assertFalse(AddonDependency("demo", "classic-ui-starter").supports("3.0.1", profile))
    }

    @Test
    fun `invalid catalog values cannot enter generated Gradle text`() {
        assertThrows(IOException::class.java) { AddonCatalog.parse(CATALOG_JSON.replace("demo", "demo'; evil()")) }
        assertThrows(IOException::class.java) { AddonCatalog.parse(CATALOG_JSON.replace("\"commercial\":false,", "")) }
        assertThrows(IOException::class.java) { AddonCatalog.parse("{\"appComponents\":[]}") }
        assertEquals("safe text", AddonCatalog.plainText("\u001b[31msafe</b>\ntext\u001b[0m"))
    }

    @Test
    fun `add-on module profile uses the actual module Gradle file`() {
        Files.writeString(tempDir.resolve("build.gradle"), "subprojects { apply plugin: 'io.jmix' }")
        val module = Files.createDirectory(tempDir.resolve("sample"))
        Files.writeString(module.resolve("sample.gradle"), "dependencies { implementation 'io.jmix.core:jmix-core-starter' }")
        assertEquals("sample/sample.gradle", AddonProjectProfile.from(tempDir)!!.buildFile)
    }

    companion object {
        val CATALOG_JSON = """
            {"appComponents":[
              {"id":"sample","name":"Sample","about":"Scheduling","description":"A sample add-on","commercial":false,
               "weight":760,"category":"Add-on","tags":["Maintenance","Integration"],"vendor":"<b>Haulmont</b>",
               "dependencies":[{"group":"demo","name":"sample-starter"},
                 {"group":"demo","name":"sample-flowui-starter","versionRange":"(1.99.999-SNAPSHOT,)"},
                 {"group":"demo","name":"sample-ui-starter"}],
               "compatibilityList":[{"platformRequirement":"3.0","artifactVersions":["3.0.0","3.0.1","3.0.999-SNAPSHOT"]}]},
              {"id":"paid","name":"Paid","commercial":true,
               "dependencies":[{"group":"demo","name":"paid-starter"}],
               "compatibilityList":[{"platformRequirement":"3.0","artifactVersions":["3.0.1"]}]}
            ]}
        """.trimIndent()
    }
}

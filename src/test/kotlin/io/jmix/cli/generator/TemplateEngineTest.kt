package io.jmix.cli.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TemplateEngineTest {

    @Test
    fun `renders dollar expressions and scriptlets`() {
        val result = TemplateEngine.render(
            "name=\${project_name}<%if (project_id) {%> id=\$project_id<%}%>",
            mapOf("project_name" to "jmix-project", "project_id" to "shp"),
        )
        assertEquals("name=jmix-project id=shp", result)
    }

    @Test
    fun `falsy scriptlet condition skips block`() {
        val result = TemplateEngine.render(
            "x<%if (project_id) {%>-\$project_id<%}%>",
            mapOf("project_id" to ""),
        )
        assertEquals("x", result)
    }

    @Test
    fun `escaped dollar renders as literal dollar`() {
        // Templates escape literal $ as \$ (e.g. Spring @Value placeholders).
        val result = TemplateEngine.render("""@Value("\${'$'}{ui.login:}")""", emptyMap())
        assertEquals("""@Value("${'$'}{ui.login:}")""", result)
    }

    @Test
    fun `path segments render like Studio`() {
        val binding = mapOf(
            "project_rootPath" to "com/company/jmixproject",
            "project_classPrefix" to "JmixProject",
            "gitignore" to ".gitignore",
        )
        assertEquals(
            "src/main/java/com/company/jmixproject/JmixProjectApplication.java",
            TemplateEngine.render("src/main/java/\${project_rootPath}/\${project_classPrefix}Application.java", binding),
        )
        assertEquals(".gitignore", TemplateEngine.render("\${gitignore}", binding))
    }

    @Test
    fun `large templates use the streaming engine`() {
        val big = "x".repeat(70_000) + "\${project_name}"
        val result = TemplateEngine.render(big, mapOf("project_name" to "ok"))
        assertEquals("x".repeat(70_000) + "ok", result)
    }

    @Test
    fun `line separators normalized to LF`() {
        assertEquals("a\nb\nc", TemplateEngine.convertLineSeparators("a\r\nb\rc"))
    }

    @Test
    fun `locale objects accessible from groovy`() {
        val locales = listOf(JmixLocale("en", "English", true), JmixLocale("de", "German"))
        val result = TemplateEngine.render(
            "\${project_locales.collect {it.code}.join(',')}",
            mapOf("project_locales" to locales),
        )
        assertEquals("en,de", result)
    }
}

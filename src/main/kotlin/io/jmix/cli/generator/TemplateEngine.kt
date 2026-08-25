package io.jmix.cli.generator

import groovy.text.SimpleTemplateEngine
import groovy.text.StreamingTemplateEngine
import java.io.StringWriter

/**
 * Groovy template rendering ported from Studio's TemplateEngine.java:
 * SimpleTemplateEngine below Groovy's 64K method limit, StreamingTemplateEngine
 * above it; every result normalized to \n line separators.
 */
object TemplateEngine {

    private const val SIMPLE_ENGINE_MAX_LENGTH = 65535

    fun render(template: String, binding: Map<String, Any?>): String {
        val engine = if (template.length < SIMPLE_ENGINE_MAX_LENGTH) {
            SimpleTemplateEngine()
        } else {
            StreamingTemplateEngine()
        }
        val writer = StringWriter()
        // Groovy writes an `out` variable into the binding — pass a mutable copy.
        engine.createTemplate(template).make(HashMap(binding)).writeTo(writer)
        return convertLineSeparators(writer.toString())
    }

    /** IntelliJ StringUtil.convertLineSeparators equivalent: \r\n and \r become \n. */
    fun convertLineSeparators(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}

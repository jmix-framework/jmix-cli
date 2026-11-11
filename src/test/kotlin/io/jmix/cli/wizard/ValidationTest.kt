package io.jmix.cli.wizard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ValidationTest {

    @Test
    fun `project name accepts letters digits dash underscore`() {
        assertNull(Validation.validateProjectName("jmix-project"))
        assertNull(Validation.validateProjectName("my_app2"))
        assertNull(Validation.validateProjectName("Untitled"))
    }

    @Test
    fun `project name rejects blank, bad start and java keywords`() {
        assertNotNull(Validation.validateProjectName(""))
        assertNotNull(Validation.validateProjectName("1abc"))
        assertNotNull(Validation.validateProjectName("class"))
        assertNotNull(Validation.validateProjectName("PUBLIC"))
        // Studio's pattern requires at least two characters.
        assertNotNull(Validation.validateProjectName("a"))
    }

    @Test
    fun `root package validation`() {
        assertNull(Validation.validateRootPackage("com.company.jmixproject"))
        assertNull(Validation.validateRootPackage("io.jmix_test"))
        assertNull(Validation.validateRootPackage("com._team2.record"))
        assertNotNull(Validation.validateRootPackage(""))
        assertNotNull(Validation.validateRootPackage("com.Company"))
        assertNotNull(Validation.validateRootPackage("1com.company"))
        assertNotNull(Validation.validateRootPackage("com.1company.demo"))
        assertNotNull(Validation.validateRootPackage("com.1company"))
        assertNotNull(Validation.validateRootPackage("com.class.demo"))
        assertNotNull(Validation.validateRootPackage("com.company.class"))
        assertNotNull(Validation.validateRootPackage("com.true.demo"))
        assertNotNull(Validation.validateRootPackage("com._.demo"))
        assertNotNull(Validation.validateRootPackage("com..demo"))
        assertNotNull(Validation.validateRootPackage(".com.demo"))
        assertNotNull(Validation.validateRootPackage("com.demo."))
    }

    @Test
    fun `project id validation`() {
        assertNull(Validation.validateProjectId("", mandatory = false))
        assertNotNull(Validation.validateProjectId("", mandatory = true))
        assertNull(Validation.validateProjectId("shp", mandatory = true))
        assertNotNull(Validation.validateProjectId("toolong12", mandatory = false))
        assertNotNull(Validation.validateProjectId("1ab", mandatory = false))
        // Studio's pattern requires at least two characters for a non-empty id.
        assertNotNull(Validation.validateProjectId("a", mandatory = false))
    }

    @Test
    fun `repository url validation`() {
        assertNull(Validation.validateRepositoryUrl("https://global.repo.jmix.io/repository/public"))
        assertNull(Validation.validateRepositoryUrl("http://nexus.local:8081/repository/jmix"))
        assertNull(Validation.validateRepositoryUrl("http://localhost/repository"))
        assertNull(Validation.validateRepositoryUrl("http://127.0.0.1:8081/"))
        assertNull(Validation.validateRepositoryUrl("http://[::1]:8081/repository"))
        assertNotNull(Validation.validateRepositoryUrl(""))
        assertNotNull(Validation.validateRepositoryUrl("   "))
        assertNotNull(Validation.validateRepositoryUrl("ftp://repo.example.com"))
        assertNotNull(Validation.validateRepositoryUrl("global.repo.jmix.io/repository/public"))
        assertNotNull(Validation.validateRepositoryUrl("http://"))
        assertNotNull(Validation.validateRepositoryUrl("https:///repository"))
        assertNotNull(Validation.validateRepositoryUrl("http://bad_host.invalid"))
        assertNotNull(Validation.validateRepositoryUrl("http://localhost:65536"))
        assertNotNull(Validation.validateRepositoryUrl("http://localhost:0"))
        assertNotNull(Validation.validateRepositoryUrl("https://repo.example.com/a%ZZ"))
        assertNotNull(Validation.validateRepositoryUrl("https://repo.example.com/repository?key=value"))
        assertNotNull(Validation.validateRepositoryUrl("https://repo.example.com/repository#part"))
        assertNotNull(Validation.validateRepositoryUrl("https://user:pass@repo.example.com/repository"))
    }

    @Test
    fun `transformProjectNamespace ported from Studio`() {
        assertEquals("jmixproject", Validation.transformProjectNamespace("Jmix-Project"))
        assertEquals("app2", Validation.transformProjectNamespace("1app2"))
        assertEquals("myproject", Validation.transformProjectNamespace("!!!"))
        assertEquals("myproject", Validation.transformProjectNamespace("123"))
    }
}

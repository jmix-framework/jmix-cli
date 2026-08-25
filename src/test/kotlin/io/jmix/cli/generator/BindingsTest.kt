package io.jmix.cli.generator

import io.jmix.cli.template.TemplateMetadata
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BindingsTest {

    private fun info(
        name: String = "jmix-project",
        metadata: TemplateMetadata = TemplateMetadata(),
        projectId: String = "",
        repositories: List<Repository> = listOf(Repository(ProjectCreationInfo.DEFAULT_REPOSITORY_URL)),
    ) = ProjectCreationInfo(
        name = name,
        targetDir = Path.of("/tmp/jmix-project"),
        rootPackage = "com.company.jmixproject",
        projectId = projectId,
        jmixVersion = "3.0.1",
        templateMetadata = metadata,
        repositories = repositories,
    )

    @Test
    fun `standard bindings ported from Studio`() {
        val binding = Bindings.createBinding(info())

        assertEquals("jmix-project", binding["project_name"])
        assertEquals("com/company/jmixproject", binding["project_rootPath"])
        assertEquals("com.company.jmixproject", binding["project_rootPackage"])
        assertEquals("com.company", binding["project_group"])
        assertEquals("JmixProject", binding["project_classPrefix"])
        assertEquals("Jmix Project", binding["project_projectPrintableName"])
        assertEquals("0.0.1-SNAPSHOT", binding["project_version"])
        assertEquals(".gitignore", binding["gitignore"])
        assertEquals(true, binding["includeExtraGradle"])
        assertFalse(binding.containsKey("module_name"))
    }

    @Test
    fun `class prefix camel-cases dash and underscore parts`() {
        assertEquals("MyAppX", "my-app_x".rejoinBySplitter("-").rejoinBySplitter("_"))
        assertEquals("My App", "my-app".rejoinBySplitter("-", " "))
    }

    @Test
    fun `addon template gets module bindings and -addon suffix`() {
        val binding = Bindings.createBinding(
            info(name = "my-addon", metadata = TemplateMetadata(addon = true), projectId = "mya"),
        )

        assertEquals("my-addon", binding["module_name"])
        assertEquals("my-addon-addon", binding["project_name"])
        assertEquals("com/company/autoconfigure/jmixproject", binding["project_autoConfigurationPath"])
        assertEquals("com.company.autoconfigure.jmixproject", binding["project_autoConfigurationPackage"])
    }

    @Test
    fun `repository without credentials renders plain maven block`() {
        val binding = Bindings.createBinding(info())
        @Suppress("UNCHECKED_CAST")
        val repos = binding["project_additionalRepositories"] as List<String>

        assertEquals(1, repos.size)
        assertEquals(
            "    maven {\n" +
                "        url = 'https://global.repo.jmix.io/repository/public'\n" +
                "    }",
            repos[0],
        )
    }

    @Test
    fun `repository with credentials renders gradle property lookup`() {
        val binding = Bindings.createBinding(
            info(repositories = listOf(Repository("https://example.com/repo", "user1", "pass1"))),
        )
        @Suppress("UNCHECKED_CAST")
        val block = (binding["project_additionalRepositories"] as List<String>)[0]

        assertTrue("credentials {" in block)
        assertTrue("rootProject.hasProperty('repoUser')" in block)
        assertTrue("'user1'" in block)
        assertTrue("'pass1'" in block)
    }

    @Test
    fun `maven local marker renders mavenLocal first`() {
        val binding = Bindings.createBinding(
            info(
                repositories = listOf(
                    Repository(ProjectCreationInfo.DEFAULT_REPOSITORY_URL),
                    Repository(Bindings.MAVEN_LOCAL_URL),
                ),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val repos = binding["project_additionalRepositories"] as List<String>

        assertEquals("    mavenLocal()", repos[0])
        assertEquals(2, repos.size)
    }

    @Test
    fun `legacy addon detected by name for jmix 1_x`() {
        val legacyMetadata = TemplateMetadata(name = "single-module-addon", addon = false)
        val creationInfo = info(metadata = legacyMetadata).copy(jmixVersion = "1.3.5")
        val binding = Bindings.createBinding(creationInfo)

        assertEquals("jmix-project", binding["module_name"])
        assertEquals("jmix-project-addon", binding["project_name"])

        // Same metadata on a modern version: addon flag wins, name check does not apply.
        val modern = Bindings.createBinding(info(metadata = legacyMetadata))
        assertFalse(modern.containsKey("module_name"))
    }

    @Test
    fun `subFolder resolution substitutes project_name only`() {
        val creationInfo = info(metadata = TemplateMetadata(subFolder = "\${project_name}-all"))
        assertEquals(Path.of("/tmp/jmix-project/jmix-project-all"), creationInfo.projectDir)
    }
}

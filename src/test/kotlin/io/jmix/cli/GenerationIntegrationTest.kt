package io.jmix.cli

import io.jmix.cli.generator.JmixLocale
import io.jmix.cli.generator.ProjectCreationInfo
import io.jmix.cli.generator.ProjectGenerator
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.template.TemplateCatalog
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/**
 * Generates a project from the real template artifact on global.repo.jmix.io.
 * Needs network; enable with JMIX_CLI_IT=true (set in CI).
 */
@EnabledIfEnvironmentVariable(named = "JMIX_CLI_IT", matches = "true")
class GenerationIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generates a working application project from the real artifact`() {
        val repo = TemplateRepository(cacheDir = tempDir.resolve("cache"))
        val versions = repo.fetchVersions()
        val latest = versions.last()

        assertTrue(versions.isNotEmpty())

        TemplateCatalog(repo.templatesJar(latest)).use { catalog ->
            val templates = catalog.projectTemplates()
            assertTrue(templates.any { it.id == "application" }, "application template must exist")
            val template = templates.first { it.id == "application" }

            val target = tempDir.resolve("jmix-project")
            val info = ProjectCreationInfo(
                name = "jmix-project",
                targetDir = target,
                rootPackage = "com.company.jmixproject",
                projectTheme = "aura",
                locales = listOf(JmixLocale("en", "English", default = true)),
                jmixVersion = latest,
                templateMetadata = template.metadata,
                createGitRepository = false,
            )

            ProjectGenerator().generate(catalog.templateRoot(template.id), info)

            for (expected in listOf(
                "settings.gradle", "build.gradle", "gradlew", ".gitignore",
                "src/main/java/com/company/jmixproject/JmixProjectApplication.java",
                "src/main/resources/com/company/jmixproject/messages_en.properties",
            )) {
                assertTrue(Files.exists(target.resolve(expected)), "missing $expected")
            }

            assertTrue(Files.isExecutable(target.resolve("gradlew")))

            val settings = Files.readString(target.resolve("settings.gradle"))
            assertTrue("rootProject.name = 'jmix-project'" in settings)
        }
    }
}

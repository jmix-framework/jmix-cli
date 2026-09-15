package io.jmix.cli

import io.jmix.cli.addon.Addon
import io.jmix.cli.addon.ResolvedAddon
import io.jmix.cli.generator.JmixLocale
import io.jmix.cli.generator.ProjectCreationInfo
import io.jmix.cli.generator.Repository
import io.jmix.cli.template.TemplateMetadata
import io.jmix.cli.template.TemplateParam
import io.jmix.cli.template.TemplateParams
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NewCommandTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `project locations include current directory and named subdirectory`() {
        val currentDir = tempDir.resolve("work/mydir")
        val homeDir = tempDir.resolve("home")

        assertEquals(
            listOf(
                "Current directory" to currentDir,
                "Subdirectory" to currentDir.resolve("sample"),
                "IdeaProjects" to homeDir.resolve("IdeaProjects/sample"),
            ),
            projectLocationOptions("sample", currentDir, homeDir),
        )
    }

    private fun info(
        currentDir: Path,
        targetDir: Path = currentDir.resolve("demo"),
        projectId: String = "",
        theme: String = "",
        locales: List<String> = listOf("en"),
        addons: List<ResolvedAddon> = emptyList(),
        repositoryUrl: String = ProjectCreationInfo.DEFAULT_REPOSITORY_URL,
        createGit: Boolean = true,
        metadata: TemplateMetadata = TemplateMetadata(),
    ) = ProjectCreationInfo(
        name = "demo",
        targetDir = targetDir,
        rootPackage = "com.company.demo",
        projectId = projectId,
        projectTheme = theme,
        locales = locales.mapIndexed { index, code -> JmixLocale(code, code, default = index == 0) },
        repositories = listOf(Repository(repositoryUrl)),
        jmixVersion = "3.0.1",
        templateMetadata = metadata,
        createGitRepository = createGit,
        addons = addons,
    )

    private fun addon(id: String, included: Boolean = false) = ResolvedAddon(
        Addon(id, id, "", "", commercial = false, dependencies = emptyList(), compatibility = emptyList()),
        version = "3.0.1", dependencies = emptyList(), included = included,
    )

    @Test
    fun `commercial entry shows its license requirement while the command keeps catalog IDs`() {
        val free = addon("quartz")
        val paid = addon("bpm").let { it.copy(addon = it.addon.copy(name = "BPM", commercial = true,
            about = "Run business processes")) }
        val entry = addonEntry(paid)
        assertTrue(entry.title.startsWith("BPM "))
        assertTrue(entry.title.contains("[$]"))
        assertFalse(entry.selected)
        assertTrue(addonEntry(paid, selected = true).selected)
        assertEquals("quartz", addonEntry(free).title)
        assertEquals(null, addonEntry(free).description)
        assertEquals("Select add-ons", addonSelectionQuestion())
        assertEquals("Select add-ons (no longer compatible: old-addon)", addonSelectionQuestion(setOf("old-addon")))
        assertEquals(null, addonSelectionLegend(listOf(free)))
        val legend = addonSelectionLegend(listOf(free, paid))!!
        assertTrue(legend.contains("[$]"))
        assertTrue(legend.contains(" - commercial add-on"))

        val command = nonInteractiveCommand(info(tempDir, addons = listOf(free, paid, addon("data-tools", included = true))),
            "application", true, tempDir)
        assertTrue(command.endsWith("--addons quartz,bpm"), command)
        assertFalse(command.contains("[$]"))
        assertFalse(command.contains("premiumRepo"))
    }

    @Test
    fun `equivalent command pins the version and omits non-interactive defaults`() {
        val currentDir = tempDir.resolve("work")

        assertEquals(
            "jmix new demo --non-interactive --jmix-version 3.0.1 --template application " +
                "--package com.company.demo --locales en",
            nonInteractiveCommand(info(currentDir), "application", installToolkit = true, currentDir = currentDir),
        )
    }

    @Test
    fun `equivalent command carries every setting that differs from the defaults`() {
        val currentDir = tempDir.resolve("work")
        val info = info(
            currentDir,
            targetDir = tempDir.resolve("My Projects/demo"),
            projectId = "dm",
            theme = "aura",
            locales = listOf("en", "ru"),
            addons = listOf(addon("quartz"), addon("data-tools", included = true), addon("russian-translation")),
            repositoryUrl = "https://nexus.jmix.io/repository/public",
            createGit = false,
        )

        assertEquals(
            "jmix new demo --non-interactive --jmix-version 3.0.1 --template application-kotlin " +
                "--package com.company.demo --project-id dm --theme aura --locales en,ru " +
                "--addons quartz,russian-translation --path '${tempDir.resolve("My Projects/demo")}' " +
                "--repository https://nexus.jmix.io/repository/public --no-git --no-agents-toolkit",
            nonInteractiveCommand(info, "application-kotlin", installToolkit = false, currentDir = currentDir),
        )
    }

    @Test
    fun `equivalent command clears a template default project id explicitly`() {
        val currentDir = tempDir.resolve("work")
        val metadata = TemplateMetadata(params = listOf(TemplateParam(TemplateParams.PROJECT_ID, defaultValue = "app")))

        val command = nonInteractiveCommand(info(currentDir, metadata = metadata), "application", true, currentDir)

        assertEquals(true, command.contains(" --project-id= "), command)
    }

    @Test
    fun `shell quoting leaves plain arguments alone and single-quotes the rest`() {
        assertEquals("com.company.demo", shellQuote("com.company.demo"))
        assertEquals("https://nexus.jmix.io/repository/public", shellQuote("https://nexus.jmix.io/repository/public"))
        assertEquals("'My Projects'", shellQuote("My Projects"))
        assertEquals("'it'\\''s'", shellQuote("it's", os = "Linux"))
        assertEquals("'it''s'", shellQuote("it's", os = "Windows 11"))
        assertEquals("'C:\\O''Brien\\My Projects'", shellQuote("C:\\O'Brien\\My Projects", os = "Windows 11"))
        assertEquals("'~/Projects'", shellQuote("~/Projects", os = "Linux"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun `equivalent PowerShell command escapes apostrophes in the target path`() {
        val currentDir = tempDir.resolve("work")
        val targetDir = tempDir.resolve("O'Brien/My Projects/demo")
        val command = nonInteractiveCommand(info(currentDir, targetDir), "application", true, currentDir, os = "Windows 11")

        assertEquals(true, command.contains("--path '${targetDir.toString().replace("'", "''")}'"), command)
    }
}

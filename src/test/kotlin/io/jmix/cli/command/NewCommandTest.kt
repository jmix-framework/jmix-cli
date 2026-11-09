package io.jmix.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import com.google.gson.JsonParser
import io.jmix.cli.FakeServer
import io.jmix.cli.account.PremiumRepository
import io.jmix.cli.account.Session
import io.jmix.cli.account.SessionStore
import io.jmix.cli.account.issuesTokens
import io.jmix.cli.addon.Addon
import io.jmix.cli.addon.ResolvedAddon
import io.jmix.cli.generator.JmixLocale
import io.jmix.cli.generator.ProjectCreationInfo
import io.jmix.cli.generator.Repository
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.telemetry.UsageStatistics
import io.jmix.cli.template.TemplateMetadata
import io.jmix.cli.template.TemplateParam
import io.jmix.cli.template.TemplateParams
import io.jmix.cli.wizard.Prompts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NewCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private val artifactId = TemplateRepository.TEMPLATES_ARTIFACT_ID
    private val artifactPath = "/${TemplateRepository.TEMPLATES_GROUP_PATH}/$artifactId"
    private val metadataPath = "$artifactPath/maven-metadata.xml"

    private fun FakeServer.publishVersions(vararg versions: String) {
        val entries = versions.joinToString("") { "<version>$it</version>" }
        val body = "<metadata><versioning><versions>$entries</versions></versioning></metadata>"
        on("GET", metadataPath) { FakeServer.Response(200, body.toByteArray()) }
    }

    @Test
    fun `an explicit prerelease resolves with one request whether the cache is fresh or missing`() {
        val version = "3.1.0-RC1"
        for (warm in listOf(false, true)) FakeServer().use { server ->
            if (warm) {
                server.publishVersions("3.0.3")
                TemplateRepository(server.url, tempDir).fetchVersions()
            }
            val requestsBefore = server.requests.size
            server.publishVersions(version)

            val stats = UsageStatistics(tempDir.resolve("installation-id"), server.url + "/rest/event/open")
            val result = NewCommand(tempDir, stats).test(listOf("demo", "--non-interactive", "--repository", server.url,
                "--jmix-version", version))

            assertEquals(1, result.statusCode)
            // The fixture stops at the jar request, after the version has passed validation.
            assertTrue(result.stderr.contains("Failed to download templates"), result.stderr)
            assertEquals(listOf(metadataPath, "$artifactPath/$version/$artifactId-$version.jar"),
                server.requests.drop(requestsBefore).map { it.path })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a successfully generated project records one usage event`(nonInteractive: Boolean) {
        val version = "3.1.0"
        val templateJar = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("project/application/template.json"))
                zip.write("""{"version":1,"name":"Application"}""".toByteArray())
                zip.putNextEntry(ZipEntry("project/application/build.gradle"))
                zip.write("// Generated application".toByteArray())
            }
        }.toByteArray()
        FakeServer().use { server ->
            server.publishVersions(version)
            server.on("GET", "$artifactPath/$version/$artifactId-$version.jar") { FakeServer.Response(200, templateJar) }
            val target = tempDir.resolve("local-project")
            val generatedAtSend = AtomicBoolean()
            server.on("POST", "/rest/event/open") {
                generatedAtSend.set(Files.isRegularFile(target.resolve("build.gradle")))
                FakeServer.Response(200)
            }
            val stats = UsageStatistics(
                tempDir.resolve("installation-id"), server.url + "/rest/event/open", timeout = Duration.ofSeconds(5),
            )
            val args = listOf(
                "private-name", "--repository", server.url, "--jmix-version", version, "--template", "application",
                "--package", "com.privatecompany.app", "--project-id", "sample", "--locales", "en", "--addons", "",
                "--path", target.toString(), "--no-git", "--no-agents-toolkit",
            )
            val result = if (nonInteractive) {
                NewCommand(tempDir, stats).test(args + "--non-interactive")
            } else {
                NewCommand(tempDir, stats).test(
                    args, ansiLevel = AnsiLevel.TRUECOLOR, width = 160, height = 40,
                    inputEvents = listOf(KeyboardEvent("q")),
                )
            }

            assertEquals(0, result.statusCode, result.stderr)
            val body = server.requests.single { it.method == "POST" && it.path == "/rest/event/open" }.body
            assertTrue(generatedAtSend.get())
            val payload = JsonParser.parseString(body).asJsonObject
            assertEquals("cli-new", payload["ide"].asString)
            assertEquals(version, payload["platformVersion"].asString)
            assertFalse(body.contains("private-name") || body.contains("privatecompany") || body.contains(server.url))
        }
    }

    @Test
    fun `manual version input refreshes a fresh cache once and reuses the result after a typo`() {
        FakeServer().use { server ->
            server.publishVersions("3.0.3")
            TemplateRepository(server.url, tempDir).fetchVersions()
            server.publishVersions("3.0.3", "3.1.0")

            val stdin = System.`in`
            try {
                System.setIn(ByteArrayInputStream("2\n9.9.9\n3.1.0\n".toByteArray()))
                val result = NewCommand(tempDir).test(listOf("demo", "--repository", server.url),
                    inputInteractive = false, outputInteractive = false)

                assertTrue(result.stdout.contains("Unknown version. Recent versions: 3.0.3, 3.1.0"), result.stdout)
                assertTrue(result.stderr.contains("Failed to download templates"), result.stderr)
                assertEquals(listOf(metadataPath, "$artifactPath/3.1.0/$artifactId-3.1.0.jar"),
                    server.requests.drop(1).map { it.path })
            } finally {
                System.setIn(stdin)
            }
        }
    }

    @Test
    fun `a failed refresh reports the cached list once when the wizard exits on an unknown version`() {
        FakeServer().use { server ->
            server.publishVersions("3.0.3")
            TemplateRepository(server.url, tempDir).fetchVersions()
            server.on("GET", metadataPath) { FakeServer.Response(503) }

            val result = NewCommand(tempDir).test(listOf("demo", "--repository", server.url,
                "--jmix-version", "3.1.0"), ansiLevel = AnsiLevel.TRUECOLOR, width = 160,
                inputInteractive = true, outputInteractive = true)

            assertEquals(1, result.statusCode)
            assertTrue(result.stderr.contains("Unknown Jmix version '3.1.0'. Available versions: 3.0.3"), result.stderr)
            assertEquals(1, Regex("Using the cached version list").findAll(result.stdout).count(), result.stdout)
            assertEquals(listOf(metadataPath, metadataPath), server.requests.map { it.path })
        }
    }

    private fun premiumRepository(server: FakeServer): Pair<PremiumRepository, Path> {
        server.issuesTokens()
        server.on("GET", "/app/rest/v3/studio/license/check") {
            FakeServer.json(200, """{"activeLicense":true,"sid":"account-PRIVATE"}""")
        }
        val sessions = SessionStore(tempDir.resolve("account/session.json"))
        sessions.save(Session("global", server.url, server.url, "r0"))
        val file = tempDir.resolve("gradle.properties")
        return PremiumRepository(sessions, file, { null }) to file
    }

    @Test
    fun `premium conflict offers keep replace and separate and reports every write`() {
        for (choice in 0..2) FakeServer().use { server ->
            val (repository, file) = premiumRepository(server)
            val original = "# Keep\npremiumRepoUser=old\npremiumRepoPass=oldpass\n"
            Files.writeString(file, original)
            val recorder = TerminalRecorder(width = 160, height = 40)
            recorder.inputEvents += List(choice) { KeyboardEvent("ArrowDown") } + KeyboardEvent("Enter")
            val terminal = Terminal(terminalInterface = recorder)
            val names = configurePremiumRepository(terminal, Prompts(terminal), true, repository)
            assertFalse(recorder.output().contains("PRIVATE"))
            if (choice == 0) {
                assertEquals(original, Files.readString(file))
                assertFalse(recorder.output().contains("credentials saved to"))
            } else {
                assertTrue(recorder.output().contains("Premium repository credentials saved to $file."))
                assertTrue(Files.readString(file).contains("PRIVATE"))
                assertEquals(if (choice == 1) "premiumRepoUser" else "premiumRepoUser.0", names.first)
            }
        }
    }

    @Test
    fun `non-interactive premium conflict leaves even partial settings untouched`() {
        FakeServer().use { server ->
            val (repository, file) = premiumRepository(server)
            val original = "premiumRepoUser=old\n"
            Files.writeString(file, original)
            val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
            val terminal = Terminal(terminalInterface = recorder)
            configurePremiumRepository(terminal, Prompts(terminal), false, repository)
            assertEquals(original, Files.readString(file))
            assertFalse(recorder.output().contains("How should") || recorder.output().contains("credentials saved to"))
        }
    }

    @Test
    fun `premium credentials saved without a conflict are explicitly reported`() {
        FakeServer().use { server ->
            val (repository, file) = premiumRepository(server)
            val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
            val terminal = Terminal(terminalInterface = recorder)
            configurePremiumRepository(terminal, Prompts(terminal), false, repository)
            assertTrue(recorder.output().contains("Premium repository credentials saved to $file."))
            assertFalse(recorder.output().contains("PRIVATE"))
        }
    }

    @Test
    fun `numbered premium conflict accepts a choice and EOF keeps the original`() {
        val stdin = System.`in`
        try {
            for (answer in listOf("2\n", "")) FakeServer().use { server ->
                val (repository, file) = premiumRepository(server)
                val original = "premiumRepoUser=old\npremiumRepoPass=oldpass\n"
                Files.writeString(file, original)
                System.setIn(ByteArrayInputStream(answer.toByteArray()))
                val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
                val terminal = Terminal(terminalInterface = recorder)
                configurePremiumRepository(terminal, Prompts(terminal), true, repository)
                assertTrue(recorder.output().contains("Keep existing credentials"))
                assertTrue(recorder.output().contains("Replace existing credentials"))
                assertTrue(recorder.output().contains("Save account credentials separately"))
                if (answer.isEmpty()) assertEquals(original, Files.readString(file))
                else assertTrue(recorder.output().contains("credentials saved to"))
            }
        } finally { System.setIn(stdin) }
    }

    @Test
    fun `quitting a premium conflict does not change credentials`() {
        FakeServer().use { server ->
            val (repository, file) = premiumRepository(server)
            val original = "premiumRepoUser=old\npremiumRepoPass=oldpass\n"
            Files.writeString(file, original)
            val recorder = TerminalRecorder(width = 160, height = 40)
            recorder.inputEvents += KeyboardEvent("q")
            val terminal = Terminal(terminalInterface = recorder)
            assertThrows<ProgramResult> { configurePremiumRepository(terminal, Prompts(terminal), true, repository) }
            assertEquals(original, Files.readString(file))
            assertFalse(recorder.output().contains("credentials saved to"))
        }
    }

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

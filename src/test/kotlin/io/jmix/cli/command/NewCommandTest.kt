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
    private val invalidPackage = "Invalid Java package name. Each segment must be a Java identifier without keywords."

    private fun metadataXml(vararg versions: String): String {
        val entries = versions.joinToString("") { "<version>$it</version>" }
        return "<metadata><versioning><versions>$entries</versions></versioning></metadata>"
    }

    private fun FakeServer.publishVersions(vararg versions: String) {
        on("GET", metadataPath) { FakeServer.Response(200, metadataXml(*versions).toByteArray()) }
    }

    private fun FakeServer.publishApplicationTemplate(
        version: String = "3.1.0",
        buildScript: String = "// Generated application",
    ) = publishProjectTemplates(
        "application/template.json" to """{"version":1,"name":"Application"}""",
        "application/build.gradle" to buildScript,
        version = version,
    )

    private fun FakeServer.publishProjectTemplates(vararg files: Pair<String, String>, version: String = "3.1.0") {
        val templateJar = templateJar(*files)
        publishVersions(version)
        on("GET", "$artifactPath/$version/$artifactId-$version.jar") { FakeServer.Response(200, templateJar) }
    }

    private fun templateJar(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                for ((path, content) in files) {
                    zip.putNextEntry(ZipEntry("project/$path"))
                    zip.write(content.toByteArray())
                }
            }
        }.toByteArray()

    private fun seedTemplateCache(url: String, jarsByVersion: Map<String, ByteArray>) {
        val cache = Files.createDirectories(TemplateRepository(url, tempDir).repoCacheDir)
        Files.writeString(cache.resolve("maven-metadata.xml"), metadataXml(*jarsByVersion.keys.toTypedArray()))
        for ((version, jar) in jarsByVersion) {
            Files.write(cache.resolve("$artifactId-$version.jar"), jar)
        }
    }

    private fun projectTestArgs(server: FakeServer, name: String = "demo") = listOf(
        name, "--repository", server.url, "--jmix-version", "3.1.0",
        "--project-id", "demo", "--locales", "en", "--addons", "",
        "--no-git", "--no-agents-toolkit",
    )

    private fun locationTestArgs(server: FakeServer) = projectTestArgs(server) + listOf("--package", "com.company.demo")

    private fun testWizard(args: List<String>, raw: Boolean, keys: List<String>, lines: String) = if (raw) {
        NewCommand(tempDir).test(
            args, ansiLevel = AnsiLevel.TRUECOLOR, width = 100, height = 30,
            inputEvents = (keys + "q").map { KeyboardEvent(it) },
        )
    } else {
        val stdin = System.`in`
        try {
            System.setIn(ByteArrayInputStream(lines.toByteArray()))
            NewCommand(tempDir).test(args, inputInteractive = false, outputInteractive = false)
        } finally {
            System.setIn(stdin)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `Back restores the listed repository version and theme on Enter`(raw: Boolean) {
        val repositories = listOf(
            ProjectCreationInfo.DEFAULT_REPOSITORY_URL to "primary",
            "https://nexus.jmix.io/repository/public" to "backup",
        )
        for ((url, name) in repositories) {
            val jars = listOf("3.0.3", "3.1.0").associateWith { version ->
                templateJar(
                    "application/template.json" to
                        """{"version":1,"name":"Application","params":[{"name":"projectTheme"}]}""",
                    "application/build.gradle" to "// $name $version \${project_theme}",
                )
            }
            seedTemplateCache(url, jars)
        }
        val target = tempDir.resolve("out")
        val args = listOf("demo", "--package", "com.company.demo", "--project-id", "demo", "--addons", "",
            "--path", target.toString(), "--no-git", "--no-agents-toolkit")
        val selectSecondAndRevisit = listOf("ArrowDown", "Enter", "Escape", "Enter")
        val result = testWizard(args, raw,
            selectSecondAndRevisit + selectSecondAndRevisit + selectSecondAndRevisit + "Enter",
            "2\n<\n\n2\n<\n\n2\n<\n\n\n",
        )

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("// backup 3.0.3 lumo", Files.readString(target.resolve("build.gradle")))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `Back restores a custom repository and manually entered version on one Enter each`(raw: Boolean) {
        FakeServer().use { server ->
            val version = "3.1.0-RC1"
            server.publishApplicationTemplate("3.1.0", "// Stable version")
            server.publishApplicationTemplate(version, "// Explicit pre-release")
            server.publishVersions(version, "3.1.0")
            val primaryJar = templateJar(
                "application/template.json" to """{"version":1,"name":"Application"}""",
                "application/build.gradle" to "// Primary repository",
            )
            seedTemplateCache(ProjectCreationInfo.DEFAULT_REPOSITORY_URL, mapOf(version to primaryJar, "3.1.0" to primaryJar))
            val target = tempDir.resolve("out")
            val args = listOf("demo", "--project-id", "demo", "--locales", "en", "--addons", "",
                "--path", target.toString(), "--no-git", "--no-agents-toolkit")
            val result = testWizard(args, raw,
                listOf("ArrowDown", "ArrowDown", "Enter") + server.url.map(Char::toString) +
                    listOf("Enter", "Escape", "Enter", "ArrowDown", "Enter") + version.map(Char::toString) +
                    listOf("Enter", "Escape", "Enter", "Enter"),
                "3\n${server.url}\n<\n\n2\n$version\n<\n\n\n",
            )

            assertEquals(0, result.statusCode, result.stderr)
            assertEquals("// Explicit pre-release", Files.readString(target.resolve("build.gradle")))
            assertEquals(listOf(metadataPath, "$artifactPath/$version/$artifactId-$version.jar"),
                server.requests.map { it.path })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `going back from package preserves the Kotlin template on Enter`(raw: Boolean) {
        FakeServer().use { server ->
            server.publishProjectTemplates(
                "application/template.json" to """{"version":1,"name":"Java Application","order":0}""",
                "application/src/main/java/Demo.java" to "class Demo {}",
                "application-kotlin/template.json" to """{"version":1,"name":"Kotlin Application","order":1}""",
                "application-kotlin/src/main/kotlin/Demo.kt" to "class Demo",
            )
            val target = tempDir.resolve("out")
            val args = projectTestArgs(server) + listOf("--path", target.toString())
            val result = if (raw) {
                NewCommand(tempDir).test(
                    args, ansiLevel = AnsiLevel.TRUECOLOR, width = 100, height = 30,
                    inputEvents = listOf("ArrowDown", "Enter", "Escape", "Enter", "Enter", "q").map { KeyboardEvent(it) },
                )
            } else {
                val stdin = System.`in`
                try {
                    System.setIn(ByteArrayInputStream("2\n<\n\n\n".toByteArray()))
                    NewCommand(tempDir).test(args, inputInteractive = false, outputInteractive = false)
                } finally {
                    System.setIn(stdin)
                }
            }

            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(Files.isRegularFile(target.resolve("src/main/kotlin/Demo.kt")), result.stdout)
            assertFalse(Files.exists(target.resolve("src/main/java/Demo.java")))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["com.class.demo", "com.1team.demo"])
    fun `the package option rejects invalid segments before generation`(pkg: String) {
        FakeServer().use { server ->
            server.publishApplicationTemplate()
            val target = tempDir.resolve("out")
            val result = NewCommand(tempDir).test(
                projectTestArgs(server) + listOf("--non-interactive", "--path", target.toString(), "--package", pkg),
            )

            assertEquals(1, result.statusCode)
            assertEquals("$invalidPackage\n", result.stderr)
            assertFalse(Files.exists(target))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `manual package input retries invalid segments`(raw: Boolean) {
        FakeServer().use { server ->
            server.publishApplicationTemplate(buildScript = "// \${project_rootPackage}")
            val target = tempDir.resolve("out")
            val packages = listOf("com.class.demo", "com.1team.demo", "com._team2.demo")
            val args = projectTestArgs(server) + listOf("--path", target.toString())
            val result = if (raw) {
                val input = packages.flatMap { pkg -> pkg.map { KeyboardEvent(it.toString()) } + KeyboardEvent("Enter") }
                NewCommand(tempDir).test(
                    args, ansiLevel = AnsiLevel.TRUECOLOR, width = 160, height = 40,
                    inputEvents = input + KeyboardEvent("q"),
                )
            } else {
                val stdin = System.`in`
                try {
                    System.setIn(ByteArrayInputStream(packages.joinToString("\n", postfix = "\n").toByteArray()))
                    NewCommand(tempDir).test(args, inputInteractive = false, outputInteractive = false)
                } finally {
                    System.setIn(stdin)
                }
            }

            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(Regex.fromLiteral(invalidPackage).findAll(result.stdout).count() >= 2, result.stdout)
            assertEquals("// com._team2.demo", Files.readString(target.resolve("build.gradle")))
        }
    }

    @Test
    fun `automatic package is validated before generation`() {
        FakeServer().use { server ->
            server.publishApplicationTemplate(buildScript = "// \${project_rootPackage}")
            val target = tempDir.resolve("out")
            val options = listOf("--non-interactive", "--path", target.toString())
            val args = projectTestArgs(server, "cl-ass") + options

            val rejected = NewCommand(tempDir).test(args)
            assertEquals(1, rejected.statusCode)
            assertEquals("$invalidPackage Pass a valid package with --package.\n", rejected.stderr)
            assertFalse(Files.exists(target))

            val overridden = NewCommand(tempDir).test(args + listOf("--package", "com.company.class_app"))
            assertEquals(0, overridden.statusCode, overridden.stderr)
            assertEquals("// com.company.class_app", Files.readString(target.resolve("build.gradle")))

            val derivedTarget = tempDir.resolve("derived")
            val derived = NewCommand(tempDir).test(
                projectTestArgs(server, "my-demo") + listOf("--non-interactive", "--path", derivedTarget.toString()),
            )
            assertEquals(0, derived.statusCode, derived.stderr)
            assertEquals("// com.company.mydemo", Files.readString(derivedTarget.resolve("build.gradle")))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://", "http://bad_host.invalid"])
    fun `an invalid repository option fails before template lookup`(url: String) {
        val result = NewCommand(tempDir).test(listOf("demo", "--non-interactive", "--repository", url))

        assertEquals(1, result.statusCode)
        assertEquals("Repository URL must be a valid HTTP(S) URL with a host.\n", result.stderr)
        assertFalse(result.stdout.contains("Fetching Jmix versions"), result.stdout)
        Files.list(tempDir).use { assertFalse(it.findAny().isPresent) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the wizard retries invalid repository input before contacting the valid source`(raw: Boolean) {
        FakeServer().use { server ->
            server.publishApplicationTemplate()
            val target = tempDir.resolve("demo")
            val args = listOf(
                "demo", "--jmix-version", "3.1.0", "--template", "application", "--package", "com.company.demo",
                "--project-id", "demo", "--locales", "en", "--addons", "", "--path", target.toString(),
                "--no-git", "--no-agents-toolkit",
            )
            val urls = listOf("http://", "http://bad_host.invalid", "${server.url}?invalid=1", "${server.url}/")
            val result = if (raw) {
                val input = urls.flatMap { url -> url.map { KeyboardEvent(it.toString()) } + KeyboardEvent("Enter") }
                NewCommand(tempDir).test(
                    args, ansiLevel = AnsiLevel.TRUECOLOR, width = 160, height = 40,
                    inputEvents = List(2) { KeyboardEvent("ArrowDown") } + KeyboardEvent("Enter") + input + KeyboardEvent("q"),
                )
            } else {
                val stdin = System.`in`
                try {
                    System.setIn(ByteArrayInputStream(urls.joinToString("\n", prefix = "3\n", postfix = "\n").toByteArray()))
                    NewCommand(tempDir).test(args, inputInteractive = false, outputInteractive = false)
                } finally {
                    System.setIn(stdin)
                }
            }

            assertEquals(0, result.statusCode, result.stderr)
            val invalidHost = Regex.fromLiteral("Repository URL must be a valid HTTP(S) URL with a host.")
            assertTrue(invalidHost.findAll(result.stdout).count() >= 2, result.stdout)
            assertTrue(result.stdout.contains("Use a repository base URL without credentials, a query, or a fragment."))
            assertEquals(listOf(metadataPath, "$artifactPath/3.1.0/$artifactId-3.1.0.jar"), server.requests.map { it.path })
            assertEquals("// Generated application", Files.readString(target.resolve("build.gradle")))
        }
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
        FakeServer().use { server ->
            server.publishApplicationTemplate(version)
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
            assertTrue(result.stdout.contains("--repository ${server.url}"), result.stdout)
            val body = server.requests.single { it.method == "POST" && it.path == "/rest/event/open" }.body
            assertTrue(generatedAtSend.get())
            val payload = JsonParser.parseString(body).asJsonObject
            assertEquals("cli-new", payload["ide"].asString)
            assertEquals(version, payload["platformVersion"].asString)
            assertFalse(body.contains("private-name") || body.contains("privatecompany") || body.contains(server.url))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `declining overwrite lets the wizard choose another folder`(raw: Boolean) {
        FakeServer().use { server ->
            server.publishApplicationTemplate()
            val occupied = Files.createDirectory(tempDir.resolve("occupied"))
            val existing = Files.writeString(occupied.resolve("build.gradle"), "// Keep this project")
            val target = Files.createDirectory(tempDir.resolve("empty folder"))
            val args = locationTestArgs(server)
            val result = if (raw) {
                fun chooseCustom(path: Path) = List(3) { KeyboardEvent("ArrowDown") } + KeyboardEvent("Enter") +
                    path.toString().map { KeyboardEvent(it.toString()) } + KeyboardEvent("Enter")
                NewCommand(tempDir).test(
                    args, ansiLevel = AnsiLevel.TRUECOLOR, width = 80, height = 40,
                    inputEvents = chooseCustom(occupied) + KeyboardEvent("Enter") + chooseCustom(target) +
                        KeyboardEvent("q"),
                )
            } else {
                val stdin = System.`in`
                try {
                    System.setIn(ByteArrayInputStream("4\n$occupied\nn\n4\n$target\n".toByteArray()))
                    NewCommand(tempDir).test(args, inputInteractive = false, outputInteractive = false)
                } finally {
                    System.setIn(stdin)
                }
            }

            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("Overwrite matching files"), result.stdout)
            assertEquals("// Keep this project", Files.readString(existing))
            assertEquals("// Generated application", Files.readString(target.resolve("build.gradle")))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["yes", ""])
    fun `an explicitly chosen non-empty folder needs confirmation even at end of input`(answer: String) {
        FakeServer().use { server ->
            server.publishApplicationTemplate()
            val target = Files.createDirectory(tempDir.resolve("occupied"))
            val buildFile = Files.writeString(target.resolve("build.gradle"), "// Keep this project")
            val stdin = System.`in`
            val result = try {
                System.setIn(ByteArrayInputStream(answer.toByteArray()))
                NewCommand(tempDir).test(
                    locationTestArgs(server) + listOf("--path", target.toString()),
                    inputInteractive = false, outputInteractive = false,
                )
            } finally {
                System.setIn(stdin)
            }

            assertTrue(result.stdout.contains("Overwrite matching files"), result.stdout)
            if (answer == "yes") {
                assertEquals(0, result.statusCode, result.stderr)
                assertEquals("// Generated application", Files.readString(buildFile))
            } else {
                assertEquals(1, result.statusCode)
                assertTrue(result.stderr.contains("Choose another folder with --path"), result.stderr)
                assertFalse(result.stdout.contains("Detecting installed JDKs"), result.stdout)
                assertEquals("// Keep this project", Files.readString(buildFile))
            }
        }
    }

    @Test
    fun `non-interactive overwrite requires force and keeps unrelated files`() {
        FakeServer().use { server ->
            server.publishApplicationTemplate()
            val target = Files.createDirectory(tempDir.resolve("occupied"))
            val buildFile = Files.writeString(target.resolve("build.gradle"), "// Keep this project")
            val notes = Files.writeString(target.resolve("notes.txt"), "Existing notes")
            val args = locationTestArgs(server) + listOf("--non-interactive", "--path", target.toString())

            val declined = NewCommand(tempDir).test(args)
            assertEquals(1, declined.statusCode)
            assertTrue(declined.stderr.contains("or use --force"), declined.stderr)
            assertEquals("// Keep this project", Files.readString(buildFile))

            val accepted = NewCommand(tempDir).test(args + "--force")
            assertEquals(0, accepted.statusCode, accepted.stderr)
            assertFalse(accepted.stdout.contains("Overwrite matching files"), accepted.stdout)
            assertEquals("// Generated application", Files.readString(buildFile))
            assertEquals("Existing notes", Files.readString(notes))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.0", "3.0.3"])
    fun `switching repositories reloads the template catalog and uses an available version`(secondVersion: String) {
        FakeServer().use { first ->
            FakeServer().use { second ->
                first.publishApplicationTemplate(buildScript = "// First repository")
                second.publishApplicationTemplate(secondVersion, "// Second repository")
                val target = tempDir.resolve("out")
                val stdin = System.`in`
                val result = try {
                    System.setIn(ByteArrayInputStream("3\n${first.url}\n1\n<\n<\n3\n${second.url}\n\n\n".toByteArray()))
                    NewCommand(tempDir).test(
                        listOf("demo", "--project-id", "demo", "--locales", "en", "--addons", "",
                            "--path", target.toString(), "--no-git", "--no-agents-toolkit"),
                        inputInteractive = false, outputInteractive = false,
                    )
                } finally {
                    System.setIn(stdin)
                }

                assertEquals(0, result.statusCode, result.stderr)
                assertEquals("// Second repository", Files.readString(target.resolve("build.gradle")))
                assertEquals(listOf(metadataPath, "$artifactPath/3.1.0/$artifactId-3.1.0.jar"),
                    first.requests.map { it.path })
                assertEquals(listOf(metadataPath, "$artifactPath/$secondVersion/$artifactId-$secondVersion.jar"),
                    second.requests.map { it.path })
            }
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

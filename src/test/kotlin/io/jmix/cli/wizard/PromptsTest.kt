package io.jmix.cli.wizard

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.mordant.input.InputEvent
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.rendering.Size
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalInterface
import com.github.ajalt.mordant.terminal.TerminalRecorder
import com.github.ajalt.mordant.terminal.TimeoutException
import com.github.ajalt.mordant.widgets.SelectList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.time.TimeMark

class PromptsTest {

    @Test
    fun `progress follows stage jumps and back navigation inside the same screen`() {
        val recorder = TerminalRecorder(width = 80, height = 20, supportsAnsiCursor = false)
        recorder.inputEvents += listOf(KeyboardEvent("Enter"), KeyboardEvent("Escape"), KeyboardEvent("Enter"))
        var stage = WizardStage.GENERAL
        val prompts = Prompts(Terminal(terminalInterface = recorder)) { WizardUiState(stage = stage) }

        prompts.useAlternateScreen {
            prompts.choose("Template", listOf("application", "addon"), { it })
            stage = WizardStage.LOCATION
            assertEquals(Answer.Back, prompts.choose("Location", listOf("here", "other"), { it }, allowBack = true))
            stage = WizardStage.ADDONS
            prompts.choose("Add-ons", listOf("Quartz", "Reports"), { it }, allowBack = true)
        }

        val headings = recorder.output().split(CLEAR_FROM_ORIGIN).drop(1)
            .map { ANSI_SEQUENCE.replace(it, "").lineSequence().first().trim() }
        assertEquals(listOf("1/5", "4/5", "3/5"), headings.map { it.takeLast(3) })
        assertTrue(headings[0].startsWith("General"))
        assertTrue(headings[1].startsWith("Location and setup"))
        assertTrue(headings[2].startsWith("Add-ons"))
        assertEquals(1, recorder.output().occurrencesOf(ENTER_ALTERNATE_SCREEN))
    }

    @Test
    fun `progress shrinks before the selected add-on loses its description and controls`() {
        val heights = listOf(9, 8, 7, 4, 9)
        val recorder = ResizingTerminalInterface(
            initialSize = Size(100, heights.first()),
            sizesAfterInput = emptyList(),
            sizesOnTimeout = heights.drop(1).map { Size(100, it) },
        )
        recorder.inputEvents += KeyboardEvent("Enter")
        val prompts = Prompts(Terminal(terminalInterface = recorder)) { WizardUiState(stage = WizardStage.ADDONS) }

        prompts.useAlternateScreen {
            prompts.chooseMany(
                "Choose add-ons",
                listOf(SelectList.Entry("Quartz", description = "Manage jobs"), SelectList.Entry("Reports", description = "Build reports")),
                allowBack = true,
                filterTexts = listOf("jobs", "reports"),
                groups = listOf("Add-ons", "Add-ons"),
            )
        }

        val frames = recorder.output().split(CLEAR_FROM_ORIGIN).drop(1)
            .map { ANSI_SEQUENCE.replace(it.substringBefore(EXIT_ALTERNATE_SCREEN), "").trimEnd() }
        assertEquals(heights.size, frames.size)
        frames.zip(heights).forEach { (frame, height) ->
            assertTrue(frame.lines().size <= height, frame)
            assertTrue(frame.contains("Quartz"), frame)
            assertTrue(frame.contains("Search:"), frame)
            assertTrue(frame.contains("enter"), frame)
            assertTrue(frame.contains("esc"), frame)
            assertEquals(height >= 8, frame.contains("3/5"), frame)
            assertEquals(height >= 9, frame.contains('━'), frame)
            assertEquals(height >= 7, frame.contains("Manage jobs"), frame)
        }
    }

    @Test
    fun `transcript progress is printed once per stage and again after going back`() = withStdin("\n\n\n\n\n") {
        val recorder = TerminalRecorder(inputInteractive = false)
        var stage = WizardStage.GENERAL
        val prompts = Prompts(Terminal(terminalInterface = recorder)) { WizardUiState(stage = stage) }

        prompts.ask("Name", "demo")
        prompts.ask("Package", "com.company.demo")
        stage = WizardStage.ADDONS
        prompts.choose("Add-ons", listOf("Quartz", "Reports"), { it })
        stage = WizardStage.LOCATION
        prompts.askYesNo("Create Git repository?", true)
        stage = WizardStage.GENERAL
        prompts.ask("Name", "demo")

        val plain = ANSI_SEQUENCE.replace(recorder.output(), "")
        assertEquals(
            listOf("Step 1/5: General", "Step 3/5: Add-ons", "Step 4/5: Location and setup", "Step 1/5: General"),
            Regex("Step [1-5]/5: [^\\r\\n]+").findAll(plain).map { it.value }.toList(),
        )
        assertFalse(plain.contains('━'))
    }

    @Test
    fun `progress fits narrow terminals and disappears for post-generation prompts`() {
        for (width in listOf(2, 4, 12, 80)) {
            val recorder = TerminalRecorder(width = width, height = 10, supportsAnsiCursor = false)
            var stage: WizardStage? = WizardStage.GENERATION
            val prompts = Prompts(Terminal(terminalInterface = recorder)) { WizardUiState(stage = stage) }

            prompts.printProgress()
            val output = recorder.output()
            val lines = ANSI_SEQUENCE.replace(output, "").trimEnd().lines()
            assertEquals(2, lines.size)
            assertTrue(lines.all { it.length < width }, lines.toString())
            assertTrue(lines[1].all { it == '━' }, lines.toString())
            if (width >= 4) assertTrue(lines[0].endsWith("5/5"), lines.toString())
            stage = null
            prompts.printProgress()
            assertEquals(output, recorder.output())
        }
    }

    @Test
    fun `selector repaints from origin after terminal narrows`() {
        val recorder = ResizingTerminalInterface(
            initialSize = Size(100, 40),
            sizesAfterInput = listOf(Size(28, 40)),
        )
        recorder.inputEvents += listOf(
            KeyboardEvent("ArrowDown"),
            KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.choose(
            question = "Select Jmix version",
            items = listOf("3.0.1", "2.8.3", "2.7.6", "2.6.2", "Other..."),
            title = { it },
            allowBack = true,
        ).requireValue()

        val output = recorder.output()
        assertEquals("2.8.3", selected)
        assertEquals(2, output.windowed(CLEAR_FROM_ORIGIN.length).count { it == CLEAR_FROM_ORIGIN })
        assertTrue(output.contains(ENTER_ALTERNATE_SCREEN))
        assertTrue(output.contains(EXIT_ALTERNATE_SCREEN))
    }

    @Test
    fun `selector reconstructs the complete frame after terminal shrinks and expands`() {
        val recorder = ResizingTerminalInterface(
            initialSize = Size(100, 30),
            sizesAfterInput = listOf(
                Size(100, 4),
                Size(100, 4),
                Size(100, 4),
                Size(100, 30),
                Size(100, 30),
            ),
        )
        recorder.inputEvents += listOf(
            KeyboardEvent("ArrowDown"),
            KeyboardEvent("ArrowUp"),
            KeyboardEvent("ArrowDown"),
            KeyboardEvent("ArrowDown"),
            KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.choose(
            question = "Select Jmix version",
            items = listOf("3.0.1", "2.8.3", "2.7.6", "2.6.2", "Other..."),
            title = { it },
            allowBack = true,
        ).requireValue()

        val output = recorder.output()
        val expandedFrame = output.substringAfterLast(CLEAR_FROM_ORIGIN).substringBefore(EXIT_ALTERNATE_SCREEN)
        assertEquals("2.7.6", selected)
        assertTrue(output.contains(ENTER_ALTERNATE_SCREEN))
        assertTrue(output.contains(EXIT_ALTERNATE_SCREEN))
        assertEquals(5, output.windowed(CLEAR_FROM_ORIGIN.length).count { it == CLEAR_FROM_ORIGIN })
        assertEquals(1, expandedFrame.split("Select Jmix version").size - 1)
        listOf("3.0.1", "2.8.3", "2.7.6", "2.6.2", "Other...").forEach {
            assertTrue(expandedFrame.contains(it), "Expanded frame should contain $it")
        }
    }

    @Test
    fun `selector redraws after terminal expansion without waiting for a keypress`() {
        val recorder = ResizingTerminalInterface(
            initialSize = Size(100, 4),
            sizesAfterInput = emptyList(),
            sizesOnTimeout = listOf(
                Size(100, 4),
                Size(100, 4),
                Size(100, 30),
            ),
        )
        recorder.inputEvents += KeyboardEvent("Enter")
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        prompts.choose(
            question = "Select Jmix version",
            items = listOf("3.0.1", "2.8.3", "2.7.6", "2.6.2", "Other..."),
            title = { it },
            allowBack = true,
        )

        val output = recorder.output()
        val expandedFrame = output.substringAfterLast(CLEAR_FROM_ORIGIN).substringBefore(EXIT_ALTERNATE_SCREEN)
        assertEquals(2, output.windowed(CLEAR_FROM_ORIGIN.length).count { it == CLEAR_FROM_ORIGIN })
        listOf("3.0.1", "2.8.3", "2.7.6", "2.6.2", "Other...").forEach {
            assertTrue(expandedFrame.contains(it), "Expanded frame should contain $it")
        }
    }

    @Test
    fun `selector keeps long options and navigation to one row each after narrowing`() {
        val recorder = ResizingTerminalInterface(
            initialSize = Size(100, 5),
            sizesAfterInput = listOf(Size(45, 5)),
        )
        recorder.inputEvents += listOf(
            KeyboardEvent("ArrowDown"),
            KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.choose(
            question = "Select project template",
            items = listOf("application", "application-kotlin"),
            title = { it },
            description = { "Full-Stack Application with a deliberately long description" },
            allowBack = true,
        ).requireValue()

        val narrowedFrame = recorder.output()
            .substringAfterLast(CLEAR_FROM_ORIGIN)
            .substringBefore(EXIT_ALTERNATE_SCREEN)
        assertEquals("application-kotlin", selected)
        assertTrue(narrowedFrame.contains("enter"))
        assertTrue(narrowedFrame.contains("esc"))
    }

    @Test
    fun `the wizard screen is switched once, not once per selection`() {
        // Switching per selection made the banner and the answers so far flash
        // between every question.
        // supportsAnsiCursor = false selects the full-screen renderer.
        val recorder = TerminalRecorder(width = 120, height = 24, supportsAnsiCursor = false)
        repeat(3) { recorder.inputEvents += KeyboardEvent("Enter") }
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val used = prompts.useAlternateScreen {
            repeat(3) {
                prompts.choose(
                    question = "Select something",
                    items = listOf("first", "second"),
                    title = { it },
                )
            }
        }

        val output = recorder.output()
        assertTrue(used, "an interactive terminal must use the alternate screen")
        assertEquals(1, output.occurrencesOf(ENTER_ALTERNATE_SCREEN), "one switch in")
        assertEquals(1, output.occurrencesOf(EXIT_ALTERNATE_SCREEN), "one switch out")
    }

    @Test
    fun `a console that only supports carriage returns keeps the primary screen`() {
        // The IntelliJ Run window renders prompts line by line, so a wizard
        // screen there would be repainted over rather than replaced.
        val recorder = TerminalRecorder(width = 100, height = 24, supportsAnsiCursor = true)
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val used = prompts.useAlternateScreen { }

        assertFalse(used)
        assertFalse(recorder.output().contains(ENTER_ALTERNATE_SCREEN))
    }

    @Test
    fun `a non-interactive terminal never switches screens`() {
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val used = prompts.useAlternateScreen { }

        assertFalse(used)
        assertFalse(recorder.output().contains(ENTER_ALTERNATE_SCREEN))
    }

    @Test
    fun `selector highlights its question and aligns descriptions in one column`() {
        val recorder = TerminalRecorder(
            width = 120,
            height = 10,
            supportsAnsiCursor = false,
        )
        recorder.inputEvents += KeyboardEvent("Enter")
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        prompts.choose(
            question = "Select project template",
            items = listOf("application", "application-kotlin", "addon"),
            title = { it },
            description = { "Description for $it" },
            allowBack = true,
        )

        val frame = recorder.output()
            .substringAfterLast(CLEAR_FROM_ORIGIN)
            .substringBefore(EXIT_ALTERNATE_SCREEN)
        val plainFrame = ANSI_SEQUENCE.replace(frame, "")
        val descriptionLines = plainFrame.lines().filter { "Description for" in it }

        assertTrue(
            PURPLE_QUESTION.containsMatchIn(frame),
            frame.replace("\u001B", "<ESC>"),
        )
        assertEquals(3, descriptionLines.size)
        assertEquals(1, descriptionLines.map { it.indexOf('—') }.distinct().size)
    }

    @Test
    fun `selector rebuilds completed wizard choices from UI state`() {
        val recorder = TerminalRecorder(
            width = 100,
            height = 10,
            supportsAnsiCursor = false,
        )
        recorder.inputEvents += KeyboardEvent("Enter")
        val prompts = Prompts(Terminal(terminalInterface = recorder)) {
            WizardUiState(listOf(WizardChoice("Project name", "jmix-project")))
        }

        prompts.choose(
            question = "Select Jmix version",
            items = listOf("3.0.1", "2.8.3"),
            title = { it },
            allowBack = true,
        )

        val frame = recorder.output()
            .substringAfterLast(CLEAR_FROM_ORIGIN)
            .substringBefore(EXIT_ALTERNATE_SCREEN)
        assertTrue(frame.contains("Project name"))
        assertTrue(frame.contains("jmix-project"))
        assertEquals(1, frame.split("Select Jmix version").size - 1)
    }

    @Test
    fun `selection state updates immutably and preserves entry order`() {
        val initial = SelectionUiState(
            question = "Select locales",
            entries = listOf(
                SelectList.Entry("en — English", selected = true),
                SelectList.Entry("de — German"),
                SelectList.Entry("fr — French"),
            ),
            multi = true,
            allowBack = true,
            maxVisibleEntries = 2,
        )

        val updated = initial.move(1).toggle()

        assertEquals(0, initial.cursorIndex)
        assertEquals(setOf(0), initial.selectedIndices)
        assertEquals(1, updated.cursorIndex)
        assertEquals(setOf(0, 1), updated.selectedIndices)
        assertEquals(listOf("en — English", "de — German"), updated.pickedValues())
    }

    @Test
    fun `IDE console selector does not append a frame for every keypress`() {
        val recorder = TerminalRecorder(
            width = 120,
            height = 40,
            supportsAnsiCursor = true,
        )
        repeat(20) { recorder.inputEvents += KeyboardEvent("ArrowDown") }
        recorder.inputEvents += KeyboardEvent("Enter")
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.choose(
            question = "Select project template",
            items = listOf("application", "addon", "rest-service"),
            title = { it },
            description = { "Description for $it" },
            allowBack = true,
        ).requireValue()

        assertEquals("rest-service", selected)
        assertEquals(1, recorder.output().split("Select project template").size - 1)
        assertTrue(recorder.output().count { it == '\n' } <= 5)
        assertEquals(
            8,
            recorder.output().count { it == '\r' },
            "Only the initial frame, two cursor moves, and final clear should repaint",
        )
    }

    @Test
    fun `IDE console selector preserves multi choice space toggles`() {
        val recorder = TerminalRecorder(
            width = 120,
            height = 40,
            supportsAnsiCursor = true,
        )
        recorder.inputEvents += listOf(
            KeyboardEvent(" "),
            KeyboardEvent("ArrowDown"),
            KeyboardEvent("Spacebar"),
            KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.chooseMany(
            question = "Select locales",
            entries = listOf("en — English", "de — German", "fr — French")
                .map { SelectList.Entry(it) },
            allowBack = true,
        )!!.requireValue()

        assertEquals(listOf("en — English", "de — German"), selected)
        assertEquals(1, recorder.output().split("Select locales").size - 1)
        assertTrue(recorder.output().count { it == '\n' } <= 5)
    }

    @Test
    fun `multi choice values distinguish identical titles in every terminal mode`() {
        for ((interactive, ansiCursor) in listOf(true to false, true to true, false to false)) {
            withStdin("2\n\n") {
                val recorder = TerminalRecorder(width = 80, height = 20,
                    inputInteractive = interactive, supportsAnsiCursor = ansiCursor)
                recorder.inputEvents += listOf(KeyboardEvent("ArrowDown"), KeyboardEvent(" "), KeyboardEvent("Enter"))
                val selected = Prompts(Terminal(terminalInterface = recorder)).chooseMany(
                    "Select add-ons",
                    listOf(SelectList.Entry("Same name"), SelectList.Entry("Same name")),
                    filterTexts = listOf("First add-on", "Second add-on"),
                    values = listOf("first-id", "second-id"),
                )!!.requireValue()

                assertEquals(listOf("second-id"), selected)
                assertFalse(recorder.output().contains("first-id"))
                assertFalse(recorder.output().contains("second-id"))
            }
        }
    }

    @Test
    fun `searchable selector filters supplied name and description text`() {
        val recorder = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = false)
        recorder.inputEvents += listOf(KeyboardEvent("/")) +
            "service SECUREx".map { KeyboardEvent(if (it == ' ') "Spacebar" else it.toString()) } + listOf(
            KeyboardEvent("Backspace"), KeyboardEvent("Enter"),
            KeyboardEvent(" "),
            KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.chooseMany(
            question = "Select add-ons",
            entries = listOf("Audit", "REST Data Store").map { SelectList.Entry(it) },
            filterTexts = listOf("Audit tracks entity changes", "REST Data Store connects to a secure service"),
        )!!.requireValue()

        assertEquals(listOf("REST Data Store"), selected)
        assertTrue(ANSI_SEQUENCE.replace(recorder.output(), "").contains("Search: service SECURE"))
    }

    @Test
    fun `searchable selector retains selections hidden by a filter`() {
        val recorder = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = false)
        recorder.inputEvents += listOf(
            KeyboardEvent(" "),
            KeyboardEvent("/"), KeyboardEvent("b"), KeyboardEvent("Enter"),
            KeyboardEvent(" "), KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.chooseMany(
            question = "Select add-ons",
            entries = listOf("Alpha", "Beta").map { SelectList.Entry(it) },
            filterTexts = listOf("Alpha", "Beta"),
        )!!.requireValue()

        assertEquals(listOf("Alpha", "Beta"), selected)
        val output = ANSI_SEQUENCE.replace(recorder.output(), "")
        for (count in 0..2) assertTrue(output.contains("Select add-ons  •  $count selected"), output)
        val searches = output.lineSequence().filter { it.startsWith("Search:") }.map(String::trimEnd).toList()
        assertTrue(searches.contains("Search: b"))
        assertTrue(searches.all { it in listOf("Search: / to search", "Search:", "Search: b") }, searches.toString())
    }

    @Test
    fun `locked searchable entries stay selected`() {
        for (ansiCursor in listOf(false, true)) {
            val recorder = TerminalRecorder(width = 55, height = 24, supportsAnsiCursor = ansiCursor)
            recorder.inputEvents += listOf(KeyboardEvent(" "), KeyboardEvent("Enter"))
            val prompts = Prompts(Terminal(terminalInterface = recorder))
            val required = "Required add-on with a description longer than the terminal width"
            val selected = prompts.chooseMany(
                question = "Select add-ons",
                entries = listOf(required, "Optional").map { SelectList.Entry(it) },
                filterTexts = listOf("Required", "Optional"),
                lockedIndices = setOf(0),
            )!!.requireValue()

            assertEquals(listOf(required), selected)
            assertTrue(recorder.output().contains("included"))
        }
    }

    @Test
    fun `searchable selector can confirm retained choices with no matches`() {
        val recorder = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = false)
        recorder.inputEvents += listOf(
            KeyboardEvent("/"),
            KeyboardEvent("z"), KeyboardEvent("z"), KeyboardEvent("z"),
            KeyboardEvent("Enter"), KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.chooseMany(
            question = "Select add-ons",
            entries = listOf(SelectList.Entry("Required", selected = true)),
            filterTexts = listOf("Required"),
        )!!.requireValue()

        assertEquals(listOf("Required"), selected)
        assertTrue(recorder.output().contains("No matches."))
    }

    @Test
    fun `English and Russian q exit add-on selection before and after search in every terminal mode`() {
        for ((interactive, ansiCursor) in listOf(true to false, true to true, false to false)) {
            for (query in listOf("", "jobs", "no matches")) {
                for (key in listOf("q", "Q", "й", "Й")) {
                    withStdin((if (query.isEmpty()) "" else "/$query\n") + "$key\n") {
                        val recorder = TerminalRecorder(
                            width = 100, height = 20,
                            inputInteractive = interactive, supportsAnsiCursor = ansiCursor,
                        )
                        if (query.isNotEmpty()) {
                            recorder.inputEvents += KeyboardEvent("/")
                            recorder.inputEvents += query.map { KeyboardEvent(it.toString()) }
                            recorder.inputEvents += KeyboardEvent("Enter")
                        }
                        recorder.inputEvents += listOf(KeyboardEvent(key), KeyboardEvent("Enter"))
                        val prompts = Prompts(Terminal(terminalInterface = recorder))

                        val result = assertThrows(CliktError::class.java) {
                            prompts.chooseMany(
                                "Select add-ons",
                                listOf(SelectList.Entry("Quartz"), SelectList.Entry("German", selected = true)),
                                filterTexts = listOf("Quartz jobs", "German translation"),
                                groups = listOf("Integrations", "Translations"),
                            )
                        }
                        assertEquals(0, result.statusCode)
                        assertFalse(prompts.isInputExhausted)
                    }
                }
            }
        }
    }

    @Test
    fun `English and Russian q edit a search while ctrl quits`() {
        for (ansiCursor in listOf(false, true)) {
            for (key in listOf("q", "Q", "й", "Й")) {
                val plainQ = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = ansiCursor)
                plainQ.inputEvents += listOf(
                    KeyboardEvent("/"), KeyboardEvent(key), KeyboardEvent("Enter"),
                    KeyboardEvent(" "), KeyboardEvent("Enter"),
                )
                val prompts = Prompts(Terminal(terminalInterface = plainQ))

                assertEquals(
                    listOf("Queue"),
                    prompts.chooseMany(
                        "Select add-ons",
                        listOf(SelectList.Entry("Queue")),
                        filterTexts = listOf("$key processing"),
                    )!!.requireValue(),
                )

                val ctrlQ = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = ansiCursor)
                ctrlQ.inputEvents += listOf(
                    KeyboardEvent("/"), KeyboardEvent(key, ctrl = true),
                    KeyboardEvent("Enter"), KeyboardEvent("Enter"),
                )
                val quitPrompts = Prompts(Terminal(terminalInterface = ctrlQ))
                val result = assertThrows(CliktError::class.java) {
                    quitPrompts.chooseMany(
                        "Select add-ons",
                        listOf(SelectList.Entry("Queue")),
                        filterTexts = listOf("Queue processing"),
                    )
                }
                assertEquals(0, result.statusCode)
            }
        }
    }

    @Test
    fun `escape cancels search editing then goes back`() {
        val recorder = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = false)
        recorder.inputEvents += listOf(
            KeyboardEvent("/"), KeyboardEvent("a"), KeyboardEvent("Enter"),
            KeyboardEvent("/"), KeyboardEvent("b"), KeyboardEvent("Escape"),
            KeyboardEvent("Escape"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val answer = prompts.chooseMany(
            question = "Select add-ons",
            entries = listOf("Alpha", "Beta").map { SelectList.Entry(it) },
            allowBack = true,
            filterTexts = listOf("Alpha", "Beta"),
        )

        assertEquals(Answer.Back, answer)
        assertTrue(ANSI_SEQUENCE.replace(recorder.output(), "").contains("Search: a"))
    }

    @Test
    fun `grouped picker renders descriptions below names and keeps the focused entry on short screens`() {
        for (height in listOf(1, 4, 8, 12, 30)) {
            val recorder = TerminalRecorder(width = 80, height = height, supportsAnsiCursor = false)
            recorder.inputEvents += List(5) { KeyboardEvent("ArrowDown") } + listOf(KeyboardEvent(" "), KeyboardEvent("Enter"))
            val entries = (1..6).map { SelectList.Entry("Add-on $it", "Description for $it") }
            val selected = Prompts(Terminal(terminalInterface = recorder)).chooseMany(
                "Select add-ons", entries, allowBack = true, maxVisibleEntries = 10,
                filterTexts = entries.map { it.title }, lockedIndices = setOf(0),
                groups = listOf("Included in template", "Add-ons", "Add-ons", "Add-ons", "Translations", "Translations"),
            )!!.requireValue()

            assertEquals(listOf("Add-on 1", "Add-on 6"), selected)
            val frame = ANSI_SEQUENCE.replace(recorder.output().substringAfterLast(CLEAR_FROM_ORIGIN)
                .substringBefore(EXIT_ALTERNATE_SCREEN), "")
            assertTrue(frame.contains("❯ [x] Add-on 6"), "Cursor should be visible at height $height: $frame")
            assertTrue(frame.lines().size <= height, "Frame must fit height $height: $frame")
            if (height >= 8) {
                assertTrue(frame.contains("Translations"))
                val lines = frame.lines()
                val nameRow = lines.indexOfFirst { "Add-on 6" in it }
                assertEquals("Description for 6", lines[nameRow + 1].trim())
                assertTrue(frame.contains("enter"))
                assertTrue(lines.first().startsWith("Select add-ons"))
                assertTrue(lines.first().trimEnd().endsWith("2 selected"), frame)
                assertEquals("Search: / to search", lines[1].trim())
            }
        }
    }

    @Test
    fun `search clears with ctrl u and restores groups without losing selected add-ons`() {
        for (ansiCursor in listOf(false, true)) {
            val recorder = TerminalRecorder(width = 100, height = 20, supportsAnsiCursor = ansiCursor)
            recorder.inputEvents += listOf(
                KeyboardEvent("/"), KeyboardEvent("q"), KeyboardEvent("Enter"), KeyboardEvent(" "),
                KeyboardEvent("/"), KeyboardEvent("u", ctrl = true), KeyboardEvent("Enter"), KeyboardEvent("Enter"),
            )
            val selected = Prompts(Terminal(terminalInterface = recorder)).chooseMany(
                "Select add-ons",
                listOf(SelectList.Entry("Quartz", "Schedule background jobs"), SelectList.Entry("German", "German translation")),
                filterTexts = listOf("Quartz scheduling Maintenance Haulmont", "German Localization Haulmont"),
                groups = listOf("Add-ons", "Translations"),
            )!!.requireValue()

            assertEquals(listOf("Quartz"), selected)
            val output = ANSI_SEQUENCE.replace(recorder.output(), "")
            assertTrue(output.contains("Schedule background jobs"))
            assertTrue(output.contains("German translation"))
            assertTrue(output.contains("Translations"))
            assertTrue(output.contains("ctrl+u"))
            assertFalse(output.contains("No matches."))
            assertFalse(output.contains(" shown"))
            assertFalse(output.contains("(editing)"))
            if (ansiCursor) assertTrue(output.contains("1 selected  •  Search: / to search"), output)
        }
    }

    @Test
    fun `searchable numbered fallback filters and toggles visible entries`() = withStdin("/second\n1\n\n") {
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.chooseMany(
            question = "Select add-ons",
            entries = listOf("First", "Second").map { SelectList.Entry(it, "$it description") },
            allowBack = true,
            filterTexts = listOf("First add-on", "Second add-on"),
            groups = listOf("Included in template", "Add-ons"),
            lockedIndices = setOf(0),
        )!!.requireValue()

        assertEquals(listOf("First", "Second"), selected)
        assertTrue(recorder.output().contains("/query filters"))
        val filtered = ANSI_SEQUENCE.replace(recorder.output().substringAfterLast("Select add-ons"), "")
        assertTrue(filtered.contains("Add-ons"))
        assertTrue(filtered.contains("Second description"))
        assertFalse(filtered.contains("Included in template"))
        assertEquals("•  2 selected", filtered.lineSequence().first().trim())
        assertEquals("Search: second", filtered.lines()[1].trim())
    }

    @Test
    fun `searchable numbered fallback confirms defaults on EOF`() = withEmptyStdin {
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val selected = prompts.chooseMany(
            question = "Select add-ons",
            entries = listOf(SelectList.Entry("Required", selected = true)),
            filterTexts = listOf("Required"),
        )!!.requireValue()

        assertEquals(listOf("Required"), selected)
        assertTrue(prompts.isInputExhausted)
        assertTrue(recorder.output().contains("No more input"))
    }

    @Test
    fun `IDE console selector returns to previous step on escape`() {
        val recorder = TerminalRecorder(
            width = 120,
            height = 40,
            supportsAnsiCursor = true,
        )
        recorder.inputEvents += KeyboardEvent("Escape")
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val answer = prompts.choose(
            question = "Select project template",
            items = listOf("application", "addon"),
            title = { it },
            allowBack = true,
        )

        assertEquals(Answer.Back, answer)
        assertEquals(1, recorder.output().split("Select project template").size - 1)
    }

    @Test
    fun `full screen selector quits cleanly on q`() {
        val recorder = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = false)
        recorder.inputEvents += KeyboardEvent("q")
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val result = assertThrows(CliktError::class.java) {
            prompts.choose("Select theme", listOf("aura", "lumo"), { it })
        }

        assertEquals(0, result.statusCode)
        assertTrue(recorder.output().contains("q"))
        assertTrue(recorder.output().contains("quit"))
    }

    @Test
    fun `IDE console selector quits cleanly on q`() {
        val recorder = TerminalRecorder(width = 120, height = 40, supportsAnsiCursor = true)
        recorder.inputEvents += KeyboardEvent("Q")
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val result = assertThrows(CliktError::class.java) {
            prompts.choose("Select theme", listOf("aura", "lumo"), { it })
        }

        assertEquals(0, result.statusCode)
    }

    @Test
    fun `raw typed prompt quits cleanly on English or Russian ctrl q`() {
        for (key in listOf("q", "Q", "й", "Й")) {
            val recorder = TerminalRecorder(width = 120, height = 40)
            recorder.inputEvents += listOf(KeyboardEvent(key, ctrl = true), KeyboardEvent("Enter"))
            val prompts = Prompts(Terminal(terminalInterface = recorder))

            val result = assertThrows(CliktError::class.java) {
                prompts.ask("Enter project name", "untitled")
            }

            assertEquals(0, result.statusCode)
            assertTrue(recorder.output().contains("ctrl+q"))
            assertTrue(recorder.output().contains("quit"))
        }
    }

    @Test
    fun `raw typed prompt accepts plain English and Russian q as input`() {
        val recorder = TerminalRecorder(width = 120, height = 40)
        recorder.inputEvents += "qQйЙ".map { KeyboardEvent(it.toString()) } + KeyboardEvent("Enter")
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        assertEquals("qQйЙ", prompts.ask("Enter project name", "untitled").requireValue())
    }

    @Test
    fun `typed prompt redraws its value and history after idle resize`() {
        val recorder = ResizingTerminalInterface(
            initialSize = Size(100, 30),
            sizesAfterInput = emptyList(),
            sizesOnTimeout = listOf(Size(50, 5), Size(100, 30)),
            inputEventsBeforeTimeout = 4,
        )
        recorder.inputEvents += "demo".map { KeyboardEvent(it.toString()) }
        recorder.inputEvents += listOf(KeyboardEvent("Backspace"), KeyboardEvent("q"), KeyboardEvent("Enter"))
        val prompts = Prompts(Terminal(terminalInterface = recorder)) {
            WizardUiState(listOf(WizardChoice("Jmix version", "3.0.1")), WizardStage.GENERAL)
        }

        prompts.useAlternateScreen {
            assertEquals("demq", prompts.ask("Enter project name", allowBack = true).requireValue())
        }

        val frames = recorder.output().split(CLEAR_FROM_ORIGIN).drop(1)
        assertEquals(3, frames.size, "Initial frame and both idle resizes must be drawn")
        frames.drop(1).forEach { frame ->
            val plain = ANSI_SEQUENCE.replace(frame, "")
            assertTrue(plain.contains("Enter project name"), plain)
            assertTrue(plain.contains("demo"), plain)
            assertTrue(plain.contains("ctrl+q"), plain)
            assertTrue(plain.contains("1/5"), plain)
        }
        assertTrue(frames.last().contains("3.0.1"), "Expanded frame must restore completed choices")
    }

    @Test
    fun `line input fallback accepts plain English and Russian q as input`() = withStdin("qQйЙ\n") {
        val prompts = Prompts(Terminal(terminalInterface = TerminalRecorder(inputInteractive = false)))

        assertEquals("qQйЙ", prompts.ask("Enter project name", "untitled").requireValue())
    }

    @Test
    fun `prompts fall back to defaults once stdin is exhausted`() = withEmptyStdin {
        val recorder = TerminalRecorder(inputInteractive = false)
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val name = prompts.ask("Enter project name", default = "untitled").requireValue()
        val theme = prompts.choose("Select theme", listOf("aura", "lumo"), { it }).requireValue()
        val git = prompts.askYesNo("Create Git repository?", default = true).requireValue()

        assertEquals("untitled", name)
        assertEquals("aura", theme)
        assertTrue(git)
        assertTrue(prompts.isInputExhausted)
        val output = recorder.output()
        assertEquals(1, output.split("No more input").size - 1)
        // Prompts after the EOF resolve silently instead of rendering.
        assertFalse(output.contains("Select theme"))
        assertFalse(output.contains("Create Git repository?"))
    }

    @Test
    fun `exhausted prompt without a valid default fails with the validation message`() = withEmptyStdin {
        val prompts = Prompts(Terminal(terminalInterface = TerminalRecorder(inputInteractive = false)))

        val error = assertThrows(CliktError::class.java) {
            prompts.ask("Enter project id", default = null) {
                if (it.isEmpty()) "Project id is required." else null
            }
        }

        assertTrue(error.message!!.contains("Project id is required."))
        assertTrue(error.message!!.contains("--non-interactive"))
    }

    @Test
    fun `tab completes the typed path prefix`() {
        val recorder = ResizingTerminalInterface(initialSize = Size(100, 40), sizesAfterInput = emptyList())
        recorder.inputEvents += listOf(KeyboardEvent("d"), KeyboardEvent("Tab"), KeyboardEvent("Enter"))
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val answer = prompts.ask(
            "Enter project location", default = "unused", allowBack = true,
            complete = { input -> PathCompletion("${input}ocs/", emptyList()) },
        ).requireValue()

        assertEquals("docs/", answer)
        assertTrue(recorder.output().contains("tab"), "bar should advertise tab completion")
    }

    @Test
    fun `tab with no progress lists candidates and keeps the input`() {
        val recorder = ResizingTerminalInterface(initialSize = Size(100, 40), sizesAfterInput = emptyList())
        recorder.inputEvents += listOf(
            KeyboardEvent("I"), KeyboardEvent("d"),
            KeyboardEvent("Tab"),
            KeyboardEvent("Enter"),
        )
        val prompts = Prompts(Terminal(terminalInterface = recorder))

        val answer = prompts.ask(
            "Enter project location", default = "unused", allowBack = true,
            complete = { input -> PathCompletion(input, listOf("IdeaProjects", "IdeaSettings")) },
        ).requireValue()

        assertEquals("Id", answer)
        assertTrue(recorder.output().contains("IdeaProjects  IdeaSettings"))
    }

    private fun withEmptyStdin(block: () -> Unit) = withStdin("", block)

    private fun withStdin(input: String, block: () -> Unit) {
        val original = System.`in`
        System.setIn(ByteArrayInputStream(input.toByteArray()))
        try {
            block()
        } finally {
            System.setIn(original)
        }
    }

    private class ResizingTerminalInterface(
        initialSize: Size,
        private val sizesAfterInput: List<Size>,
        private val sizesOnTimeout: List<Size> = emptyList(),
        private val inputEventsBeforeTimeout: Int = 0,
        private val delegate: TerminalRecorder = TerminalRecorder(
            width = initialSize.width,
            height = initialSize.height,
            supportsAnsiCursor = false,
        ),
    ) : TerminalInterface by delegate {
        var inputEvents: MutableList<InputEvent>
            get() = delegate.inputEvents
            set(value) {
                delegate.inputEvents = value
            }

        private var currentSize = initialSize
        private var inputIndex = 0
        private var timeoutIndex = 0

        fun output(): String = delegate.output()

        override fun getTerminalSize(): Size = currentSize

        // Matches Mordant's macOS JNA backend, where automatic polling is
        // disabled because terminal size is obtained through `stty`.
        override fun shouldAutoUpdateSize(): Boolean = false

        override fun readInputEvent(timeout: TimeMark, mouseTracking: MouseTracking): InputEvent? {
            if (inputIndex >= inputEventsBeforeTimeout) {
                sizesOnTimeout.getOrNull(timeoutIndex++)?.let {
                    currentSize = it
                    throw TimeoutException()
                }
            }
            sizesAfterInput.getOrNull(inputIndex++)?.let { currentSize = it }
            return delegate.readInputEvent(timeout, mouseTracking)
        }
    }

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    private companion object {
        const val ENTER_ALTERNATE_SCREEN = "\u001B[?1049h"
        const val EXIT_ALTERNATE_SCREEN = "\u001B[?1049l"
        const val CLEAR_FROM_ORIGIN = "\u001B[1;1H\u001B[2J"
        val ANSI_SEQUENCE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val PURPLE_QUESTION = Regex("\u001B\\[[0-9;]*95[0-9;]*mSelect project template")
    }
}

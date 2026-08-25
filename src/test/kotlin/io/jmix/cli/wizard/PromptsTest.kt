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
        assertEquals(listOf("en — English", "de — German"), updated.pickedTitles())
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

    private fun withEmptyStdin(block: () -> Unit) {
        val original = System.`in`
        System.setIn(ByteArrayInputStream(ByteArray(0)))
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
            sizesOnTimeout.getOrNull(timeoutIndex++)?.let {
                currentSize = it
                throw TimeoutException()
            }
            sizesAfterInput.getOrNull(inputIndex++)?.let { currentSize = it }
            return delegate.readInputEvent(timeout, mouseTracking)
        }
    }

    private companion object {
        const val ENTER_ALTERNATE_SCREEN = "\u001B[?1049h"
        const val EXIT_ALTERNATE_SCREEN = "\u001B[?1049l"
        const val CLEAR_FROM_ORIGIN = "\u001B[1;1H\u001B[2J"
        val ANSI_SEQUENCE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val PURPLE_QUESTION = Regex("\u001B\\[[0-9;]*95[0-9;]*mSelect project template")
    }
}

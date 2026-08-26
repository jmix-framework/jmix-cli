package io.jmix.cli.wizard

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightMagenta
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.SelectList
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.Viewport
import kotlin.time.Duration.Companion.milliseconds

/** A prompt outcome: an answered value, or a request to return to the previous step. */
sealed interface Answer<out T> {
    data class Value<T>(val value: T) : Answer<T>
    data object Back : Answer<Nothing>
}

/** Unwraps a prompt that was asked without back navigation. */
fun <T> Answer<T>.requireValue(): T = (this as Answer.Value).value

/** A completed wizard choice that can be reconstructed during a full repaint. */
data class WizardChoice(
    val label: String,
    val value: String,
)

/** Immutable wizard data required by the interactive renderer. */
data class WizardUiState(
    val choices: List<WizardChoice> = emptyList(),
)

internal data class SelectionWindow(
    val firstIndex: Int,
    val lastIndexExclusive: Int,
) {
    val size: Int
        get() = lastIndexExclusive - firstIndex
}

/** Keeps [cursorIndex] inside a stable, bounded window of selectable entries. */
internal fun selectionWindow(
    entryCount: Int,
    cursorIndex: Int,
    previousFirstIndex: Int,
    maxVisibleEntries: Int?,
): SelectionWindow {
    require(entryCount > 0) { "Selection list must contain at least one entry" }
    require(cursorIndex in 0 until entryCount) { "Cursor index must point to an entry" }
    require(maxVisibleEntries == null || maxVisibleEntries > 0) { "Visible entry count must be positive" }

    val visibleCount = minOf(maxVisibleEntries ?: entryCount, entryCount)
    val maxFirstIndex = entryCount - visibleCount
    val currentFirstIndex = previousFirstIndex.coerceIn(0, maxFirstIndex)
    val firstIndex = when {
        cursorIndex < currentFirstIndex -> cursorIndex
        cursorIndex >= currentFirstIndex + visibleCount -> cursorIndex - visibleCount + 1
        else -> currentFirstIndex
    }.coerceIn(0, maxFirstIndex)

    return SelectionWindow(firstIndex, firstIndex + visibleCount)
}

/** Immutable state for one interactive selection prompt. */
internal data class SelectionUiState(
    val question: String,
    val entries: List<SelectList.Entry>,
    val multi: Boolean,
    val allowBack: Boolean,
    val maxVisibleEntries: Int?,
    val cursorIndex: Int = 0,
    val firstVisibleIndex: Int = 0,
    val selectedIndices: Set<Int> = entries.indices.filterTo(linkedSetOf()) { entries[it].selected },
) {
    init {
        require(entries.isNotEmpty()) { "Selection list must contain at least one entry" }
        require(cursorIndex in entries.indices) { "Cursor index must point to an entry" }
    }

    fun move(offset: Int): SelectionUiState {
        val next = (cursorIndex + offset).coerceIn(entries.indices)
        return if (next == cursorIndex) this else copy(cursorIndex = next)
    }

    fun toggle(): SelectionUiState {
        if (!multi) return this
        val next = selectedIndices.toMutableSet()
        if (!next.add(cursorIndex)) next.remove(cursorIndex)
        return copy(selectedIndices = next)
    }

    fun pickedTitles(): List<String> = if (multi) {
        entries.filterIndexed { index, _ -> index in selectedIndices }.map { it.title }
    } else {
        listOf(entries[cursorIndex].title)
    }
}

/**
 * Interactive prompt helpers. Selection prompts render an arrow-key list with
 * an always-visible key bar (↑/↓ move, space toggles, enter confirms, esc goes
 * back), falling back to numbered lists where raw mode is unavailable (for
 * example, pipes). Typed prompts accept `<` to go back.
 */
class Prompts(
    private val terminal: Terminal,
    private val wizardUiState: () -> WizardUiState = { WizardUiState() },
) {
    private val questionStyle = TextStyle(brightMagenta, bold = true)

    /**
     * True once piped stdin hit EOF. From that point every prompt resolves to
     * its default without rendering, so a half-answered wizard finishes with
     * deterministic defaults instead of aborting after partial input.
     */
    var isInputExhausted = false
        private set

    /** Asks until [validate] returns null; empty input takes [default]. */
    fun ask(
        question: String,
        default: String? = null,
        allowBack: Boolean = false,
        complete: ((String) -> PathCompletion)? = null,
        validate: (String) -> String? = { null },
    ): Answer<String> {
        if (isInputExhausted) return defaultAnswer(default, validate)
        val hintPlain = default?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        val prompt = questionStyle(question) +
            (default?.takeIf { it.isNotEmpty() }?.let { gray(" ($it)") } ?: "") +
            questionStyle(":") + " "
        val promptWidth = question.length + hintPlain.length + 2
        while (true) {
            val line = when (val input = readLineWithBar(prompt, promptWidth, allowBack, complete)) {
                is LineInput.Back -> return Answer.Back
                is LineInput.Text -> input.text
            }
            val trimmed = line.trim()
            // Cooked-mode fallback keeps `<` for back navigation.
            if (allowBack && trimmed == BACK_INPUT) return Answer.Back
            val value = trimmed.ifEmpty { default ?: "" }
            val error = validate(value)
            if (error == null) return Answer.Value(value)
            if (isInputExhausted) throw CliktError(noInputError(error))
            terminal.println(brightRed(error))
        }
    }

    /** Resolves an exhausted-input prompt to its default, or fails when the default is invalid. */
    private fun defaultAnswer(default: String?, validate: (String) -> String?): Answer<String> {
        val value = default ?: ""
        validate(value)?.let { throw CliktError(noInputError(it)) }
        return Answer.Value(value)
    }

    private fun noInputError(error: String) =
        "$error No input is available to correct it — pass the value as an option or use --non-interactive."

    fun askYesNo(question: String, default: Boolean, allowBack: Boolean = false): Answer<Boolean> {
        if (isInputExhausted) return Answer.Value(default)
        val options = if (default) listOf(YES, NO) else listOf(NO, YES)
        when (val result = runSelect(question, options.map { SelectList.Entry(it) }, multi = false, allowBack)) {
            is SelectResult.Picked -> return Answer.Value(result.titles.single() == YES)
            SelectResult.Back -> return Answer.Back
            SelectResult.Unsupported -> {}
        }

        val hintPlain = if (default) " (Y/n)" else " (y/N)"
        val prompt = questionStyle(question) + gray(hintPlain) + questionStyle(":") + " "
        val promptWidth = question.length + hintPlain.length + 2
        while (true) {
            val line = when (val input = readLineWithBar(prompt, promptWidth, allowBack)) {
                is LineInput.Back -> return Answer.Back
                is LineInput.Text -> input.text
            }
            when (line.trim().lowercase()) {
                "" -> return Answer.Value(default)
                "y", "yes" -> return Answer.Value(true)
                "n", "no" -> return Answer.Value(false)
                BACK_INPUT -> if (allowBack) return Answer.Back else terminal.println(brightRed("Please answer y or n"))
                else -> terminal.println(brightRed("Please answer y or n"))
            }
        }
    }

    /** Single-choice selection; arrow-key list with numbered fallback. */
    fun <T> choose(
        question: String,
        items: List<T>,
        title: (T) -> String,
        description: (T) -> String? = { null },
        defaultIndex: Int = 0,
        allowBack: Boolean = false,
    ): Answer<T> {
        if (items.size == 1) return Answer.Value(items.first())
        if (isInputExhausted) return Answer.Value(items[defaultIndex])

        val titles = items.map(title)
        val descriptions = items.map { description(it)?.takeIf(String::isNotBlank) }
        val titleColumnWidth = if (descriptions.any { it != null }) titles.maxOf(String::length) else 0
        // Single compact row per entry, with every description starting in
        // the same column: "title       — description".
        val labels = titles.mapIndexed { index, itemTitle ->
            descriptions[index]?.let { itemDescription ->
                itemTitle.padEnd(titleColumnWidth) + gray(" — $itemDescription")
            } ?: itemTitle
        }
        when (val result = runSelect(question, labels.map { SelectList.Entry(it) }, multi = false, allowBack)) {
            is SelectResult.Picked ->
                return Answer.Value(items[labels.indexOf(result.titles.single()).coerceAtLeast(0)])
            SelectResult.Back -> return Answer.Back
            SelectResult.Unsupported -> {}
        }

        terminal.println(questionStyle(question))
        val numberWidth = items.size.toString().length
        items.forEachIndexed { i, _ ->
            val marker = if (i == defaultIndex) cyan("*") else " "
            val number = (i + 1).toString().padStart(numberWidth)
            terminal.println("  $marker $number) ${labels[i]}")
        }
        val answer = ask("Enter number", (defaultIndex + 1).toString(), allowBack) { input ->
            val n = input.toIntOrNull()
            if (n == null || n !in 1..items.size) "Enter a number between 1 and ${items.size}" else null
        }
        return when (answer) {
            is Answer.Back -> Answer.Back
            is Answer.Value -> Answer.Value(items[answer.value.toInt() - 1])
        }
    }

    /**
     * Multi-choice selection (space toggles). Returns chosen titles in list
     * order, or null when the arrow-key widget is unavailable — the caller
     * falls back to a typed prompt.
     */
    fun chooseMany(
        question: String,
        entries: List<SelectList.Entry>,
        allowBack: Boolean = false,
        maxVisibleEntries: Int? = null,
    ): Answer<List<String>>? = when (val result = runSelect(
        question = question,
        entries = entries,
        multi = true,
        allowBack = allowBack,
        maxVisibleEntries = maxVisibleEntries,
    )) {
        is SelectResult.Picked -> Answer.Value(result.titles)
        SelectResult.Back -> Answer.Back
        SelectResult.Unsupported -> null
    }

    // --- Custom select loop ----------------------------------------------------

    private sealed interface SelectResult {
        data class Picked(val titles: List<String>) : SelectResult
        data object Back : SelectResult
        data object Unsupported : SelectResult
    }

    /**
     * Arrow-key selection built on Mordant raw mode, with a custom
     * always-visible key bar that includes back navigation.
     */
    private fun runSelect(
        question: String,
        entries: List<SelectList.Entry>,
        multi: Boolean,
        allowBack: Boolean,
        maxVisibleEntries: Int? = null,
    ): SelectResult {
        val rawMode = terminal.enterRawModeOrNull() ?: return SelectResult.Unsupported
        val initialState = SelectionUiState(
            question = question,
            entries = entries,
            multi = multi,
            allowBack = allowBack,
            maxVisibleEntries = maxVisibleEntries,
        )

        // Mordant detects the IntelliJ Run console as interactive, but its
        // animation renderer can only return to the start of the current line
        // there. A multiline widget would therefore be appended on every key.
        val animationUsesCarriageReturnsOnly = terminal.terminalInfo.supportsAnsiCursor
        if (animationUsesCarriageReturnsOnly) {
            return rawMode.use { scope ->
                runCarriageReturnSelect(initialState, scope)
            }
        }

        return rawMode.use { scope -> runFullScreenSelect(initialState, scope) }
    }

    /**
     * Selection UI for ANSI terminals. An alternate screen gives every frame
     * a stable origin even when a short terminal clips the previous frame.
     */
    private fun runFullScreenSelect(
        initialState: SelectionUiState,
        rawMode: RawModeScope,
    ): SelectResult {
        terminal.rawPrint(ENTER_ALTERNATE_SCREEN)
        try {
            terminal.cursor.hide(showOnExit = false)
            var state = renderSelection(initialState)
            var renderedWidth = terminal.size.width
            var renderedHeight = terminal.size.height
            while (true) {
                val key = runCatching { rawMode.readKeyOrNull(RESIZE_POLL_INTERVAL) }.getOrElse { abort() }
                if (key == null) {
                    terminal.updateSize()
                    if (terminal.size.width != renderedWidth || terminal.size.height != renderedHeight) {
                        state = renderSelection(state)
                        renderedWidth = terminal.size.width
                        renderedHeight = terminal.size.height
                    }
                    continue
                }
                when {
                    key.isCtrlC -> abort()
                    key.key == "Escape" && state.allowBack -> return SelectResult.Back
                    key.key == "ArrowUp" -> {
                        state = renderSelection(state.move(-1))
                        renderedWidth = terminal.size.width
                        renderedHeight = terminal.size.height
                    }
                    key.key == "ArrowDown" -> {
                        state = renderSelection(state.move(1))
                        renderedWidth = terminal.size.width
                        renderedHeight = terminal.size.height
                    }
                    (key.key == " " || key.key == "Spacebar") && state.multi -> {
                        state = renderSelection(state.toggle())
                        renderedWidth = terminal.size.width
                        renderedHeight = terminal.size.height
                    }
                    key.key == "Enter" -> return SelectResult.Picked(state.pickedTitles())
                }
            }
        } finally {
            terminal.cursor.show()
            terminal.rawPrint(EXIT_ALTERNATE_SCREEN)
        }
    }

    /** Rebuilds the complete frame from immutable wizard and selection data. */
    private fun renderSelection(state: SelectionUiState): SelectionUiState {
        // Mordant's macOS JNA backend disables automatic polling because it
        // obtains terminal dimensions through `stty`.
        terminal.updateSize()
        val terminalWidth = terminal.size.width.coerceAtLeast(1)
        val terminalHeight = terminal.size.height.coerceAtLeast(1)

        val navigation = when {
            terminalHeight >= 4 -> renderBar(selectBarParts(state.multi, state.allowBack))
            terminalHeight >= 3 -> renderBarLine(selectBarParts(state.multi, state.allowBack))
            else -> null
        }
        val navigationRows = navigation?.count { it == '\n' }?.plus(1) ?: 0
        val showTitle = terminalHeight - navigationRows >= 2
        val fixedSelectionRows = navigationRows + if (showTitle) 1 else 0
        val requestedEntries = minOf(state.maxVisibleEntries ?: state.entries.size, state.entries.size)
        val visibleEntryCount = minOf(
            requestedEntries,
            (terminalHeight - fixedSelectionRows).coerceAtLeast(1),
        )
        val window = selectionWindow(
            entryCount = state.entries.size,
            cursorIndex = state.cursorIndex,
            previousFirstIndex = state.firstVisibleIndex,
            maxVisibleEntries = visibleEntryCount,
        )
        val positionedState = state.copy(firstVisibleIndex = window.firstIndex)
        val position = if (window.size < state.entries.size) {
            gray("  ${window.firstIndex + 1}\u2013${window.lastIndexExclusive} of ${state.entries.size}")
        } else {
            ""
        }
        val selectionRows = fixedSelectionRows + window.size
        val historyCapacity = (terminalHeight - selectionRows - 1).coerceAtLeast(0)
        val choices = wizardUiState().choices.takeLast(historyCapacity)
        val lines = buildList {
            choices.forEach { add(renderChoice(it)) }
            if (choices.isNotEmpty()) add("")
            if (showTitle) add(questionStyle(state.question) + position)
            (window.firstIndex until window.lastIndexExclusive).forEach { index ->
                add(renderEntry(positionedState, index))
            }
            navigation?.let { addAll(it.lines()) }
        }
        val viewport = Viewport(
            // PRE keeps every option on exactly one terminal row. Viewport
            // crops long labels horizontally instead of letting them wrap and
            // push the navigation bar below a short terminal.
            content = Text(lines.joinToString("\n"), whitespace = Whitespace.PRE),
            width = (terminalWidth - 1).coerceAtLeast(1),
            height = lines.size.coerceAtMost(terminalHeight),
        )

        terminal.cursor.move {
            setPosition(0, 0)
            clearScreen()
        }
        terminal.print(viewport)
        return positionedState
    }

    private fun renderChoice(choice: WizardChoice): String =
        brightGreen("✓ ") + choice.label + ": " + cyan(choice.value)

    private fun renderEntry(state: SelectionUiState, index: Int): String {
        val atCursor = index == state.cursorIndex
        val selected = index in state.selectedIndices
        val cursor = if (atCursor) cyan("❯") else " "
        val title = when {
            !state.multi && atCursor -> brightGreen(state.entries[index].title)
            state.multi && selected -> brightGreen(state.entries[index].title)
            else -> state.entries[index].title
        }
        return if (state.multi) {
            val marker = if (selected) brightGreen("[x]") else gray("[ ]")
            "$cursor $marker $title"
        } else {
            "$cursor $title"
        }
    }

    /**
     * Compact selector for IDE consoles that support raw keys and carriage
     * returns, but not multiline cursor movement.
     */
    private fun runCarriageReturnSelect(
        initialState: SelectionUiState,
        rawMode: RawModeScope,
    ): SelectResult {
        var state = initialState
        val lineWidth = (terminal.size.width - 1).coerceAtLeast(1)
        val clearLine = " ".repeat(lineWidth)

        terminal.println(questionStyle(state.question))
        terminal.println(renderBar(selectBarParts(state.multi, state.allowBack)))

        fun redraw() {
            val marker = when {
                !state.multi -> cyan("❯")
                state.cursorIndex in state.selectedIndices -> cyan("❯ [x]")
                else -> "❯ [ ]"
            }
            val position = if (state.entries.size > 1) {
                gray(" ${state.cursorIndex + 1}/${state.entries.size}")
            } else {
                ""
            }
            val styledLine = "$marker ${state.entries[state.cursorIndex].title}$position"
            val plainLine = ANSI_SEQUENCE.replace(styledLine, "")
            val visibleLine = if (plainLine.length <= lineWidth) {
                styledLine
            } else {
                plainLine.take((lineWidth - 1).coerceAtLeast(0)) + "…"
            }

            terminal.rawPrint("\r$clearLine\r")
            terminal.print(visibleLine)
        }

        try {
            redraw()
            while (true) {
                val key = runCatching { rawMode.readKey() }.getOrElse { abort() }
                when {
                    key.isCtrlC -> abort()
                    key.key == "Escape" && state.allowBack -> return SelectResult.Back
                    key.key == "ArrowUp" -> {
                        val next = state.move(-1)
                        if (next === state) continue
                        state = next
                    }
                    key.key == "ArrowDown" -> {
                        val next = state.move(1)
                        if (next === state) continue
                        state = next
                    }
                    (key.key == " " || key.key == "Spacebar") && state.multi -> state = state.toggle()
                    key.key == "Enter" -> return SelectResult.Picked(state.pickedTitles())
                    else -> continue
                }
                redraw()
            }
        } finally {
            terminal.rawPrint("\r$clearLine\r")
        }
    }

    private fun selectBarParts(multi: Boolean, allowBack: Boolean): List<Pair<String, String>> =
        buildList {
            add("↑" to "up")
            add("↓" to "down")
            if (multi) add("space" to "toggle")
            add("enter" to if (multi) "confirm" else "select")
            if (allowBack) add("esc" to "back")
        }

    private fun typedBarParts(
        allowBack: Boolean,
        rawMode: Boolean,
        hasCompletion: Boolean = false,
    ): List<Pair<String, String>> =
        buildList {
            if (hasCompletion) add("tab" to "complete")
            add("enter" to "confirm")
            // Without raw mode the terminal only delivers whole lines, so Esc
            // cannot be detected — fall back to typing `<`.
            if (allowBack) add((if (rawMode) "esc" else "<") to "back")
        }

    private sealed interface LineInput {
        data class Text(val text: String) : LineInput
        data object Back : LineInput
    }

    /**
     * Reads a line under the prompt+bar block, erasing the block afterwards.
     * In raw mode Esc returns [LineInput.Back]; otherwise the caller accepts
     * `<` as the back sentinel.
     */
    private fun readLineWithBar(
        prompt: String,
        promptWidth: Int,
        allowBack: Boolean,
        complete: ((String) -> PathCompletion)? = null,
    ): LineInput {
        val rawMode = if (allowBack || complete != null) terminal.enterRawModeOrNull() else null
        if (rawMode == null) {
            printPromptWithBar(prompt, promptWidth, typedBarParts(allowBack, rawMode = false))
            val line = readlnOrNull()
            if (line == null) {
                // Ctrl+D on a real terminal is a deliberate abort, like Ctrl+C.
                if (terminal.terminalInfo.inputInteractive) abort()
                // EOF on piped stdin. Fall back to defaults for this and all
                // remaining prompts instead of aborting a half-finished wizard.
                isInputExhausted = true
                terminal.println()
                terminal.println(gray("No more input — using defaults for the remaining steps."))
                return LineInput.Text("")
            }
            clearPromptWithBar(cursorOnPrompt = false)
            return LineInput.Text(line)
        }
        val barParts = typedBarParts(allowBack, rawMode = true, hasCompletion = complete != null)
        printPromptWithBar(prompt, promptWidth, barParts)
        val buffer = StringBuilder()
        rawMode.use { scope ->
            while (true) {
                val key = runCatching { scope.readKey() }.getOrElse { abort() }
                when {
                    key.isCtrlC -> abort()
                    key.key == "Escape" && allowBack -> {
                        clearPromptWithBar(cursorOnPrompt = true)
                        return LineInput.Back
                    }
                    key.key == "Enter" -> {
                        clearPromptWithBar(cursorOnPrompt = true)
                        return LineInput.Text(buffer.toString())
                    }
                    key.key == "Backspace" -> if (buffer.isNotEmpty()) {
                        buffer.deleteCharAt(buffer.length - 1)
                        terminal.print("\b \b")
                    }
                    key.key == "Tab" && complete != null ->
                        applyCompletion(complete(buffer.toString()), buffer, prompt, promptWidth, barParts)
                    // Printable single characters; ignore other control keys.
                    key.key.length == 1 && !key.ctrl && !key.alt -> {
                        buffer.append(key.key)
                        terminal.print(key.key)
                    }
                }
            }
        }
    }

    /**
     * Applies one Tab press: extends the buffer with the completed text, or —
     * when the input is already at the longest common prefix — lists the
     * candidates above a freshly repainted prompt.
     */
    private fun applyCompletion(
        completion: PathCompletion,
        buffer: StringBuilder,
        prompt: String,
        promptWidth: Int,
        barParts: List<Pair<String, String>>,
    ) {
        val current = buffer.toString()
        if (completion.text != current) {
            if (completion.text.startsWith(current)) {
                val suffix = completion.text.removePrefix(current)
                buffer.append(suffix)
                terminal.print(suffix)
            } else {
                // Case-insensitive completion may rewrite the typed segment
                // with the on-disk casing — replace the whole line.
                repeat(current.length) { terminal.print("\b \b") }
                buffer.setLength(0)
                buffer.append(completion.text)
                terminal.print(completion.text)
            }
            return
        }
        if (completion.candidates.isEmpty()) return
        clearPromptWithBar(cursorOnPrompt = true)
        val shown = completion.candidates.take(COMPLETION_CANDIDATES_SHOWN)
        val ellipsis = if (completion.candidates.size > shown.size) "  …" else ""
        terminal.println(gray(shown.joinToString("  ") + ellipsis))
        printPromptWithBar(prompt, promptWidth, barParts)
        terminal.print(current)
    }

    /** Key hints with highlighted keys, so the bar stands out from content. */
    private fun renderBarLine(parts: List<Pair<String, String>>): String {
        val key = TextStyle(cyan, bold = true)
        return parts.joinToString(dim(" • ")) { "${key(it.first)} ${dim(it.second)}" }
    }

    /**
     * The navigation bar: a dim rule separator over the key hints, so the bar
     * stands out from the content above it.
     */
    private fun renderBar(parts: List<Pair<String, String>>): String {
        val plainWidth = parts.sumOf { it.first.length + it.second.length + 1 } + (parts.size - 1) * 3
        val rule = dim(gray(RULE_CHAR.repeat(plainWidth)))
        return "$rule\n${renderBarLine(parts)}"
    }

    /**
     * Prints the prompt with the navigation bar below it, leaving the cursor
     * on the prompt line so input is typed in place.
     */
    private fun printPromptWithBar(prompt: String, promptWidth: Int, barParts: List<Pair<String, String>>) {
        terminal.print(prompt + "\n" + renderBar(barParts))
        runCatching { terminal.cursor.move { up(2); startOfLine(); right(promptWidth) } }
    }

    /** Erase prompt, rule, and bar so the step summary replaces the block. */
    private fun clearPromptWithBar(cursorOnPrompt: Boolean) {
        runCatching {
            terminal.cursor.move {
                startOfLine()
                if (cursorOnPrompt) {
                    clearLine()
                    down(1)
                    clearLine()
                    down(1)
                    clearLine()
                    up(2)
                } else {
                    // Cooked-mode Enter moves the cursor from the prompt to
                    // the rule line before readlnOrNull returns.
                    clearLine()
                    down(1)
                    clearLine()
                    up(2)
                    clearLine()
                }
            }
        }
    }

    private fun abort(): Nothing = throw CliktError("Aborted.")

    private companion object {
        const val BACK_INPUT = "<"
        const val COMPLETION_CANDIDATES_SHOWN = 8
        const val YES = "Yes"
        const val NO = "No"
        const val RULE_CHAR = "─"
        const val ENTER_ALTERNATE_SCREEN = "\u001B[?1049h"
        const val EXIT_ALTERNATE_SCREEN = "\u001B[?1049l"
        val ANSI_SEQUENCE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val RESIZE_POLL_INTERVAL = 100.milliseconds
    }
}

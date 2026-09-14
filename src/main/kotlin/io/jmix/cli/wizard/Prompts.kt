package io.jmix.cli.wizard

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.rendering.TextColors
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

/** Stable phases; individual questions can be resolved by flags or template defaults. */
enum class WizardStage(val title: String) {
    GENERAL("General"),
    LOCALIZATION("Localization"),
    ADDONS("Add-ons"),
    LOCATION("Location and setup"),
    GENERATION("Finishing up"),
}

/** Immutable wizard data required by the interactive renderer. */
data class WizardUiState(
    val choices: List<WizardChoice> = emptyList(),
    val stage: WizardStage? = null,
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
    maxRows: Int = Int.MAX_VALUE,
    rowCount: (index: Int, first: Boolean) -> Int = { _, _ -> 1 },
): SelectionWindow {
    require(entryCount > 0) { "Selection list must contain at least one entry" }
    require(cursorIndex in 0 until entryCount) { "Cursor index must point to an entry" }
    require(maxVisibleEntries == null || maxVisibleEntries > 0) { "Visible entry count must be positive" }
    require(maxRows > 0) { "Visible row count must be positive" }
    require(rowCount(cursorIndex, true) in 1..maxRows) { "The cursor entry must fit in the window" }

    val visibleCount = minOf(maxVisibleEntries ?: entryCount, entryCount)
    val maxFirstIndex = entryCount - visibleCount
    val currentFirstIndex = previousFirstIndex.coerceIn(0, maxFirstIndex)
    var firstIndex = when {
        cursorIndex < currentFirstIndex -> cursorIndex
        cursorIndex >= currentFirstIndex + visibleCount -> cursorIndex - visibleCount + 1
        else -> currentFirstIndex
    }.coerceIn(0, maxFirstIndex)

    fun endIndex(first: Int): Int {
        var rows = 0
        var end = first
        while (end < minOf(first + visibleCount, entryCount)) {
            rows += rowCount(end, end == first)
            if (rows > maxRows) break
            end++
        }
        return end
    }

    while (cursorIndex >= endIndex(firstIndex)) firstIndex++
    return SelectionWindow(firstIndex, endIndex(firstIndex))
}

/** Immutable state for one interactive selection prompt. */
internal data class SelectionUiState(
    val question: String,
    val entries: List<SelectList.Entry>,
    val multi: Boolean,
    val allowBack: Boolean,
    val maxVisibleEntries: Int?,
    val filterTexts: List<String>? = null,
    val lockedIndices: Set<Int> = emptySet(),
    val groups: List<String>? = null,
    val cursorIndex: Int = 0,
    val firstVisibleIndex: Int = 0,
    val selectedIndices: Set<Int> = entries.indices.filterTo(linkedSetOf()) { entries[it].selected } + lockedIndices,
    val filterQuery: String = "",
    val editingFilter: Boolean = false,
    val filterBeforeEdit: String = "",
    val values: List<String> = entries.map { it.title },
) {
    init {
        require(entries.isNotEmpty()) { "Selection list must contain at least one entry" }
        require(cursorIndex in entries.indices) { "Cursor index must point to an entry" }
        require(filterTexts == null || filterTexts.size == entries.size) {
            "Filter text count must match the selection entry count"
        }
        require(lockedIndices.all { it in entries.indices }) { "Locked index must point to an entry" }
        require(groups == null || groups.size == entries.size) { "Group count must match the selection entry count" }
        require(values.size == entries.size) { "Value count must match the selection entry count" }
    }

    val visibleIndices: List<Int>
        get() = if (filterTexts == null || filterQuery.isEmpty()) {
            entries.indices.toList()
        } else {
            val words = filterQuery.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            entries.indices.filter { index -> words.all { filterTexts[index].contains(it, ignoreCase = true) } }
        }

    fun move(offset: Int): SelectionUiState {
        val visible = visibleIndices
        if (visible.isEmpty()) return this
        val position = visible.indexOf(cursorIndex).coerceAtLeast(0)
        val next = visible[(position + offset).coerceIn(visible.indices)]
        return if (next == cursorIndex) this else copy(cursorIndex = next)
    }

    fun toggle(): SelectionUiState {
        if (!multi || cursorIndex in lockedIndices || cursorIndex !in visibleIndices) return this
        val next = selectedIndices.toMutableSet()
        if (!next.add(cursorIndex)) next.remove(cursorIndex)
        return copy(selectedIndices = next)
    }

    fun startFilterEdit(): SelectionUiState =
        copy(editingFilter = true, filterBeforeEdit = filterQuery)

    fun appendToFilter(text: String): SelectionUiState = withFilter(filterQuery + text)

    fun eraseFilterCharacter(): SelectionUiState =
        if (filterQuery.isEmpty()) this else withFilter(filterQuery.dropLast(1))

    fun finishFilterEdit(): SelectionUiState = copy(editingFilter = false)

    fun cancelFilterEdit(): SelectionUiState =
        withFilter(filterBeforeEdit).copy(editingFilter = false)

    fun setFilter(query: String): SelectionUiState = withFilter(query)

    private fun withFilter(query: String): SelectionUiState {
        val filtered = copy(filterQuery = query, firstVisibleIndex = 0)
        val visible = filtered.visibleIndices
        return if (visible.isEmpty() || cursorIndex in visible) filtered else filtered.copy(cursorIndex = visible.first())
    }

    fun pickedValues(): List<String> = if (multi) {
        values.filterIndexed { index, _ -> index in selectedIndices }
    } else {
        listOf(values[cursorIndex])
    }
}

/**
 * Interactive prompt helpers. Selection prompts render an arrow-key list with
 * an always-visible key bar (↑/↓ move, space toggles, enter confirms, esc goes
 * back, q quits), falling back to numbered lists where raw mode is unavailable
 * (for example, pipes). Raw typed prompts accept Esc to go back and Ctrl+Q to
 * quit; line-input fallbacks accept `<` to go back.
 */
class Prompts(
    private val terminal: Terminal,
    /**
     * Optional banner drawn above every frame, given the terminal width. It is
     * dropped whenever the frame would not leave room for the question itself.
     */
    private val header: (Int) -> List<String> = { emptyList() },
    // Trailing position keeps `Prompts(terminal) { state }` working.
    private val wizardUiState: () -> WizardUiState = { WizardUiState() },
) {
    private val questionStyle = TextStyle(brightMagenta, bold = true)
    private var lastPrintedStage: WizardStage? = null

    /** Prints a phase once in the transcript, including generation outside the held screen. */
    fun printProgress(compact: Boolean = !usesAlternateScreen) {
        val stage = wizardUiState().stage ?: return
        if (isInputExhausted || stage == lastPrintedStage) return
        if (compact) {
            terminal.println(cyan("Step ${stage.ordinal + 1}/${WizardStage.entries.size}: ${stage.title}"))
        } else {
            terminal.updateSize()
            progressLines(stage, terminal.size.width, 2).forEach(terminal::println)
        }
        lastPrintedStage = stage
    }

    private fun progressLines(stage: WizardStage?, width: Int, maxRows: Int): List<String> {
        if (stage == null || maxRows <= 0) return emptyList()
        val columns = (width - 1).coerceAtLeast(1)
        val count = "${stage.ordinal + 1}/${WizardStage.entries.size}"
        val titleWidth = (columns - count.length - 1).coerceAtLeast(0)
        val title = if (stage.title.length <= titleWidth) stage.title else {
            stage.title.take((titleWidth - 1).coerceAtLeast(0)) + if (titleWidth > 0) "…" else ""
        }
        val heading = title.padEnd((columns - count.length).coerceAtLeast(0)) + gray(count.take(columns))
        if (maxRows == 1) return listOf(heading)
        val filled = (columns * (stage.ordinal + 1) / WizardStage.entries.size).coerceAtLeast(1)
        return listOf(heading, ACCENT("━".repeat(filled)) + gray("─".repeat(columns - filled)))
    }

    /**
     * True once piped stdin hit EOF. From that point every prompt resolves to
     * its default without rendering, so a half-answered wizard finishes with
     * deterministic defaults instead of aborting after partial input.
     */
    var isInputExhausted = false
        private set

    /**
     * True while [useAlternateScreen] holds the alternate screen open. Each
     * selection then reuses it instead of switching buffers, which would make
     * the banner and the answers so far flash on every step.
     */
    private var alternateScreenHeld = false

    /**
     * True while the wizard holds one alternate screen. Callers use it to skip
     * printing that would land on top of the current frame — the frames already
     * redraw the answers from [wizardUiState].
     */
    val isAlternateScreenHeld: Boolean get() = alternateScreenHeld

    /**
     * Runs [block] with a single alternate screen for the whole wizard, so the
     * screen is swapped once rather than once per selection. Falls through
     * untouched when the terminal cannot address the cursor: the fallback
     * prompts print line by line and must stay in the primary buffer.
     */
    /**
     * True when selections render as full-screen frames. Consoles that only
     * support carriage returns (the IntelliJ Run window) print line by line and
     * must stay in the primary buffer.
     */
    val usesAlternateScreen: Boolean
        get() = terminal.terminalInfo.outputInteractive && !terminal.terminalInfo.supportsAnsiCursor

    fun useAlternateScreen(block: () -> Unit): Boolean {
        if (alternateScreenHeld || !usesAlternateScreen) {
            block()
            return false
        }
        terminal.rawPrint(ENTER_ALTERNATE_SCREEN)
        alternateScreenHeld = true
        try {
            block()
        } finally {
            alternateScreenHeld = false
            terminal.cursor.show()
            terminal.rawPrint(EXIT_ALTERNATE_SCREEN)
        }
        return true
    }

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
        var lastError: String? = null
        while (true) {
            renderTypedFrame(lastError)
            val line = when (val input = readLineWithBar(prompt, promptWidth, allowBack, complete, lastError)) {
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
            // Inside the held screen the next frame repaints over the error, so
            // it travels with the frame instead of being printed once.
            if (alternateScreenHeld) lastError = error else terminal.println(brightRed(error))
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
            is SelectResult.Picked -> return Answer.Value(result.values.single() == YES)
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
                return Answer.Value(items[labels.indexOf(result.values.single()).coerceAtLeast(0)])
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
     * Multi-choice selection (space toggles). Search-enabled lists provide a
     * numbered fallback; other lists return null when raw mode is unavailable.
     * Values can supply stable IDs independently of the displayed entry titles.
     */
    fun chooseMany(
        question: String,
        entries: List<SelectList.Entry>,
        allowBack: Boolean = false,
        maxVisibleEntries: Int? = null,
        filterTexts: List<String>? = null,
        lockedIndices: Set<Int> = emptySet(),
        groups: List<String>? = null,
        values: List<String> = entries.map { it.title },
    ): Answer<List<String>>? = when (val result = runSelect(
        question = question,
        entries = entries,
        multi = true,
        allowBack = allowBack,
        maxVisibleEntries = maxVisibleEntries,
        filterTexts = filterTexts,
        lockedIndices = lockedIndices,
        groups = groups,
        values = values,
    )) {
        is SelectResult.Picked -> Answer.Value(result.values)
        SelectResult.Back -> Answer.Back
        SelectResult.Unsupported -> if (filterTexts == null) {
            null
        } else {
            runSearchableChooseManyFallback(question, entries, allowBack, maxVisibleEntries, filterTexts, lockedIndices, groups, values)
        }
    }

    private fun runSearchableChooseManyFallback(
        question: String,
        entries: List<SelectList.Entry>,
        allowBack: Boolean,
        maxVisibleEntries: Int?,
        filterTexts: List<String>,
        lockedIndices: Set<Int>,
        groups: List<String>?,
        values: List<String>,
    ): Answer<List<String>> {
        var state = SelectionUiState(
            question = question,
            entries = entries,
            multi = true,
            allowBack = allowBack,
            maxVisibleEntries = maxVisibleEntries,
            filterTexts = filterTexts,
            lockedIndices = lockedIndices,
            groups = groups,
            values = values,
        )
        if (isInputExhausted) return Answer.Value(state.pickedValues())

        while (true) {
            terminal.println(questionStyle(question) + selectionCount(state))
            terminal.println(filterStatus(state))
            val visible = state.visibleIndices
            printNumberedEntries(state)
            terminal.println(gray("/query filters; numbers toggle visible entries; Enter confirms"))
            terminal.println(gray((if (allowBack) "< goes back; " else "") + "q quits"))
            terminal.print(questionStyle("Selection:") + " ")

            val line = readlnOrNull()
            if (line == null) {
                isInputExhausted = true
                terminal.println()
                terminal.println(gray("No more input — using defaults for the remaining steps."))
                return Answer.Value(state.pickedValues())
            }
            val input = line.trim()
            when {
                input.isEmpty() -> return Answer.Value(state.pickedValues())
                input == BACK_INPUT && allowBack -> return Answer.Back
                isQuitInput(input) -> quit()
                input.startsWith("/") -> state = state.setFilter(input.drop(1).trim())
                else -> {
                    val numbers = input.split(',').map { it.trim().toIntOrNull() }
                    if (numbers.any { it == null || it !in 1..visible.size }) {
                        terminal.println(brightRed("Enter visible numbers separated by commas, /query, or Enter."))
                    } else {
                        numbers.filterNotNull().distinct().forEach { number ->
                            state = state.copy(cursorIndex = visible[number - 1]).toggle()
                        }
                    }
                }
            }
        }
    }

    private fun printNumberedEntries(state: SelectionUiState) {
        val visible = state.visibleIndices
        if (visible.isEmpty()) {
            terminal.println(gray("  No matches."))
            return
        }
        val numberWidth = visible.size.toString().length
        visible.forEachIndexed { position, index ->
            state.groups?.get(index)?.let { group ->
                if (position == 0 || group != state.groups[visible[position - 1]]) terminal.println(cyan(group))
            }
            val marker = if (index in state.selectedIndices) brightGreen("[x]") else gray("[ ]")
            val number = (position + 1).toString().padStart(numberWidth)
            val included = if (index in state.lockedIndices) gray("(included) ") else ""
            terminal.println("  $marker $number) $included${state.entries[index].title}")
            descriptionLine(state.entries[index], terminal.size.width, indent = numberWidth + 8)?.let(terminal::println)
        }
    }

    // --- Custom select loop ----------------------------------------------------

    private sealed interface SelectResult {
        data class Picked(val values: List<String>) : SelectResult
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
        filterTexts: List<String>? = null,
        lockedIndices: Set<Int> = emptySet(),
        groups: List<String>? = null,
        values: List<String> = entries.map { it.title },
    ): SelectResult {
        val rawMode = terminal.enterRawModeOrNull() ?: run {
            printProgress(compact = true)
            return SelectResult.Unsupported
        }
        val initialState = SelectionUiState(
            question = question,
            entries = entries,
            multi = multi,
            allowBack = allowBack,
            maxVisibleEntries = maxVisibleEntries,
            filterTexts = filterTexts,
            lockedIndices = lockedIndices,
            groups = groups,
            values = values,
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
        val ownsScreen = !alternateScreenHeld
        if (ownsScreen) terminal.rawPrint(ENTER_ALTERNATE_SCREEN)
        try {
            terminal.cursor.hide(showOnExit = false)
            var state = renderSelection(initialState)
            var renderedWidth = terminal.size.width
            var renderedHeight = terminal.size.height
            while (true) {
                val key = readKeyOrNullCompat(rawMode)
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
                    state.editingFilter && isQuitInput(key.key) && key.ctrl && !key.alt -> quit()
                    state.editingFilter && key.key == "Escape" -> state = renderSelection(state.cancelFilterEdit())
                    state.editingFilter && key.key == "Enter" -> state = renderSelection(state.finishFilterEdit())
                    state.editingFilter && key.key == "Backspace" -> state = renderSelection(state.eraseFilterCharacter())
                    state.editingFilter && key.key.equals("u", ignoreCase = true) && key.ctrl ->
                        state = renderSelection(state.setFilter(""))
                    state.editingFilter && key.key == "Spacebar" -> state = renderSelection(state.appendToFilter(" "))
                    state.editingFilter && key.key.length == 1 && !key.ctrl && !key.alt ->
                        state = renderSelection(state.appendToFilter(key.key))
                    isQuitInput(key.key) && !key.ctrl && !key.alt -> quit()
                    key.key == "/" && state.filterTexts != null -> state = renderSelection(state.startFilterEdit())
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
                    key.key == "Enter" -> return SelectResult.Picked(state.pickedValues())
                }
            }
        } finally {
            terminal.cursor.show()
            if (ownsScreen) terminal.rawPrint(EXIT_ALTERNATE_SCREEN)
        }
    }

    /**
     * Banner rows for the current frame, or none when the rest of the frame
     * needs the space. A wizard that hides its question to show a logo would be
     * worse than one without a logo.
     */
    private fun headerLines(width: Int, height: Int, rowsInUse: Int): List<String> {
        if (!alternateScreenHeld) return emptyList()
        val header = header(width)
        if (header.isEmpty()) return emptyList()
        // One blank row separates the banner from the frame body.
        return if (height - rowsInUse - header.size - 1 >= MIN_ROWS_BELOW_HEADER) header + "" else emptyList()
    }

    /**
     * Draws the answers so far above a typed prompt. Only needed while the
     * alternate screen is held for the whole wizard: without it the prompt
     * would land on whatever the previous selection frame left behind.
     */
    private fun renderTypedFrame(error: String?) {
        if (!alternateScreenHeld) {
            printProgress(compact = true)
            return
        }
        terminal.updateSize()
        val height = terminal.size.height.coerceAtLeast(1)
        val uiState = wizardUiState()
        // Rows kept for the prompt, its navigation bar and the error line.
        val reserved = 4 + if (error != null) 1 else 0
        val progress = progressLines(uiState.stage, terminal.size.width, height - reserved)
        val banner = headerLines(terminal.size.width.coerceAtLeast(1), height, reserved + progress.size)
        val choices = uiState.choices
            .takeLast((height - reserved - banner.size - progress.size).coerceAtLeast(0))
        terminal.cursor.move {
            setPosition(0, 0)
            clearScreen()
        }
        progress.forEach(terminal::println)
        banner.forEach { terminal.println(it) }
        choices.forEach { terminal.println(renderChoice(it)) }
        if (choices.isNotEmpty()) terminal.println()
        error?.let { terminal.println(brightRed(it)) }
    }

    /** Rebuilds the complete frame from immutable wizard and selection data. */
    private fun renderSelection(state: SelectionUiState): SelectionUiState {
        // Mordant's macOS JNA backend disables automatic polling because it
        // obtains terminal dimensions through `stty`.
        terminal.updateSize()
        val terminalWidth = terminal.size.width.coerceAtLeast(1)
        val terminalHeight = terminal.size.height.coerceAtLeast(1)
        val uiState = wizardUiState()

        val barParts = selectBarParts(state)
        val navigation = when {
            terminalHeight >= 4 -> renderBar(barParts)
            terminalHeight >= 3 -> renderBarLine(barParts)
            else -> null
        }
        val navigationRows = navigation?.count { it == '\n' }?.plus(1) ?: 0
        val statusRows = if (state.filterTexts != null && terminalHeight >= 2) 1 else 0
        val showTitle = terminalHeight - navigationRows - statusRows >= 2
        val fixedSelectionRows = navigationRows + statusRows + if (showTitle) 1 else 0
        // Keep the focused entry usable before spending rows on progress or history.
        val minimumEntryRows = 1 + (if (state.groups != null) 1 else 0) +
            (if (state.entries.any { it.description != null }) 1 else 0)
        val progress = progressLines(uiState.stage, terminalWidth, terminalHeight - fixedSelectionRows - minimumEntryRows)
        val entryRowBudget = (terminalHeight - fixedSelectionRows - progress.size).coerceAtLeast(1)
        val showGroups = state.groups != null && entryRowBudget >= 2
        val showDescriptions = entryRowBudget >= if (showGroups) 3 else 2
        val visibleIndices = state.visibleIndices
        val requestedEntries = minOf(state.maxVisibleEntries ?: visibleIndices.size, visibleIndices.size)
        fun groupHeading(position: Int, first: Boolean): String? {
            if (!showGroups) return null
            val group = state.groups[visibleIndices[position]]
            return group.takeIf { first || it != state.groups[visibleIndices[position - 1]] }
        }
        fun entryRows(position: Int, first: Boolean): Int = 1 +
            (if (groupHeading(position, first) != null) 1 else 0) +
            (if (showDescriptions && state.entries[visibleIndices[position]].description != null) 1 else 0)
        val window = if (visibleIndices.isEmpty()) null else selectionWindow(
            entryCount = visibleIndices.size,
            cursorIndex = visibleIndices.indexOf(state.cursorIndex).coerceAtLeast(0),
            previousFirstIndex = state.firstVisibleIndex,
            maxVisibleEntries = requestedEntries,
            maxRows = entryRowBudget,
            rowCount = ::entryRows,
        )
        val positionedState = state.copy(firstVisibleIndex = window?.firstIndex ?: 0)
        val position = if (window != null && window.size < visibleIndices.size) {
            gray("  ${window.firstIndex + 1}\u2013${window.lastIndexExclusive} of ${visibleIndices.size}")
        } else {
            ""
        }
        val selectionRows = fixedSelectionRows + (window?.let {
            (it.firstIndex until it.lastIndexExclusive).sumOf { index -> entryRows(index, index == window.firstIndex) }
        } ?: 1)
        val banner = headerLines(terminalWidth, terminalHeight, selectionRows + progress.size)
        val historyCapacity = (terminalHeight - selectionRows - banner.size - progress.size - 1).coerceAtLeast(0)
        val choices = uiState.choices.takeLast(historyCapacity)
        val lines = buildList {
            addAll(progress)
            addAll(banner)
            choices.forEach { add(renderChoice(it)) }
            if (choices.isNotEmpty()) add("")
            if (showTitle) add(questionStyle(state.question) + position + selectionCount(state))
            if (statusRows > 0) add(filterStatus(positionedState))
            if (window == null) {
                add(gray("  No matches."))
            } else {
                (window.firstIndex until window.lastIndexExclusive).forEach { visibleIndex ->
                    groupHeading(visibleIndex, visibleIndex == window.firstIndex)?.let { add(cyan(it)) }
                    val index = visibleIndices[visibleIndex]
                    add(renderEntry(positionedState, index))
                    if (showDescriptions) descriptionLine(state.entries[index], terminalWidth)?.let(::add)
                }
            }
            navigation?.let { addAll(it.lines()) }
        }
        val viewport = Viewport(
            // Each heading, title and description has its own bounded row so
            // wrapping cannot push the cursor or navigation below the screen.
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

    private fun filterStatus(state: SelectionUiState): String {
        val query = state.filterQuery.ifEmpty { if (state.editingFilter) "" else "/ to search" }
        return gray("Search: ") + query
    }

    private fun selectionCount(state: SelectionUiState): String =
        if (state.filterTexts == null) "" else gray("  •  ${state.selectedIndices.size} selected")

    private fun descriptionLine(entry: SelectList.Entry, width: Int, indent: Int = 6): String? =
        entry.description?.let { description ->
            val available = (width - indent - 1).coerceAtLeast(1)
            val text = terminal.render(description).lineSequence().joinToString(" ") { it.trim() }
            val clipped = if (text.length > available) text.take((available - 1).coerceAtLeast(0)) + "…" else text
            gray(" ".repeat(indent) + clipped)
        }

    private fun renderChoice(choice: WizardChoice): String =
        brightGreen("✓ ") + choice.label + ":" + cyan(" ${choice.value}")

    private fun renderEntry(state: SelectionUiState, index: Int): String {
        val atCursor = index == state.cursorIndex
        val selected = index in state.selectedIndices
        val cursor = if (atCursor) cyan("❯") else " "
        // Include the separator in the highlight to preserve a title's leading ANSI style.
        val title = when {
            !state.multi && atCursor -> brightGreen(" ${state.entries[index].title}")
            state.multi && selected -> brightGreen(" ${state.entries[index].title}")
            else -> " ${state.entries[index].title}"
        }
        return if (state.multi) {
            val marker = if (selected) brightGreen("[x]") else gray("[ ]")
            val included = if (index in state.lockedIndices) gray(" (included)") else ""
            "$cursor $marker$included$title"
        } else {
            "$cursor$title"
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
        printProgress(compact = true)
        var state = initialState
        val lineWidth = (terminal.size.width - 1).coerceAtLeast(1)
        val clearLine = " ".repeat(lineWidth)

        terminal.println(questionStyle(state.question))
        val showCatalog = state.groups != null || state.entries.any { it.description != null }
        if (showCatalog) printNumberedEntries(state)
        terminal.println(renderBar(selectBarParts(state)))

        fun redraw() {
            val visible = state.visibleIndices
            val visiblePosition = visible.indexOf(state.cursorIndex)
            val marker = when {
                visible.isEmpty() -> ""
                !state.multi -> cyan("❯")
                state.cursorIndex in state.selectedIndices -> cyan("❯ [x]")
                else -> "❯ [ ]"
            }
            val position = if (visible.size > 1 && visiblePosition >= 0) {
                gray(" ${visiblePosition + 1}/${visible.size}")
            } else {
                ""
            }
            val entry = if (visible.isEmpty()) gray("No matches.") else {
                val included = if (state.cursorIndex in state.lockedIndices) gray("(included) ") else ""
                included + state.entries[state.cursorIndex].title + position
            }
            val search = if (state.filterTexts == null) "" else gray("  •  ") + filterStatus(state)
            val styledLine = if (state.editingFilter) filterStatus(state) else "$marker $entry${selectionCount(state)}$search"
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
                val wasEditingFilter = state.editingFilter
                when {
                    key.isCtrlC -> abort()
                    state.editingFilter && isQuitInput(key.key) && key.ctrl && !key.alt -> quit()
                    state.editingFilter && key.key == "Escape" -> state = state.cancelFilterEdit()
                    state.editingFilter && key.key == "Enter" -> state = state.finishFilterEdit()
                    state.editingFilter && key.key == "Backspace" -> state = state.eraseFilterCharacter()
                    state.editingFilter && key.key.equals("u", ignoreCase = true) && key.ctrl -> state = state.setFilter("")
                    state.editingFilter && key.key == "Spacebar" -> state = state.appendToFilter(" ")
                    state.editingFilter && key.key.length == 1 && !key.ctrl && !key.alt ->
                        state = state.appendToFilter(key.key)
                    isQuitInput(key.key) && !key.ctrl && !key.alt -> quit()
                    key.key == "/" && state.filterTexts != null -> state = state.startFilterEdit()
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
                    key.key == "Enter" -> return SelectResult.Picked(state.pickedValues())
                    else -> continue
                }
                if (wasEditingFilter != state.editingFilter) {
                    terminal.rawPrint("\r$clearLine\r")
                    if (!state.editingFilter && showCatalog) printNumberedEntries(state)
                    terminal.println(renderBar(selectBarParts(state)))
                }
                redraw()
            }
        } finally {
            terminal.rawPrint("\r$clearLine\r")
        }
    }

    private fun selectBarParts(state: SelectionUiState): List<Pair<String, String>> =
        buildList {
            if (state.editingFilter) {
                add("backspace" to "erase")
                add("ctrl+u" to "clear")
                add("enter" to "apply")
                add("esc" to "cancel")
                add("ctrl+$QUIT_INPUT" to "quit")
                return@buildList
            }
            add("↑" to "up")
            add("↓" to "down")
            if (state.multi) add("space" to "toggle")
            if (state.filterTexts != null) add("/" to "search")
            add("enter" to if (state.multi) "confirm" else "select")
            if (state.allowBack) add("esc" to "back")
            add(QUIT_INPUT to "quit")
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
            if (rawMode) add("ctrl+$QUIT_INPUT" to "quit")
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
        error: String? = null,
    ): LineInput {
        val rawMode = terminal.enterRawModeOrNull()
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
        var renderedSize = terminal.size
        rawMode.use { scope ->
            while (true) {
                val key = readKeyOrNullCompat(scope)
                if (alternateScreenHeld) {
                    val size = terminal.updateSize()
                    if (size.width != renderedSize.width || size.height != renderedSize.height) {
                        renderTypedFrame(error)
                        printPromptWithBar(prompt, promptWidth, barParts)
                        terminal.print(buffer.toString())
                        renderedSize = terminal.size
                    }
                }
                if (key == null) continue
                when {
                    key.isCtrlC -> abort()
                    isQuitInput(key.key) && key.ctrl && !key.alt -> quit()
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

    /**
     * Polls for a key like [RawModeScope.readKeyOrNull], treating the poll
     * timeout as "no key". The Mordant 3.0.2 Windows backend throws on the
     * timeout instead of returning null (fixed upstream but unreleased), which
     * would otherwise abort the wizard 100ms after a selection list opens.
     */
    private fun readKeyOrNullCompat(rawMode: RawModeScope) = try {
        rawMode.readKeyOrNull(RESIZE_POLL_INTERVAL)
    } catch (e: RuntimeException) {
        if (e.message?.contains(WINDOWS_POLL_TIMEOUT_MESSAGE) == true) null else abort()
    }

    // ponytail: terminals expose characters, so other layouts need explicit aliases.
    private fun isQuitInput(input: String): Boolean =
        input.equals(QUIT_INPUT, ignoreCase = true) || input.equals("й", ignoreCase = true)

    private fun abort(): Nothing = throw CliktError("Aborted.")

    private fun quit(): Nothing = throw ProgramResult(0)

    private companion object {
        const val BACK_INPUT = "<"
        const val QUIT_INPUT = "q"

        /** Rows a frame needs below the banner: question, one entry, nav bar. */
        const val MIN_ROWS_BELOW_HEADER = 3
        const val COMPLETION_CANDIDATES_SHOWN = 8
        const val YES = "Yes"
        const val NO = "No"
        const val RULE_CHAR = "─"
        const val WINDOWS_POLL_TIMEOUT_MESSAGE = "Timeout reading from console input"
        const val ENTER_ALTERNATE_SCREEN = "\u001B[?1049h"
        const val EXIT_ALTERNATE_SCREEN = "\u001B[?1049l"
        val RESIZE_POLL_INTERVAL = 100.milliseconds
    }
}

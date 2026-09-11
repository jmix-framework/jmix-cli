package io.jmix.cli.wizard

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.terminal.Terminal
import java.util.Locale

/** Matches one ANSI escape sequence; used to measure styled text. */
internal val ANSI_SEQUENCE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

/** The wizard's accent color, shared by the phase bar and download bars. */
internal val ACCENT: TextStyle = TextColors.rgb("#6C5CE7")

/** What the indicator shows: a label plus byte counts once they are known. */
internal data class Status(val label: String, val done: Long = 0, val total: Long = -1)

/**
 * One-line activity indicator for downloads and other slow steps: a spinner,
 * a label naming what is being reached, and a bar with byte counts once the
 * size is known. Interactive output animates a single line that is removed
 * when the step ends — inside the wizard's held screen the next frame repaints
 * anyway, and consoles that only honor carriage returns (the IntelliJ Run
 * window) redraw the same line, like the selection prompts do. Piped output
 * gets one plain line per phase instead.
 */
class StatusReporter(
    private val terminal: Terminal,
    private val refreshMillis: Long = REFRESH_MILLIS,
) {

    /** Updates for the running step, called from the thread doing the work. */
    inner class Handle internal constructor(private val indicator: Indicator?) {

        /** Names the next phase of the step and drops any byte counts. */
        fun relabel(label: String) {
            if (indicator == null) terminal.println(gray("$label...")) else indicator.update { Status(label) }
        }

        /** Reports bytes so far and the total, or -1 when the total is unknown. */
        fun progress(done: Long, total: Long) {
            indicator?.update { it.copy(done = done, total = total) }
        }

        /** Prints a line that stays in the transcript, above the indicator. */
        fun println(line: String) {
            if (indicator == null) terminal.println(line) else indicator.printAbove(line)
        }
    }

    /** Runs [block] behind an indicator labeled [label]; the line is gone when this returns. */
    fun <T> run(label: String, block: (Handle) -> T): T {
        if (!terminal.terminalInfo.outputInteractive) {
            terminal.println(gray("$label..."))
            return block(Handle(null))
        }
        val indicator = Indicator(label)
        try {
            indicator.start()
            return block(Handle(indicator))
        } finally {
            indicator.stop()
        }
    }

    internal inner class Indicator(label: String) {
        private val lock = Any()
        @Volatile
        private var status = Status(label)
        private var stopped = false
        private var tick = 0
        private val thread = Thread(::loop, "jmix-status").apply { isDaemon = true }

        fun start() {
            terminal.cursor.hide(showOnExit = false)
            // Paint before starting work, including fast cache hits.
            draw()
            thread.start()
        }

        fun update(change: (Status) -> Status) {
            status = change(status)
        }

        fun printAbove(line: String) {
            synchronized(lock) {
                if (!stopped) clearLine()
                terminal.println(line)
                if (!stopped) draw()
            }
        }

        fun stop() {
            synchronized(lock) {
                if (stopped) return
                stopped = true
                clearLine()
            }
            thread.interrupt()
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                terminal.cursor.show()
            }
        }

        private fun loop() {
            try {
                while (true) {
                    synchronized(lock) {
                        if (stopped) return
                        draw()
                        tick++
                    }
                    Thread.sleep(refreshMillis)
                }
            } catch (e: InterruptedException) {
                // Stopped: the line was cleared under the lock already.
            }
        }

        private fun draw() {
            terminal.updateSize()
            clearLine()
            terminal.print(statusLine(status, SPINNER_FRAMES[tick % SPINNER_FRAMES.size], lineWidth()))
        }

        // Spaces rather than an erase sequence: consoles that only honor
        // carriage returns overwrite the line the same way.
        private fun clearLine() = terminal.rawPrint("\r" + " ".repeat(lineWidth()) + "\r")

        private fun lineWidth() = (terminal.size.width - 1).coerceAtLeast(1)
    }

    companion object {
        private const val REFRESH_MILLIS = 100L
        private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    }
}

/** Fits the label and real progress into one line, adding a bar when there is room. */
internal fun statusLine(status: Status, frame: String, width: Int): String {
    if (width <= 2) return frame.take(width.coerceAtLeast(0))
    val ratio = if (status.total > 0) (status.done.toDouble() / status.total).coerceIn(0.0, 1.0) else null
    val percent = ratio?.let { "${(it * 100).toInt()}%" }.orEmpty()
    val counts = when {
        ratio != null -> "$percent  ${formatBytes(status.done)} / ${formatBytes(status.total)}"
        status.done > 0 -> formatBytes(status.done)
        else -> ""
    }
    // Keep real progress visible; drop the bar before shortening the label.
    val detail = when {
        counts.length + 6 <= width -> counts
        percent.length + 4 <= width -> percent
        else -> ""
    }
    val bar = if (ratio != null && status.label.length + detail.length + BAR_WIDTH + 6 <= width) {
        val filled = (BAR_WIDTH * ratio).toInt()
        "  " + ACCENT("━".repeat(filled)) + gray("─".repeat(BAR_WIDTH - filled))
    } else ""
    val suffix = bar + if (detail.isNotEmpty()) gray("  $detail") else ""
    val labelWidth = (width - 2 - ANSI_SEQUENCE.replace(suffix, "").length).coerceAtLeast(0)
    val label = if (status.label.length <= labelWidth) status.label else {
        status.label.take((labelWidth - 1).coerceAtLeast(0)) + if (labelWidth > 0) "…" else ""
    }
    return cyan(frame) + " " + label + suffix
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= MEGABYTE -> String.format(Locale.ROOT, "%.1f MB", bytes / MEGABYTE.toDouble())
    bytes >= KILOBYTE -> "${bytes / KILOBYTE} KB"
    else -> "$bytes B"
}

private const val BAR_WIDTH = 20
private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024

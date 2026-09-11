package io.jmix.cli.wizard

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusReporterTest {

    @Test
    fun `piped output gets one plain line per phase and no carriage returns`() {
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
        val reporter = StatusReporter(Terminal(terminalInterface = recorder))

        val result = reporter.run("Fetching Jmix versions from repo.example") { progress ->
            progress.progress(10, 100)
            progress.relabel("Unpacking the archive")
            progress.println("Warning: slow network")
            42
        }

        assertEquals(42, result)
        val plain = ANSI_SEQUENCE.replace(recorder.output(), "")
        assertEquals(
            listOf("Fetching Jmix versions from repo.example...", "Unpacking the archive...", "Warning: slow network"),
            plain.trimEnd().lines(),
        )
        assertFalse(recorder.output().contains('\r'))
    }

    @Test
    fun `interactive output animates one line, keeps printed lines, and removes the indicator`() {
        val recorder = TerminalRecorder(width = 100, height = 20, supportsAnsiCursor = false)
        val reporter = StatusReporter(Terminal(terminalInterface = recorder), refreshMillis = 5)

        reporter.run("Downloading templates from repo.example") { progress ->
            progress.progress(50, 100)
            awaitOutput(recorder) { it.contains("50%") }
            progress.println("Warning: slow network")
            awaitOutput(recorder) { it.contains("Warning: slow network\n") && it.substringAfter("Warning: slow network\n").contains("Downloading") }
        }

        val plain = ANSI_SEQUENCE.replace(recorder.output(), "")
        assertTrue(plain.contains("Downloading templates from repo.example"), plain)
        assertTrue(plain.contains("50%  50 B / 100 B"), plain)
        assertTrue(plain.contains("Warning: slow network\n"), plain)
        assertTrue(plain.endsWith("\r" + " ".repeat(99) + "\r"), plain.takeLast(120))
        assertFalse(plain.contains('\n' + "Downloading"), "the indicator must redraw in place, not scroll")
    }

    @Test
    fun `a failing step still removes its indicator before the error surfaces`() {
        val recorder = TerminalRecorder(width = 40, height = 20)
        val reporter = StatusReporter(Terminal(terminalInterface = recorder), refreshMillis = 5)

        assertThrows(IOException::class.java) {
            reporter.run("Fetching") {
                awaitOutput(recorder) { it.contains("Fetching") }
                throw IOException("boom")
            }
        }

        assertTrue(ANSI_SEQUENCE.replace(recorder.output(), "").endsWith("\r" + " ".repeat(39) + "\r"))
        assertTrue(recorder.output().endsWith("\u001B[?25h"), "restore the cursor on failure")
    }

    @Test
    fun `even a fast or interrupted operation paints before work and restores the cursor`() {
        val recorder = TerminalRecorder(width = 80, height = 20, supportsAnsiCursor = false)
        val reporter = StatusReporter(Terminal(terminalInterface = recorder))

        try {
            reporter.run("Fetching") {
                assertTrue(recorder.output().contains("Fetching"), "paint before starting IO")
                assertTrue(recorder.output().contains("\u001B[?25l"), "hide the cursor while animating")
                Thread.currentThread().interrupt()
            }
            assertTrue(Thread.currentThread().isInterrupted, "preserve interruption during cleanup")
            assertTrue(recorder.output().endsWith("\u001B[?25h"), "restore the cursor before returning")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `status line adds a bar and byte counts once the total is known`() {
        fun plain(line: String) = ANSI_SEQUENCE.replace(line, "")

        assertEquals("⠋ Fetching", plain(statusLine(Status("Fetching"), "⠋", 80)))
        assertEquals(
            "⠋ Downloading  ━━━━━━━━━━──────────  50%  6.0 MB / 12.0 MB",
            plain(statusLine(Status("Downloading", 6L * 1024 * 1024, 12L * 1024 * 1024), "⠋", 80)),
        )
        assertEquals("⠋ Downloading  512 KB", plain(statusLine(Status("Downloading", 512 * 1024), "⠋", 80)))
        // More bytes than announced: the bar is clamped, the counts stay truthful.
        assertEquals(
            "⠋ Downloading  ━━━━━━━━━━━━━━━━━━━━  100%  9 B / 7 B",
            plain(statusLine(Status("Downloading", 9, 7), "⠋", 80)),
        )
    }

    @Test
    fun `long labels never hide real progress in a normal or narrow terminal`() {
        val status = Status("Loading templates 3.0.2 from global.repo.jmix.io", 6L * 1024 * 1024, 12L * 1024 * 1024)
        for (width in listOf(26, 80, 120)) {
            val line = ANSI_SEQUENCE.replace(statusLine(status, "⠋", width), "")
            assertTrue(line.length <= width, line)
            assertTrue(line.contains("50%"), line)
            if (width >= 80) assertTrue(line.contains("6.0 MB / 12.0 MB"), line)
            if (width >= 120) assertTrue(line.contains("━"), line)
        }

        for (width in 1..25) {
            assertTrue(ANSI_SEQUENCE.replace(statusLine(status, "⠋", width), "").length <= width)
        }
    }

    @Test
    fun `byte counts use the largest fitting unit`() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1536 * 1024))
    }

    /** Waits for the render thread instead of sleeping a fixed time, so slow CI hosts do not flake. */
    private fun awaitOutput(recorder: TerminalRecorder, condition: (String) -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition(ANSI_SEQUENCE.replace(recorder.output(), ""))) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for indicator output: ${recorder.output()}")
            Thread.sleep(5)
        }
    }
}

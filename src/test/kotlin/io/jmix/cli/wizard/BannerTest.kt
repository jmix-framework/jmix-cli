package io.jmix.cli.wizard

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BannerTest {

    private fun render(width: Int, version: String? = null): String {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = width)
        // Both are needed: the recorder reports the size, the terminal renders to it.
        val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = width, terminalInterface = recorder)
        Banner.print(terminal, version)
        return recorder.output()
    }

    @Test
    fun `a wide terminal gets the block wordmark`() {
        val output = render(120, "1.2.3")

        assertTrue(output.contains("██"), "expected block art:\n$output")
        assertTrue(output.contains("Jmix CLI 1.2.3"))
        assertTrue(output.contains("Create a Jmix project"))
    }

    @Test
    fun `a narrow terminal falls back to ascii art without wrapping the subtitle`() {
        val output = render(31)

        assertFalse(output.contains("██"), "block art does not fit:\n$output")
        assertTrue(output.contains("|_|"), "expected ascii art:\n$output")
        assertTrue(output.contains("Jmix CLI"))
        // The tagline would wrap at this width, so it is dropped.
        assertFalse(output.contains("Create a Jmix project"))
    }

    @Test
    fun `a very narrow terminal prints the name only`() {
        val output = render(20)

        assertTrue(output.contains("Jmix CLI"))
        assertFalse(output.contains("|_|"))
        assertFalse(output.contains("██"))
    }

    @Test
    fun `the version is omitted when it is unknown`() {
        assertTrue(render(120).contains("Jmix CLI"))
    }

    @Test
    fun `the logo is drawn in its own colors when the terminal supports them`() {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.TRUECOLOR, width = 120)
        Banner.print(Terminal(ansiLevel = AnsiLevel.TRUECOLOR, width = 120, terminalInterface = recorder), "1.0.0")
        val output = recorder.output()

        // Sampled from the Jmix logo: pink, green, cyan and orange arcs.
        listOf("38;2;252;18;100", "38;2;34;214;133", "38;2;37;205;227", "38;2;253;180;43").forEach {
            assertTrue(output.contains(it), "missing logo color $it")
        }
    }

    @Test
    fun `the logo is a rectangle so the wordmark stays aligned`() {
        assertEquals(
            1, Banner.logoRowWidths.distinct().size,
            "every logo row must be equally wide, got ${Banner.logoRowWidths}",
        )
    }

    @Test
    fun `every banner line fits the terminal width`() {
        for (width in listOf(20, 24, 31, 40, 80, 120)) {
            render(width, "9.9.9").lines().forEach {
                assertTrue(it.length <= width, "line wider than $width: '$it'")
            }
        }
    }
}

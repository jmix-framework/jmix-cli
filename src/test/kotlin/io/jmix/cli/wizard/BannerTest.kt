package io.jmix.cli.wizard

import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BannerTest {

    /**
     * Renders the banner into a string. [blocks] is passed explicitly so the
     * assertions hold on hosts whose console cannot show block glyphs.
     */
    private fun render(width: Int, version: String? = null, blocks: Boolean = true): String {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = width)
        // Both are needed: the recorder reports the size, the terminal renders to it.
        val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = width, terminalInterface = recorder)
        Banner.print(terminal, version, blocks)
        return recorder.output()
    }

    @Test
    fun `a wide terminal gets the block wordmark`() {
        val output = render(120, "1.2.3")

        assertTrue(output.contains("██"), "expected block art:\n$output")
        assertTrue(output.contains("Jmix CLI 1.2.3"))
        assertTrue(output.contains("Choose a command"))
    }

    @Test
    fun `a console without block glyphs gets the ascii wordmark`() {
        val output = render(120, blocks = false)

        assertFalse(output.contains("██"), "block art needs block glyphs:\n$output")
        assertTrue(output.contains("|_|"), "expected ascii art:\n$output")
        assertTrue(output.contains("Jmix CLI"))
    }

    @Test
    fun `a narrow terminal falls back to ascii art without wrapping the subtitle`() {
        val output = render(27, "9.9.9")

        assertFalse(output.contains("██"), "block art does not fit:\n$output")
        assertTrue(output.contains("|_|"), "expected ascii art:\n$output")
        assertTrue(output.contains("Jmix CLI"))
        // The tagline would wrap at this width, so it is dropped.
        assertFalse(output.contains("Choose a command"))
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
        Banner.print(
            Terminal(ansiLevel = AnsiLevel.TRUECOLOR, width = 120, terminalInterface = recorder),
            "1.0.0", blocks = true,
        )
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

    @Test
    fun `account rows share a left edge inside a right aligned block`() {
        val account = listOf(
            listOf("Jmix account [global]" to gray),
            listOf("Ann" to (brightGreen + bold)),
            listOf("ann@example.com" to gray),
        )
        val lines = Banner.accountLines(120, account).map { ANSI_SEQUENCE.replace(it, "") }
        assertEquals(listOf("Jmix account [global]", "Ann", "ann@example.com"), lines.take(3).map { it.trimStart() })
        assertEquals(119, lines.first().length)
        assertEquals(1, lines.take(3).map { it.indexOfFirst { c -> c != ' ' } }.distinct().size)
        val wide = Banner.lines(120, "1.0.0", blocks = true, account = account).map { ANSI_SEQUENCE.replace(it, "") }
        val accountColumn = lines.first().indexOfFirst { it != ' ' }
        assertEquals(lines.map { it.trimStart() }, wide.take(3).map { it.drop(accountColumn) })
        assertEquals(Banner.lines(120, "1.0.0", blocks = true).size, wide.size)
        val narrow = Banner.lines(42, "1.0.0", blocks = true, account = account).map { ANSI_SEQUENCE.replace(it, "") }
        assertEquals(lines.map { it.trimStart() }, narrow.take(3).map { it.trimStart() })
        for (width in listOf(1, 20, 40, 80)) {
            val rows = Banner.accountLines(width, account).map { ANSI_SEQUENCE.replace(it, "") }
            assertEquals(3, rows.size)
            rows.forEach { assertTrue(it.length <= width, it) }
            assertEquals(1, rows.map { it.indexOfFirst { c -> c != ' ' } }.distinct().size)
        }
    }

    @Test
    fun `styled account cropping never counts or splits ANSI sequences`() {
        val parts = listOf("Jmix account: " to gray, "Ann Lee" to (brightGreen + bold), " [global]" to cyan)
        for (width in listOf(1, 15, 20, 40, 80)) {
            val styled = Banner.accountLines(width, listOf(parts)).single()
            val plain = ANSI_SEQUENCE.replace(styled, "")
            assertTrue(plain.length <= width, plain)
            assertFalse(plain.contains('\u001b'), styled)
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = width)
            val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = width, terminalInterface = recorder)
            assertEquals(plain, terminal.render(styled))
        }
    }

    @Test
    fun `intro reveals only the colored logo while the wordmark and account stay static`() {
        val account = listOf(listOf("Jmix account" to gray), listOf("Not signed in" to gray))
        val frames = listOf(0.0, 0.25, 0.5, 1.0).map { progress ->
            Banner.lines(120, "1.0.0", blocks = true, account = account, introProgress = progress)
                .map { ANSI_SEQUENCE.replace(it, "") }
        }
        assertEquals(1, frames.map { it.size }.distinct().size)
        val accountColumn = 119 - "Not signed in".length
        assertEquals(1, frames.map { frame -> frame.take(account.size).map { it.drop(accountColumn) } }.distinct().size)
        val logoWidth = Banner.logoRowWidths.max()
        val art = frames.map { it.dropLast(1) }
        val pixels = art.map { frame -> frame.sumOf { line -> line.take(logoWidth).count { it in "█▄▀" } } }
        assertEquals(0, pixels.first())
        assertTrue(pixels.zipWithNext().all { (before, after) -> after > before }, pixels.toString())
        val wordmarks = art.map { frame -> frame.map { it.drop(logoWidth) } }
        assertEquals(1, wordmarks.distinct().size)
        assertTrue(wordmarks.first().any { it.contains("██") })
        assertFalse(frames.last().any { it.contains('╔') || it.contains('║') || it.contains('╚') })
        assertTrue(frames.all { it.last().contains("Choose a command") })
    }

    @Test
    fun `wordmark-only banners never animate`() {
        for (width in listOf(20, 27, 31, 40)) {
            assertEquals(
                Banner.lines(width, blocks = true, introProgress = 1.0),
                Banner.lines(width, blocks = true, introProgress = 0.0),
                "width $width",
            )
        }
    }
}

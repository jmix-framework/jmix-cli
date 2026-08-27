package io.jmix.cli.wizard

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The wizard's opening banner: the Jmix logo next to the product name.
 * Renderings degrade with the terminal — logo plus wordmark, wordmark alone,
 * an ASCII wordmark for consoles without block glyphs, and finally one line.
 */
object Banner {

    // Colors sampled from the official logo artwork.
    private val PINK = TextColors.rgb("#FC1264")
    private val GREEN = TextColors.rgb("#22D685")
    private val CYAN = TextColors.rgb("#25CDE3")
    private val ORANGE = TextColors.rgb("#FDB42B")

    // The logo's own #17124B is nearly black — 1.2:1 against a dark terminal
    // background. This lighter indigo keeps the hue and stays readable on both
    // dark and light themes.
    private val INDIGO = TextColors.rgb("#6C5CE7")
    private val BRAND = INDIGO

    private val PALETTE: Map<Char, TextStyle> = mapOf(
        'P' to PINK, 'G' to GREEN, 'B' to CYAN, 'O' to ORANGE, 'N' to INDIGO,
    )

    /**
     * The logo rasterized from the official artwork into half-block cells: four
     * arcs around the diamond outline. Each row pairs its glyphs with a
     * per-cell palette key, so the shape is the real one rather than a
     * hand-drawn approximation.
     */
    private val LOGO = listOf(
        "     ▄████▄     " to "     PPPPPP     ",
        "   ▄█▀ ▄▄ ▀▄▄   " to "   PPP NN PGG   ",
        " ▄▄▀ ▄████▄ ▀█▄ " to " OOP NNNNNN GGG ",
        "██ ▄██▀  ▀██▄ ██" to "OO NNNN  NNNN GG",
        "██ ▀██▄  ▄██▀ ██" to "OO NNNN  NNNN GG",
        " ▀█▄ ▀████▀ ▄▀▀ " to " OOO NNNNNN BGG ",
        "   ▀▀▄ ▀▀ ▄█▀   " to "   OOB NN BBB   ",
        "     ▀████▀     " to "     BBBBBB     ",
    )

    private val BLOCK = listOf(
        "     ██╗███╗   ███╗██╗██╗  ██╗",
        "     ██║████╗ ████║██║╚██╗██╔╝",
        "     ██║██╔████╔██║██║ ╚███╔╝ ",
        "██   ██║██║╚██╔╝██║██║ ██╔██╗ ",
        "╚█████╔╝██║ ╚═╝ ██║██║██╔╝ ██╗",
        " ╚════╝ ╚═╝     ╚═╝╚═╝╚═╝  ╚═╝",
    )

    private val ASCII = listOf(
        "     _  __  __  _  __  __",
        "    | ||  \\/  || | \\ \\/ /",
        " _  | || |\\/| || |  \\  / ",
        "| |_| || |  | || |  /  \\ ",
        " \\___/ |_|  |_||_| /_/\\_\\",
    )

    private const val TAGLINE = "Create a Jmix project"
    private const val LOGO_GAP = "  "

    /** Cell count of each logo row; they must match or the wordmark shifts. */
    internal val logoRowWidths: List<Int> get() = LOGO.map { it.first.length }

    private val LOGO_WIDTH = logoRowWidths.max()
    private val BLOCK_WIDTH = BLOCK.maxOf { it.length } + 2
    private val ASCII_WIDTH = ASCII.maxOf { it.length } + 2
    private val WITH_LOGO_WIDTH = LOGO_WIDTH + LOGO_GAP.length + BLOCK_WIDTH

    /**
     * @param blocks whether the console can render block glyphs; overridable so
     *   tests cover every rendering regardless of the host they run on.
     */
    fun print(terminal: Terminal, version: String? = null, blocks: Boolean = supportsBlocks()) {
        terminal.println()
        lines(terminal.size.width, version, blocks).forEach { terminal.println(it) }
        terminal.println()
    }

    /**
     * The banner as rendered lines, so the wizard can keep it as a frame header
     * instead of losing it to the first repaint.
     */
    fun lines(width: Int, version: String? = null, blocks: Boolean = supportsBlocks()): List<String> {
        val subtitle = listOfNotNull("Jmix CLI", version).joinToString(" ")
        val art = when {
            blocks && width >= WITH_LOGO_WIDTH -> logoAndWordmark()
            blocks && width >= BLOCK_WIDTH -> BLOCK.map { BRAND(it) }
            width >= ASCII_WIDTH -> ASCII.map { BRAND(it) }
            // Too narrow for any art: the name still has to appear.
            else -> return listOf(BRAND(bold(subtitle)))
        }
        // The tagline is dropped rather than wrapped onto a second line.
        val tagline = "$LOGO_GAP·$LOGO_GAP$TAGLINE"
        val caption =
            if (width >= subtitle.length + tagline.length) bold(subtitle) + gray(tagline) else bold(subtitle)
        return art + caption
    }

    /** The logo on the left, with the wordmark centered against it. */
    private fun logoAndWordmark(): List<String> {
        val wordmarkPad = (LOGO.size - BLOCK.size).coerceAtLeast(0) / 2
        return (0 until maxOf(LOGO.size, BLOCK.size + wordmarkPad)).map { line ->
            val logo = LOGO.getOrNull(line)?.let { (glyphs, colors) -> colorize(glyphs, colors) }
                ?: " ".repeat(LOGO_WIDTH)
            val word = BLOCK.getOrNull(line - wordmarkPad)?.let { BRAND(it) } ?: ""
            (logo + LOGO_GAP + word).trimEnd()
        }
    }

    /** Applies the per-cell palette, coalescing runs of one color into one span. */
    private fun colorize(glyphs: String, colors: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < glyphs.length) {
            val key = colors[index]
            var end = index
            while (end < glyphs.length && colors[end] == key) end++
            val run = glyphs.substring(index, end)
            out.append(PALETTE[key]?.invoke(run) ?: run)
            index = end
        }
        return out.toString()
    }

    /**
     * Block characters need both a UTF-8 capable encoding and a font that has
     * them. Windows consoles older than Terminal render them as boxes, so the
     * ASCII form is used unless the session is a modern one.
     */
    internal fun supportsBlocks(): Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return true
        // Windows Terminal sets WT_SESSION; the legacy console does not.
        return System.getenv("WT_SESSION") != null
    }
}

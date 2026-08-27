package io.jmix.cli.wizard

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The wizard's opening banner: the Jmix mark next to the product name.
 * Renderings degrade with the terminal — mark plus wordmark, wordmark alone,
 * an ASCII wordmark for consoles without block glyphs, and finally one line.
 */
object Banner {

    // Sampled from the Jmix logo shipped in the project templates.
    private val PINK = TextColors.rgb("#F5145B")
    private val GREEN = TextColors.rgb("#22D68A")
    private val BLUE = TextColors.rgb("#35C9E0")
    private val ORANGE = TextColors.rgb("#FFAE2B")
    private val PLAY = TextColors.rgb("#909090")
    private val BRAND = TextColors.rgb("#3B7DDD")

    /** Four arcs around a play triangle, each segment in its own color. */
    private val MARK: List<List<Pair<String, TextStyle?>>> = listOf(
        listOf("  ▄▄▀▀▄▄  " to PINK),
        listOf("▄▀" to PINK, "      " to null, "▀▄" to GREEN),
        // A narrow triangle: U+25B6 renders double-width in some terminals and
        // would push the wordmark out of alignment.
        listOf("▌" to ORANGE, "   ▸  " to PLAY, "  ▐" to GREEN),
        listOf("▀▄" to ORANGE, "      " to null, "▄▀" to BLUE),
        listOf("  ▀▀▄▄▀▀  " to BLUE),
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
    private const val MARK_GAP = "  "

    /** Width of each mark row; they must all match or the wordmark shifts. */
    internal val markRowWidths: List<Int> get() = MARK.map { row -> row.sumOf { it.first.length } }

    private val MARK_WIDTH = markRowWidths.max()
    private val BLOCK_WIDTH = BLOCK.maxOf { it.length } + 2
    private val ASCII_WIDTH = ASCII.maxOf { it.length } + 2
    private val WITH_MARK_WIDTH = MARK_WIDTH + MARK_GAP.length + BLOCK_WIDTH

    fun print(terminal: Terminal, version: String? = null) {
        val width = terminal.size.width
        val subtitle = listOfNotNull("Jmix CLI", version).joinToString(" ")
        val blocks = supportsBlocks()

        terminal.println()
        when {
            blocks && width >= WITH_MARK_WIDTH -> markAndWordmark().forEach { terminal.println(it) }
            blocks && width >= BLOCK_WIDTH -> BLOCK.forEach { terminal.println(BRAND(it)) }
            width >= ASCII_WIDTH -> ASCII.forEach { terminal.println(BRAND(it)) }
            else -> {
                // Too narrow for any art: the name still has to appear.
                terminal.println(BRAND(bold(subtitle)))
                terminal.println()
                return
            }
        }
        // The tagline is dropped rather than wrapped onto a second line.
        val tagline = "$MARK_GAP·$MARK_GAP$TAGLINE"
        terminal.println(
            if (width >= subtitle.length + tagline.length) bold(subtitle) + gray(tagline) else bold(subtitle),
        )
        terminal.println()
    }

    /** The mark on the left, vertically centered against the wordmark. */
    private fun markAndWordmark(): List<String> {
        val topPad = (BLOCK.size - MARK.size).coerceAtLeast(0) / 2
        return BLOCK.indices.map { line ->
            val markLine = MARK.getOrNull(line - topPad)
            val mark = markLine?.joinToString("") { (text, color) -> color?.invoke(text) ?: text }
                ?: " ".repeat(MARK_WIDTH)
            mark + MARK_GAP + BRAND(BLOCK[line])
        }
    }

    /**
     * Block characters need both a UTF-8 capable encoding and a font that has
     * them. Windows consoles older than Terminal render them as boxes, so the
     * ASCII form is used unless the session is a modern one.
     */
    private fun supportsBlocks(): Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return true
        // Windows Terminal sets WT_SESSION; the legacy console does not.
        return System.getenv("WT_SESSION") != null
    }
}

package io.jmix.cli

import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.charset.Charset

/**
 * Switches an attached Windows console to the UTF-8 code page so the wizard's
 * markers and box-drawing glyphs survive the legacy OEM default, and restores
 * the previous code pages when the process exits (the console outlives it).
 */
internal object WindowsConsole {

    private const val UTF8_CODE_PAGE = 65001

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetConsoleCP(): Int
        fun GetConsoleOutputCP(): Int
        fun GetOEMCP(): Int
        fun SetConsoleCP(codePage: Int): Boolean
        fun SetConsoleOutputCP(codePage: Int): Boolean
    }

    /**
     * The charset of the OEM code page, the one a new console starts in. Java starts a child whose standard
     * streams are all redirected in a console of its own, so Windows PowerShell 5.1 writes redirected output in
     * it. Null off Windows, on failure, and for a code page without a charset in the runtime image (720 has none).
     */
    val oemCharset: Charset? by lazy {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return@lazy null
        runCatching { charsetOf(Native.load("kernel32", Kernel32::class.java).GetOEMCP()) }.getOrNull()
    }

    /** The charset of a Windows [codePage], named as the JDK names console code pages; null when there is none. */
    fun charsetOf(codePage: Int): Charset? = runCatching {
        Charset.forName(
            when (codePage) {
                UTF8_CODE_PAGE -> "UTF-8"
                // cp932 and its kin would be IBM charsets.
                in 874..950 -> "ms$codePage"
                else -> "cp$codePage"
            },
        )
    }.getOrNull()

    /**
     *  Enables UTF-8 console code pages; a no-op off Windows, without a console, or on failure.
     */
    fun enableUtf8() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return
        runCatching {
            val kernel32 = Native.load("kernel32", Kernel32::class.java)
            val previousInput = kernel32.GetConsoleCP()
            val previousOutput = kernel32.GetConsoleOutputCP()
            // Zero code pages mean no console is attached (std streams are pipes).
            if (previousInput == 0 || previousOutput == 0) return
            if (previousInput == UTF8_CODE_PAGE && previousOutput == UTF8_CODE_PAGE) return
            val inputChanged = kernel32.SetConsoleCP(UTF8_CODE_PAGE)
            val outputChanged = kernel32.SetConsoleOutputCP(UTF8_CODE_PAGE)
            if (inputChanged || outputChanged) {
                Runtime.getRuntime().addShutdownHook(Thread {
                    if (inputChanged) kernel32.SetConsoleCP(previousInput)
                    if (outputChanged) kernel32.SetConsoleOutputCP(previousOutput)
                })
            }
        }
    }
}

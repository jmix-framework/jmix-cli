package io.jmix.cli.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DownloadsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a copy over the byte cap fails and one exactly at the cap succeeds`() {
        val cap = 1L shl 20
        val target = dir.resolve("out.bin")

        copyWithProgress(ByteArrayInputStream(ByteArray(cap.toInt())), target, cap, { _, _ -> }, maxBytes = cap)
        assertEquals(cap, Files.size(target))

        val error = assertThrows<IOException> {
            copyWithProgress(ByteArrayInputStream(ByteArray(cap.toInt() + 1)), target, -1, { _, _ -> }, maxBytes = cap)
        }
        assertEquals("download exceeds 1 MiB", error.message)
    }
}

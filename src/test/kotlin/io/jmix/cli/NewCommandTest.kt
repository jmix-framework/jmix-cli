package io.jmix.cli

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NewCommandTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `project locations include current directory and named subdirectory`() {
        val currentDir = tempDir.resolve("work/mydir")
        val homeDir = tempDir.resolve("home")

        assertEquals(
            listOf(
                "Current directory" to currentDir,
                "Subdirectory" to currentDir.resolve("sample"),
                "IdeaProjects" to homeDir.resolve("IdeaProjects/sample"),
            ),
            projectLocationOptions("sample", currentDir, homeDir),
        )
    }
}

package io.jmix.cli.wizard

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PathCompleterTest {

    @TempDir
    lateinit var tempDir: Path

    private val sep = File.separator

    @BeforeEach
    fun fixture() {
        Files.createDirectories(tempDir.resolve("IdeaProjects"))
        Files.createDirectories(tempDir.resolve("IdeaSettings"))
        Files.createDirectories(tempDir.resolve("docs"))
        Files.createDirectories(tempDir.resolve(".hidden"))
        Files.createFile(tempDir.resolve("Ideas.txt"))
    }

    @Test
    fun `single match completes fully with a trailing separator`() {
        val completion = PathCompleter.complete("do", cwd = tempDir)
        assertEquals("docs$sep", completion.text)
        assertTrue(completion.candidates.isEmpty())
    }

    @Test
    fun `several matches extend to the longest common prefix`() {
        val completion = PathCompleter.complete("Id", cwd = tempDir)
        assertEquals("Idea", completion.text)
        assertTrue(completion.candidates.isEmpty())
    }

    @Test
    fun `no progress lists the candidates`() {
        val completion = PathCompleter.complete("Idea", cwd = tempDir)
        assertEquals("Idea", completion.text)
        assertEquals(listOf("IdeaProjects", "IdeaSettings"), completion.candidates)
    }

    @Test
    fun `completes inside a nested directory and ignores files`() {
        Files.createDirectories(tempDir.resolve("IdeaProjects/demo-app"))
        val completion = PathCompleter.complete("IdeaProjects${sep}de", cwd = tempDir)
        assertEquals("IdeaProjects${sep}demo-app$sep", completion.text)
    }

    @Test
    fun `expands tilde against the home directory`() {
        val completion = PathCompleter.complete("~/do", cwd = Path.of("/nowhere"), home = tempDir)
        assertEquals("~/docs$sep", completion.text)
    }

    @Test
    fun `bare tilde completes to the home prefix`() {
        assertEquals("~$sep", PathCompleter.complete("~", cwd = tempDir, home = tempDir).text)
    }

    @Test
    fun `hidden directories complete only from a dot segment`() {
        assertEquals(emptyList<String>(), PathCompleter.complete("", cwd = tempDir).candidates.filter { it.startsWith(".") })
        assertEquals(".hidden$sep", PathCompleter.complete(".h", cwd = tempDir).text)
    }

    @Test
    fun `unknown directory leaves the input unchanged`() {
        val completion = PathCompleter.complete("missing${sep}x", cwd = tempDir)
        assertEquals("missing${sep}x", completion.text)
        assertTrue(completion.candidates.isEmpty())
    }
}

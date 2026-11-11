package io.jmix.cli.command

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.wizard.Answer
import io.jmix.cli.wizard.Prompts
import io.jmix.cli.wizard.StatusReporter
import io.jmix.cli.wizard.requireValue
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectInteractionTest {
    @Test
    fun `the shared location picker selects the named subdirectory`(@TempDir dir: Path) {
        val recorder = TerminalRecorder(width = 120, height = 40)
        recorder.inputEvents += listOf(KeyboardEvent("ArrowDown"), KeyboardEvent("Enter"))
        val prompts = Prompts(Terminal(terminalInterface = recorder))
        assertEquals(dir.resolve("trial"), chooseProjectLocation(prompts, "trial", currentDir = dir).requireValue())
    }

    @Test
    fun `the shared location picker supports back from the list and custom path`(@TempDir dir: Path) {
        for (custom in listOf(false, true)) {
            val recorder = TerminalRecorder(width = 120, height = 40)
            if (custom) recorder.inputEvents += List(3) { KeyboardEvent("ArrowDown") } + KeyboardEvent("Enter")
            recorder.inputEvents += KeyboardEvent("Escape")
            assertEquals(Answer.Back, chooseProjectLocation(Prompts(Terminal(terminalInterface = recorder)), "trial", currentDir = dir))
        }
    }

    @Test
    fun `the numbered location picker accepts a custom path with spaces`(@TempDir dir: Path) {
        val target = dir.resolve("My trial")
        val stdin = System.`in`
        try {
            System.setIn(ByteArrayInputStream("4\n$target\n".toByteArray()))
            val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
            assertEquals(target, chooseProjectLocation(Prompts(Terminal(terminalInterface = recorder)), "trial").requireValue())
            assertTrue(recorder.output().contains("Other..."))
        } finally {
            System.setIn(stdin)
        }
    }

    @Test
    fun `the line input follow-up opens the module picker and stops on EOF after Back`(@TempDir dir: Path) {
        val modules = listOf(dir.resolve("demo/bpm"), dir.resolve("demo/webdav"))
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false)
        val terminal = Terminal(terminalInterface = recorder)
        val input = if (ProjectLauncher.ideaOpenCommand(dir) != null) "n\ny\n<\n" else "y\n<\n"
        val stdin = System.`in`
        try {
            System.setIn(ByteArrayInputStream(input.toByteArray()))
            ProjectActions(dir, null, terminal, Prompts(terminal), StatusReporter(terminal)).offer("bootRun", modules)
        } finally {
            System.setIn(stdin)
        }

        val output = recorder.output()
        assertTrue(output.contains("Run a demo module?"), output)
        assertTrue(output.contains("Select a demo module to run"), output)
        assertFalse(output.contains("Opening the project"), output)
    }

    @Test
    fun `the printed module command invokes its wrapper with the selected project directory`(@TempDir dir: Path) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val project = Files.createDirectories(dir.resolve("Ann's trials"))
        val module = Files.createDirectories(project.resolve("demo/Bob's app"))
        val wrapper = module.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
        assertTrue(wrapper.toFile().setExecutable(true))
        val process = ProcessBuilder("sh", "-c", projectRunCommand(project, module, "bootRun", os = "Linux"))
            .directory(project.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        assertEquals(0, process.waitFor(), output.toString())
        assertEquals(listOf("-p", "demo/Bob's app", "bootRun"), output)
    }

    @Test
    fun `PowerShell module commands use the batch wrapper and call operator for quoted paths`(@TempDir dir: Path) {
        val module = dir.resolve("demo/Bob's app")
        val command = projectRunCommand(dir, module, "bootRun", os = "Windows 11")
        assertTrue(command.startsWith("& './"), command)
        assertTrue(command.contains("Bob''s app"), command)
        assertTrue(command.contains("gradlew.bat' -p '"), command)
        assertTrue(command.endsWith(" bootRun"), command)
        assertEquals("./gradlew.bat bootRun", projectRunCommand(dir, dir, "bootRun", os = "Windows 11"))
    }
}

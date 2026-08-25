package io.jmix.cli.env

import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentToolkitInstallerTest {

    @Test
    fun `installer branch follows the jmix major version`() {
        assertEquals("v3", AgentToolkitInstaller.branch("3.0.1"))
        assertEquals("v2", AgentToolkitInstaller.branch("2.8.3"))
        assertEquals(
            "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit/v3/install.sh",
            AgentToolkitInstaller.installerUrl("3.0.1", os = "Linux"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit/v3/install.ps1",
            AgentToolkitInstaller.installerUrl("3.0.1", os = "Windows 11"),
        )
    }

    @Test
    fun `rejects versions that do not start with a numeric major`() {
        assertThrows(IOException::class.java) { AgentToolkitInstaller.branch("evil'; touch pwned #") }
        assertThrows(IOException::class.java) { AgentToolkitInstaller.branch("latest") }
    }

    @Test
    fun `runs the downloaded script as a file argument, never through a shell string`() {
        val script = Path.of("/tmp/toolkit.sh")
        assertEquals(listOf("bash", "/tmp/toolkit.sh"), AgentToolkitInstaller.command(script, os = "Linux"))
        assertEquals(
            listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "/tmp/toolkit.sh"),
            AgentToolkitInstaller.command(script, os = "Windows 11"),
        )
    }
}

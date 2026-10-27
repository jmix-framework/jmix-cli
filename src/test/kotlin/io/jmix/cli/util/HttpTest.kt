package io.jmix.cli.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpTest {

    @Test
    fun `requests identify the CLI`() {
        val agent = Http.request("http://127.0.0.1:1/").build().headers().firstValue("User-Agent").orElseThrow()
        assertTrue(agent.startsWith("jmix-cli/"), agent)
    }
}

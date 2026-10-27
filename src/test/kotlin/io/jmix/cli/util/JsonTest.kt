package io.jmix.cli.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `string reads string members and nothing else`() {
        val json = JsonParser.parseString("""{"s":"x","blank":" ","n":5,"b":true,"z":null,"a":["x"],"o":{"s":"x"}}""") as JsonObject
        assertEquals("x", json.string("s"))
        assertEquals(" ", json.string("blank"))
        for (name in listOf("n", "b", "z", "a", "o", "missing")) {
            assertNull(json.string(name), name)
        }
    }
}

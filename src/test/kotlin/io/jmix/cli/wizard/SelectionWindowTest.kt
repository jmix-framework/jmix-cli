package io.jmix.cli.wizard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectionWindowTest {

    @Test
    fun `unbounded window contains every entry`() {
        assertEquals(
            SelectionWindow(0, 17),
            selectionWindow(17, cursorIndex = 10, previousFirstIndex = 0, maxVisibleEntries = null),
        )
    }

    @Test
    fun `bounded window stays still while cursor remains visible`() {
        assertEquals(
            SelectionWindow(0, 6),
            selectionWindow(17, cursorIndex = 5, previousFirstIndex = 0, maxVisibleEntries = 6),
        )
    }

    @Test
    fun `bounded window follows cursor in both directions`() {
        val afterMovingDown = selectionWindow(
            17,
            cursorIndex = 6,
            previousFirstIndex = 0,
            maxVisibleEntries = 6,
        )
        assertEquals(SelectionWindow(1, 7), afterMovingDown)

        val afterMovingUp = selectionWindow(
            17,
            cursorIndex = 0,
            previousFirstIndex = afterMovingDown.firstIndex,
            maxVisibleEntries = 6,
        )
        assertEquals(SelectionWindow(0, 6), afterMovingUp)
    }

    @Test
    fun `bounded window reaches the last entry`() {
        assertEquals(
            SelectionWindow(11, 17),
            selectionWindow(17, cursorIndex = 16, previousFirstIndex = 10, maxVisibleEntries = 6),
        )
    }

    @Test
    fun `window never exceeds a short list`() {
        assertEquals(
            SelectionWindow(0, 3),
            selectionWindow(3, cursorIndex = 2, previousFirstIndex = 0, maxVisibleEntries = 6),
        )
    }
}

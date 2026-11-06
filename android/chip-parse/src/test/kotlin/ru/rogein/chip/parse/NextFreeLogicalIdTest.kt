package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NextFreeLogicalIdTest {
    @Test
    fun firstIdWhenEmpty() {
        assertEquals(1, nextFreeLogicalId(emptySet()))
    }

    @Test
    fun skipsUsedAndTyped() {
        assertEquals(3, nextFreeLogicalId(setOf(1, 2, 4)))
        assertEquals(2, nextFreeLogicalId(setOf(1, 3), 1))
        assertEquals(3, nextFreeLogicalId(setOf(1), 2))
    }
}

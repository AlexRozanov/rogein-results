package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfrChipMemoryTest {
    @Test
    fun logicalIdUsesSfrPacking() {
        assertArrayEquals(byteArrayOf(0x06, 0x03, 0, 0), SfrChipMemory.logicalIdBlock(606))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), SfrChipMemory.logicalIdBlock(200))
    }

    @Test
    fun clearTimeIsBcd() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x11, 0x42, 0x41),
            SfrChipMemory.clearTimeBlock(11, 42, 41),
        )
    }

    @Test
    fun clearPlanKeepsLogicalIdAndHidesOldPunchesFirst() {
        val plan = SfrChipMemory.clearPlan(pointerEnd = 8, hour = 9, minute = 1, second = 2)
        assertEquals(4, plan.first().first)
        assertArrayEquals(byteArrayOf(0x05, 0, 0, 0), plan.first().second)
        assertTrue(plan.any { it.first == 8 })
        assertFalse(plan.any { it.first == 3 })
        assertFalse(plan.any { it.first == 9 })
        val time = plan.first { it.first == 1 }.second
        assertArrayEquals(byteArrayOf(0x00, 0x09, 0x01, 0x02), time)
    }

    @Test
    fun clearPlanRespects64BlockChip() {
        val plan = SfrChipMemory.clearPlan(pointerEnd = null, hour = 10, minute = 0, second = 0, blockCount = 64)
        assertTrue(plan.any { it.first == 63 })
        assertFalse(plan.any { it.first >= 64 })
    }

    @Test
    fun nfcToolsWireOrderIsReversed() {
        val logical = byteArrayOf(0x06, 0x03, 0, 0)
        assertArrayEquals(logical, SfrChipMemory.toWire(logical, BlockOrder.Hid))
        assertArrayEquals(
            byteArrayOf(0, 0, 0x03, 0x06),
            SfrChipMemory.toWire(logical, BlockOrder.NfcTools),
        )
    }
}

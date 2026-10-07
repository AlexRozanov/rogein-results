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

    @Test
    fun punchBlockIsCpThenBcdTime() {
        assertArrayEquals(
            byteArrayOf(31, 0x10, 0x15, 0x45),
            SfrChipMemory.punchBlock(31, 10, 15, 45),
        )
    }

    @Test
    fun writeCoursePlanPutsPunchesAndPointerAfterClear() {
        val punches = listOf(
            Punch(CP_START, 10, 0, 0),
            Punch(31, 10, 0, 45),
            Punch(CP_FINISH, 10, 1, 30),
        )
        val plan = SfrChipMemory.writeCoursePlan(
            id = 8,
            punches = punches,
            oldPointerEnd = 8,
            hour = 9,
            minute = 1,
            second = 2,
        )
        assertEquals(4, plan.first().first)
        assertArrayEquals(byteArrayOf(0x05, 0, 0, 0), plan.first().second)
        assertEquals(4, plan.last().first)
        assertArrayEquals(byteArrayOf(7, 0, 0, 0), plan.last().second)
        val blocks = mutableMapOf<Int, ByteArray>()
        for ((index, data) in plan) {
            blocks[index] = data
        }
        assertArrayEquals(byteArrayOf(8, 0, 0, 0), blocks[3])
        assertArrayEquals(byteArrayOf(7, 0, 0, 0), blocks[4])
        assertArrayEquals(
            byteArrayOf(31, 0x10, 0x00, 0x45),
            blocks[6],
        )
    }

    @Test
    fun testCoursePlanCorrectAddsStartAndFinish() {
        assertEquals(
            listOf(CP_START, 31, 32, 33, CP_FINISH),
            TestCoursePlan.fullSequence(listOf(31, 32, 33)),
        )
        assertEquals(
            listOf(CP_START, 31, 32, CP_FINISH),
            TestCoursePlan.fullSequence(listOf(CP_START, 31, 32, CP_FINISH)),
        )
    }

    @Test
    fun testCoursePlanWrongSwapsOrSkips() {
        assertEquals(
            listOf(CP_START, 32, 31, 33, CP_FINISH),
            TestCoursePlan.wrongSequence(listOf(31, 32, 33)),
        )
        assertEquals(
            listOf(CP_START, CP_FINISH),
            TestCoursePlan.wrongSequence(listOf(31)),
        )
    }

    @Test
    fun writeCoursePlanRoundTripsThroughParser() {
        val punches = TestCoursePlan.punches(
            TestCoursePlan.fullSequence(listOf(31, 32)),
            hour = 11,
            minute = 0,
            second = 0,
        )
        val plan = SfrChipMemory.writeCoursePlan(
            id = 12,
            punches = punches,
            oldPointerEnd = null,
            hour = 10,
            minute = 59,
            second = 0,
            blockCount = 64,
        )
        val blocks = MutableList(64) { ByteArray(BLOCK_SIZE) }
        for ((index, data) in plan) {
            blocks[index] = data.copyOf()
        }
        val dump = SfrChipParser.interpret("uid", blocks, BlockOrder.Hid)
        assertEquals(12, dump.logicalId)
        assertEquals(punches, dump.punches)
        assertEquals(4 + punches.size, dump.pointerEnd)
    }
}

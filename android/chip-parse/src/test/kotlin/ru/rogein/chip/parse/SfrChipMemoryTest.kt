package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        assertTrue(plan.any { it.first == 9 })
        assertTrue(plan.any { it.first == BLOCK_COUNT - 1 })
        assertFalse(plan.any { it.first >= BLOCK_COUNT })
        assertArrayEquals(ByteArray(BLOCK_SIZE), plan.first { it.first == 9 }.second)
        assertArrayEquals(ByteArray(BLOCK_SIZE), plan.first { it.first == BLOCK_COUNT - 1 }.second)
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
    fun stationProgramWritesControlBlocks() {
        val plan = SfrChipMemory.stationProgramPlan(
            CP_START, 12, 0, 0, oldPointerEnd = 8, blockCount = 64,
        )
        val byBlock = plan.associate { it.first to it.second }
        assertArrayEquals(ByteArray(4), byBlock[3])
        assertArrayEquals(ByteArray(4), byBlock[0])
        assertNull(byBlock[7])
        assertNull(byBlock[63])
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00), byBlock[4])
        assertArrayEquals(byteArrayOf(CP_START.toByte(), 0x12, 0x00, 0x00), byBlock[5])
        assertArrayEquals(byteArrayOf(0x00, 0x25, 0x00, 0x00), byBlock[6])
        assertEquals(4, plan.first().first)
        assertTrue(plan.indexOfFirst { it.first == 4 } < plan.indexOfFirst { it.first == 3 })
        assertEquals(7, plan.size)
    }

    @Test
    fun stationControlWritesPunchNumber() {
        val plan = SfrChipMemory.stationProgramPlan(
            StationCommand.Control, 8, 15, 0, oldPointerEnd = 7, blockCount = 64, controlNumber = 31,
        )
        val byBlock = plan.associate { it.first to it.second }
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00), byBlock[4])
        assertArrayEquals(byteArrayOf(31, 0x08, 0x15, 0x00), byBlock[5])
        assertArrayEquals(byteArrayOf(0x00, 0x25, 0x00, 0x00), byBlock[6])
    }

    @Test
    fun stationServiceWritesOnlyModeBlock() {
        val plan = SfrChipMemory.stationProgramPlan(
            StationCommand.Battery, 12, 0, 0, oldPointerEnd = 8, blockCount = 64,
        )
        val byBlock = plan.associate { it.first to it.second }
        assertArrayEquals(ByteArray(4), byBlock[3])
        assertArrayEquals(STATION_BATTERY_BLOCK, byBlock[4])
        assertArrayEquals(ByteArray(4), byBlock[5])
        assertArrayEquals(ByteArray(4), byBlock[6])
        assertNull(byBlock[8])
        assertTrue(plan.indexOfFirst { it.first == 4 } < plan.indexOfFirst { it.first == 3 })
        val active = SfrChipMemory.stationProgramPlan(
            StationCommand.Active, 0, 0, 0, oldPointerEnd = 7, blockCount = 64,
        ).associate { it.first to it.second }
        assertArrayEquals(STATION_ACTIVE_BLOCK, active[4])
        val activeFull = SfrChipMemory.stationProgramPlan(
            StationCommand.Active, 0, 0, 0, oldPointerEnd = 7, blockCount = 64,
            activeSpeed = StationActiveSpeed.Full,
        ).associate { it.first to it.second }
        assertArrayEquals(stationActiveBlock(StationActiveSpeed.Full), activeFull[4])
        val activeTen = SfrChipMemory.stationProgramPlan(
            StationCommand.Active, 0, 0, 0, oldPointerEnd = 7, blockCount = 64,
            activeSpeed = StationActiveSpeed.Ten,
        ).associate { it.first to it.second }
        assertArrayEquals(stationActiveBlock(StationActiveSpeed.Ten), activeTen[4])
        val passive = SfrChipMemory.stationProgramPlan(
            StationCommand.Passive, 0, 0, 0, oldPointerEnd = 7, blockCount = 64,
        ).associate { it.first to it.second }
        assertArrayEquals(STATION_PASSIVE_BLOCK, passive[4])
        val activeAfterControl = SfrChipMemory.stationProgramPlan(
            StationCommand.Active, 0, 0, 0, oldPointerEnd = 7, blockCount = 64, controlNumber = 31,
        ).associate { it.first to it.second }
        assertArrayEquals(STATION_ACTIVE_BLOCK, activeAfterControl[4])
        assertArrayEquals(ByteArray(4), activeAfterControl[5])
        val passiveAfterControl = SfrChipMemory.stationProgramPlan(
            StationCommand.Passive, 0, 0, 0, oldPointerEnd = 7, blockCount = 64, controlNumber = 31,
        ).associate { it.first to it.second }
        assertArrayEquals(STATION_PASSIVE_BLOCK, passiveAfterControl[4])
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
    fun testCoursePlanClusterCorrectShufflesInsideCluster() {
        val controls = listOf(31, 32, 33, 34, 35)
        val randomOrder = listOf(false, true, true, true, false)
        val seq = TestCoursePlan.fullSequence(controls, randomOrder, random = kotlin.random.Random(7))
        assertEquals(CP_START, seq.first())
        assertEquals(CP_FINISH, seq.last())
        assertEquals(31, seq[1])
        assertEquals(35, seq[seq.lastIndex - 1])
        assertEquals(setOf(32, 33, 34), seq.subList(2, 5).toSet())
        assertEquals(
            controls.size + 2,
            courseTakenWithOrder(
                seq.filter { it != CP_START && it != CP_FINISH },
                controls,
                randomOrder,
            ),
        )
    }

    @Test
    fun testCoursePlanClusterWrongLeavesClusterEarly() {
        val controls = listOf(31, 32, 33, 34, 35)
        val randomOrder = listOf(false, true, true, true, false)
        val seq = TestCoursePlan.wrongSequence(controls, randomOrder, random = kotlin.random.Random(3))
        val punches = seq.filter { it != CP_START && it != CP_FINISH }
        assertTrue(punches.contains(35))
        // Ошибка грозди: взяли 35 до закрытия [32·33·34]
        val idx35 = punches.indexOf(35)
        val clusterHits = punches.take(idx35).count { it in setOf(32, 33, 34) }
        assertTrue(clusterHits < 3)
        assertTrue(
            courseTakenWithOrder(punches, controls, randomOrder) < controls.size,
        )
    }

    @Test
    fun testCoursePlanFormatControlsLabelShowsCluster() {
        assertEquals(
            "31 → [32·33·34] → 35",
            TestCoursePlan.formatControlsLabel(
                listOf(31, 32, 33, 34, 35),
                listOf(false, true, true, true, false),
            ),
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

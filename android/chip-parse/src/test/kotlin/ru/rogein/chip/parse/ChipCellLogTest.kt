package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChipCellLogTest {
    @Test
    fun issuedBlankChipHasNoWarnings() {
        val blocks = MutableList(64) { ByteArray(BLOCK_SIZE) }
        blocks[3] = byteArrayOf(12, 0, 0, 0)
        blocks[4] = byteArrayOf(5, 0, 0, 0)
        val log = ChipCellLog.fromRaw("uid", blocks, 64)
        assertEquals(12, log.logicalId)
        assertTrue(log.warnings.isEmpty())
        assertNull(log.hint)
        assertEquals("пусто", log.cells[5].label)
    }

    @Test
    fun reportsHolesAndBadTimeInsidePointer() {
        val blocks = MutableList(16) { ByteArray(BLOCK_SIZE) }
        blocks[3] = byteArrayOf(8, 0, 0, 0)
        blocks[4] = byteArrayOf(8, 0, 0, 0)
        blocks[5] = byteArrayOf(CP_START.toByte(), 0x10, 0x00, 0x00)
        blocks[7] = byteArrayOf(31, 0x99.toByte(), 0x00, 0x00)
        blocks[8] = byteArrayOf(CP_FINISH.toByte(), 0x10, 0x05, 0x00)
        val log = ChipCellLog.fromRaw("uid", blocks)
        assertTrue(log.hint!!.contains("дыры в ячейках (1)"))
        assertTrue(log.hint!!.contains("битое время (1)"))
        assertEquals("пусто", log.cells[6].label)
        assertEquals("битое время КП 31", log.cells[7].label)
        assertTrue(log.format().contains("блок  hex"))
    }

    @Test
    fun reportsGarbagePastPointer() {
        val blocks = MutableList(12) { ByteArray(BLOCK_SIZE) }
        blocks[3] = byteArrayOf(8, 0, 0, 0)
        blocks[4] = byteArrayOf(6, 0, 0, 0)
        blocks[5] = byteArrayOf(CP_START.toByte(), 0x10, 0x00, 0x00)
        blocks[6] = byteArrayOf(CP_FINISH.toByte(), 0x10, 0x05, 0x00)
        blocks[9] = byteArrayOf(45, 0x11, 0x00, 0x00)
        val log = ChipCellLog.fromRaw("uid", blocks)
        assertTrue(log.hint!!.contains("данные за указателем (1)"))
        assertTrue(log.cells[9].afterPointer)
        assertFalse(log.cells[6].afterPointer)
    }

    @Test
    fun finishRangeHexHintDoesNotNeedFullChip() {
        val blocks = MutableList(9) { ByteArray(BLOCK_SIZE) }
        blocks[3] = byteArrayOf(8, 0, 0, 0)
        blocks[4] = byteArrayOf(8, 0, 0, 0)
        blocks[5] = byteArrayOf(CP_START.toByte(), 0x10, 0x00, 0x00)
        blocks[8] = byteArrayOf(CP_FINISH.toByte(), 0x10, 0x05, 0x00)
        val hex = ChipCellLog.blocksToHex(blocks)
        val hint = ChipCellLog.hintFromRawHex(hex)
        assertTrue(hint!!.contains("дыры в ячейках"))
        assertFalse(hint.contains("за указателем"))
    }

    @Test
    fun fieldExportKeepsHolesVisible() {
        val raw = loadExport("/export.txt")
        val log = ChipCellLog.fromRaw("E0:02:24:01:CB:73:CD:9F", raw)
        assertEquals(606, log.logicalId)
        assertEquals("пусто", log.cells[5].label)
        assertTrue(log.cells[6].label.startsWith("КП 45"))
        assertTrue(log.format().contains("пусто"))
        assertTrue(ChipCellLog.hintFromRawHex(ChipCellLog.blocksToHex(raw))!!.contains("дыры"))
    }

    @Test
    fun stationActivationChipIsLabeledService() {
        val hid = stationBlocks(StationCommand.Active)
        val log = ChipCellLog.fromRaw("uid", hid, hid.size)
        assertTrue(log.isService)
        assertEquals(StationCommand.Active, log.stationRole?.command)
        assertTrue(log.format().contains("тип=служебный"))
        assertTrue(log.format().contains("карта включения активного режима станции"))
        assertEquals("режим: активный", log.cells[4].label)
        assertEquals("пусто", log.cells[5].label)
        assertNull(log.pointerEnd)
        assertNull(log.logicalId)
        assertNull(log.hint)
        val nfc = hid.map { it.reversed4() }
        val flipped = ChipCellLog.fromRaw("uid", nfc, nfc.size)
        assertEquals(StationCommand.Active, flipped.stationRole?.command)
        assertEquals("режим: активный", flipped.cells[4].label)
    }

    @Test
    fun stationProgrammerChipIsLabeledService() {
        val hid = stationBlocks(StationCommand.Control, 31)
        val log = ChipCellLog.fromRaw("uid", hid)
        assertTrue(log.kindLine!!.contains("программатор станции КП 31"))
        assertEquals("режим: программатор", log.cells[4].label)
        assertTrue(log.cells[5].label.startsWith("КП станции 31"))
        assertEquals("параметр станции", log.cells[6].label)
        assertTrue(log.warnings.isEmpty())
        assertEquals("—", log.format().lineSequence().first { it.startsWith("указатель=") }.substringAfter("="))
    }

    @Test
    fun athleteDumpIsNotServiceChip() {
        val raw = loadExport("/export.txt")
        val log = ChipCellLog.fromRaw("E0:02:24:01:CB:73:CD:9F", raw)
        assertNull(log.stationRole)
        assertFalse(log.format().contains("тип=служебный"))
        assertTrue(log.cells[4].label.startsWith("указатель"))
    }

    @Test
    fun stationServiceWarnsAboutLeftoverPunches() {
        val hid = stationBlocks(StationCommand.Battery).toMutableList()
        while (hid.size < 12) hid += ByteArray(BLOCK_SIZE)
        hid[9] = byteArrayOf(45, 0x11, 0x00, 0x00)
        val log = ChipCellLog.fromRaw("uid", hid)
        assertEquals(StationCommand.Battery, log.stationRole?.command)
        assertTrue(log.hint!!.contains("остались отметки участника (1)"))
    }
}

private fun stationBlocks(command: StationCommand, controlNumber: Int? = null, blockCount: Int = 16): List<ByteArray> {
    val hid = MutableList(blockCount) { ByteArray(BLOCK_SIZE) }
    val plan = SfrChipMemory.stationProgramPlan(
        command, 12, 0, 0, oldPointerEnd = 7, blockCount = blockCount, controlNumber = controlNumber,
    )
    for ((index, block) in plan) {
        if (index < hid.size) hid[index] = block
    }
    return hid
}

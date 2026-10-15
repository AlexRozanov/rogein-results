package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfrChipParserTest {
    @Test
    fun parsesNfcToolsExport() {
        val raw = loadExport("/export.txt")
        val dump = SfrChipParser.parse("E0:02:24:01:CB:73:CD:9F", raw)

        assertEquals(BlockOrder.NfcTools, dump.blockOrder)
        assertEquals(606, dump.logicalId)
        assertEquals("11:42:41", dump.clearedAt)
        assertEquals(27, dump.pointerEnd)
        assertEquals(22, dump.punches.size)
        assertEquals(45, dump.punches.first().cp)
        assertEquals("11:45:50", dump.punches.first().time)
        assertEquals(CP_FINISH, dump.punches.last().cp)
        assertEquals("12:39:00", dump.punches.last().time)
        assertEquals(100, dump.punches[dump.punches.size - 2].cp)
        assertTrue(dump.punches.zipWithNext().all { (a, b) ->
            a.hour * 3600 + a.minute * 60 + a.second <=
                b.hour * 3600 + b.minute * 60 + b.second
        })
    }

    @Test
    fun parses64BlockFieldDump() {
        val dump = SfrChipParser.parse("uid", loadExport("/field-64-error.txt"))
        assertEquals(64, dump.blockCount)
        assertEquals(BlockOrder.NfcTools, dump.blockOrder)
        assertEquals(81, dump.logicalId)
        assertEquals("11:47:17", dump.clearedAt)
        assertEquals(36, dump.pointerEnd)
        assertEquals(32, dump.punches.size)
        assertTrue(dump.punches.first().isStart)
        assertTrue(dump.punches.last().isFinish)
        assertEquals("11:51:59", dump.punches.first().time)
        assertEquals("12:55:12", dump.punches.last().time)
    }

    @Test
    fun parsesOther64BlockFieldDumps() {
        val second = SfrChipParser.parse("uid", loadExport("/field-64-error2.txt"))
        val third = SfrChipParser.parse("uid", loadExport("/field-64-error3.txt"))
        assertEquals(64, second.blockCount)
        assertEquals(20, second.logicalId)
        assertTrue(second.punches.first().isStart)
        assertTrue(second.punches.last().isFinish)
        assertEquals(64, third.blockCount)
        assertEquals(15, third.logicalId)
        assertTrue(third.punches.last().isFinish)
    }

    @Test
    fun parses128BlockFieldDump() {
        val dump = SfrChipParser.parse("uid", loadExport("/field-128-ok.txt"))
        assertEquals(128, dump.blockCount)
        assertEquals(BlockOrder.NfcTools, dump.blockOrder)
        assertEquals(98, dump.logicalId)
        assertEquals(36, dump.pointerEnd)
        assertEquals(32, dump.punches.size)
        assertTrue(dump.punches.last().isFinish)
    }

    @Test
    fun truncatedDumpStillYieldsPunchesBeforePointer() {
        val full = loadExport("/field-64-error.txt")
        val dump = SfrChipParser.parse("uid", full.take(10))
        assertEquals(10, dump.blockCount)
        assertEquals(36, dump.pointerEnd)
        assertEquals(81, dump.logicalId)
        assertTrue(dump.punches.isNotEmpty())
        assertTrue(dump.punches.first().isStart)
    }

    @Test
    fun finishRangeFromNfcToolsDumpMatchesFullParse() {
        val full = loadExport("/export.txt")
        val dumpFull = SfrChipParser.parse("uid", full)
        val end = SfrChipParser.maxPointerCandidate(full[4], full.size)
        assertEquals(27, end)
        assertEquals(BlockOrder.NfcTools, SfrChipParser.wireOrder(full[3], full[4], full.size))
        val sparse = MutableList(end + 1) { ByteArray(4) }
        sparse[3] = full[3]
        sparse[4] = full[4]
        for (i in 5..end) sparse[i] = full[i]
        val dump = SfrChipParser.parse("uid", sparse, declaredBlockCount = full.size)
        assertEquals(dumpFull.logicalId, dump.logicalId)
        assertEquals(dumpFull.punches, dump.punches)
        assertEquals(full.size, dump.blockCount)
        assertEquals(BlockOrder.NfcTools, dump.blockOrder)
    }

    @Test
    fun hidPointerUsesFirstByte() {
        val block4 = byteArrayOf(0x06, 0, 0, 0)
        val block3 = byteArrayOf(0x06, 0x03, 0, 0)
        assertEquals(6, SfrChipParser.maxPointerCandidate(block4, 64))
        assertEquals(BlockOrder.Hid, SfrChipParser.wireOrder(block3, block4, 64))
    }

    @Test
    fun hidOrderIsParsedWithoutReverse() {
        val hid = MutableList(BLOCK_COUNT) { ByteArray(4) }
        hid[1] = byteArrayOf(0x00, 0x11, 0x42, 0x41)
        hid[3] = byteArrayOf(0x06, 0x03, 0x00, 0x00)
        hid[4] = byteArrayOf(0x06, 0x00, 0x00, 0x00)
        hid[5] = byteArrayOf(241.toByte(), 0x10, 0x00, 0x00)
        hid[6] = byteArrayOf(240.toByte(), 0x10, 0x05, 0x00)
        val dump = SfrChipParser.interpret("uid", hid, BlockOrder.Hid)
        assertEquals(606, dump.logicalId)
        assertEquals("11:42:41", dump.clearedAt)
        assertEquals(2, dump.punches.size)
        assertTrue(dump.punches[0].isStart)
        assertTrue(dump.punches[1].isFinish)
    }

    @Test
    fun detectsStationProgrammerInHidAndNfcToolsOrder() {
        val roles = listOf(
            StationCommand.Start to CP_START,
            StationCommand.Finish to CP_FINISH,
            StationCommand.Clear to CP_CLEAR,
        )
        for ((command, cp) in roles) {
            val hid = stationChip(command)
            assertEquals(command, SfrChipParser.stationCommand(hid))
            assertEquals(cp, SfrChipParser.stationProgramCp(hid))
            assertNull(SfrChipParser.parse("uid", hid, declaredBlockCount = 64).logicalId)
            val nfc = hid.map { it.reversed4() }
            assertEquals(command, SfrChipParser.stationCommand(nfc))
        }
        assertEquals("старт", SfrChipParser.stationProgramTitle(CP_START))
        assertEquals("финиш", SfrChipParser.stationProgramTitle(CP_FINISH))
        assertEquals("очистка", SfrChipParser.stationProgramTitle(CP_CLEAR))
    }

    @Test
    fun detectsOrdinaryControlStation() {
        val hid = stationChip(StationCommand.Control, 31)
        assertEquals(StationCommand.Control, SfrChipParser.stationCommand(hid))
        assertEquals(31, SfrChipParser.stationProgramCp(hid))
        val nfc = hid.map { it.reversed4() }
        assertEquals(31, SfrChipParser.stationProgramCp(nfc))
    }

    @Test
    fun detectsStationServiceCardsInHidAndNfcToolsOrder() {
        for (command in listOf(StationCommand.Active, StationCommand.Passive, StationCommand.Battery)) {
            val hid = stationChip(command)
            assertEquals(command, SfrChipParser.stationCommand(hid))
            assertNull(SfrChipParser.stationProgramCp(hid))
            val nfc = hid.map { it.reversed4() }
            assertEquals(command, SfrChipParser.stationCommand(nfc))
        }
    }

    @Test
    fun athleteDumpIsNotStationProgrammer() {
        assertNull(SfrChipParser.stationCommand(loadExport("/export.txt")))
        val empty = MutableList(7) { ByteArray(4) }
        assertNull(SfrChipParser.stationCommand(empty))
        assertNull(SfrChipParser.parse("uid", empty, declaredBlockCount = 64).logicalId)
    }
}

private fun stationChip(command: StationCommand, controlNumber: Int? = null): List<ByteArray> {
    val hid = MutableList(7) { ByteArray(4) }
    val plan = SfrChipMemory.stationProgramPlan(
        command, 12, 0, 0, oldPointerEnd = 7, blockCount = 64, controlNumber = controlNumber,
    )
    for ((index, block) in plan) {
        if (index < hid.size) hid[index] = block
    }
    return hid
}

internal fun loadExport(resource: String): List<ByteArray> {
    val text = SfrChipParserTest::class.java.getResource(resource)?.readText()
        ?: error("missing $resource")
    val byIndex = sortedMapOf<Int, ByteArray>()
    val re = Regex("""\[\s*([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})\s*]\s*Сектор\s*([0-9A-Fa-f]+)""")
    for (line in text.lineSequence()) {
        val m = re.find(line) ?: continue
        val idx = m.groupValues[5].toInt(16)
        byIndex[idx] = byteArrayOf(
            m.groupValues[1].toInt(16).toByte(),
            m.groupValues[2].toInt(16).toByte(),
            m.groupValues[3].toInt(16).toByte(),
            m.groupValues[4].toInt(16).toByte(),
        )
    }
    val count = (byIndex.keys.maxOrNull() ?: -1) + 1
    require(count >= MIN_BLOCK_COUNT) { "export $resource has $count blocks" }
    return List(count) { byIndex[it] ?: ByteArray(4) }
}

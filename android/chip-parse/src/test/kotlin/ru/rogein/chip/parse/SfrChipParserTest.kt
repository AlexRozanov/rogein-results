package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
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

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
    val blocks = MutableList(BLOCK_COUNT) { ByteArray(4) }
    val re = Regex("""\[\s*([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})\s*]\s*Сектор\s*([0-9A-Fa-f]+)""")
    for (line in text.lineSequence()) {
        val m = re.find(line) ?: continue
        val idx = m.groupValues[5].toInt(16)
        blocks[idx] = byteArrayOf(
            m.groupValues[1].toInt(16).toByte(),
            m.groupValues[2].toInt(16).toByte(),
            m.groupValues[3].toInt(16).toByte(),
            m.groupValues[4].toInt(16).toByte(),
        )
    }
    return blocks
}

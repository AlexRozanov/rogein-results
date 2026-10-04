package ru.rogein.chip.parse

object SfrChipParser {
    fun parse(uid: String, rawBlocks: List<ByteArray>): ChipDump {
        require(rawBlocks.size in MIN_BLOCK_COUNT..BLOCK_COUNT) {
            "expected $MIN_BLOCK_COUNT…$BLOCK_COUNT blocks, got ${rawBlocks.size}"
        }
        rawBlocks.forEachIndexed { i, block ->
            require(block.size == BLOCK_SIZE) { "block $i size ${block.size}" }
        }
        val hid = interpret(uid, rawBlocks, BlockOrder.Hid)
        val nfc = interpret(uid, rawBlocks.map { it.reversed4() }, BlockOrder.NfcTools)
        return listOf(hid, nfc).maxBy(::score)
    }

    internal fun interpret(
        uid: String,
        blocks: List<ByteArray>,
        order: BlockOrder,
    ): ChipDump {
        val clearedAt = blocks.getOrNull(1)?.let(::readClearTime)
        val logicalId = blocks.getOrNull(3)?.let(::readLogicalId)
        val pointerEnd = blocks.getOrNull(4)?.let { it[0].toInt() and 0xFF }
        val punches = mutableListOf<Punch>()
        val last = blocks.lastIndex
        val end = pointerEnd?.takeIf { it >= 5 }?.coerceAtMost(last)
        if (end != null) {
            for (i in 5..end) {
                val punch = readPunch(blocks[i]) ?: continue
                punches += punch
            }
        }
        return ChipDump(
            uid = uid,
            logicalId = logicalId,
            clearedAt = clearedAt,
            punches = punches,
            blockOrder = order,
            pointerEnd = pointerEnd,
            blockCount = blocks.size,
        )
    }

    private fun score(dump: ChipDump): Int {
        var s = 0
        val end = dump.pointerEnd
        if (end != null && end in 5 until dump.blockCount) s += 10
        s += dump.punches.size
        if (dump.logicalId != null) s += 5
        if (dump.clearedAt != null) s += 2
        if (dump.punches.lastOrNull()?.isFinish == true) s += 4
        if (timesAreMonotonic(dump.punches)) s += 8
        return s
    }

    private fun timesAreMonotonic(punches: List<Punch>): Boolean {
        if (punches.size < 2) return true
        var prev = -1
        for (p in punches) {
            val sec = p.hour * 3600 + p.minute * 60 + p.second
            if (sec < prev) return false
            prev = sec
        }
        return true
    }

    private fun readClearTime(block: ByteArray): String? {
        val h = hexDecimal(block[1]) ?: return null
        val m = hexDecimal(block[2]) ?: return null
        val s = hexDecimal(block[3]) ?: return null
        if (h > 23 || m > 59 || s > 59) return null
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun readLogicalId(block: ByteArray): Int? {
        val low = block[0].toInt() and 0xFF
        val mid = block[1].toInt() and 0xFF
        val id = low + mid * 200
        if (id <= 0 || id > MAX_LOGICAL_ID) return null
        return id
    }

    private fun readPunch(block: ByteArray): Punch? {
        val cp = block[0].toInt() and 0xFF
        if (cp == 0) return null
        val h = hexDecimal(block[1]) ?: return null
        val m = hexDecimal(block[2]) ?: return null
        val s = hexDecimal(block[3]) ?: return null
        if (h > 23 || m > 59 || s > 59) return null
        return Punch(cp = cp, hour = h, minute = m, second = s)
    }

    /** Байт 0x45 хранится как цифры «45», не как число 69. */
    internal fun hexDecimal(b: Byte): Int? {
        val v = b.toInt() and 0xFF
        val hi = v shr 4
        val lo = v and 0x0F
        if (hi > 9 || lo > 9) return null
        return hi * 10 + lo
    }
}

internal fun ByteArray.reversed4(): ByteArray {
    require(size == 4)
    return byteArrayOf(this[3], this[2], this[1], this[0])
}

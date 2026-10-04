package ru.rogein.chip.parse

object SfrChipMemory {
    fun logicalIdBlock(id: Int): ByteArray {
        require(id in 1..MAX_LOGICAL_ID) { "логический номер от 1 до $MAX_LOGICAL_ID" }
        return byteArrayOf((id % 200).toByte(), (id / 200).toByte(), 0, 0)
    }

    fun clearTimeBlock(hour: Int, minute: Int, second: Int): ByteArray {
        require(hour in 0..23 && minute in 0..59 && second in 0..59)
        return byteArrayOf(0, bcd(hour), bcd(minute), bcd(second))
    }

    /** Порядок как в SFR basic: сначала прячем старые отметки, потом затираем их. Блок 3 не трогаем. */
    fun clearPlan(
        pointerEnd: Int?,
        hour: Int,
        minute: Int,
        second: Int,
        blockCount: Int = BLOCK_COUNT,
    ): List<Pair<Int, ByteArray>> {
        require(blockCount in MIN_BLOCK_COUNT..BLOCK_COUNT) { "blockCount $blockCount" }
        val zeros = ByteArray(BLOCK_SIZE)
        val lastWritable = blockCount - 1
        val lastPunch = when (pointerEnd) {
            null -> lastWritable
            else -> pointerEnd.coerceIn(5, lastWritable)
        }
        val plan = ArrayList<Pair<Int, ByteArray>>(lastPunch)
        plan += 4 to byteArrayOf(0x05, 0, 0, 0)
        for (block in 5..lastPunch) {
            plan += block to zeros.copyOf()
        }
        plan += 1 to clearTimeBlock(hour, minute, second)
        plan += 0 to zeros.copyOf()
        plan += 2 to zeros.copyOf()
        return plan
    }

    fun toWire(logical: ByteArray, order: BlockOrder): ByteArray {
        require(logical.size == BLOCK_SIZE)
        return if (order == BlockOrder.NfcTools) logical.reversed4() else logical.copyOf()
    }

    private fun bcd(value: Int): Byte = (((value / 10) shl 4) or (value % 10)).toByte()
}

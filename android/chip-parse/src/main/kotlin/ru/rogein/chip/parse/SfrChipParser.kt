package ru.rogein.chip.parse

object SfrChipParser {
    fun parse(uid: String, rawBlocks: List<ByteArray>, declaredBlockCount: Int? = null): ChipDump {
        require(rawBlocks.size in MIN_BLOCK_COUNT..BLOCK_COUNT) {
            "expected $MIN_BLOCK_COUNT…$BLOCK_COUNT blocks, got ${rawBlocks.size}"
        }
        rawBlocks.forEachIndexed { i, block ->
            require(block.size == BLOCK_SIZE) { "block $i size ${block.size}" }
        }
        val count = (declaredBlockCount ?: rawBlocks.size).coerceIn(MIN_BLOCK_COUNT, BLOCK_COUNT)
        val hid = interpret(uid, rawBlocks, BlockOrder.Hid, count)
        val nfc = interpret(uid, rawBlocks.map { it.reversed4() }, BlockOrder.NfcTools, count)
        return listOf(hid, nfc).maxBy(::score)
    }

    /**
     * Верхняя граница чтения 5…end: больший из двух кандидатов указателя
     * (HID — байт 0, NFC Tools — байт 3), если он в диапазоне чипа.
     */
    fun maxPointerCandidate(block4: ByteArray, blockCount: Int): Int {
        require(block4.size == BLOCK_SIZE)
        val last = (blockCount - 1).coerceAtLeast(4)
        val hid = block4[0].toInt() and 0xFF
        val nfc = block4[3].toInt() and 0xFF
        var end = 4
        if (hid in 5..last) end = maxOf(end, hid)
        if (nfc in 5..last) end = maxOf(end, nfc)
        return end
    }

    fun wireOrder(block3: ByteArray, block4: ByteArray, blockCount: Int): BlockOrder {
        require(block3.size == BLOCK_SIZE && block4.size == BLOCK_SIZE)
        val last = (blockCount - 1).coerceAtLeast(4)
        val hidEnd = (block4[0].toInt() and 0xFF).takeIf { it in 5..last }
        val nfcEnd = (block4[3].toInt() and 0xFF).takeIf { it in 5..last }
        val hidId = readLogicalId(block3)
        val nfcId = readLogicalId(block3.reversed4())
        return when {
            hidEnd != null && nfcEnd == null -> BlockOrder.Hid
            nfcEnd != null && hidEnd == null -> BlockOrder.NfcTools
            hidId != null && nfcId == null -> BlockOrder.Hid
            nfcId != null && hidId == null -> BlockOrder.NfcTools
            block4[1].toInt() == 0 && block4[2].toInt() == 0 && block4[3].toInt() == 0 -> BlockOrder.Hid
            block4[0].toInt() == 0 && block4[1].toInt() == 0 && block4[2].toInt() == 0 -> BlockOrder.NfcTools
            else -> BlockOrder.Hid
        }
    }

    internal fun interpret(
        uid: String,
        blocks: List<ByteArray>,
        order: BlockOrder,
        declaredBlockCount: Int = blocks.size,
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
            blockCount = declaredBlockCount.coerceIn(MIN_BLOCK_COUNT, BLOCK_COUNT),
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

    fun stationRole(blocks: List<ByteArray>): StationRole? = orientedStation(blocks)?.role

    fun stationByteOrder(blocks: List<ByteArray>): BlockOrder? =
        orientedStation(blocks)?.let { if (it.flipped) BlockOrder.NfcTools else BlockOrder.Hid }

    fun stationCommand(blocks: List<ByteArray>): StationCommand? = stationRole(blocks)?.command

    fun stationProgramCp(blocks: List<ByteArray>): Int? = stationRole(blocks)?.cp

    /** Режим, уже записанный на чипе: карта станции или чип участника. Ничего не пишет. */
    fun describeChipMode(blocks: List<ByteArray>): String {
        val found = orientedStation(blocks) ?: return describeParticipantChip(blocks)
        return formatStationMode(found.role, found.block5)
    }

    /**
     * Активный, пассивный и батарея живут только в блоке 4.
     * Если он уже перезаписан, старые блоки 5–6 с номером КП не считаются текущим режимом,
     * даже когда другой порядок байт ещё похож на программатор.
     */
    private fun orientedStation(blocks: List<ByteArray>): OrientedStation? {
        val block4 = blocks.getOrNull(4) ?: return null
        val block5 = blocks.getOrNull(5)
        val block6 = blocks.getOrNull(6)
        val direct = matchStation(block4, block5, block6)
        val flippedBlock5 = block5?.reversed4()
        val flipped = matchStation(block4.reversed4(), flippedBlock5, block6?.reversed4())
        val useFlipped = when {
            direct == null -> flipped != null
            flipped == null -> false
            flipped.command.isService && !direct.command.isService -> true
            else -> false
        }
        val role = (if (useFlipped) flipped else direct) ?: return null
        return OrientedStation(role, if (useFlipped) flippedBlock5 else block5, useFlipped)
    }

    private fun formatStationMode(role: StationRole, logicalBlock5: ByteArray?): String {
        val label = stationCommandLabel(role.command, role.cp)
        val hasClock = role.command == StationCommand.Control ||
            role.command == StationCommand.Start ||
            role.command == StationCommand.Finish ||
            role.command == StationCommand.Clear
        val clock = if (hasClock) logicalBlock5?.let(::readClearTime) else null
        return if (clock != null) "На чипе: $label. Время на карте $clock." else "На чипе: $label."
    }

    private fun describeParticipantChip(blocks: List<ByteArray>): String {
        val block3 = blocks.getOrNull(3)
        val id = block3?.let(::readLogicalId) ?: block3?.reversed4()?.let(::readLogicalId)
        if (id != null) return "Чип участника № $id. Это не карта станции."
        return unrecognizedChip()
    }

    private fun unrecognizedChip(): String =
        "Режим чипа не распознан. Это не карта станции."

    fun stationCommandLabel(command: StationCommand, cp: Int? = command.controlCp): String = when (command) {
        StationCommand.Control -> "программатор станции КП ${cp ?: "—"}"
        StationCommand.Start -> "программатор станции «старт» (КП $CP_START)"
        StationCommand.Finish -> "программатор станции «финиш» (КП $CP_FINISH)"
        StationCommand.Clear -> "программатор станции «очистка» (КП $CP_CLEAR)"
        StationCommand.Active -> "карта включения активного режима станции"
        StationCommand.Passive -> "карта пассивного режима станции"
        StationCommand.Battery -> "карта сброса и проверки батареи станции"
    }

    fun stationProgramTitle(cp: Int): String? = when (cp) {
        CP_START -> "старт"
        CP_FINISH -> "финиш"
        CP_CLEAR -> "очистка"
        else -> null
    }

    private data class OrientedStation(val role: StationRole, val block5: ByteArray?, val flipped: Boolean)

    private fun matchStation(block4: ByteArray, block5: ByteArray?, block6: ByteArray?): StationRole? {
        if (block4.contentEquals(STATION_ACTIVE_BLOCK)) {
            return StationRole(StationCommand.Active, null)
        }
        if (block4.contentEquals(STATION_PASSIVE_BLOCK)) {
            return StationRole(StationCommand.Passive, null)
        }
        if (block4.contentEquals(STATION_BATTERY_BLOCK)) {
            return StationRole(StationCommand.Battery, null)
        }
        if (block5 == null) return null
        if (!block4.contentEquals(STATION_MODE_BLOCK)) return null
        if (block6 != null && !block6.contentEquals(STATION_PARAM_BLOCK)) return null
        val cp = block5[0].toInt() and 0xFF
        if (cp == 0) return null
        val command = StationCommand.fromPunchCp(cp)
        return StationRole(command, cp)
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

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

    /**
     * Программатор станции: как в SFR basic, всё лишнее (номер, время очистки, отметки) — нули.
     * Иначе финиш увидит старый логический номер.
     * Старт/финиш/очистка/КП: блоки 4–6. Активный/пассивный/батарея: только блок 4, 5–6 обнуляются.
     */
    fun stationProgramPlan(
        command: StationCommand,
        hour: Int,
        minute: Int,
        second: Int,
        oldPointerEnd: Int?,
        blockCount: Int = BLOCK_COUNT,
        controlNumber: Int? = null,
    ): List<Pair<Int, ByteArray>> {
        require(blockCount in MIN_BLOCK_COUNT..BLOCK_COUNT) { "blockCount $blockCount" }
        val zeros = ByteArray(BLOCK_SIZE)
        val lastWritable = blockCount - 1
        val lastPunch = when (oldPointerEnd) {
            null -> lastWritable
            in 5..lastWritable -> oldPointerEnd
            else -> lastWritable
        }
        val plan = ArrayList<Pair<Int, ByteArray>>(lastPunch + 4)
        plan += 3 to zeros.copyOf()
        plan += 0 to zeros.copyOf()
        plan += 1 to zeros.copyOf()
        plan += 2 to zeros.copyOf()
        for (block in 7..lastPunch) {
            plan += block to zeros.copyOf()
        }
        val cp = command.controlCp ?: controlNumber?.also(::requireStationControlNumber)
        if (cp != null) {
            plan += 4 to STATION_MODE_BLOCK.copyOf()
            plan += 5 to punchBlock(cp, hour, minute, second)
            plan += 6 to STATION_PARAM_BLOCK.copyOf()
        } else {
            require(command.controlCp == null && command != StationCommand.Control) {
                "для КП нужен номер 1…255, кроме $CP_START, $CP_FINISH, $CP_CLEAR"
            }
            plan += 4 to command.modeBlock.copyOf()
            plan += 5 to zeros.copyOf()
            plan += 6 to zeros.copyOf()
        }
        return plan
    }

    fun stationProgramPlan(
        cp: Int,
        hour: Int,
        minute: Int,
        second: Int,
        oldPointerEnd: Int?,
        blockCount: Int = BLOCK_COUNT,
    ): List<Pair<Int, ByteArray>> {
        val command = StationCommand.fromPunchCp(cp)
        return stationProgramPlan(
            command,
            hour,
            minute,
            second,
            oldPointerEnd,
            blockCount,
            controlNumber = cp.takeIf { command == StationCommand.Control },
        )
    }

    fun requireStationControlNumber(cp: Int) {
        require(cp in 1..255 && cp != CP_START && cp != CP_FINISH && cp != CP_CLEAR) {
            "номер КП станции 1…255, кроме старта $CP_START, финиша $CP_FINISH и очистки $CP_CLEAR"
        }
    }

    fun punchBlock(cp: Int, hour: Int, minute: Int, second: Int): ByteArray {
        require(cp in 1..255) { "номер КП от 1 до 255" }
        require(hour in 0..23 && minute in 0..59 && second in 0..59)
        return byteArrayOf(cp.toByte(), bcd(hour), bcd(minute), bcd(second))
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

    /** Очистка, номер, отметки, затем указатель на последнюю отметку. */
    fun writeCoursePlan(
        id: Int,
        punches: List<Punch>,
        oldPointerEnd: Int?,
        hour: Int,
        minute: Int,
        second: Int,
        blockCount: Int = BLOCK_COUNT,
    ): List<Pair<Int, ByteArray>> {
        require(punches.isNotEmpty()) { "нет отметок для записи" }
        val lastWritable = blockCount - 1
        val newEnd = 4 + punches.size
        require(newEnd <= lastWritable) {
            "слишком много отметок для чипа: ${punches.size}"
        }
        val plan = clearPlan(oldPointerEnd, hour, minute, second, blockCount).toMutableList()
        plan += 3 to logicalIdBlock(id)
        punches.forEachIndexed { index, punch ->
            plan += (5 + index) to punchBlock(punch.cp, punch.hour, punch.minute, punch.second)
        }
        plan += 4 to byteArrayOf(newEnd.toByte(), 0, 0, 0)
        return plan
    }

    fun toWire(logical: ByteArray, order: BlockOrder): ByteArray {
        require(logical.size == BLOCK_SIZE)
        return if (order == BlockOrder.NfcTools) logical.reversed4() else logical.copyOf()
    }

    private fun bcd(value: Int): Byte = (((value / 10) shl 4) or (value % 10)).toByte()
}

object TestCoursePlan {
    fun fullSequence(controls: List<Int>): List<Int> {
        val inner = controls.filter { it != CP_START && it != CP_FINISH }
        require(inner.isNotEmpty()) { "у дистанции нет порядка КП" }
        return listOf(CP_START) + inner + listOf(CP_FINISH)
    }

    /** Не проходит проверку порядка: меняет два КП местами или пропускает единственный. */
    fun wrongSequence(controls: List<Int>): List<Int> {
        val seq = fullSequence(controls).toMutableList()
        val inner = seq.indices.filter { seq[it] != CP_START && seq[it] != CP_FINISH }
        when {
            inner.size >= 2 -> {
                val tmp = seq[inner[0]]
                seq[inner[0]] = seq[inner[1]]
                seq[inner[1]] = tmp
            }
            inner.size == 1 -> seq.removeAt(inner[0])
            else -> error("у дистанции нет порядка КП")
        }
        return seq
    }

    fun punches(cps: List<Int>, hour: Int, minute: Int, second: Int, stepSeconds: Int = 45): List<Punch> {
        var t = hour * 3600 + minute * 60 + second
        return cps.map { cp ->
            val h = ((t / 3600) % 24 + 24) % 24
            val m = ((t % 3600) / 60 + 60) % 60
            val s = ((t % 60) + 60) % 60
            t += stepSeconds
            Punch(cp, h, m, s)
        }
    }
}

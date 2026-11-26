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
     * Программатор станции: блоки 4–6 (режим/КП/время) и обнуление заголовка 0–3.
     * Хвост с отметками участника не трогаем — полная очистка 64–128 блоков
     * не успевала за 3 с отсчёта, и чип приходилось держать на телефоне до нуля.
     * Время на блоке 5 = момент «ноль» отсчёта (обычно now+3 с).
     */
    @Suppress("UNUSED_PARAMETER")
    fun stationProgramPlan(
        command: StationCommand,
        hour: Int,
        minute: Int,
        second: Int,
        oldPointerEnd: Int?,
        blockCount: Int = BLOCK_COUNT,
        controlNumber: Int? = null,
        activeSpeed: StationActiveSpeed = StationActiveSpeed.DEFAULT,
    ): List<Pair<Int, ByteArray>> {
        require(blockCount in MIN_BLOCK_COUNT..BLOCK_COUNT) { "blockCount $blockCount" }
        val zeros = ByteArray(BLOCK_SIZE)
        val cp = when (command) {
            StationCommand.Control -> controlNumber?.also(::requireStationControlNumber)
            else -> command.controlCp
        }
        val plan = ArrayList<Pair<Int, ByteArray>>(8)
        // Сначала режим/КП/время — чип уже программатор, даже если запись оборвётся.
        if (cp != null) {
            plan += 4 to STATION_MODE_BLOCK.copyOf()
            plan += 5 to punchBlock(cp, hour, minute, second)
            plan += 6 to STATION_PARAM_BLOCK.copyOf()
        } else {
            require(command.controlCp == null && command != StationCommand.Control) {
                "для КП нужен номер 1…255, кроме $CP_START, $CP_FINISH, $CP_CLEAR"
            }
            val mode = if (command == StationCommand.Active) {
                stationActiveBlock(activeSpeed)
            } else {
                command.modeBlock.copyOf()
            }
            plan += 4 to mode
            plan += 5 to zeros.copyOf()
            plan += 6 to zeros.copyOf()
        }
        plan += 3 to zeros.copyOf()
        plan += 0 to zeros.copyOf()
        plan += 1 to zeros.copyOf()
        plan += 2 to zeros.copyOf()
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

    /**
     * Порядок как в SFR basic: сначала прячем старые отметки указателем, потом затираем все ячейки до конца чипа.
     * [pointerEnd] оставляем в сигнатуре: выдача/очистка всё равно нулят хвост, иначе при чтении можно подхватить мусор.
     * Блок 3 не трогаем.
     */
    @Suppress("UNUSED_PARAMETER")
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
        val plan = ArrayList<Pair<Int, ByteArray>>(lastWritable)
        plan += 4 to byteArrayOf(0x05, 0, 0, 0)
        for (block in 5..lastWritable) {
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
    val defaultRogainePool = listOf(31, 32, 33, 41, 42, 43, 51, 52, 53, 61, 62, 71, 81, 91)

    fun randomRogaineSequence(
        startCp: Int,
        finishCp: Int,
        pool: List<Int> = defaultRogainePool,
        count: Int = 8,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): List<Int> {
        val start = if (startCp > 0) startCp else CP_START
        val finish = if (finishCp > 0) finishCp else CP_FINISH
        val inner = pool.filter { it > 0 && it != start && it != finish && it != CP_CLEAR }.distinct()
        val source = if (inner.isEmpty()) defaultRogainePool.filter { it != start && it != finish } else inner
        val take = source.shuffled(random).take(count.coerceIn(1, source.size.coerceAtLeast(1)))
        return listOf(start) + take + listOf(finish)
    }

    private fun innerControls(controls: List<Int>): List<Int> =
        controls.filter { it != CP_START && it != CP_FINISH }

    private fun innerRandomOrder(controls: List<Int>, randomOrder: List<Int>): List<Int> {
        val groups = parseClusterGroups(randomOrder, controls.size)
        return controls.mapIndexedNotNull { index, cp ->
            if (cp == CP_START || cp == CP_FINISH) null else groups[index]
        }
    }

    fun fullSequence(
        controls: List<Int>,
        randomOrder: List<Int> = emptyList(),
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): List<Int> {
        val inner = innerControls(controls)
        require(inner.isNotEmpty()) { "у дистанции нет порядка КП" }
        val groups = innerRandomOrder(controls, randomOrder)
        val path = ArrayList<Int>(inner.size)
        for (seg in courseSegmentsFromGroups(inner, groups)) {
            when (seg) {
                is CourseSegment.Fixed -> path += seg.cp
                is CourseSegment.Cluster -> path += seg.cps.shuffled(random)
            }
        }
        return listOf(CP_START) + path + listOf(CP_FINISH)
    }

    /**
     * Не проходит проверку порядка.
     * Если есть гроздь — ошибка именно в ней: переход на следующий обязательный КП,
     * не закрыв гроздь (или пропуск КП внутри грозди).
     * Иначе — как раньше: обмен двух КП или пропуск единственного.
     */
    fun wrongSequence(
        controls: List<Int>,
        randomOrder: List<Int> = emptyList(),
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): List<Int> {
        val inner = innerControls(controls)
        require(inner.isNotEmpty()) { "у дистанции нет порядка КП" }
        val groups = innerRandomOrder(controls, randomOrder)
        val segments = courseSegmentsFromGroups(inner, groups)
        val clusterIdx = segments.indexOfFirst { it is CourseSegment.Cluster && (it as CourseSegment.Cluster).cps.size >= 1 }
        if (clusterIdx >= 0) {
            return wrongClusterSequence(segments, clusterIdx, random)
        }
        val seq = (listOf(CP_START) + inner + listOf(CP_FINISH)).toMutableList()
        val idxs = seq.indices.filter { seq[it] != CP_START && seq[it] != CP_FINISH }
        when {
            idxs.size >= 2 -> {
                val tmp = seq[idxs[0]]
                seq[idxs[0]] = seq[idxs[1]]
                seq[idxs[1]] = tmp
            }
            idxs.size == 1 -> seq.removeAt(idxs[0])
            else -> error("у дистанции нет порядка КП")
        }
        return seq
    }

    private fun wrongClusterSequence(
        segments: List<CourseSegment>,
        clusterIdx: Int,
        random: kotlin.random.Random,
    ): List<Int> {
        val path = ArrayList<Int>()
        path += CP_START
        fun appendRest(from: Int, skipFixed: Int? = null) {
            for (j in from until segments.size) {
                when (val later = segments[j]) {
                    is CourseSegment.Fixed -> if (later.cp != skipFixed) path += later.cp
                    is CourseSegment.Cluster -> path += later.cps.shuffled(random)
                }
            }
            path += CP_FINISH
        }
        for (i in segments.indices) {
            when (val seg = segments[i]) {
                is CourseSegment.Fixed -> path += seg.cp
                is CourseSegment.Cluster -> {
                    if (i != clusterIdx) {
                        path += seg.cps.shuffled(random)
                        continue
                    }
                    val shuffled = seg.cps.shuffled(random)
                    val nextFixed = segments.drop(i + 1)
                        .firstOrNull { it is CourseSegment.Fixed } as? CourseSegment.Fixed
                    when {
                        // Не закрыв гроздь — взять следующий обязательный КП (типичная ошибка randomOrder).
                        nextFixed != null -> {
                            if (shuffled.size >= 2) {
                                path += shuffled.dropLast(1)
                                path += nextFixed.cp
                                path += shuffled.last()
                            } else {
                                path += nextFixed.cp
                                path += shuffled
                            }
                            appendRest(i + 1, skipFixed = nextFixed.cp)
                            return path
                        }
                        // Гроздь в конце: пропустить один (или единственный) КП.
                        shuffled.size >= 2 -> {
                            path += shuffled.dropLast(1)
                            appendRest(i + 1)
                            return path
                        }
                        else -> {
                            appendRest(i + 1)
                            return path
                        }
                    }
                }
            }
        }
        path += CP_FINISH
        return path
    }

    /** Подпись порядка КП с квадратными скобками вокруг гроздей. */
    fun formatControlsLabel(controls: List<Int>, randomOrder: List<Int> = emptyList()): String {
        val inner = innerControls(controls)
        if (inner.isEmpty()) return ""
        val groups = innerRandomOrder(controls, randomOrder)
        return courseSegmentsFromGroups(inner, groups).joinToString(" → ") { seg ->
            when (seg) {
                is CourseSegment.Fixed -> seg.cp.toString()
                is CourseSegment.Cluster -> "[${seg.cps.joinToString("·")}]"
            }
        }
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

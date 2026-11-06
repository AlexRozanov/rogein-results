package ru.rogein.chip.parse

data class ChipCellLine(
    val index: Int,
    val hex: String,
    val label: String,
    val afterPointer: Boolean,
)

data class ChipMemoryLog(
    val uid: String,
    val logicalId: Int?,
    val clearedAt: String?,
    val pointerEnd: Int?,
    val blockCount: Int,
    val blockOrder: BlockOrder,
    val cells: List<ChipCellLine>,
    val warnings: List<String>,
    val stationRole: StationRole? = null,
) {
    val hint: String? get() = warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
    val isService: Boolean get() = stationRole != null
    val kindLine: String?
        get() = stationRole?.let {
            "Служебный чип: ${SfrChipParser.stationCommandLabel(it.command, it.cp)}"
        }

    fun fileName(): String {
        val who = when {
            stationRole != null -> "station"
            logicalId != null -> logicalId.toString()
            else -> uid.replace(":", "").ifBlank { "chip" }
        }
        return "chip-log-$who.txt"
    }

    fun format(): String = buildString {
        appendLine("uid=$uid")
        if (stationRole != null) {
            appendLine("тип=служебный")
            appendLine("роль=${SfrChipParser.stationCommandLabel(stationRole.command, stationRole.cp)}")
        }
        appendLine("номер=${logicalId ?: "—"}")
        appendLine("очистка=${clearedAt ?: "—"}")
        appendLine("указатель=${pointerEnd ?: "—"}")
        appendLine("блоков=$blockCount")
        appendLine("порядок=$blockOrder")
        appendLine("прочитано_ячеек=${cells.size}")
        if (warnings.isEmpty()) {
            appendLine("предупреждения=нет")
        } else {
            appendLine("предупреждения=${warnings.joinToString("; ")}")
        }
        appendLine()
        appendLine("блок  hex           разбор")
        for (cell in cells) {
            val idx = "%02X".format(cell.index)
            append(idx)
            append("    ")
            append(cell.hex.padEnd(12))
            append("  ")
            append(cell.label)
            if (cell.afterPointer) append("  [за указателем]")
            appendLine()
        }
    }
}

object ChipCellLog {
    fun fromRaw(uid: String, rawBlocks: List<ByteArray>, declaredBlockCount: Int? = null): ChipMemoryLog {
        val dump = SfrChipParser.parse(uid, rawBlocks, declaredBlockCount)
        val station = SfrChipParser.stationRole(rawBlocks)
        val order = SfrChipParser.stationByteOrder(rawBlocks) ?: dump.blockOrder
        val logical = when (order) {
            BlockOrder.Hid -> rawBlocks
            BlockOrder.NfcTools -> rawBlocks.map { it.reversed4() }
        }
        val last = logical.lastIndex
        val end = if (station == null) dump.pointerEnd?.takeIf { it >= 5 }?.coerceAtMost(last) else null
        val cells = logical.mapIndexed { index, block ->
            if (station != null) stationLine(index, block, station) else line(index, block, end)
        }
        return ChipMemoryLog(
            uid = uid,
            logicalId = if (station == null) dump.logicalId else null,
            clearedAt = if (station == null) dump.clearedAt else null,
            pointerEnd = if (station == null) dump.pointerEnd else null,
            blockCount = dump.blockCount,
            blockOrder = order,
            cells = cells,
            warnings = if (station != null) {
                stationWarnings(logical)
            } else {
                warnings(logical, dump.pointerEnd, last, end)
            },
            stationRole = station,
        )
    }

    fun fromRawHex(uid: String, rawHex: String, declaredBlockCount: Int? = null): ChipMemoryLog? {
        val blocks = hexToBlocks(rawHex) ?: return null
        if (blocks.size < MIN_BLOCK_COUNT) return null
        return fromRaw(uid, blocks, declaredBlockCount ?: blocks.size)
    }

    fun hintFromRawHex(rawHex: String): String? = fromRawHex("", rawHex)?.hint

    fun blocksToHex(blocks: List<ByteArray>): String =
        blocks.joinToString(";") { block ->
            block.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        }

    fun hexToBlocks(rawHex: String): List<ByteArray>? {
        if (rawHex.isBlank()) return null
        val parts = rawHex.split(';')
        if (parts.any { it.length != BLOCK_SIZE * 2 || it.any { ch -> ch.digitToIntOrNull(16) == null } }) {
            return null
        }
        return parts.map { hex ->
            ByteArray(BLOCK_SIZE) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }

    private fun line(index: Int, block: ByteArray, end: Int?): ChipCellLine {
        val hex = block.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val after = end != null && index > end
        val label = when (index) {
            0, 2 -> "служебный"
            1 -> headerClearLabel(block)
            3 -> headerIdLabel(block)
            4 -> "указатель ${block[0].toInt() and 0xFF}"
            else -> punchLabel(block)
        }
        return ChipCellLine(index, hex, label, after)
    }

    private fun stationLine(index: Int, block: ByteArray, role: StationRole): ChipCellLine {
        val hex = block.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val blank = block.all { it.toInt() == 0 }
        val label = when (index) {
            0, 2 -> "служебный"
            1 -> if (blank) "пусто" else headerClearLabel(block)
            3 -> if (blank) "пусто" else headerIdLabel(block)
            4 -> stationModeLabel(role.command)
            5 -> when {
                blank -> "пусто"
                role.command.isService -> "остаток: ${punchLabel(block)}"
                else -> when (val cell = decodePunchCell(block)) {
                    PunchCell.Empty -> "пусто"
                    is PunchCell.Valid -> "КП станции ${cell.punch.cp} ${cell.punch.time}"
                    is PunchCell.Invalid -> punchLabel(block)
                }
            }
            6 -> when {
                blank -> "пусто"
                block.contentEquals(STATION_PARAM_BLOCK) -> "параметр станции"
                else -> punchLabel(block)
            }
            else -> punchLabel(block)
        }
        return ChipCellLine(index, hex, label, afterPointer = false)
    }

    private fun stationModeLabel(command: StationCommand): String = when (command) {
        StationCommand.Active -> "режим: активный"
        StationCommand.Passive -> "режим: пассивный"
        StationCommand.Battery -> "режим: батарея"
        StationCommand.Control, StationCommand.Start, StationCommand.Finish, StationCommand.Clear ->
            "режим: программатор"
    }

    private fun headerClearLabel(block: ByteArray): String {
        val h = SfrChipParser.hexDecimal(block[1]) ?: return "время очистки"
        val m = SfrChipParser.hexDecimal(block[2]) ?: return "время очистки"
        val s = SfrChipParser.hexDecimal(block[3]) ?: return "время очистки"
        if (h > 23 || m > 59 || s > 59) return "время очистки"
        return "очистка %02d:%02d:%02d".format(h, m, s)
    }

    private fun headerIdLabel(block: ByteArray): String {
        val id = (block[0].toInt() and 0xFF) + (block[1].toInt() and 0xFF) * 200
        return if (id in 1..MAX_LOGICAL_ID) "номер $id" else "номер"
    }

    private fun punchLabel(block: ByteArray): String = when (val cell = decodePunchCell(block)) {
        PunchCell.Empty -> "пусто"
        is PunchCell.Valid -> "КП ${cell.punch.cp} ${cell.punch.time}"
        is PunchCell.Invalid ->
            if (cell.cp == 0) "мусор (КП 0)" else "битое время КП ${cell.cp}"
    }

    private fun stationWarnings(logical: List<ByteArray>): List<String> {
        var leftover = 0
        for (index in 7..logical.lastIndex) {
            if (decodePunchCell(logical[index]) !is PunchCell.Empty) leftover += 1
        }
        return if (leftover > 0) {
            listOf("остались отметки участника ($leftover)")
        } else {
            emptyList()
        }
    }

    private fun warnings(
        logical: List<ByteArray>,
        pointerEnd: Int?,
        last: Int,
        end: Int?,
    ): List<String> {
        val out = ArrayList<String>(4)
        if (pointerEnd != null && pointerEnd > last) {
            out += "считано до блока $last, указатель $pointerEnd"
        }
        if (end == null) return out
        var empty = 0
        var invalid = 0
        var punches = 0
        for (index in 5..end) {
            when (decodePunchCell(logical[index])) {
                PunchCell.Empty -> empty += 1
                is PunchCell.Valid -> punches += 1
                is PunchCell.Invalid -> invalid += 1
            }
        }
        val issuedBlank = end == 5 && punches == 0 && invalid == 0
        if (!issuedBlank && empty > 0) out += "дыры в ячейках ($empty)"
        if (invalid > 0) out += "битое время ($invalid)"
        var tail = 0
        for (index in (end + 1)..last) {
            if (decodePunchCell(logical[index]) !is PunchCell.Empty) tail += 1
        }
        if (tail > 0) out += "данные за указателем ($tail)"
        return out
    }
}

internal sealed class PunchCell {
    data object Empty : PunchCell()
    data class Valid(val punch: Punch) : PunchCell()
    data class Invalid(val cp: Int) : PunchCell()
}

internal fun decodePunchCell(block: ByteArray): PunchCell {
    val cp = block[0].toInt() and 0xFF
    val blank = block.all { it.toInt() == 0 }
    if (blank) return PunchCell.Empty
    if (cp == 0) return PunchCell.Invalid(0)
    val h = SfrChipParser.hexDecimal(block[1])
    val m = SfrChipParser.hexDecimal(block[2])
    val s = SfrChipParser.hexDecimal(block[3])
    if (h == null || m == null || s == null || h > 23 || m > 59 || s > 59) {
        return PunchCell.Invalid(cp)
    }
    return PunchCell.Valid(Punch(cp, h, m, s))
}

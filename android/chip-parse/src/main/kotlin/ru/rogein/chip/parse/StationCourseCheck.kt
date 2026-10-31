package ru.rogein.chip.parse

enum class StationCheckMark { Ok, Missing, Extra, Repeat }

data class StationCheckRow(
    val mark: StationCheckMark,
    val text: String,
)

data class StationCheckReport(
    val ok: Boolean,
    val rows: List<StationCheckRow>,
    val duplicateNote: String?,
)

object StationCourseCheck {
    const val DUPLICATE_NOTE =
        "Сырого номера станции в отметке нет: там только логический номер и время. " +
            "Повтор может быть двумя станциями с одним номером или несколькими отметками на одной."

    fun check(controls: List<Int>, punches: List<Punch>): StationCheckReport {
        val expected = controls.filter { it != CP_START && it != CP_FINISH && it != CP_CLEAR && it > 0 }
        val need = LinkedHashMap<Int, Int>()
        for (cp in expected) need[cp] = (need[cp] ?: 0) + 1
        val byCp = punches.groupBy { it.cp }
        val rows = ArrayList<StationCheckRow>()
        rows += visitRow("Старт", CP_START, byCp, required = 1)
        for ((cp, count) in need) {
            rows += visitRow("КП $cp", cp, byCp, required = count)
        }
        rows += visitRow("Финиш", CP_FINISH, byCp, required = 1)
        val known = need.keys + setOf(CP_START, CP_FINISH, CP_CLEAR)
        for (cp in punches.map { it.cp }.distinct()) {
            if (cp == 0 || cp in known) continue
            rows += StationCheckRow(StationCheckMark.Extra, "Лишний КП $cp  ${timesOf(byCp[cp].orEmpty())}")
        }
        val clear = byCp[CP_CLEAR].orEmpty()
        if (clear.size > 1) {
            rows += StationCheckRow(StationCheckMark.Repeat, "Очистка ×${clear.size}  ${timesOf(clear)}")
        } else if (clear.size == 1) {
            rows += StationCheckRow(StationCheckMark.Ok, "Очистка  ${timesOf(clear)}")
        }
        val failed = rows.any {
            it.mark == StationCheckMark.Missing ||
                it.mark == StationCheckMark.Extra ||
                it.mark == StationCheckMark.Repeat
        }
        val note = if (rows.any { it.mark == StationCheckMark.Repeat }) DUPLICATE_NOTE else null
        return StationCheckReport(ok = !failed, rows = rows, duplicateNote = note)
    }

    private fun visitRow(
        title: String,
        cp: Int,
        byCp: Map<Int, List<Punch>>,
        required: Int,
    ): StationCheckRow {
        val got = byCp[cp].orEmpty()
        val times = timesOf(got)
        return when {
            got.isEmpty() -> StationCheckRow(StationCheckMark.Missing, "Нет: $title")
            got.size < required ->
                StationCheckRow(StationCheckMark.Missing, "$title: нужно $required, есть ${got.size}  $times")
            got.size > required ->
                StationCheckRow(StationCheckMark.Repeat, "$title ×${got.size}  $times")
            else -> StationCheckRow(StationCheckMark.Ok, "$title  $times")
        }
    }

    private fun timesOf(punches: List<Punch>): String = punches.joinToString(", ") { it.time }
}

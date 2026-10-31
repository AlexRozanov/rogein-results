package ru.rogein.chip.parse

enum class StationCheckMark { Ok, Missing, Extra, Repeat, Clock }

data class StationCheckRow(
    val mark: StationCheckMark,
    val text: String,
)

data class StationCheckReport(
    val ok: Boolean,
    val attention: Boolean,
    val rows: List<StationCheckRow>,
    val duplicateNote: String?,
)

object StationCourseCheck {
    const val DUPLICATE_NOTE =
        "Повтор номера — замечание. Станции лежат рядом, а в пассивном режиме перед отметкой нажимают кнопку, " +
            "поэтому по секундам не отличить двойное касание от двух станций с одним номером."

    fun check(
        controls: List<Int>,
        punches: List<Punch>,
        nowSeconds: Int? = null,
        clearedAt: String? = null,
    ): StationCheckReport {
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
        if (clear.isNotEmpty()) {
            val times = timesOf(clear)
            val title = if (clear.size == 1) "Очистка  $times" else "Очистка ×${clear.size}  $times"
            rows += StationCheckRow(
                StationCheckMark.Extra,
                "$title. Это КП $CP_CLEAR: станция запрограммирована как очистка.",
            )
        }
        rows += clockWarnings(punches)
        rows += frozenClockWarnings(punches)
        if (nowSeconds != null) rows += phoneShiftWarning(punches, nowSeconds, clearedAt)
        val failed = rows.any {
            it.mark == StationCheckMark.Missing ||
                it.mark == StationCheckMark.Extra ||
                it.mark == StationCheckMark.Clock
        }
        val attention = rows.any { it.mark == StationCheckMark.Repeat }
        val note = if (attention) DUPLICATE_NOTE else null
        return StationCheckReport(ok = !failed, attention = attention, rows = rows, duplicateNote = note)
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

    /**
     * При проверке станции лежат рядом, поэтому соседние отметки должны быть в пределах минуты.
     * Скачок назад — отстающие часы (в протоколе это выглядит как переход через полночь).
     * Разрыв больше минуты — часы одной из станций ушли вперёд.
     */
    private fun clockWarnings(punches: List<Punch>): List<StationCheckRow> {
        val rows = ArrayList<StationCheckRow>()
        var previous: Punch? = null
        for (punch in punches) {
            if (punch.cp == 0 || punch.cp == CP_CLEAR) continue
            val earlier = previous
            if (earlier != null) {
                val delta = secondsOf(punch) - secondsOf(earlier)
                when {
                    -delta >= CLOCK_SKEW_SECONDS -> rows += StationCheckRow(
                        StationCheckMark.Clock,
                        "Возможная ошибка часов: ${cpLabel(punch.cp)} ${punch.time} " +
                            "раньше ${cpLabel(earlier.cp)} ${earlier.time} на ${formatGap(-delta)}",
                    )
                    delta > CLOCK_SKEW_SECONDS -> rows += StationCheckRow(
                        StationCheckMark.Clock,
                        "Интервал больше минуты: ${cpLabel(punch.cp)} ${punch.time} " +
                            "через ${formatGap(delta)} после ${cpLabel(earlier.cp)} ${earlier.time}",
                    )
                }
            }
            previous = punch
        }
        return rows
    }

    /** Три разных номера в одну секунду: станция пишет застывшее время, а не идущие часы. */
    private fun frozenClockWarnings(punches: List<Punch>): List<StationCheckRow> {
        return punches
            .filter { it.cp > 0 }
            .groupBy { it.time }
            .mapNotNull { (time, group) ->
                val names = group.map { cpName(it.cp) }.distinct()
                if (names.size < FROZEN_CLOCK_MIN) null
                else StationCheckRow(
                    StationCheckMark.Clock,
                    "Одинаковое время $time у ${names.joinToString(", ")}: часы станции, похоже, стоят",
                )
            }
    }

    /**
     * Соседние отметки могут быть согласованы и всё равно все уехать на много часов.
     * Ближайшая к часам телефона должна попасть в две минуты: чип прикладывают сразу после последней станции.
     */
    private fun phoneShiftWarning(
        punches: List<Punch>,
        nowSeconds: Int,
        clearedAt: String?,
    ): List<StationCheckRow> {
        val nearest = punches
            .filter { it.cp > 0 && it.cp != CP_CLEAR }
            .minByOrNull { circularDistance(secondsOf(it), nowSeconds) }
            ?: return emptyList()
        val gap = circularDistance(secondsOf(nearest), nowSeconds)
        if (gap <= PHONE_OFFSET_SECONDS) return emptyList()
        val clearNote = parseClock(clearedAt)
            ?.takeIf { circularDistance(it, nowSeconds) <= FRESH_CLEAR_SECONDS }
            ?.let { " Очистка была в ${formatClock(it)}." }
            .orEmpty()
        return listOf(
            StationCheckRow(
                StationCheckMark.Clock,
                "Возможная ошибка часов: ближайшая отметка ${cpName(nearest.cp)} ${nearest.time} " +
                    "отличается от времени телефона ${formatClock(nowSeconds)} на ${formatGap(gap)}.$clearNote",
            ),
        )
    }

    private fun cpName(cp: Int): String = when (cp) {
        CP_START -> "старт"
        CP_FINISH -> "финиш"
        CP_CLEAR -> "очистка"
        else -> "КП $cp"
    }

    private fun cpLabel(cp: Int): String = when (cp) {
        CP_START -> "старта"
        CP_FINISH -> "финиша"
        else -> "КП $cp"
    }

    private fun secondsOf(punch: Punch): Int = punch.hour * 3600 + punch.minute * 60 + punch.second

    private fun parseClock(value: String?): Int? {
        if (value == null || value.length != 8 || value[2] != ':' || value[5] != ':') return null
        val hour = value.substring(0, 2).toIntOrNull() ?: return null
        val minute = value.substring(3, 5).toIntOrNull() ?: return null
        val second = value.substring(6, 8).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return hour * 3600 + minute * 60 + second
    }

    private fun formatClock(seconds: Int): String {
        val day = ((seconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY
        return "%02d:%02d:%02d".format(day / 3600, (day % 3600) / 60, day % 60)
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val raw = kotlin.math.abs(a - b) % SECONDS_PER_DAY
        return minOf(raw, SECONDS_PER_DAY - raw)
    }

    private fun formatGap(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
            hours > 0 -> "$hours ч"
            minutes > 0 -> "$minutes мин"
            else -> "$seconds с"
        }
    }

    private const val CLOCK_SKEW_SECONDS = 60
    private const val PHONE_OFFSET_SECONDS = 120
    private const val FRESH_CLEAR_SECONDS = 30 * 60
    private const val FROZEN_CLOCK_MIN = 3
    private const val SECONDS_PER_DAY = 24 * 3600
}

package ru.rogein.chip.parse

data class ReceiptMark(
    val cp: Int,
    val seconds: Int,
)

data class SplitReceipt(
    val date: String,
    val title: String,
    val course: String,
    val bib: String,
    val name: String,
    val marks: List<ReceiptMark>,
    val startCp: Int,
    val elapsedSeconds: Int,
    val status: String,
    val finishCp: Int = CP_FINISH,
    val startClockSeconds: Int? = null,
    val pointsRaw: Int = 0,
    val penaltyPoints: Int = 0,
    val pointsFinal: Int = 0,
    val rogaine: Boolean = false,
)

object ReceiptText {
    const val WIDTH = 32

    fun format(data: SplitReceipt): String {
        val lines = ArrayList<String>()
        lines += formatReceiptDate(data.date)
        val title = data.title.trim()
        if (title.isNotEmpty()) lines.addAll(wrap(title))
        val course = data.course.trim()
        if (course.isNotEmpty()) {
            val label = if (data.rogaine) "Формат" else "Дистанция"
            lines.addAll(wrap("$label $course"))
        }
        val who = buildString {
            if (data.bib.isNotBlank()) append("№ ${data.bib}")
            val name = data.name.trim()
            if (name.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append(name)
            }
        }
        if (who.isNotEmpty()) lines.addAll(wrap(who))
        lines += ""
        if (data.rogaine) {
            appendRogaineSplits(lines, data)
        } else if (data.marks.isEmpty()) {
            lines += "Нет отметок"
        } else {
            val t0 = data.marks.firstOrNull { it.cp == data.startCp }?.seconds
                ?: data.marks.first().seconds
            data.marks.forEachIndexed { index, mark ->
                val elapsed = punchLegSeconds(t0, mark.seconds)
                val leg = if (index == 0) 0 else punchLegSeconds(data.marks[index - 1].seconds, mark.seconds)
                lines += splitLine(index + 1, mark.cp, elapsed, leg)
            }
        }
        lines += ""
        if (data.rogaine) {
            lines += "Все очки   ${data.pointsRaw}"
            if (data.penaltyPoints > 0) {
                lines += "Штрафные очки ${data.penaltyPoints}"
            }
            lines += "Очки с учётом штрафных ${data.pointsFinal}"
        }
        lines += "Результат  ${formatElapsed(data.elapsedSeconds)}"
        lines += "Отметка    ${receiptStatus(data.status)}"
        lines += ""
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun appendRogaineSplits(lines: MutableList<String>, data: SplitReceipt) {
        val marks = data.marks.map { OrientMark(it.cp, it.seconds) }
        val rows = rogaineSplitRows(marks, data.startCp, data.finishCp, data.startClockSeconds)
        if (rows.isEmpty()) {
            lines += "Нет отметок"
            return
        }
        for (row in rows) {
            val time = formatDayClock(row.seconds)
            val leg = row.legSeconds?.let(::formatDayClock)
            val cp = row.cp?.toString().orEmpty()
            lines += when (row.kind) {
                RogaineSplitKind.Start -> "Старт           $time"
                RogaineSplitKind.Finish -> {
                    if (leg == null) "Финиш           $time" else "Финиш  $time  $leg"
                }
                RogaineSplitKind.Control -> {
                    val seq = (row.seq ?: 0).toString().padStart(2)
                    val num = (if (row.added) "$cp*" else cp).padStart(3)
                    val split = (leg ?: "—").padStart(8)
                    "$seq $num  $time $split"
                }
            }
        }
    }

    fun formatReceiptDate(iso: String): String {
        val parts = iso.trim().split('-')
        if (parts.size != 3) return iso.trim()
        val year = parts[0]
        val month = parts[1].toIntOrNull() ?: return iso.trim()
        val day = parts[2].toIntOrNull() ?: return iso.trim()
        val names = arrayOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )
        if (month !in 1..12) return iso.trim()
        return "$day ${names[month - 1]} $year"
    }

    internal fun receiptStatus(status: String): String = when (status) {
        STATUS_OK -> "OK"
        STATUS_DQ -> "дискв."
        STATUS_DNS -> "не стартовал"
        STATUS_DNF -> "не финишировал"
        STATUS_ERR -> "ошибка"
        else -> status
    }

    private fun splitLine(index: Int, cp: Int, elapsed: Int, leg: Int): String {
        val n = index.toString().padStart(2)
        val c = cp.toString().padStart(3)
        val e = formatElapsed(elapsed).padStart(8)
        val l = formatElapsed(leg).padStart(8)
        return "$n $c$e$l"
    }

    private fun wrap(text: String): List<String> {
        if (text.length <= WIDTH) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > WIDTH) {
            val cut = rest.lastIndexOf(' ', WIDTH).takeIf { it > 0 } ?: WIDTH
            out += rest.take(cut).trimEnd()
            rest = rest.drop(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}

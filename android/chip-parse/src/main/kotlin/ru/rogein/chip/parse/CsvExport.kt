package ru.rogein.chip.parse

data class FinishCsvPunch(
    val cp: Int,
    val clock: String,
)

data class FinishCsvRow(
    val chipRawId: String,
    val bibId: String,
    val name: String,
    val course: String,
    val punches: List<FinishCsvPunch>,
)

object CsvExport {
    fun finishDump(
        competitionDate: String,
        startCp: Int,
        rows: List<FinishCsvRow>,
    ): String {
        require(rows.isNotEmpty()) { "Нет считанных чипов для экспорта" }
        val date = competitionDate.trim().ifBlank { "1970-01-01" }
        val maxPunches = rows.maxOf { it.punches.size }.coerceAtLeast(1)
        val header = ArrayList<String>(9 + maxPunches * 2)
        header += listOf(
            "system",
            "chip raw id",
            "id",
            "team name",
            "course",
            "result",
            "brief",
            "start st id",
            "Start time",
        )
        for (i in 1..maxPunches) {
            header += "st$i"
            header += "time$i"
        }
        val out = StringBuilder()
        out.appendLine(header.joinToString(";") { csvField(it) })
        for (row in rows) {
            val startPunch = row.punches.firstOrNull { it.cp == startCp } ?: row.punches.firstOrNull()
            val startStation = startPunch?.cp?.toString().orEmpty()
            val startTime = startPunch?.let { dateTime(date, it.clock) }.orEmpty()
            val cols = ArrayList<String>(header.size)
            cols += listOf(
                "sfr",
                row.chipRawId,
                row.bibId,
                row.name.ifBlank { "без ФИО" },
                row.course,
                "OK",
                "ok",
                startStation,
                startTime,
            )
            for (i in 0 until maxPunches) {
                val punch = row.punches.getOrNull(i)
                if (punch == null) {
                    cols += ""
                    cols += ""
                } else {
                    cols += punch.cp.toString()
                    cols += dateTime(date, punch.clock)
                }
            }
            out.appendLine(cols.joinToString(";") { csvField(it) })
        }
        return out.toString()
    }

    fun protocol(rows: List<ProtocolCsvRow>): String {
        require(rows.isNotEmpty()) { "Нет участников для экспорта" }
        val out = StringBuilder()
        out.appendLine(listOf("номер", "фио", "дистанция").joinToString(";") { csvField(it) })
        for (row in rows) {
            out.appendLine(
                listOf(row.id.toString(), row.name.ifBlank { "без ФИО" }, row.course)
                    .joinToString(";") { csvField(it) },
            )
        }
        return out.toString()
    }

    fun courses(rows: List<CourseCsvRow>): String {
        require(rows.isNotEmpty()) { "Нет дистанций для экспорта" }
        val out = StringBuilder()
        for (row in rows) {
            val cols = ArrayList<String>(1 + row.controls.size)
            cols += row.name
            cols.addAll(row.controls.map { it.toString() })
            out.appendLine(cols.joinToString(";") { csvField(it) })
        }
        return out.toString()
    }

    fun filePrefix(date: String, title: String, slug: String): String {
        val day = date.trim().ifBlank { "start" }
        val raw = slug.trim().ifBlank { title.trim() }.ifBlank { "start" }
        val safe = raw.lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "start" }
        return "$day-$safe"
    }

    internal fun csvField(value: String): String {
        val needsQuote = value.any { it == ';' || it == '"' || it == '\n' || it == '\r' || it == ',' }
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    internal fun dateTime(date: String, clock: String): String {
        val time = when {
            clock.matches(Regex("\\d{1,2}:\\d{2}:\\d{2}")) -> {
                val parts = clock.split(':')
                "%02d:%02d:%02d".format(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            }
            clock.matches(Regex("\\d{1,2}:\\d{2}")) -> {
                val parts = clock.split(':')
                "%02d:%02d:00".format(parts[0].toInt(), parts[1].toInt())
            }
            else -> clock.trim()
        }
        return "${date.trim()} $time"
    }
}

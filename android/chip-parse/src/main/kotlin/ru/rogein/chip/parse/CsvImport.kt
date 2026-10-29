package ru.rogein.chip.parse

data class ProtocolCsvRow(
    val id: Int,
    val name: String,
    val course: String,
)

data class CourseCsvRow(
    val name: String,
    val controls: List<Int>,
)

object CsvImport {
    fun protocol(text: String): List<ProtocolCsvRow> {
        val lines = logicalLines(text)
        if (lines.isEmpty()) error("Файл стартового протокола пуст")
        val delimiter = detectDelimiter(lines.first())
        val rows = ArrayList<ProtocolCsvRow>()
        for ((index, line) in lines.withIndex()) {
            val cols = split(line, delimiter)
            if (cols.all { it.isBlank() }) continue
            val first = cols.getOrElse(0) { "" }.trim()
            if (index == 0 && first.matches(Regex("(?i)номер|id|номер чипа|chip"))) continue
            val id = first.toIntOrNull() ?: error("Строка ${index + 1}: некорректный номер «$first»")
            if (id <= 0) error("Строка ${index + 1}: номер должен быть > 0")
            val name = cols.getOrElse(1) { "" }.trim()
            if (name.isEmpty()) error("Строка ${index + 1}: не указано ФИО")
            val course = cols.getOrElse(2) { "" }.trim()
            rows += ProtocolCsvRow(id, name, course)
        }
        if (rows.isEmpty()) error("В файле нет участников")
        return rows
    }

    fun courses(text: String): List<CourseCsvRow> {
        val lines = logicalLines(text)
        if (lines.isEmpty()) error("Файл дистанций пуст")
        val rows = ArrayList<CourseCsvRow>()
        val seen = HashSet<String>()
        for ((index, line) in lines.withIndex()) {
            val cols = splitCourseFields(line)
            if (cols.all { it.isBlank() }) continue
            val name = cols.getOrElse(0) { "" }.trim()
            if (index == 0 && name.matches(Regex("(?i)название|дистанция|course|name"))) continue
            if (name.isEmpty()) error("Строка ${index + 1}: не указано название дистанции")
            val key = courseKey(name)
            if (!seen.add(key)) error("Строка ${index + 1}: дистанция «$name» повторяется")
            val dashPath = cols.firstOrNull(::isDashCoursePath)
            val controls = if (dashPath != null) {
                parseDashCoursePath(dashPath, index + 1)
            } else {
                parseSimpleControls(cols.drop(1), index + 1)
            }
            if (controls.isEmpty()) error("Строка ${index + 1}: у дистанции «$name» нет КП")
            rows += CourseCsvRow(name, controls)
        }
        if (rows.isEmpty()) error("Файл дистанций пуст")
        return rows
    }

    fun finishDump(text: String): List<FinishCsvRow> {
        val lines = logicalLines(text)
        if (lines.isEmpty()) error("Файл финиша пуст")
        val delimiter = detectDelimiter(lines.first())
        val rows = LinkedHashMap<String, FinishCsvRow>()
        var seenHeader = false
        for ((index, line) in lines.withIndex()) {
            val cols = splitQuoted(line, delimiter)
            if (cols.all { it.isBlank() }) continue
            val first = cols.getOrElse(0) { "" }.trim()
            if (first.startsWith("---")) continue
            if (!seenHeader && isFinishHeader(cols)) {
                seenHeader = true
                continue
            }
            if (cols.size < 10) {
                if (!seenHeader && index == 0) {
                    error("Это не финишный CSV. Нужен файл с колонками system, chip raw id, id, course и отметками.")
                }
                continue
            }
            val bib = cols.getOrElse(2) { "" }.trim()
            val name = cols.getOrElse(3) { "" }.trim()
            if (bib.isEmpty() && name.isEmpty()) continue
            val punches = ArrayList<FinishCsvPunch>()
            var i = 9
            while (i + 1 < cols.size) {
                val cpRaw = cols[i].trim()
                val tsRaw = cols[i + 1].trim()
                i += 2
                if (cpRaw.isEmpty() && tsRaw.isEmpty()) continue
                val cp = cpRaw.toIntOrNull() ?: continue
                if (cp <= 0) continue
                val clock = clockFromCsvTime(tsRaw) ?: continue
                punches += FinishCsvPunch(cp, clock)
            }
            if (punches.isEmpty()) continue
            val id = bib.toIntOrNull()
            if (id == null || id <= 0) error("Строка ${index + 1}: некорректный номер «$bib»")
            val course = cols.getOrElse(4) { "" }.trim()
            val key = "$id|${courseKey(course)}"
            rows[key] = FinishCsvRow(
                chipRawId = cols.getOrElse(1) { "" }.trim(),
                bibId = id.toString(),
                name = name.ifBlank { "без ФИО" },
                course = course,
                punches = punches,
            )
        }
        if (rows.isEmpty()) {
            error("В файле нет финишных отметок. Нужен CSV финиша, как при экспорте с телефона.")
        }
        return rows.values.toList()
    }

    private fun logicalLines(text: String): List<String> =
        text.removePrefix("\uFEFF")
            .split('\n')
            .map { it.trimEnd('\r').removePrefix("\uFEFF") }
            .filter { it.isNotBlank() }

    private fun detectDelimiter(line: String): Char {
        val tabs = line.count { it == '\t' }
        if (tabs > 0) return '\t'
        return if (line.count { it == ';' } >= line.count { it == ',' }) ';' else ','
    }

    private fun split(line: String, delimiter: Char): List<String> =
        line.split(delimiter).map { it.trim() }

    private fun isFinishHeader(cols: List<String>): Boolean {
        val first = cols.getOrElse(0) { "" }.trim().lowercase()
        val second = cols.getOrElse(1) { "" }.trim().lowercase()
        return first == "system" || second.contains("chip")
    }

    internal fun splitQuoted(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"')
                        i += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    out += cur.toString().trim()
                    cur.clear()
                }
                else -> cur.append(ch)
            }
            i += 1
        }
        out += cur.toString().trim()
        if (inQuotes) {
            return line.split(delimiter).map { it.trim().trim('"') }
        }
        return out
    }

    internal fun clockFromCsvTime(raw: String): String? {
        val token = raw.trim().substringAfterLast(' ').trim()
        val parts = token.split(':')
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = if (parts.size >= 3) parts[2].toIntOrNull() ?: return null else 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return "%02d:%02d:%02d".format(hour, minute, second)
    }

    private fun splitCourseFields(line: String): List<String> {
        val delimiter = detectDelimiter(line)
        val cols = split(line, delimiter).filter { it.isNotEmpty() }
        if (cols.isNotEmpty()) return cols
        return line.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isDashCoursePath(value: String): Boolean {
        val parts = value.split('-').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return false
        return isStartOrFinishToken(parts.first()) || isStartOrFinishToken(parts.last())
    }

    private fun isStartOrFinishToken(token: String): Boolean {
        if (token.length < 2) return false
        val kind = token.first().lowercaseChar()
        return (kind == 's' || kind == 'f') && token.drop(1).all { it.isDigit() }
    }

    private fun parseDashCoursePath(path: String, lineNo: Int): List<Int> {
        val controls = ArrayList<Int>()
        for (token in path.split('-')) {
            val part = token.trim()
            if (part.isEmpty() || isStartOrFinishToken(part)) continue
            val cp = part.toIntOrNull()
                ?: error("Строка $lineNo: некорректный номер КП «$part»")
            if (cp <= 0) error("Строка $lineNo: номер КП должен быть > 0")
            controls += cp
        }
        return controls
    }

    private fun parseSimpleControls(cols: List<String>, lineNo: Int): List<Int> {
        val controls = ArrayList<Int>()
        for (raw in cols) {
            for (token in raw.split(',', ' ', '\t')) {
                val part = token.trim()
                if (part.isEmpty()) continue
                val cp = part.toIntOrNull()
                    ?: error("Строка $lineNo: некорректный номер КП «$part»")
                if (cp <= 0) error("Строка $lineNo: номер КП должен быть > 0")
                controls += cp
            }
        }
        return controls
    }
}

fun FinishCsvRow.toChipDump(): ChipDump {
    val bib = bibId.trim().toIntOrNull() ?: error("некорректный номер «$bibId»")
    if (bib <= 0) error("номер должен быть > 0")
    val marks = punches.map { punch ->
        val clock = CsvImport.clockFromCsvTime(punch.clock) ?: punch.clock
        val parts = clock.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
        Punch(punch.cp, hour, minute, second)
    }
    return ChipDump(
        uid = chipRawId.trim(),
        logicalId = bib,
        clearedAt = null,
        punches = marks,
        blockOrder = BlockOrder.Hid,
        pointerEnd = 4 + marks.size,
        blockCount = marks.size + 4,
    )
}

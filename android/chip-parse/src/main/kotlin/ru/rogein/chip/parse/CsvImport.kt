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

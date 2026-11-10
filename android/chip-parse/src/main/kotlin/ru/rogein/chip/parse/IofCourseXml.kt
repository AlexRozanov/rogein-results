package ru.rogein.chip.parse

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

const val IOF_COURSE_MISMATCH = "IOF_COURSE_MISMATCH"

class CourseOrderMismatchException(
    message: String = "Порядок КП в XML не совпадает с загруженными дистанциями.",
) : IllegalStateException("$IOF_COURSE_MISMATCH: $message")

fun looksLikeIofCourseXml(text: String): Boolean {
    val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
    if (!trimmed.startsWith("<")) return false
    val head = trimmed.take(400).lowercase()
    return head.contains("coursedata") || head.contains("iofversion")
}

fun parseIofCourseXml(text: String): List<CourseCsvRow> {
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val document = try {
        factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        error("Не удалось прочитать IOF XML: ${e.message ?: "ошибка разбора"}")
    }
    val root = document.documentElement
        ?: error("Файл IOF XML пуст")
    if (!localName(root).equals("CourseData", ignoreCase = true)) {
        error("Это не файл дистанций IOF XML (нужен CourseData)")
    }
    val rows = ArrayList<CourseCsvRow>()
    val seen = HashSet<String>()
    for (race in childElements(root, "RaceCourseData")) {
        for (course in childElements(race, "Course")) {
            val name = childText(course, "Name").trim()
            if (name.isEmpty()) continue
            val key = courseKey(name)
            if (!seen.add(key)) error("Дистанция «$name» повторяется в XML")
            val parsed = parseIofCourse(course, name)
            if (parsed.controls.isEmpty()) error("У дистанции «$name» нет КП")
            rows += parsed
        }
    }
    if (rows.isEmpty()) error("В IOF XML нет дистанций")
    return rows
}

fun coursesNeedReplace(
    existing: List<Pair<String, List<Int>>>,
    incoming: List<CourseCsvRow>,
): Boolean {
    if (existing.isEmpty()) return false
    for (row in incoming) {
        val old = existing.find { courseKey(it.first) == courseKey(row.name) } ?: continue
        if (old.second != row.controls) return true
    }
    return false
}

fun mergeCourseGeometry(existing: CourseCsvRow, incoming: CourseCsvRow): CourseCsvRow {
    if (existing.controls != incoming.controls) return existing
    return existing.copy(
        lengthMeters = incoming.lengthMeters ?: existing.lengthMeters,
        climbMeters = incoming.climbMeters ?: existing.climbMeters,
        legMeters = if (incoming.legMeters.any { it != null }) incoming.legMeters else existing.legMeters,
    )
}

fun hasCourseDistances(lengthMeters: Int?, legMeters: List<Int?>): Boolean =
    (lengthMeters != null && lengthMeters > 0) || legMeters.any { it != null && it > 0 }

fun splitSpeedKmh(meters: Int?, seconds: Int?): Double? {
    if (meters == null || meters <= 0 || seconds == null || seconds <= 0) return null
    return meters * 3.6 / seconds
}

fun formatSpeedKmh(kmh: Double): String {
    val tenths = kotlin.math.round(kmh * 10.0).toInt()
    val whole = tenths / 10
    val frac = kotlin.math.abs(tenths % 10)
    return if (frac == 0) "$whole км/ч" else "$whole,$frac км/ч"
}

fun formatRaceTime(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return "%d:%02d:%02d".format(h, m, s)
}

fun formatSplitMmSs(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatPaceMinPerKm(meters: Int?, seconds: Int?): String? {
    if (meters == null || meters <= 0 || seconds == null || seconds <= 0) return null
    val secPerKm = kotlin.math.round(seconds * 1000.0 / meters).toInt().coerceAtLeast(0)
    val min = secPerKm / 60
    val sec = secPerKm % 60
    return "%d:%02d/км".format(min, sec)
}

fun punchSplitLine(
    order: String,
    cp: String,
    elapsed: String,
    split: String?,
    pace: String?,
): String {
    val left = order.padStart(2) + " " + cp.padEnd(6)
    val times = buildString {
        append(elapsed.padStart(8))
        if (split != null) {
            append(' ')
            append(split.padStart(5))
        }
        if (!pace.isNullOrBlank()) {
            append("  ")
            append(pace)
        }
    }
    return left + times
}

fun formatCpParen(cp: Int, added: Boolean = false): String {
    val star = if (added) "*" else ""
    val body = if (cp < 100) "( ${cp})" else "(${cp})"
    return body + star
}

data class OrientSplitPrintRow(
    val text: String,
    val wrong: Boolean,
    val kind: PunchKind,
)

data class OrientSplitPrint(
    val startLine: String?,
    val rows: List<OrientSplitPrintRow>,
    val resultLine: String,
    val averageLine: String?,
    val statusLine: String,
)

fun buildOrientSplitPrint(
    punches: List<OrientMark>,
    required: List<Int>,
    startCp: Int,
    finishCp: Int,
    elapsedSeconds: Int,
    status: String,
    diagnostics: List<String> = emptyList(),
    courseControls: List<Int> = emptyList(),
    lengthMeters: Int? = null,
    legMeters: List<Int?> = emptyList(),
): OrientSplitPrint? {
    val classified = classifyActualPunches(punches, required, startCp, finishCp)
    if (classified.isEmpty()) return null
    val showPace = hasCourseDistances(lengthMeters, legMeters)
    val t0 = classified.firstOrNull { it.kind == PunchKind.Start }?.mark?.seconds
        ?: classified.first().mark.seconds
    val startLine = classified.firstOrNull { it.kind == PunchKind.Start }?.let { start ->
        "Старт: ${formatDayClock(start.mark.seconds)}"
    }
    val rows = ArrayList<OrientSplitPrintRow>()
    var nextCpOrder = 1
    classified.forEachIndexed { index, item ->
        if (item.kind == PunchKind.Start) return@forEachIndexed
        val mark = item.mark
        val elapsed = formatRaceTime(punchLegSeconds(t0, mark.seconds))
        val splitSeconds = if (index == 0) {
            null
        } else {
            punchLegSeconds(classified[index - 1].mark.seconds, mark.seconds)
        }
        val split = splitSeconds?.let(::formatSplitMmSs)
        val pace = if (!showPace || splitSeconds == null) {
            null
        } else {
            val meters = courseLegMeters(
                courseControls,
                legMeters,
                classified[index - 1].mark.cp,
                mark.cp,
                startCp,
                finishCp,
            )
            formatPaceMinPerKm(meters, splitSeconds)
        }
        val finish = item.kind == PunchKind.Finish
        val order = if (finish) "" else nextCpOrder++.toString()
        val cp = if (finish) "Финиш:" else formatCpParen(mark.cp, mark.added)
        rows += OrientSplitPrintRow(
            text = punchSplitLine(order, cp, elapsed, split, pace),
            wrong = item.wrong,
            kind = item.kind,
        )
    }
    return OrientSplitPrint(
        startLine = startLine,
        rows = rows,
        resultLine = "Результат: ${formatRaceTime(elapsedSeconds)}",
        averageLine = formatPaceMinPerKm(lengthMeters, elapsedSeconds)?.let { "Средняя скорость  $it" },
        statusLine = "Отметка — ${resultStatusLine(status, diagnostics)}",
    )
}

fun remapCourseLegMeters(
    startCp: Int,
    finishCp: Int,
    fullControls: List<Int>,
    publishedControls: List<Int>,
    legs: List<Int?>,
): List<Int?> {
    if (legs.isEmpty()) return emptyList()
    val orig = ArrayList<Int>(fullControls.size + 2).apply {
        add(startCp)
        addAll(fullControls)
        add(finishCp)
    }
    val published = ArrayList<Int>(publishedControls.size + 2).apply {
        add(startCp)
        addAll(publishedControls)
        add(finishCp)
    }
    return (0 until published.lastIndex).map { index ->
        val from = published[index]
        val to = published[index + 1]
        val fromIdx = orig.indexOf(from)
        if (fromIdx >= 0 && orig.getOrNull(fromIdx + 1) == to) {
            legs.getOrNull(fromIdx)?.takeIf { it > 0 }
        } else {
            null
        }
    }
}

fun courseLegMeters(
    controls: List<Int>,
    legMeters: List<Int?>,
    fromCp: Int,
    toCp: Int,
    startCp: Int,
    finishCp: Int,
): Int? {
    if (legMeters.isEmpty()) return null
    val seq = ArrayList<Int>(controls.size + 2)
    seq += startCp
    seq += controls
    seq += finishCp
    val fromIdx = seq.indexOf(fromCp).takeIf { it >= 0 } ?: return null
    val toIdx = seq.indexOf(toCp).takeIf { it >= 0 } ?: return null
    if (toIdx != fromIdx + 1) return null
    val meters = legMeters.getOrNull(fromIdx) ?: return null
    return meters.takeIf { it > 0 }
}

private fun parseIofCourse(course: Element, name: String): CourseCsvRow {
    val length = childText(course, "Length").toDoubleOrNull()?.toInt()
    val climb = childText(course, "Climb").toDoubleOrNull()?.toInt()
    val controls = ArrayList<Int>()
    val legs = ArrayList<Int?>()
    var pendingMeters = 0
    var pendingHas = false
    var emitted = 0
    for (node in childElements(course, "CourseControl")) {
        val type = node.getAttribute("type").trim().lowercase()
        val code = childText(node, "Control").trim()
        val leg = childText(node, "LegLength").toDoubleOrNull()?.toInt()
        if (leg != null) {
            pendingMeters += leg
            pendingHas = true
        }
        val kind = controlKind(type, code)
        if (kind == IofControlKind.Skip) continue
        if (emitted > 0) {
            legs += if (pendingHas) pendingMeters else null
        }
        pendingMeters = 0
        pendingHas = false
        if (kind == IofControlKind.Control) {
            val cp = code.toIntOrNull()
                ?: error("У дистанции «$name» некорректный номер КП «$code»")
            if (cp <= 0) error("У дистанции «$name» номер КП должен быть > 0")
            controls += cp
        }
        emitted += 1
    }
    return CourseCsvRow(
        name = name,
        controls = controls,
        lengthMeters = length?.takeIf { it >= 0 },
        climbMeters = climb?.takeIf { it >= 0 },
        legMeters = legs,
    )
}

private enum class IofControlKind { Start, Control, Finish, Skip }

private fun controlKind(type: String, code: String): IofControlKind = when (type) {
    "start" -> IofControlKind.Start
    "finish" -> IofControlKind.Finish
    "crossingpoint", "endofmarkedroute" -> IofControlKind.Skip
    "control" -> IofControlKind.Control
    else -> when {
        isStartOrFinishXmlCode(code, 's') -> IofControlKind.Start
        isStartOrFinishXmlCode(code, 'f') -> IofControlKind.Finish
        code.toIntOrNull() != null -> IofControlKind.Control
        else -> IofControlKind.Skip
    }
}

private fun isStartOrFinishXmlCode(code: String, kind: Char): Boolean {
    if (code.length < 2) return false
    return code.first().lowercaseChar() == kind && code.drop(1).all { it.isDigit() }
}

private fun localName(node: Node): String {
    val local = node.localName
    if (!local.isNullOrEmpty()) return local
    val name = node.nodeName
    return name.substringAfter(':', name)
}

private fun childElements(parent: Element, local: String): List<Element> {
    val out = ArrayList<Element>()
    var child = parent.firstChild
    while (child != null) {
        if (child is Element && localName(child).equals(local, ignoreCase = true)) {
            out += child
        }
        child = child.nextSibling
    }
    return out
}

private fun childText(parent: Element, local: String): String {
    val child = childElements(parent, local).firstOrNull() ?: return ""
    return child.textContent.orEmpty().trim()
}

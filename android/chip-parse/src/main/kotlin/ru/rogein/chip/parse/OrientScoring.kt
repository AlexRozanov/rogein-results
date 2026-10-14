package ru.rogein.chip.parse

data class OrientMark(
    val cp: Int,
    val seconds: Int,
    val seq: Int = 0,
)

data class OrientSettings(
    val startCp: Int = CP_START,
    val finishCp: Int = CP_FINISH,
    val controlMinutes: Int = 120,
)

data class OrientExclusion(
    val courseKey: String?,
    val fromCp: Int,
    val toCp: Int,
    val maxLegSeconds: Int? = null,
)

data class OrientCalc(
    val status: String,
    val taken: Int,
    val elapsedSeconds: Int,
    val diagnostics: List<String>,
) {
    val ok: Boolean get() = status == STATUS_OK
}

const val STATUS_OK = "OK"
const val STATUS_DQ = "Дисквалификация"
const val STATUS_ERR = "Ошибка"
const val STATUS_DNS = "Не стартовал"

fun courseKey(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), " ")

fun parseDaySeconds(time: String): Int? {
    val parts = time.trim().split(':')
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
    if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
    return h * 3600 + m * 60 + s
}

fun formatElapsed(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDayClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

fun punchLegSeconds(previous: Int, current: Int): Int {
    var delta = current - previous
    if (delta < 0) delta += 24 * 3600
    return delta.coerceAtLeast(0)
}

data class CourseCpProgress(
    val cp: Int,
    val taken: Boolean,
    val added: Boolean,
    val punched: Boolean,
    val blocker: Boolean,
)

fun orientPunchWindow(marks: List<OrientMark>, startCp: Int, finishCp: Int): List<OrientMark> {
    val sorted = marks.sortedWith(compareBy<OrientMark> { it.seconds }.thenBy { it.seq })
    if (sorted.isEmpty()) return emptyList()
    val startIdx = sorted.indexOfFirst { it.cp == startCp }
    if (startIdx < 0) return sorted
    val finishIdx = sorted.indices.reversed().firstOrNull { i ->
        i > startIdx && sorted[i].cp == finishCp
    }
    val windowEnd = finishIdx ?: sorted.lastIndex
    return sorted.subList(startIdx, windowEnd + 1)
}

fun courseProgress(
    punches: List<Int>,
    required: List<Int>,
    addCounts: Map<Int, Int> = emptyMap(),
): List<CourseCpProgress> {
    if (required.isEmpty()) return emptyList()
    val punched = punches.toSet()
    val remaining = addCounts.toMutableMap()
    var i = 0
    var firstMiss = true
    return required.map { cp ->
        var q = i
        while (q < punches.size && punches[q] != cp) q += 1
        var taken = false
        var added = false
        if (q < punches.size && punches[q] == cp) {
            taken = true
            i = q + 1
        } else {
            val left = remaining[cp] ?: 0
            if (left > 0) {
                remaining[cp] = left - 1
                taken = true
                added = true
            }
        }
        val blocker = !taken && firstMiss
        if (!taken) firstMiss = false
        CourseCpProgress(cp, taken, added, punched.contains(cp), blocker)
    }
}

enum class PunchKind {
    Ok,
    Start,
    Finish,
    Extra,
    OutOfOrder,
}

data class ClassifiedPunch(
    val mark: OrientMark,
    val kind: PunchKind,
) {
    val wrong: Boolean get() = kind == PunchKind.OutOfOrder
}

fun classifyActualPunches(
    marks: List<OrientMark>,
    courseCps: List<Int>,
    startCp: Int,
    finishCp: Int,
): List<ClassifiedPunch> {
    val sorted = marks.sortedWith(compareBy<OrientMark> { it.seconds }.thenBy { it.seq })
    if (sorted.isEmpty()) return emptyList()
    var startIdx = sorted.indexOfFirst { it.cp == startCp }
    if (startIdx < 0) startIdx = 0
    val finishIdx = sorted.indices.reversed().firstOrNull { i ->
        i > startIdx && sorted[i].cp == finishCp
    } ?: sorted.lastIndex
    var next = 0
    var broken = false
    fun afterError(mark: OrientMark): ClassifiedPunch {
        broken = true
        return ClassifiedPunch(mark, PunchKind.OutOfOrder)
    }
    fun keep(mark: OrientMark, kind: PunchKind): ClassifiedPunch {
        if (broken) return afterError(mark)
        return ClassifiedPunch(mark, kind)
    }
    return sorted.mapIndexed { i, mark ->
        if (i < startIdx || i > finishIdx) {
            return@mapIndexed keep(mark, PunchKind.Extra)
        }
        if (i == finishIdx && mark.cp == finishCp) {
            if (next < courseCps.size && courseCps[next] == finishCp) next += 1
            return@mapIndexed keep(mark, PunchKind.Finish)
        }
        if (mark.cp == startCp && (next >= courseCps.size || courseCps[next] != startCp)) {
            return@mapIndexed keep(mark, PunchKind.Start)
        }
        if (courseCps.isEmpty()) {
            return@mapIndexed keep(mark, PunchKind.Ok)
        }
        if (next < courseCps.size && mark.cp == courseCps[next]) {
            next += 1
            val kind = when (mark.cp) {
                startCp -> PunchKind.Start
                finishCp -> PunchKind.Finish
                else -> PunchKind.Ok
            }
            return@mapIndexed keep(mark, kind)
        }
        if (next < courseCps.size && courseCps.subList(next, courseCps.size).contains(mark.cp)) {
            return@mapIndexed afterError(mark)
        }
        keep(mark, if (mark.cp in courseCps) PunchKind.Ok else PunchKind.Extra)
    }
}

fun addableCourseCps(progress: List<CourseCpProgress>): List<Pair<Int, Int>> {
    val remaining = LinkedHashMap<Int, Int>()
    for (item in progress) {
        if (item.taken) continue
        remaining[item.cp] = (remaining[item.cp] ?: 0) + 1
    }
    return remaining.entries.map { it.toPair() }
}

fun filterCourseCps(controls: List<Int>, removed: Set<Int>): List<Int> =
    controls.filter { it !in removed }

fun courseSubsequenceTaken(punches: List<Int>, required: List<Int>, addCounts: Map<Int, Int> = emptyMap()): Int {
    if (required.isEmpty()) return 0
    val remaining = addCounts.toMutableMap()
    var p = 0
    var taken = 0
    for (cp in required) {
        var q = p
        while (q < punches.size && punches[q] != cp) q += 1
        if (q < punches.size) {
            p = q + 1
            taken += 1
            continue
        }
        val left = remaining[cp] ?: 0
        if (left > 0) {
            remaining[cp] = left - 1
            taken += 1
            continue
        }
        break
    }
    return taken
}

fun orientCourseLegs(
    controls: List<Int>,
    startCp: Int,
    finishCp: Int,
    removed: Set<Int>,
): List<Pair<Int, Int>> {
    val kept = controls.filter { it !in removed }
    val seq = ArrayList<Int>()
    if (kept.firstOrNull() != startCp) seq += startCp
    seq += kept
    if (seq.lastOrNull() != finishCp && finishCp > 0) seq += finishCp
    return seq.zipWithNext().filter { it.first != it.second }
}

fun resolveOrientLegTo(fromCp: Int, required: List<Int>, startCp: Int, finishCp: Int): Int? {
    if (fromCp == startCp) return required.firstOrNull()
    val i = required.indexOf(fromCp)
    if (i < 0) return null
    return required.getOrNull(i + 1) ?: finishCp
}

fun applyOrientExclusionRules(
    window: List<OrientMark>,
    required: List<Int>,
    startCp: Int,
    finishCp: Int,
    courseKey: String?,
    rules: List<OrientExclusion>,
    elapsedSeconds: Int,
): Int {
    if (window.size < 2 || rules.isEmpty()) return elapsedSeconds
    var adjusted = elapsedSeconds
    for (rule in rules) {
        val ruleCourse = rule.courseKey?.takeIf { it.isNotBlank() }
        if (ruleCourse != null && courseKey != ruleCourse) continue
        if (ruleCourse == null && courseKey != null &&
            rules.any { it.fromCp == rule.fromCp && it.courseKey == courseKey }
        ) {
            continue
        }
        val toCp = if (rule.toCp > 0) {
            rule.toCp
        } else {
            resolveOrientLegTo(rule.fromCp, required, startCp, finishCp) ?: continue
        }
        var pending: Int? = null
        for (mark in window) {
            if (mark.cp == rule.fromCp) {
                pending = mark.seconds
            } else if (mark.cp == toCp) {
                val t0 = pending
                pending = null
                if (t0 != null) {
                    var delta = mark.seconds - t0
                    if (delta < 0) delta += 24 * 3600
                    val cap = rule.maxLegSeconds?.let { delta.coerceAtMost(it) } ?: delta
                    adjusted = (adjusted - cap).coerceAtLeast(0)
                }
            }
        }
    }
    return adjusted
}

fun calculateOrientResult(
    marks: List<OrientMark>,
    settings: OrientSettings,
    courseName: String,
    courses: Map<String, List<Int>>,
    removedCps: Set<Int>,
    exclusions: List<OrientExclusion>,
    addCpCounts: Map<Int, Int>,
): OrientCalc {
    val diagnostics = mutableListOf<String>()
    val name = courseName.trim()
    val key = courseKey(name)
    val required: List<Int>? = when {
        name.isEmpty() -> {
            diagnostics += "missing_course"
            null
        }
        !courses.containsKey(key) -> {
            diagnostics += "course_unknown"
            null
        }
        courses.getValue(key).isEmpty() -> {
            diagnostics += "course_empty"
            null
        }
        else -> filterCourseCps(courses.getValue(key), removedCps)
    }
    val startCp = settings.startCp
    val sorted = marks.sortedWith(compareBy<OrientMark> { it.seconds }.thenBy { it.seq })
    if (sorted.isEmpty()) {
        diagnostics += "no_marks"
        return OrientCalc(STATUS_DNS, 0, 0, diagnostics)
    }
    val startIdx = sorted.indexOfFirst { it.cp == startCp }
    if (startIdx < 0) {
        diagnostics += "start_missing"
        return OrientCalc(STATUS_DNS, 0, 0, diagnostics)
    }
    val finishIdx = sorted.indices.reversed().firstOrNull { i ->
        i > startIdx && sorted[i].cp == settings.finishCp
    }
    if (finishIdx == null) diagnostics += "finish_missing"
    val windowEnd = finishIdx ?: (sorted.lastIndex)
    val window = sorted.subList(startIdx, windowEnd + 1)
    val punches = window.map { it.cp }
    val taken = required?.let { courseSubsequenceTaken(punches, it, addCpCounts) } ?: 0
    val orderBroken = required != null &&
        classifyActualPunches(window, required, startCp, settings.finishCp).any { it.wrong }
    val complete = required != null && taken == required.size && !orderBroken
    if (required != null && !complete) diagnostics += "course_incomplete"
    var elapsed = window.last().seconds - window.first().seconds
    if (elapsed < 0) elapsed += 24 * 3600
    elapsed = if (required != null) {
        applyOrientExclusionRules(
            window,
            required,
            startCp,
            settings.finishCp,
            key,
            exclusions,
            elapsed,
        )
    } else {
        elapsed
    }
    val controlSeconds = settings.controlMinutes.coerceAtLeast(0) * 60
    if (elapsed > controlSeconds) diagnostics += "overtime"
    val status = when {
        diagnostics.any {
            it == "missing_course" || it == "course_unknown" ||
                it == "course_empty" || it == "start_cp_not_configured"
        } -> STATUS_ERR
        !complete || finishIdx == null || elapsed > controlSeconds -> STATUS_DQ
        else -> STATUS_OK
    }
    return OrientCalc(status, taken, elapsed, diagnostics)
}

fun assignPlaces(results: List<OrientCalc>): List<Int?> {
    val ok = results.withIndex()
        .filter { it.value.ok }
        .sortedBy { it.value.elapsedSeconds }
    val places = MutableList<Int?>(results.size) { null }
    var place = 1
    var prevElapsed: Int? = null
    var seen = 0
    for ((index, calc) in ok) {
        seen += 1
        if (prevElapsed != calc.elapsedSeconds) {
            place = seen
            prevElapsed = calc.elapsedSeconds
        }
        places[index] = place
    }
    return places
}

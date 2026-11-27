package ru.rogein.chip.parse

data class OrientMark(
    val cp: Int,
    val seconds: Int,
    val seq: Int = 0,
    val added: Boolean = false,
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
    val pointsRaw: Int = 0,
    val penaltyPoints: Int = 0,
    val pointsFinal: Int = 0,
    val penaltyMinutes: Int = 0,
) {
    val ok: Boolean get() = status == STATUS_OK
}

const val STATUS_OK = "OK"
const val STATUS_DQ = "Дисквалификация"
const val STATUS_ERR = "Ошибка"
const val STATUS_DNS = "Не стартовал"
const val STATUS_DNF = "DNF"
const val DID_NOT_FINISH = "did_not_finish"

/** OK, then DQ, then DNF, then other non-finishes. */
fun protocolStatusRank(status: String): Int = when (status) {
    STATUS_OK -> 0
    STATUS_DQ -> 1
    STATUS_DNF -> 2
    STATUS_ERR -> 3
    STATUS_DNS -> 4
    else -> 5
}

fun diagnosticLabel(code: String): String = when (code) {
    "missing_course" -> "нет дистанции"
    "course_empty" -> "дистанция без КП"
    "course_unknown" -> "дистанция не найдена"
    "start_cp_not_configured" -> "не задана стартовая станция"
    "start_time_not_configured" -> "не задано общее время старта"
    "no_marks" -> "нет отметок"
    "start_missing" -> "нет отметки старта"
    "finish_missing" -> "нет отметки финиша"
    "finish_not_last" -> "финиш не последняя отметка"
    "course_incomplete" -> "дистанция пройдена не полностью"
    "overtime" -> "превышение контрольного времени"
    "did_not_finish" -> "не финишировал"
    else -> code
}

fun resultStatusLine(status: String, diagnostics: List<String> = emptyList()): String {
    val reasons = diagnostics.map(::diagnosticLabel).distinct()
    return when (status) {
        STATUS_OK -> "OK"
        STATUS_DQ -> {
            val reason = reasons.ifEmpty { listOf("превышение лимита времени") }
            "Дисквалификация: ${reason.joinToString(", ")}"
        }
        STATUS_DNS -> {
            val reason = reasons.ifEmpty { listOf("не стартовал") }
            "Не стартовал: ${reason.joinToString(", ")}"
        }
        STATUS_DNF -> "Не финишировал"
        STATUS_ERR -> {
            val reason = reasons.ifEmpty { listOf("ошибка") }
            "Ошибка: ${reason.joinToString(", ")}"
        }
        else -> if (reasons.isEmpty()) status else "$status: ${reasons.joinToString(", ")}"
    }
}

fun courseKey(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), " ")

fun uniqueCourseCopyName(source: String, existing: List<String>): String {
    val names = existing.map { courseKey(it) }.toSet()
    val trimmed = source.trim()
    val base = "$trimmed (копия)"
    if (courseKey(base) !in names) return base
    var n = 2
    while (true) {
        val candidate = "$trimmed (копия $n)"
        if (courseKey(candidate) !in names) return candidate
        n++
    }
}

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

fun orientMarkOrder(marks: List<OrientMark>): List<OrientMark> =
    marks.sortedWith(
        compareBy<OrientMark> { it.seconds }
            .thenBy { it.seq }
            .thenBy { if (it.added) 1 else 0 },
    )

fun orientPunchWindow(marks: List<OrientMark>, startCp: Int, finishCp: Int): List<OrientMark> {
    val sorted = orientMarkOrder(marks)
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
    randomOrder: List<Int> = emptyList(),
): List<CourseCpProgress> {
    if (required.isEmpty()) return emptyList()
    val punched = punches.toSet()
    val remainingAdds = addCounts.toMutableMap()
    val segments = courseSegments(required, randomOrder)
    val takenFlags = BooleanArray(required.size)
    val addedFlags = BooleanArray(required.size)
    var punchAt = 0
    var controlAt = 0
    for (seg in segments) {
        when (seg) {
            is CourseSegment.Fixed -> {
                var q = punchAt
                while (q < punches.size && punches[q] != seg.cp) q += 1
                if (q < punches.size && punches[q] == seg.cp) {
                    takenFlags[controlAt] = true
                    punchAt = q + 1
                } else {
                    val left = remainingAdds[seg.cp] ?: 0
                    if (left > 0) {
                        remainingAdds[seg.cp] = left - 1
                        takenFlags[controlAt] = true
                        addedFlags[controlAt] = true
                    }
                }
                controlAt += 1
            }
            is CourseSegment.Cluster -> {
                val need = LinkedHashMap<Int, Int>()
                for (cp in seg.cps) need[cp] = (need[cp] ?: 0) + 1
                val slotByCp = HashMap<Int, ArrayDeque<Int>>()
                for (offset in seg.cps.indices) {
                    val cp = seg.cps[offset]
                    slotByCp.getOrPut(cp) { ArrayDeque() }.addLast(controlAt + offset)
                }
                while (need.values.any { it > 0 }) {
                    var foundIdx = -1
                    var foundCp = 0
                    var q = punchAt
                    while (q < punches.size) {
                        val cp = punches[q]
                        if ((need[cp] ?: 0) > 0) {
                            foundIdx = q
                            foundCp = cp
                            break
                        }
                        q += 1
                    }
                    if (foundIdx >= 0) {
                        need[foundCp] = (need[foundCp] ?: 1) - 1
                        val slot = slotByCp[foundCp]?.removeFirstOrNull()
                        if (slot != null) takenFlags[slot] = true
                        punchAt = foundIdx + 1
                        continue
                    }
                    var usedAdd = false
                    for ((cp, count) in need) {
                        if (count <= 0) continue
                        val left = remainingAdds[cp] ?: 0
                        if (left > 0) {
                            remainingAdds[cp] = left - 1
                            need[cp] = count - 1
                            val slot = slotByCp[cp]?.removeFirstOrNull()
                            if (slot != null) {
                                takenFlags[slot] = true
                                addedFlags[slot] = true
                            }
                            usedAdd = true
                            break
                        }
                    }
                    if (!usedAdd) break
                }
                controlAt += seg.cps.size
            }
        }
    }
    var firstMiss = true
    return required.mapIndexed { index, cp ->
        val taken = takenFlags.getOrElse(index) { false }
        val blocker = !taken && firstMiss
        if (!taken) firstMiss = false
        CourseCpProgress(cp, taken, addedFlags.getOrElse(index) { false }, punched.contains(cp), blocker)
    }
}

/**
 * Вставляет стартовую отметку из персональной корректировки с явным временем.
 * Без времени старт не добавляется — его нельзя восстановить «посередине».
 */
fun marksWithAddedStart(
    marks: List<OrientMark>,
    startCp: Int,
    addCounts: Map<Int, Int>,
    addTimes: Map<Int, Int> = emptyMap(),
): List<OrientMark> {
    if (startCp <= 0) return marks
    if (marks.any { it.cp == startCp }) return marks
    if ((addCounts[startCp] ?: 0) <= 0) return marks
    val seconds = addTimes[startCp] ?: return marks
    return orientMarkOrder(marks + OrientMark(startCp, seconds, 0, added = true))
}

fun applyAddedCps(
    marks: List<OrientMark>,
    required: List<Int>,
    addCounts: Map<Int, Int>,
    startCp: Int = CP_START,
    finishCp: Int = CP_FINISH,
    addTimes: Map<Int, Int> = emptyMap(),
    randomOrder: List<Int> = emptyList(),
): List<OrientMark> {
    if (required.isEmpty() || addCounts.values.all { it <= 0 }) return marks
    val remaining = addCounts.filterValues { it > 0 }.toMutableMap()
    if (remaining.isEmpty()) return marks
    val result = marks.toMutableList()
    var p = 0
    if (result.firstOrNull()?.cp == startCp && required.firstOrNull() != startCp) {
        p = 1
    }
    fun insertAt(cp: Int, atHint: Int): Int {
        var at = atHint
        val finishAt = result.indexOfLast { it.cp == finishCp }
        if (finishAt >= 0 && at >= finishAt && cp != finishCp) at = finishAt
        result.add(
            at,
            syntheticAddedMark(
                cp,
                result.getOrNull(at - 1),
                result.getOrNull(at),
                addTimes[cp],
            ),
        )
        return at + 1
    }
    val segments = courseSegments(required, randomOrder)
    for (seg in segments) {
        when (seg) {
            is CourseSegment.Fixed -> {
                var q = p
                while (q < result.size &&
                    (result[q].cp != seg.cp || (result[q].cp == finishCp && seg.cp != finishCp))
                ) {
                    q += 1
                }
                if (q < result.size) {
                    p = q + 1
                    continue
                }
                val left = remaining[seg.cp] ?: 0
                if (left <= 0) break
                remaining[seg.cp] = left - 1
                p = insertAt(seg.cp, p)
            }
            is CourseSegment.Cluster -> {
                val need = LinkedHashMap<Int, Int>()
                for (cp in seg.cps) need[cp] = (need[cp] ?: 0) + 1
                while (need.values.any { it > 0 }) {
                    var foundIdx = -1
                    var foundCp = 0
                    var q = p
                    while (q < result.size) {
                        val cp = result[q].cp
                        if ((need[cp] ?: 0) > 0) {
                            foundIdx = q
                            foundCp = cp
                            break
                        }
                        q += 1
                    }
                    if (foundIdx >= 0) {
                        need[foundCp] = (need[foundCp] ?: 1) - 1
                        p = foundIdx + 1
                        continue
                    }
                    var usedAdd = false
                    for ((cp, count) in need) {
                        if (count <= 0) continue
                        val left = remaining[cp] ?: 0
                        if (left <= 0) continue
                        remaining[cp] = left - 1
                        need[cp] = count - 1
                        p = insertAt(cp, p)
                        usedAdd = true
                        break
                    }
                    if (!usedAdd) break
                }
            }
        }
    }
    return result
}

private fun syntheticAddedMark(
    cp: Int,
    prev: OrientMark?,
    next: OrientMark?,
    forcedSeconds: Int? = null,
): OrientMark {
    val seconds = forcedSeconds ?: when {
        prev != null && next != null && next.seconds > prev.seconds ->
            prev.seconds + (next.seconds - prev.seconds) / 2
        prev != null -> prev.seconds
        next != null -> next.seconds
        else -> 0
    }
    val seq = when {
        prev != null && prev.seconds == seconds -> prev.seq
        else -> 0
    }
    return OrientMark(cp, seconds, seq, added = true)
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
    randomOrder: List<Int> = emptyList(),
): List<ClassifiedPunch> {
    val sorted = orientMarkOrder(marks)
    if (sorted.isEmpty()) return emptyList()
    var startIdx = sorted.indexOfFirst { it.cp == startCp }
    if (startIdx < 0) startIdx = 0
    val finishIdx = sorted.indices.reversed().firstOrNull { i ->
        i > startIdx && sorted[i].cp == finishCp
    } ?: sorted.lastIndex
    val segments = courseSegments(courseCps, randomOrder)
    var segIdx = 0
    var clusterNeed = LinkedHashMap<Int, Int>()
    fun loadCluster(seg: CourseSegment.Cluster) {
        clusterNeed.clear()
        for (cp in seg.cps) clusterNeed[cp] = (clusterNeed[cp] ?: 0) + 1
    }
    if (segments.firstOrNull() is CourseSegment.Cluster) {
        loadCluster(segments.first() as CourseSegment.Cluster)
    }
    var broken = false
    fun afterError(mark: OrientMark): ClassifiedPunch {
        broken = true
        return ClassifiedPunch(mark, PunchKind.OutOfOrder)
    }
    fun keep(mark: OrientMark, kind: PunchKind): ClassifiedPunch {
        if (broken) return afterError(mark)
        return ClassifiedPunch(mark, kind)
    }
    fun futureCps(): Set<Int> {
        val out = HashSet<Int>()
        for (i in (segIdx + 1) until segments.size) {
            when (val seg = segments[i]) {
                is CourseSegment.Fixed -> out += seg.cp
                is CourseSegment.Cluster -> out += seg.cps
            }
        }
        return out
    }
    fun advanceSegment() {
        segIdx += 1
        clusterNeed.clear()
        val next = segments.getOrNull(segIdx)
        if (next is CourseSegment.Cluster) loadCluster(next)
    }
    return sorted.mapIndexed { i, mark ->
        if (i < startIdx || i > finishIdx) {
            return@mapIndexed keep(mark, PunchKind.Extra)
        }
        if (i == finishIdx && mark.cp == finishCp) {
            return@mapIndexed keep(mark, PunchKind.Finish)
        }
        if (mark.cp == startCp && (segIdx >= segments.size ||
                (segments.getOrNull(segIdx) as? CourseSegment.Fixed)?.cp != startCp)
        ) {
            return@mapIndexed keep(mark, PunchKind.Start)
        }
        if (courseCps.isEmpty()) {
            return@mapIndexed keep(mark, PunchKind.Ok)
        }
        if (segIdx >= segments.size) {
            return@mapIndexed keep(mark, if (mark.cp in courseCps) PunchKind.Ok else PunchKind.Extra)
        }
        when (val seg = segments[segIdx]) {
            is CourseSegment.Fixed -> {
                if (mark.cp == seg.cp) {
                    advanceSegment()
                    val kind = when (mark.cp) {
                        startCp -> PunchKind.Start
                        finishCp -> PunchKind.Finish
                        else -> PunchKind.Ok
                    }
                    return@mapIndexed keep(mark, kind)
                }
                if (mark.cp in futureCps()) {
                    val laterSame = sorted.subList(i + 1, finishIdx + 1).any { it.cp == mark.cp }
                    if (laterSame) {
                        return@mapIndexed keep(mark, PunchKind.Extra)
                    }
                    return@mapIndexed afterError(mark)
                }
                keep(mark, if (mark.cp in courseCps) PunchKind.Ok else PunchKind.Extra)
            }
            is CourseSegment.Cluster -> {
                if ((clusterNeed[mark.cp] ?: 0) > 0) {
                    clusterNeed[mark.cp] = (clusterNeed[mark.cp] ?: 1) - 1
                    if (clusterNeed.values.all { it <= 0 }) advanceSegment()
                    return@mapIndexed keep(mark, PunchKind.Ok)
                }
                if (mark.cp in futureCps()) {
                    val laterSame = sorted.subList(i + 1, finishIdx + 1).any { it.cp == mark.cp }
                    if (laterSame) {
                        return@mapIndexed keep(mark, PunchKind.Extra)
                    }
                    return@mapIndexed afterError(mark)
                }
                keep(mark, if (mark.cp in courseCps) PunchKind.Ok else PunchKind.Extra)
            }
        }
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

fun courseSubsequenceTaken(
    punches: List<Int>,
    required: List<Int>,
    addCounts: Map<Int, Int> = emptyMap(),
    randomOrder: List<Int> = emptyList(),
): Int = courseTakenWithOrder(punches, required, randomOrder, addCounts)

fun orientCourseSequence(
    controls: List<Int>,
    startCp: Int,
    finishCp: Int,
    removed: Set<Int>,
    randomOrder: List<Int> = emptyList(),
): Pair<List<Int>, List<Int>> {
    val groups = parseClusterGroups(randomOrder, controls.size)
    val kept = ArrayList<Int>()
    val keptGroups = ArrayList<Int>()
    for (i in controls.indices) {
        if (controls[i] in removed) continue
        kept += controls[i]
        keptGroups += groups[i]
    }
    val seq = ArrayList<Int>()
    val seqGroups = ArrayList<Int>()
    if (kept.firstOrNull() != startCp) {
        seq += startCp
        seqGroups += 0
    }
    for (i in kept.indices) {
        seq += kept[i]
        seqGroups += keptGroups[i]
    }
    if (seq.lastOrNull() != finishCp && finishCp > 0) {
        seq += finishCp
        seqGroups += 0
    }
    return seq to seqGroups
}

/**
 * Перегоны для исключения времени.
 * Внутри грозди перегонов нет; вход (заданный КП → гроздь), выход (гроздь → заданный КП)
 * и переход между смежными упорядоченными гроздьями (последний КП первой → первый КП второй) остаются.
 * Для грозди в паре хранится первый/последний КП списка как представитель.
 */
fun orientCourseLegs(
    controls: List<Int>,
    startCp: Int,
    finishCp: Int,
    removed: Set<Int>,
    randomOrder: List<Int> = emptyList(),
): List<Pair<Int, Int>> {
    val (seq, seqGroups) = orientCourseSequence(controls, startCp, finishCp, removed, randomOrder)
    val out = ArrayList<Pair<Int, Int>>()
    val segments = courseSegments(seq, seqGroups)
    for (i in 0 until segments.lastIndex) {
        val a = segments[i]
        val b = segments[i + 1]
        when {
            a is CourseSegment.Fixed && b is CourseSegment.Fixed -> {
                if (a.cp != b.cp) out += a.cp to b.cp
            }
            a is CourseSegment.Fixed && b is CourseSegment.Cluster -> {
                out += a.cp to b.cps.first()
            }
            a is CourseSegment.Cluster && b is CourseSegment.Fixed -> {
                out += a.cps.last() to b.cp
            }
            a is CourseSegment.Cluster && b is CourseSegment.Cluster -> {
                out += a.cps.last() to b.cps.first()
            }
        }
    }
    return out
}

/** Наборы КП для from/to с учётом входа/выхода грозди. */
fun exclusionEndpointSets(
    fromCp: Int,
    toCp: Int,
    controls: List<Int>,
    startCp: Int,
    finishCp: Int,
    randomOrder: List<Int> = emptyList(),
    removed: Set<Int> = emptySet(),
): Pair<Set<Int>, Set<Int>> {
    val (seq, seqGroups) = orientCourseSequence(controls, startCp, finishCp, removed, randomOrder)
    val segments = courseSegments(seq, seqGroups)
    for (i in 0 until segments.lastIndex) {
        val a = segments[i]
        val b = segments[i + 1]
        when {
            a is CourseSegment.Fixed && b is CourseSegment.Cluster &&
                a.cp == fromCp && toCp in b.cps -> return setOf(fromCp) to b.cps.toSet()
            a is CourseSegment.Cluster && b is CourseSegment.Fixed &&
                fromCp in a.cps && b.cp == toCp -> return a.cps.toSet() to setOf(toCp)
            a is CourseSegment.Cluster && b is CourseSegment.Cluster &&
                fromCp in a.cps && toCp in b.cps -> return a.cps.toSet() to b.cps.toSet()
        }
    }
    return setOf(fromCp) to setOf(toCp)
}

fun formatOrientLegLabel(
    fromCp: Int,
    toCp: Int,
    controls: List<Int>,
    startCp: Int,
    finishCp: Int,
    randomOrder: List<Int> = emptyList(),
    removed: Set<Int> = emptySet(),
    formatCp: (Int) -> String = { it.toString() },
): String {
    val (seq, seqGroups) = orientCourseSequence(controls, startCp, finishCp, removed, randomOrder)
    val segments = courseSegments(seq, seqGroups)
    fun clusterLabel(cps: List<Int>) = "[${cps.joinToString("·")}]"
    for (i in 0 until segments.lastIndex) {
        val a = segments[i]
        val b = segments[i + 1]
        when {
            a is CourseSegment.Fixed && b is CourseSegment.Cluster &&
                a.cp == fromCp && toCp == b.cps.first() ->
                return "${formatCp(fromCp)} → ${clusterLabel(b.cps)}"
            a is CourseSegment.Cluster && b is CourseSegment.Fixed &&
                fromCp == a.cps.last() && b.cp == toCp ->
                return "${clusterLabel(a.cps)} → ${formatCp(toCp)}"
            a is CourseSegment.Cluster && b is CourseSegment.Cluster &&
                fromCp == a.cps.last() && toCp == b.cps.first() ->
                return "${clusterLabel(a.cps)} → ${clusterLabel(b.cps)}"
        }
    }
    return "${formatCp(fromCp)} → ${formatCp(toCp)}"
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
    randomOrder: List<Int> = emptyList(),
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
        val (fromSet, toSet) = exclusionEndpointSets(
            rule.fromCp,
            toCp,
            required,
            startCp,
            finishCp,
            randomOrder,
        )
        var pending: Int? = null
        for (mark in window) {
            if (mark.cp in fromSet) {
                pending = mark.seconds
            } else if (mark.cp in toSet) {
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
    addCpTimes: Map<Int, Int> = emptyMap(),
    courseRandomOrder: Map<String, List<Int>> = emptyMap(),
): OrientCalc {
    val diagnostics = mutableListOf<String>()
    val name = courseName.trim()
    val key = courseKey(name)
    val requiredPair: Pair<List<Int>, List<Int>>? = when {
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
        else -> filterCourseWithOrder(
            courses.getValue(key),
            courseRandomOrder[key].orEmpty(),
            removedCps,
        )
    }
    return calculateOrientResultInner(
        marks = marks,
        settings = settings,
        required = requiredPair?.first,
        requiredRandomOrder = requiredPair?.second.orEmpty(),
        key = key,
        diagnostics = diagnostics,
        exclusions = exclusions,
        addCpCounts = addCpCounts,
        addCpTimes = addCpTimes,
    )
}

private fun calculateOrientResultInner(
    marks: List<OrientMark>,
    settings: OrientSettings,
    required: List<Int>?,
    requiredRandomOrder: List<Int>,
    key: String,
    diagnostics: MutableList<String>,
    exclusions: List<OrientExclusion>,
    addCpCounts: Map<Int, Int>,
    addCpTimes: Map<Int, Int>,
): OrientCalc {
    val startCp = settings.startCp
    val sorted = marksWithAddedStart(
        orientMarkOrder(marks),
        startCp,
        addCpCounts,
        addCpTimes,
    )
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
    val window = sorted.subList(startIdx, windowEnd + 1).toList()
    // Как на десктопе: addCounts закрывают слоты в матчере гроздей без опоры на порядок вставки.
    val windowPunches = window.map { it.cp }
    val taken = required?.let {
        courseSubsequenceTaken(windowPunches, it, addCpCounts, requiredRandomOrder)
    } ?: 0
    val expanded = required?.let {
        applyAddedCps(window, it, addCpCounts, startCp, settings.finishCp, addCpTimes, requiredRandomOrder)
    } ?: window
    val orderBroken = required != null &&
        classifyActualPunches(expanded, required, startCp, settings.finishCp, requiredRandomOrder)
            .any { it.wrong }
    // Матчер уже останавливается на раннем КП следующего сегмента; orderBroken — доп. страховка.
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
            requiredRandomOrder,
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

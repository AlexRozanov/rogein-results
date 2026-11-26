package ru.rogein.chip.parse

sealed class CourseSegment {
    data class Fixed(val cp: Int) : CourseSegment()
    data class Cluster(val cps: List<Int>) : CourseSegment()
}

/**
 * Группы гроздей: 0 = заданный порядок, >0 = id грозди.
 * Подряд идущие КП с одним и тем же id — одна гроздь; разные id подряд — смежные грозди
 * ([74 75 76] → [77 78]): первую нужно закрыть до второй.
 *
 * Старые boolean / 0·1: каждый непрерывный run единиц получает свой id.
 */
fun normalizeClusterGroups(controlCount: Int, groups: List<Int>?): List<Int> {
    if (controlCount <= 0) return emptyList()
    val src = groups.orEmpty()
    return List(controlCount) { src.getOrElse(it) { 0 }.coerceAtLeast(0) }
}

/** Совместимость: List&lt;Boolean&gt; → группы с уникальным id на каждый run. */
fun normalizeRandomOrder(controlCount: Int, flags: List<Boolean>?): List<Boolean> {
    if (controlCount <= 0) return emptyList()
    val src = flags.orEmpty()
    return List(controlCount) { src.getOrElse(it) { false } }
}

fun clusterGroupsFromFlags(controlCount: Int, flags: List<Boolean>?): List<Int> {
    val bools = normalizeRandomOrder(controlCount, flags)
    return boolRunsToGroups(bools)
}

fun boolRunsToGroups(flags: List<Boolean>): List<Int> {
    val out = MutableList(flags.size) { 0 }
    var nextId = 1
    var i = 0
    while (i < flags.size) {
        if (!flags[i]) {
            i += 1
            continue
        }
        val id = nextId++
        while (i < flags.size && flags[i]) {
            out[i] = id
            i += 1
        }
    }
    return out
}

/**
 * Разбор JSON/пакетов: boolean, 0/1 или явные id групп (>1).
 * Если все значения ≤1 — трактуем как флаги и нумеруем runs (чтобы 0,1,1,0,1 → две грозди).
 */
fun parseClusterGroups(raw: List<*>?, controlCount: Int): List<Int> {
    if (controlCount <= 0) return emptyList()
    if (raw.isNullOrEmpty()) return List(controlCount) { 0 }
    val nums = ArrayList<Int>(controlCount)
    var anyAboveOne = false
    for (i in 0 until controlCount) {
        val value = raw.getOrNull(i)
        val n = when (value) {
            null -> 0
            is Boolean -> if (value) 1 else 0
            is Number -> value.toInt().coerceAtLeast(0)
            is String -> when {
                value.equals("true", true) || value == "1" -> 1
                value.equals("false", true) || value == "0" || value.isBlank() -> 0
                else -> value.toIntOrNull()?.coerceAtLeast(0) ?: 0
            }
            else -> 0
        }
        if (n > 1) anyAboveOne = true
        nums += n
    }
    return if (anyAboveOne) {
        normalizeClusterGroups(controlCount, nums)
    } else {
        boolRunsToGroups(nums.map { it > 0 })
    }
}

fun anyClusterGroup(groups: List<Int>?): Boolean =
    groups.orEmpty().any { it > 0 }

fun anyRandomOrder(flags: List<Boolean>?): Boolean =
    flags.orEmpty().any { it }

fun groupsToBoolFlags(groups: List<Int>?): List<Boolean> =
    groups.orEmpty().map { it > 0 }

fun nextClusterGroupId(groups: List<Int>): Int =
    (groups.maxOrNull() ?: 0) + 1

/** Включить/выключить гроздь: выкл→0; вкл — к предыдущей грозди или новый id. */
fun toggleClusterGroup(groups: List<Int>, index: Int, joinPrevious: Boolean = true): List<Int> {
    if (index !in groups.indices) return groups
    val out = groups.toMutableList()
    if (out[index] > 0) {
        out[index] = 0
        return out
    }
    val prev = groups.getOrNull(index - 1)?.takeIf { it > 0 }
    out[index] = when {
        joinPrevious && prev != null -> prev
        else -> nextClusterGroupId(groups)
    }
    return out
}

/**
 * Разделить гроздь после [index]: КП справа в том же run получают новый id.
 * @return null если делить нечего.
 */
fun splitClusterAfter(groups: List<Int>, index: Int): List<Int>? {
    if (index !in groups.indices) return null
    val id = groups[index]
    if (id <= 0) return null
    val next = groups.getOrNull(index + 1) ?: return null
    if (next != id) return null
    val newId = nextClusterGroupId(groups)
    val out = groups.toMutableList()
    var i = index + 1
    while (i < out.size && out[i] == id) {
        out[i] = newId
        i += 1
    }
    return out
}

/** Объединить гроздь в [index] со следующей смежной (если id разные). */
fun mergeClusterWithNext(groups: List<Int>, index: Int): List<Int>? {
    if (index !in groups.indices) return null
    val left = groups[index]
    if (left <= 0) return null
    var end = index
    while (end + 1 < groups.size && groups[end + 1] == left) end += 1
    val rightIdx = end + 1
    if (rightIdx >= groups.size) return null
    val right = groups[rightIdx]
    if (right <= 0 || right == left) return null
    val out = groups.toMutableList()
    for (i in out.indices) {
        if (out[i] == right) out[i] = left
    }
    return out
}

fun courseSegments(controls: List<Int>, randomOrder: List<Int>?): List<CourseSegment> =
    courseSegmentsFromGroups(controls, parseClusterGroups(randomOrder, controls.size))

fun courseSegmentsFromGroups(controls: List<Int>, clusterGroups: List<Int>?): List<CourseSegment> {
    val groups = normalizeClusterGroups(controls.size, clusterGroups)
    val out = ArrayList<CourseSegment>()
    var i = 0
    while (i < controls.size) {
        val g = groups[i]
        if (g > 0) {
            val cluster = ArrayList<Int>()
            while (i < controls.size && groups[i] == g) {
                cluster += controls[i]
                i += 1
            }
            out += CourseSegment.Cluster(cluster)
        } else {
            out += CourseSegment.Fixed(controls[i])
            i += 1
        }
    }
    return out
}

fun filterCourseWithOrder(
    controls: List<Int>,
    randomOrder: List<Int>?,
    removed: Set<Int>,
): Pair<List<Int>, List<Int>> =
    filterCourseWithGroups(controls, parseClusterGroups(randomOrder, controls.size), removed)

fun filterCourseWithGroups(
    controls: List<Int>,
    clusterGroups: List<Int>?,
    removed: Set<Int>,
): Pair<List<Int>, List<Int>> {
    val groups = normalizeClusterGroups(controls.size, clusterGroups)
    val cps = ArrayList<Int>()
    val outGroups = ArrayList<Int>()
    for (i in controls.indices) {
        if (controls[i] in removed) continue
        cps += controls[i]
        outGroups += groups[i]
    }
    return cps to outGroups
}

private fun segmentCps(seg: CourseSegment): List<Int> = when (seg) {
    is CourseSegment.Fixed -> listOf(seg.cp)
    is CourseSegment.Cluster -> seg.cps
}

private fun futureRequired(segments: List<CourseSegment>, from: Int): Set<Int> {
    val out = HashSet<Int>()
    for (i in from until segments.size) {
        out += segmentCps(segments[i])
    }
    return out
}

/**
 * How many required CPs are satisfied under fixed/cluster rules.
 * Punching a later-segment CP before the current segment is done stops matching.
 * Adjacent clusters are ordered: first cluster must be completed before the next.
 */
fun courseTakenWithOrder(
    punches: List<Int>,
    controls: List<Int>,
    randomOrder: List<Int>? = null,
    addCounts: Map<Int, Int> = emptyMap(),
): Int = courseTakenWithGroups(
    punches,
    controls,
    parseClusterGroups(randomOrder, controls.size),
    addCounts,
)

fun courseTakenWithGroups(
    punches: List<Int>,
    controls: List<Int>,
    clusterGroups: List<Int>? = null,
    addCounts: Map<Int, Int> = emptyMap(),
): Int {
    if (controls.isEmpty()) return 0
    val segments = courseSegmentsFromGroups(controls, clusterGroups)
    val remainingAdds = addCounts.toMutableMap()
    var p = 0
    var taken = 0
    for ((segIdx, seg) in segments.withIndex()) {
        val future = futureRequired(segments, segIdx + 1)
        when (seg) {
            is CourseSegment.Fixed -> {
                var q = p
                while (q < punches.size && punches[q] != seg.cp) q += 1
                if (q < punches.size && punches[q] == seg.cp) {
                    p = q + 1
                    taken += 1
                    continue
                }
                val left = remainingAdds[seg.cp] ?: 0
                if (left > 0) {
                    remainingAdds[seg.cp] = left - 1
                    taken += 1
                    continue
                }
                return taken
            }
            is CourseSegment.Cluster -> {
                val need = LinkedHashMap<Int, Int>()
                for (cp in seg.cps) {
                    need[cp] = (need[cp] ?: 0) + 1
                }
                while (need.values.any { it > 0 }) {
                    var foundIdx = -1
                    var foundCp = 0
                    var q = p
                    while (q < punches.size) {
                        val cp = punches[q]
                        if ((need[cp] ?: 0) > 0) {
                            foundIdx = q
                            foundCp = cp
                            break
                        }
                        if (cp in future) break
                        q += 1
                    }
                    if (foundIdx >= 0) {
                        need[foundCp] = (need[foundCp] ?: 1) - 1
                        p = foundIdx + 1
                        taken += 1
                        continue
                    }
                    var usedAdd = false
                    for ((cp, count) in need) {
                        if (count <= 0) continue
                        val left = remainingAdds[cp] ?: 0
                        if (left > 0) {
                            remainingAdds[cp] = left - 1
                            need[cp] = count - 1
                            taken += 1
                            usedAdd = true
                            break
                        }
                    }
                    if (!usedAdd) return taken
                }
            }
        }
    }
    return taken
}

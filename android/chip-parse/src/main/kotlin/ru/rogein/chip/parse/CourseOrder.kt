package ru.rogein.chip.parse

sealed class CourseSegment {
    data class Fixed(val cp: Int) : CourseSegment()
    data class Cluster(val cps: List<Int>) : CourseSegment()
}

fun normalizeRandomOrder(controlCount: Int, flags: List<Boolean>?): List<Boolean> {
    if (controlCount <= 0) return emptyList()
    val src = flags.orEmpty()
    return List(controlCount) { src.getOrElse(it) { false } }
}

fun anyRandomOrder(flags: List<Boolean>?): Boolean =
    flags.orEmpty().any { it }

fun courseSegments(controls: List<Int>, randomOrder: List<Boolean>?): List<CourseSegment> {
    val flags = normalizeRandomOrder(controls.size, randomOrder)
    val out = ArrayList<CourseSegment>()
    var i = 0
    while (i < controls.size) {
        if (flags[i]) {
            val cluster = ArrayList<Int>()
            while (i < controls.size && flags[i]) {
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
    randomOrder: List<Boolean>?,
    removed: Set<Int>,
): Pair<List<Int>, List<Boolean>> {
    val flags = normalizeRandomOrder(controls.size, randomOrder)
    val cps = ArrayList<Int>()
    val ro = ArrayList<Boolean>()
    for (i in controls.indices) {
        if (controls[i] in removed) continue
        cps += controls[i]
        ro += flags[i]
    }
    return cps to ro
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
 */
fun courseTakenWithOrder(
    punches: List<Int>,
    controls: List<Int>,
    randomOrder: List<Boolean>? = null,
    addCounts: Map<Int, Int> = emptyMap(),
): Int {
    if (controls.isEmpty()) return 0
    val segments = courseSegments(controls, randomOrder)
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

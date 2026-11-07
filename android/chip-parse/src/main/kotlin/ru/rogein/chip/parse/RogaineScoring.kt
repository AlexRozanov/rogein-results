package ru.rogein.chip.parse

data class RogaineSettings(
    val startCp: Int = CP_START,
    val finishCp: Int = CP_FINISH,
    val controlMinutes: Int = 240,
    val penaltyPerMinute: Int = 1,
    val dqMinutes: Int = 15,
    val startMode: String = "station",
    val competitionStartTime: String = "",
)

data class RogaineFormatOverride(
    val controlMinutes: Int? = null,
    val penaltyPerMinute: Int? = null,
    val dqMinutes: Int? = null,
    val finishCp: Int? = null,
    val competitionStartTime: String = "",
    val cpTypeRules: List<RogaineCpTypeRule> = emptyList(),
)

data class RogaineCpTypeRule(
    val type: String,
    val maxCount: Int? = null,
)

fun applyRogaineFormat(global: RogaineSettings, override: RogaineFormatOverride?): RogaineSettings {
    if (override == null) return global
    return global.copy(
        controlMinutes = override.controlMinutes ?: global.controlMinutes,
        penaltyPerMinute = override.penaltyPerMinute ?: global.penaltyPerMinute,
        dqMinutes = override.dqMinutes ?: global.dqMinutes,
        finishCp = override.finishCp ?: global.finishCp,
        competitionStartTime = override.competitionStartTime.ifBlank { global.competitionStartTime },
    )
}

const val ROGAINE_REMOVE_LEGS = "remove_legs"
const val ROGAINE_REMOVE_POINTS = "points_only"

fun calculateRogaineResult(
    marks: List<OrientMark>,
    settings: RogaineSettings,
    removedCps: Set<Int> = emptySet(),
    addedCps: Set<Int> = emptySet(),
    exclusions: List<OrientExclusion> = emptyList(),
    courseKey: String? = null,
    removeLegsCps: Set<Int> = emptySet(),
    cpTypeByNumber: Map<Int, String> = emptyMap(),
    cpTypeRules: List<RogaineCpTypeRule> = emptyList(),
): OrientCalc {
    val diagnostics = mutableListOf<String>()
    val timeStart = settings.startMode == "time"
    val startCp = settings.startCp.takeIf { it > 0 }
    val finishCp = settings.finishCp
    val sorted = orientMarkOrder(marks)
    val effective = when {
        !timeStart && startCp == null -> {
            diagnostics += "start_cp_not_configured"
            emptyList()
        }
        startCp != null -> {
            val idx = sorted.indexOfFirst { it.cp == startCp }
            if (idx < 0) {
                diagnostics += "start_missing"
                emptyList()
            } else {
                sorted.subList(idx, sorted.size)
            }
        }
        else -> sorted
    }
    if (effective.isEmpty()) {
        if (sorted.isEmpty()) diagnostics += "no_marks"
        return OrientCalc(STATUS_ERR, 0, 0, diagnostics)
    }
    if (effective.none { it.cp == finishCp }) diagnostics += "finish_missing"
    if (effective.last().cp != finishCp) diagnostics += "finish_not_last"

    val clockStart = if (timeStart) {
        parseDaySeconds(settings.competitionStartTime).also {
            if (it == null) diagnostics += "start_time_not_configured"
        }
    } else {
        effective.first().seconds
    }
    var elapsed = 0
    if (clockStart != null) {
        val finishTime = effective.last().seconds
        elapsed = punchLegSeconds(clockStart, finishTime)
        elapsed = applyOrientExclusionRules(
            effective,
            emptyList(),
            startCp ?: 0,
            finishCp,
            courseKey,
            exclusions,
            elapsed,
        )
        if (removeLegsCps.isNotEmpty()) {
            elapsed = applyRemovedCpLegs(effective, removeLegsCps, elapsed)
        }
    }

    val unique = LinkedHashSet<Int>()
    for (mark in effective) {
        if (mark.cp == startCp || mark.cp == finishCp) continue
        if (mark.cp in removedCps) continue
        unique += mark.cp
    }
    for (cp in addedCps) {
        if (cp == startCp || cp == finishCp || cp in removedCps) continue
        unique += cp
    }
    val scored = filterCpsByFormatTypeRules(unique.toList(), cpTypeByNumber, cpTypeRules)
    val pointsRaw = scored.sumOf { it / 10 }
    val controlSeconds = settings.controlMinutes.coerceAtLeast(0) * 60
    val dqSeconds = (settings.controlMinutes.coerceAtLeast(0) + settings.dqMinutes.coerceAtLeast(0)) * 60
    val delay = (elapsed - controlSeconds).coerceAtLeast(0)
    val penaltyMinutes = if (delay > 0) (delay + 59) / 60 else 0
    val penaltyPoints = penaltyMinutes * settings.penaltyPerMinute.coerceAtLeast(0)
    val pointsFinal = pointsRaw - penaltyPoints
    val status = when {
        diagnostics.isNotEmpty() -> STATUS_ERR
        elapsed > dqSeconds -> STATUS_DQ
        else -> STATUS_OK
    }
    return OrientCalc(
        status = status,
        taken = scored.size,
        elapsedSeconds = elapsed,
        diagnostics = diagnostics,
        pointsRaw = pointsRaw,
        penaltyPoints = penaltyPoints,
        pointsFinal = pointsFinal,
        penaltyMinutes = penaltyMinutes,
    )
}

fun filterCpsByFormatTypeRules(
    orderedCps: List<Int>,
    cpTypeByNumber: Map<Int, String>,
    rules: List<RogaineCpTypeRule>,
): List<Int> {
    if (rules.isEmpty()) return orderedCps
    val maxByType = LinkedHashMap<String, Int?>()
    for (rule in rules) {
        val key = rule.type.trim().lowercase()
        if (key.isEmpty()) continue
        maxByType[key] = rule.maxCount?.takeIf { it > 0 }
    }
    if (maxByType.isEmpty()) return orderedCps
    val taken = HashMap<String, Int>()
    val kept = ArrayList<Int>()
    for (cp in orderedCps) {
        val type = cpTypeByNumber[cp]?.trim()?.lowercase().orEmpty()
        if (type.isEmpty() || type !in maxByType) continue
        val used = taken[type] ?: 0
        val limit = maxByType[type]
        if (limit != null && used >= limit) continue
        taken[type] = used + 1
        kept += cp
    }
    return kept
}

fun applyRemovedCpLegs(marks: List<OrientMark>, removedCps: Set<Int>, elapsedSeconds: Int): Int {
    if (marks.size < 2 || removedCps.isEmpty()) return elapsedSeconds
    val sorted = orientMarkOrder(marks)
    val legs = HashSet<Int>()
    sorted.forEachIndexed { index, mark ->
        if (mark.cp !in removedCps) return@forEachIndexed
        if (index > 0) legs += index - 1
        if (index + 1 < sorted.size) legs += index
    }
    var subtract = 0
    for (leg in legs) {
        subtract += punchLegSeconds(sorted[leg].seconds, sorted[leg + 1].seconds)
    }
    return (elapsedSeconds - subtract).coerceAtLeast(0)
}

fun assignRogainePlaces(results: List<OrientCalc>): List<Int?> {
    val ok = results.withIndex()
        .filter { it.value.ok }
        .sortedWith(compareByDescending<IndexedValue<OrientCalc>> { it.value.pointsFinal }.thenBy { it.value.elapsedSeconds })
    val places = MutableList<Int?>(results.size) { null }
    var place = 1
    var prev: Pair<Int, Int>? = null
    var seen = 0
    for ((index, calc) in ok) {
        seen += 1
        val key = calc.pointsFinal to calc.elapsedSeconds
        if (prev != key) {
            place = seen
            prev = key
        }
        places[index] = place
    }
    return places
}

/** Команда — одна зачётная единица: место по лучшему члену, все получают одно место. */
fun assignRogaineTeamPlaces(results: List<OrientCalc>, teamIds: List<Int>): List<Int?> {
    data class Unit(val members: List<Int>, val points: Int, val time: Int)
    val buckets = LinkedHashMap<String, ArrayList<Int>>()
    for (index in results.indices) {
        if (!results[index].ok) continue
        val teamId = teamIds.getOrElse(index) { 0 }
        val key = if (teamId > 0) "t$teamId" else "p$index"
        buckets.getOrPut(key) { ArrayList() }.add(index)
    }
    val units = buckets.map { (_, members) ->
        val best = members.minWith(
            compareByDescending<Int> { results[it].pointsFinal }.thenBy { results[it].elapsedSeconds },
        )
        Unit(members, results[best].pointsFinal, results[best].elapsedSeconds)
    }.sortedWith(compareByDescending<Unit> { it.points }.thenBy { it.time })
    val places = MutableList<Int?>(results.size) { null }
    var place = 1
    var prev: Pair<Int, Int>? = null
    var seen = 0
    for (unit in units) {
        seen += 1
        val key = unit.points to unit.time
        if (prev != key) {
            place = seen
            prev = key
        }
        for (index in unit.members) {
            places[index] = place
        }
    }
    return places
}

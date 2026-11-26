package ru.rogein.chip.parse

import org.json.JSONArray
import org.json.JSONObject

data class StartPackFile(
    val revision: Long,
    val sport: String,
    val courses: List<StartPackCourseFile>,
    val people: List<StartPackPersonFile>,
    val mapScaleDenominator: Int? = null,
    val controlPositionsMm: Map<Int, Pair<Double, Double>> = emptyMap(),
    /** null = поле отсутствует в JSON (старый пакет) — исключения на телефоне не трогаем. */
    val exclusions: List<EventPackExclusionFile>? = null,
)

data class StartPackCourseFile(
    val name: String,
    val controls: List<Int>,
    val lengthMeters: Int? = null,
    val climbMeters: Int? = null,
    val legMeters: List<Int?> = emptyList(),
    val randomOrder: List<Int> = emptyList(),
)

data class StartPackPersonFile(
    val id: Int,
    val name: String,
    val courses: List<String>,
    val currentCourse: String,
)

fun parseStartPackJson(text: String): StartPackFile {
    val root = JSONObject(text)
    val courses = jsonArray(root.optJSONArray("courses")).mapNotNull { item ->
        val obj = item as? JSONObject ?: return@mapNotNull null
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        val controls = jsonArray(obj.optJSONArray("controls")).mapNotNull { value ->
            when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }
        val rawOrder = jsonArray(obj.optJSONArray("randomOrder"))
        StartPackCourseFile(
            name = name,
            controls = controls,
            lengthMeters = obj.optIntOrNull("lengthMeters"),
            climbMeters = obj.optIntOrNull("climbMeters"),
            legMeters = jsonArray(obj.optJSONArray("legMeters")).map { value ->
                when (value) {
                    JSONObject.NULL -> null
                    is Number -> value.toInt().takeIf { it > 0 }
                    is String -> value.toIntOrNull()?.takeIf { it > 0 }
                    else -> null
                }
            },
            randomOrder = parseClusterGroups(rawOrder, controls.size),
        )
    }
    val people = jsonArray(root.optJSONArray("people")).mapNotNull { item ->
        val obj = item as? JSONObject ?: return@mapNotNull null
        val id = obj.optInt("id", 0)
        val name = obj.optString("name").trim()
        if (id <= 0 || name.isEmpty()) return@mapNotNull null
        val courseNames = jsonArray(obj.optJSONArray("courses")).mapNotNull { value ->
            value.toString().trim().takeIf { it.isNotEmpty() }
        }
        val current = obj.optString("currentCourse").trim()
        StartPackPersonFile(
            id = id,
            name = name,
            courses = if (courseNames.isEmpty() && current.isNotEmpty()) listOf(current) else courseNames,
            currentCourse = current,
        )
    }
    val controlPositionsMm = LinkedHashMap<Int, Pair<Double, Double>>()
    val positionsObj = root.optJSONObject("controlPositionsMm")
        ?: root.optJSONObject("control_positions_mm")
    if (positionsObj != null) {
        val keys = positionsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val cp = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val arr = positionsObj.optJSONArray(key) ?: continue
            if (arr.length() < 2) continue
            val x = arr.optDouble(0, Double.NaN)
            val y = arr.optDouble(1, Double.NaN)
            if (!x.isFinite() || !y.isFinite()) continue
            controlPositionsMm[cp] = x to y
        }
    }
    val mapScale = when {
        root.has("mapScaleDenominator") && !root.isNull("mapScaleDenominator") ->
            root.optInt("mapScaleDenominator").takeIf { it > 0 }
        root.has("map_scale_denominator") && !root.isNull("map_scale_denominator") ->
            root.optInt("map_scale_denominator").takeIf { it > 0 }
        else -> null
    }
    val exclusions = if (root.has("exclusions") && !root.isNull("exclusions")) {
        jsonObjects(root.optJSONArray("exclusions")).mapNotNull { obj ->
            val fromCp = obj.optInt("fromCp", obj.optInt("from_cp", 0))
            val toCp = obj.optInt("toCp", obj.optInt("to_cp", 0))
            if (fromCp <= 0 || toCp <= 0) return@mapNotNull null
            EventPackExclusionFile(
                course = obj.optString("course").trim(),
                fromCp = fromCp,
                toCp = toCp,
                maxLegSeconds = when {
                    obj.has("maxLegSeconds") && !obj.isNull("maxLegSeconds") ->
                        obj.optInt("maxLegSeconds").takeIf { it > 0 }
                    obj.has("max_leg_seconds") && !obj.isNull("max_leg_seconds") ->
                        obj.optInt("max_leg_seconds").takeIf { it > 0 }
                    else -> null
                },
            )
        }
    } else {
        null
    }
    return StartPackFile(
        revision = root.optLong("revision"),
        sport = normalizeSportKind(root.optString("sport")),
        courses = courses,
        people = people,
        mapScaleDenominator = mapScale,
        controlPositionsMm = controlPositionsMm,
        exclusions = exclusions,
    )
}

private fun jsonObjects(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

fun parseChipQueueJson(text: String): ChipQueueFile {
    val root = JSONObject(text)
    val items = jsonArray(root.optJSONArray("items")).mapNotNull { item ->
        val obj = item as? JSONObject ?: return@mapNotNull null
        val id = obj.optString("id").trim()
        if (id.isEmpty()) return@mapNotNull null
        val punches = jsonArray(obj.optJSONArray("punches")).mapNotNull { punch ->
            val row = punch as? JSONObject ?: return@mapNotNull null
            val cp = row.optInt("cp", 0)
            val time = row.optString("time").trim()
            if (cp <= 0 || time.isEmpty()) return@mapNotNull null
            ChipQueuePunchFile(cp, time)
        }
        ChipQueueItemFile(
            id = id,
            uid = obj.optString("uid").trim(),
            logicalId = if (obj.isNull("logicalId")) null else obj.optInt("logicalId"),
            name = obj.optString("name").trim(),
            courseName = obj.optString("courseName").trim(),
            punches = punches,
        )
    }
    return ChipQueueFile(items)
}

data class ChipQueueFile(
    val items: List<ChipQueueItemFile>,
)

data class ChipQueueItemFile(
    val id: String,
    val uid: String,
    val logicalId: Int?,
    val name: String,
    val courseName: String,
    val punches: List<ChipQueuePunchFile>,
)

data class ChipQueuePunchFile(
    val cp: Int,
    val time: String,
)

private fun jsonArray(array: JSONArray?): List<Any> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.get(it) }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = optInt(key, Int.MIN_VALUE)
    return value.takeIf { it != Int.MIN_VALUE && it >= 0 }
}

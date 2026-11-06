package ru.rogein.chip.parse

import org.json.JSONArray
import org.json.JSONObject

data class EventPackFile(
    val revision: Long,
    val sport: String,
    val title: String,
    val settings: EventPackSettingsFile,
    val formats: List<EventPackFormatFile>,
    val awardGroups: List<EventPackAwardGroupFile>,
    val people: List<EventPackPersonFile>,
    val legends: List<EventPackLegendFile>,
    val exclusions: List<EventPackExclusionFile>,
    val mapPoints: List<EventPackMapPointFile>,
    val metersPerPixel: Double?,
    val mapFileName: String,
    val mapWidth: Int? = null,
    val mapHeight: Int? = null,
)

data class EventPackSettingsFile(
    val controlMinutes: Int,
    val penaltyPerMinute: Int,
    val dqMinutes: Int,
    val startCp: Int,
    val finishCp: Int,
    val startMode: String,
    val competitionDate: String,
    val competitionStartTime: String,
)

data class EventPackFormatFile(
    val name: String,
    val controlMinutes: Int?,
    val penaltyPerMinute: Int?,
    val dqMinutes: Int?,
    val finishCp: Int?,
    val competitionStartTime: String,
    val controls: List<Int> = emptyList(),
    val cpTypeRules: List<EventPackCpTypeRuleFile> = emptyList(),
)

data class EventPackCpTypeRuleFile(
    val type: String,
    val maxCount: Int?,
)

data class EventPackAwardGroupFile(
    val name: String,
    val genderMode: String,
    val minAge: Int?,
    val sortOrder: Int,
    val formatNames: List<String>,
)

data class EventPackPersonFile(
    val id: Int,
    val name: String,
    val format: String,
    val gender: String,
    val birthDate: String,
    val teamId: Int,
    val team: String,
)

data class EventPackLegendFile(
    val cp: Int,
    val name: String,
    val type: String,
    val mapX: Double?,
    val mapY: Double?,
)

data class EventPackExclusionFile(
    val course: String,
    val fromCp: Int,
    val toCp: Int,
    val maxLegSeconds: Int?,
)

data class EventPackMapPointFile(
    val kind: String,
    val cp: Int?,
    val name: String,
    val mapX: Double,
    val mapY: Double,
)

fun parseEventPackJson(text: String): EventPackFile {
    val root = JSONObject(text)
    val settingsObj = root.optJSONObject("settings") ?: JSONObject()
    val formats = jsonObjects(root.optArr("formats")).mapNotNull { obj ->
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        EventPackFormatFile(
            name = name,
            controlMinutes = objInt(obj, "controlMinutes", "control_minutes"),
            penaltyPerMinute = objInt(obj, "penaltyPerMinute", "penalty_per_minute"),
            dqMinutes = objInt(obj, "dqMinutes", "dq_minutes"),
            finishCp = objInt(obj, "finishCp", "finish_cp"),
            competitionStartTime = obj.optStr("competitionStartTime", "competition_start_time"),
            controls = jsonInts(obj.optArr("controls", "cps")),
            cpTypeRules = jsonObjects(obj.optArr("cpTypeRules", "cp_type_rules")).mapNotNull { rule ->
                val type = rule.optStr("type", "cpType", "cp_type")
                if (type.isEmpty()) return@mapNotNull null
                EventPackCpTypeRuleFile(
                    type = type,
                    maxCount = objInt(rule, "maxCount", "max_count")?.takeIf { it > 0 },
                )
            },
        )
    }
    val awardGroups = jsonObjects(root.optArr("awardGroups", "award_groups")).mapNotNull { obj ->
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        EventPackAwardGroupFile(
            name = name,
            genderMode = obj.optStr("genderMode", "gender_mode").ifBlank { "any" },
            minAge = objInt(obj, "minAge", "min_age"),
            sortOrder = obj.optInt("sortOrder", obj.optInt("sort_order", 0)),
            formatNames = jsonArray(obj.optArr("formatNames", "format_names")).mapNotNull { value ->
                value.toString().trim().takeIf { it.isNotEmpty() }
            },
        )
    }
    val people = jsonObjects(root.optJSONArray("people")).mapNotNull { obj ->
        val id = obj.optInt("id", 0)
        val name = obj.optString("name").trim()
        if (id <= 0 || name.isEmpty()) return@mapNotNull null
        val format = obj.optString("format").trim().ifBlank {
            jsonArray(obj.optJSONArray("courses")).mapNotNull { value ->
                value.toString().trim().takeIf { it.isNotEmpty() }
            }.firstOrNull().orEmpty().ifBlank { obj.optString("currentCourse").trim() }
        }
        EventPackPersonFile(
            id = id,
            name = name,
            format = format,
            gender = obj.optString("gender").trim(),
            birthDate = obj.optString("birthDate").trim().ifBlank { obj.optString("birth").trim() },
            teamId = objInt(obj, "teamId", "team_id")?.takeIf { it > 0 } ?: 0,
            team = obj.optString("team").trim(),
        )
    }
    val legends = jsonObjects(root.optArr("legends")).mapNotNull { obj ->
        val cp = objInt(obj, "cp", "cpNumber", "cp_number") ?: 0
        if (cp <= 0) return@mapNotNull null
        EventPackLegendFile(
            cp = cp,
            name = obj.optString("name").trim(),
            type = obj.optStr("type", "cpType", "cp_type"),
            mapX = objDouble(obj, "mapX", "map_x"),
            mapY = objDouble(obj, "mapY", "map_y"),
        )
    }
    val exclusions = jsonObjects(root.optJSONArray("exclusions")).mapNotNull { obj ->
        val fromCp = obj.optInt("fromCp", 0)
        val toCp = obj.optInt("toCp", 0)
        if (fromCp <= 0 || toCp <= 0) return@mapNotNull null
        EventPackExclusionFile(
            course = obj.optString("course").trim(),
            fromCp = fromCp,
            toCp = toCp,
            maxLegSeconds = objInt(obj, "maxLegSeconds"),
        )
    }
    val mapPoints = jsonObjects(root.optArr("mapPoints", "map_points")).mapNotNull { obj ->
        val mapX = objDouble(obj, "mapX", "map_x") ?: return@mapNotNull null
        val mapY = objDouble(obj, "mapY", "map_y") ?: return@mapNotNull null
        EventPackMapPointFile(
            kind = obj.optString("kind").trim().ifBlank { "cp" },
            cp = objInt(obj, "cp", "cp_number"),
            name = obj.optString("name").trim(),
            mapX = mapX,
            mapY = mapY,
        )
    }
    return EventPackFile(
        revision = root.optLong("revision"),
        sport = run {
            val raw = root.optString("sport").trim()
            if (raw.isEmpty()) SPORT_ROGAINE else normalizeSportKind(raw)
        },
        title = root.optString("title").trim(),
        settings = EventPackSettingsFile(
            controlMinutes = objInt(settingsObj, "controlMinutes", "control_minutes") ?: 120,
            penaltyPerMinute = objInt(settingsObj, "penaltyPerMinute", "penalty_per_minute") ?: 1,
            dqMinutes = objInt(settingsObj, "dqMinutes", "dq_minutes") ?: 15,
            startCp = objInt(settingsObj, "startCp", "start_cp") ?: CP_START,
            finishCp = objInt(settingsObj, "finishCp", "finish_cp") ?: CP_FINISH,
            startMode = settingsObj.optStr("startMode", "start_mode").ifBlank { "station" },
            competitionDate = settingsObj.optStr("competitionDate", "competition_date"),
            competitionStartTime = settingsObj.optStr("competitionStartTime", "competition_start_time"),
        ),
        formats = formats,
        awardGroups = awardGroups,
        people = people,
        legends = legends,
        exclusions = exclusions,
        mapPoints = mapPoints,
        metersPerPixel = objDouble(root, "metersPerPixel", "meters_per_pixel"),
        mapWidth = objInt(root, "mapWidth", "map_width")?.takeIf { it > 0 },
        mapHeight = objInt(root, "mapHeight", "map_height")?.takeIf { it > 0 },
        mapFileName = root.optStr("mapFileName", "map_file_name"),
    )
}

private fun jsonObjects(array: JSONArray?): List<JSONObject> =
    jsonArray(array).mapNotNull { it as? JSONObject }

private fun jsonArray(array: JSONArray?): List<Any> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.get(it) }
}

private fun jsonInts(array: JSONArray?): List<Int> =
    jsonArray(array).mapNotNull { value ->
        when (value) {
            is Number -> value.toInt().takeIf { it > 0 }
            is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

private fun objInt(obj: JSONObject, vararg keys: String): Int? {
    for (key in keys) {
        if (!obj.has(key) || obj.isNull(key)) continue
        val value = obj.opt(key)
        val parsed = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
        if (parsed != null) return parsed
    }
    return null
}

private fun objDouble(obj: JSONObject, vararg keys: String): Double? {
    for (key in keys) {
        if (!obj.has(key) || obj.isNull(key)) continue
        val value = obj.optDouble(key)
        if (value.isFinite()) return value
    }
    return null
}

private fun JSONObject.optArr(vararg keys: String): JSONArray? {
    for (key in keys) {
        optJSONArray(key)?.let { return it }
    }
    return null
}

private fun JSONObject.optStr(vararg keys: String): String {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = optString(key).trim()
        if (value.isNotEmpty()) return value
    }
    return ""
}

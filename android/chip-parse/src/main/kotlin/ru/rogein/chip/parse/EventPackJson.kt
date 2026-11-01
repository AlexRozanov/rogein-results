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
    val formats = jsonObjects(root.optJSONArray("formats")).mapNotNull { obj ->
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        EventPackFormatFile(
            name = name,
            controlMinutes = objInt(obj, "controlMinutes"),
            penaltyPerMinute = objInt(obj, "penaltyPerMinute"),
            dqMinutes = objInt(obj, "dqMinutes"),
            finishCp = objInt(obj, "finishCp"),
            competitionStartTime = obj.optString("competitionStartTime").trim(),
        )
    }
    val awardGroups = jsonObjects(root.optJSONArray("awardGroups")).mapNotNull { obj ->
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        EventPackAwardGroupFile(
            name = name,
            genderMode = obj.optString("genderMode").trim().ifBlank { "any" },
            minAge = objInt(obj, "minAge"),
            sortOrder = obj.optInt("sortOrder", 0),
            formatNames = jsonArray(obj.optJSONArray("formatNames")).mapNotNull { value ->
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
            team = obj.optString("team").trim(),
        )
    }
    val legends = jsonObjects(root.optJSONArray("legends")).mapNotNull { obj ->
        val cp = obj.optInt("cp", 0)
        if (cp <= 0) return@mapNotNull null
        EventPackLegendFile(
            cp = cp,
            name = obj.optString("name").trim(),
            type = obj.optString("type").trim(),
            mapX = objDouble(obj, "mapX"),
            mapY = objDouble(obj, "mapY"),
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
    val mapPoints = jsonObjects(root.optJSONArray("mapPoints")).mapNotNull { obj ->
        val mapX = objDouble(obj, "mapX") ?: return@mapNotNull null
        val mapY = objDouble(obj, "mapY") ?: return@mapNotNull null
        EventPackMapPointFile(
            kind = obj.optString("kind").trim().ifBlank { "cp" },
            cp = objInt(obj, "cp"),
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
            controlMinutes = settingsObj.optInt("controlMinutes", 120),
            penaltyPerMinute = settingsObj.optInt("penaltyPerMinute", 1),
            dqMinutes = settingsObj.optInt("dqMinutes", 15),
            startCp = settingsObj.optInt("startCp", CP_START),
            finishCp = settingsObj.optInt("finishCp", CP_FINISH),
            startMode = settingsObj.optString("startMode").trim().ifBlank { "station" },
            competitionDate = settingsObj.optString("competitionDate").trim(),
            competitionStartTime = settingsObj.optString("competitionStartTime").trim(),
        ),
        formats = formats,
        awardGroups = awardGroups,
        people = people,
        legends = legends,
        exclusions = exclusions,
        mapPoints = mapPoints,
        metersPerPixel = objDouble(root, "metersPerPixel"),
        mapFileName = root.optString("mapFileName").trim(),
    )
}

private fun jsonObjects(array: JSONArray?): List<JSONObject> =
    jsonArray(array).mapNotNull { it as? JSONObject }

private fun jsonArray(array: JSONArray?): List<Any> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.get(it) }
}

private fun objInt(obj: JSONObject, key: String): Int? {
    if (!obj.has(key) || obj.isNull(key)) return null
    val value = obj.opt(key)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

private fun objDouble(obj: JSONObject, key: String): Double? {
    if (!obj.has(key) || obj.isNull(key)) return null
    val value = obj.optDouble(key)
    return value.takeIf { it.isFinite() }
}

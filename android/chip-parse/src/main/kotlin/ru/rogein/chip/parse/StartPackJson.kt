package ru.rogein.chip.parse

import org.json.JSONArray
import org.json.JSONObject

data class StartPackFile(
    val revision: Long,
    val sport: String,
    val courses: List<StartPackCourseFile>,
    val people: List<StartPackPersonFile>,
)

data class StartPackCourseFile(
    val name: String,
    val controls: List<Int>,
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
        StartPackCourseFile(
            name = name,
            controls = jsonArray(obj.optJSONArray("controls")).mapNotNull { value ->
                when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                }
            },
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
    return StartPackFile(
        revision = root.optLong("revision"),
        sport = normalizeSportKind(root.optString("sport")),
        courses = courses,
        people = people,
    )
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

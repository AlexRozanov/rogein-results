package ru.rogein.chip.startlist

import org.json.JSONArray
import org.json.JSONObject

data class StartPack(
    val revision: Long,
    val sport: String,
    val courses: List<StartCourse>,
    val people: List<StartPerson>,
)

data class StartCourse(
    val name: String,
    val controls: List<Int>,
)

data class StartPerson(
    val id: Int,
    val name: String,
    val courses: List<String>,
    val currentCourse: String,
    val issued: Boolean = false,
)

fun parseStartPack(json: String): StartPack {
    val root = JSONObject(json)
    val courses = jsonArray(root.optJSONArray("courses")).mapNotNull { item ->
        val obj = item as? JSONObject ?: return@mapNotNull null
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        StartCourse(
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
        StartPerson(
            id = id,
            name = name,
            courses = if (courseNames.isEmpty() && current.isNotEmpty()) listOf(current) else courseNames,
            currentCourse = current,
        )
    }
    return StartPack(
        revision = root.optLong("revision"),
        sport = root.optString("sport"),
        courses = courses,
        people = people,
    )
}

private fun jsonArray(array: JSONArray?): List<Any> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.get(it) }
}

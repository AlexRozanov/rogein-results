package ru.rogein.chip.parse.field

import org.json.JSONArray
import org.json.JSONObject

data class PersonUpsertPayload(
    val logicalId: Int,
    val name: String,
    val gender: String,
    val birthDate: String,
    val course: String,
    val courses: List<String>,
)

data class PersonCoursePayload(
    val logicalId: Int,
    val course: String,
    val courses: List<String>,
)

data class PersonDeletePayload(
    val logicalId: Int,
)

fun personUpsertPayloadJson(
    logicalId: Int,
    name: String,
    gender: String,
    birthDate: String,
    course: String,
    courses: List<String>,
): String {
    val obj = JSONObject()
        .put("logical_id", logicalId)
        .put("name", name)
        .put("gender", gender)
        .put("birth_date", birthDate)
        .put("course", course)
    val arr = JSONArray()
    courses.forEach { arr.put(it) }
    obj.put("courses", arr)
    return obj.toString()
}

fun personCoursePayloadJson(
    logicalId: Int,
    course: String,
    courses: List<String>,
): String {
    val obj = JSONObject()
        .put("logical_id", logicalId)
        .put("course", course)
    val arr = JSONArray()
    courses.forEach { arr.put(it) }
    obj.put("courses", arr)
    return obj.toString()
}

fun personDeletePayloadJson(logicalId: Int): String =
    JSONObject().put("logical_id", logicalId).toString()

fun parsePersonUpsertPayload(payloadJson: String): PersonUpsertPayload {
    val obj = JSONObject(payloadJson)
    val coursesArr = obj.optJSONArray("courses") ?: JSONArray()
    val courses = buildList {
        for (i in 0 until coursesArr.length()) {
            val name = coursesArr.optString(i).trim()
            if (name.isNotEmpty()) add(name)
        }
    }
    val course = obj.optString("course").ifBlank {
        courses.firstOrNull().orEmpty()
    }
    return PersonUpsertPayload(
        logicalId = obj.getInt("logical_id"),
        name = obj.optString("name"),
        gender = obj.optString("gender"),
        birthDate = obj.optString("birth_date"),
        course = course,
        courses = courses.ifEmpty { listOfNotNull(course.takeIf { it.isNotBlank() }) },
    )
}

fun parsePersonCoursePayload(payloadJson: String): PersonCoursePayload {
    val obj = JSONObject(payloadJson)
    val coursesArr = obj.optJSONArray("courses") ?: JSONArray()
    val courses = buildList {
        for (i in 0 until coursesArr.length()) {
            val name = coursesArr.optString(i).trim()
            if (name.isNotEmpty()) add(name)
        }
    }
    val course = obj.optString("course")
    return PersonCoursePayload(
        logicalId = obj.getInt("logical_id"),
        course = course,
        courses = courses.ifEmpty { listOfNotNull(course.takeIf { it.isNotBlank() }) },
    )
}

fun parsePersonDeletePayload(payloadJson: String): PersonDeletePayload {
    val obj = JSONObject(payloadJson)
    return PersonDeletePayload(obj.getInt("logical_id"))
}

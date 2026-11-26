package ru.rogein.chip.parse.field

import org.json.JSONArray
import org.json.JSONObject
import ru.rogein.chip.parse.parseClusterGroups

data class QueueDropPayload(val queueId: String)

data class FinishReassignPayload(
    val queueId: String,
    val oldLogicalId: Int?,
    val newLogicalId: Int,
    val uid: String,
)

data class CourseUpsertPayload(
    val name: String,
    val previousName: String,
    val controls: List<Int>,
    val lengthMeters: Int?,
    val climbMeters: Int?,
    val legsJson: String,
    val randomOrder: List<Int> = emptyList(),
)

data class CourseDeletePayload(val name: String)

data class ExclusionUpsertPayload(
    val exclusionId: String,
    val courseName: String,
    val fromCp: Int,
    val toCp: Int,
    val maxLegSeconds: Int?,
)

data class ExclusionDeletePayload(
    val exclusionId: String,
    val courseName: String,
    val fromCp: Int,
    val toCp: Int,
)

fun queueDropPayloadJson(queueId: String): String =
    JSONObject().put("queue_id", queueId).toString()

fun finishReassignPayloadJson(
    queueId: String,
    oldLogicalId: Int?,
    newLogicalId: Int,
    uid: String,
): String {
    val obj = JSONObject()
        .put("queue_id", queueId)
        .put("new_logical_id", newLogicalId)
        .put("uid", uid)
    if (oldLogicalId == null) obj.put("old_logical_id", JSONObject.NULL) else obj.put("old_logical_id", oldLogicalId)
    return obj.toString()
}

fun courseUpsertPayloadJson(
    name: String,
    previousName: String,
    controls: List<Int>,
    lengthMeters: Int?,
    climbMeters: Int?,
    legsJson: String,
    randomOrder: List<Int> = emptyList(),
): String {
    val arr = JSONArray()
    controls.forEach { arr.put(it) }
    val flags = JSONArray()
    parseClusterGroups(randomOrder, controls.size).forEach { flags.put(it) }
    val obj = JSONObject()
        .put("name", name)
        .put("previous_name", previousName)
        .put("controls", arr)
        .put("random_order", flags)
        .put("legs_json", legsJson)
    if (lengthMeters == null) obj.put("length_meters", JSONObject.NULL) else obj.put("length_meters", lengthMeters)
    if (climbMeters == null) obj.put("climb_meters", JSONObject.NULL) else obj.put("climb_meters", climbMeters)
    return obj.toString()
}

fun courseDeletePayloadJson(name: String): String =
    JSONObject().put("name", name).toString()

fun exclusionUpsertPayloadJson(
    exclusionId: String,
    courseName: String,
    fromCp: Int,
    toCp: Int,
    maxLegSeconds: Int?,
): String {
    val obj = JSONObject()
        .put("exclusion_id", exclusionId)
        .put("course", courseName)
        .put("from_cp", fromCp)
        .put("to_cp", toCp)
    if (maxLegSeconds == null) obj.put("max_leg_seconds", JSONObject.NULL)
    else obj.put("max_leg_seconds", maxLegSeconds)
    return obj.toString()
}

fun exclusionDeletePayloadJson(
    exclusionId: String,
    courseName: String,
    fromCp: Int,
    toCp: Int,
): String = JSONObject()
    .put("exclusion_id", exclusionId)
    .put("course", courseName)
    .put("from_cp", fromCp)
    .put("to_cp", toCp)
    .toString()

fun parseQueueDropPayload(payloadJson: String): QueueDropPayload =
    QueueDropPayload(JSONObject(payloadJson).getString("queue_id"))

fun parseFinishReassignPayload(payloadJson: String): FinishReassignPayload {
    val obj = JSONObject(payloadJson)
    val old = if (!obj.has("old_logical_id") || obj.isNull("old_logical_id")) null else obj.getInt("old_logical_id")
    return FinishReassignPayload(
        queueId = obj.getString("queue_id"),
        oldLogicalId = old,
        newLogicalId = obj.getInt("new_logical_id"),
        uid = obj.optString("uid"),
    )
}

fun parseCourseUpsertPayload(payloadJson: String): CourseUpsertPayload {
    val obj = JSONObject(payloadJson)
    val controlsArr = obj.optJSONArray("controls") ?: JSONArray()
    val controls = buildList {
        for (i in 0 until controlsArr.length()) add(controlsArr.getInt(i))
    }
    val flagsArr = obj.optJSONArray("random_order") ?: obj.optJSONArray("randomOrder") ?: JSONArray()
    val rawOrder = buildList<Any?> {
        for (i in 0 until flagsArr.length()) add(flagsArr.get(i))
    }
    return CourseUpsertPayload(
        name = obj.optString("name"),
        previousName = obj.optString("previous_name"),
        controls = controls,
        lengthMeters = if (obj.has("length_meters") && !obj.isNull("length_meters")) obj.optInt("length_meters") else null,
        climbMeters = if (obj.has("climb_meters") && !obj.isNull("climb_meters")) obj.optInt("climb_meters") else null,
        legsJson = obj.optString("legs_json", "[]"),
        randomOrder = parseClusterGroups(rawOrder, controls.size),
    )
}

fun parseCourseDeletePayload(payloadJson: String): CourseDeletePayload =
    CourseDeletePayload(JSONObject(payloadJson).optString("name"))

fun parseExclusionUpsertPayload(payloadJson: String): ExclusionUpsertPayload {
    val obj = JSONObject(payloadJson)
    return ExclusionUpsertPayload(
        exclusionId = obj.optString("exclusion_id"),
        courseName = obj.optString("course"),
        fromCp = obj.getInt("from_cp"),
        toCp = obj.getInt("to_cp"),
        maxLegSeconds = if (obj.has("max_leg_seconds") && !obj.isNull("max_leg_seconds")) {
            obj.optInt("max_leg_seconds")
        } else {
            null
        },
    )
}

fun parseExclusionDeletePayload(payloadJson: String): ExclusionDeletePayload {
    val obj = JSONObject(payloadJson)
    return ExclusionDeletePayload(
        exclusionId = obj.optString("exclusion_id"),
        courseName = obj.optString("course"),
        fromCp = obj.optInt("from_cp"),
        toCp = obj.optInt("to_cp"),
    )
}

package ru.rogein.chip.parse.field

import org.json.JSONObject
import java.util.UUID

data class PersonTeamJoinPayload(
    val logicalId: Int,
    val otherLogicalId: Int,
    val teamId: Int,
    val droppedTeamId: Int,
)

data class PersonTeamLeavePayload(
    val logicalId: Int,
    val teamId: Int,
)

data class PersonTeamAssignPayload(
    val logicalId: Int,
    val teamId: Int,
)

data class CorrectionAddCpPayload(
    val correctionId: String,
    val logicalId: Int,
    val cp: Int,
    val course: String,
    val time: String,
)

data class CorrectionRemoveCpPayload(
    val correctionId: String,
    val logicalId: Int?,
    val cp: Int,
    val removeMode: String,
)

data class CorrectionDnfPayload(
    val correctionId: String,
    val logicalId: Int,
)

data class CorrectionClearDnfPayload(
    val logicalId: Int,
)

data class CorrectionCopyPunchesPayload(
    val correctionId: String,
    val logicalId: Int,
    val fromLogicalId: Int,
)

data class CorrectionAssignCoursePayload(
    val correctionId: String,
    val logicalId: Int,
    val fromCourse: String,
    val toCourse: String,
)

data class CorrectionDeletePayload(
    val correctionId: String,
)

fun newCorrectionId(): String = UUID.randomUUID().toString()

fun personTeamJoinPayloadJson(
    logicalId: Int,
    otherLogicalId: Int,
    teamId: Int,
    droppedTeamId: Int = 0,
): String = JSONObject()
    .put("logical_id", logicalId)
    .put("other_logical_id", otherLogicalId)
    .put("team_id", teamId)
    .put("dropped_team_id", droppedTeamId)
    .toString()

fun personTeamLeavePayloadJson(logicalId: Int, teamId: Int): String =
    JSONObject().put("logical_id", logicalId).put("team_id", teamId).toString()

fun personTeamAssignPayloadJson(logicalId: Int, teamId: Int): String =
    JSONObject().put("logical_id", logicalId).put("team_id", teamId).toString()

fun correctionAddCpPayloadJson(
    correctionId: String,
    logicalId: Int,
    cp: Int,
    course: String = "",
    time: String = "",
): String {
    val obj = JSONObject()
        .put("correction_id", correctionId)
        .put("logical_id", logicalId)
        .put("cp", cp)
        .put("course", course)
    if (time.isNotBlank()) obj.put("time", time)
    return obj.toString()
}

fun correctionRemoveCpPayloadJson(
    correctionId: String,
    logicalId: Int?,
    cp: Int,
    removeMode: String,
): String {
    val obj = JSONObject()
        .put("correction_id", correctionId)
        .put("cp", cp)
        .put("remove_mode", removeMode)
    if (logicalId == null) obj.put("logical_id", JSONObject.NULL) else obj.put("logical_id", logicalId)
    return obj.toString()
}

fun correctionDnfPayloadJson(correctionId: String, logicalId: Int): String =
    JSONObject().put("correction_id", correctionId).put("logical_id", logicalId).toString()

fun correctionClearDnfPayloadJson(logicalId: Int): String =
    JSONObject().put("logical_id", logicalId).toString()

fun correctionCopyPunchesPayloadJson(
    correctionId: String,
    logicalId: Int,
    fromLogicalId: Int,
): String = JSONObject()
    .put("correction_id", correctionId)
    .put("logical_id", logicalId)
    .put("from_logical_id", fromLogicalId)
    .toString()

fun correctionAssignCoursePayloadJson(
    correctionId: String,
    logicalId: Int,
    fromCourse: String,
    toCourse: String,
): String = JSONObject()
    .put("correction_id", correctionId)
    .put("logical_id", logicalId)
    .put("from_course", fromCourse)
    .put("to_course", toCourse)
    .toString()

fun correctionDeletePayloadJson(correctionId: String): String =
    JSONObject().put("correction_id", correctionId).toString()

fun parsePersonTeamJoinPayload(payloadJson: String): PersonTeamJoinPayload {
    val obj = JSONObject(payloadJson)
    return PersonTeamJoinPayload(
        logicalId = obj.getInt("logical_id"),
        otherLogicalId = obj.getInt("other_logical_id"),
        teamId = obj.getInt("team_id"),
        droppedTeamId = obj.optInt("dropped_team_id", 0),
    )
}

fun parsePersonTeamLeavePayload(payloadJson: String): PersonTeamLeavePayload {
    val obj = JSONObject(payloadJson)
    return PersonTeamLeavePayload(obj.getInt("logical_id"), obj.optInt("team_id", 0))
}

fun parsePersonTeamAssignPayload(payloadJson: String): PersonTeamAssignPayload {
    val obj = JSONObject(payloadJson)
    return PersonTeamAssignPayload(obj.getInt("logical_id"), obj.getInt("team_id"))
}

fun parseCorrectionAddCpPayload(payloadJson: String): CorrectionAddCpPayload {
    val obj = JSONObject(payloadJson)
    return CorrectionAddCpPayload(
        correctionId = obj.optString("correction_id"),
        logicalId = obj.getInt("logical_id"),
        cp = obj.optInt("cp", obj.optInt("cp_number")),
        course = obj.optString("course"),
        time = obj.optString("time"),
    )
}

fun parseCorrectionRemoveCpPayload(payloadJson: String): CorrectionRemoveCpPayload {
    val obj = JSONObject(payloadJson)
    val logicalId = if (!obj.has("logical_id") || obj.isNull("logical_id")) null else obj.getInt("logical_id")
    return CorrectionRemoveCpPayload(
        correctionId = obj.optString("correction_id"),
        logicalId = logicalId,
        cp = obj.optInt("cp", obj.optInt("cp_number")),
        removeMode = obj.optString("remove_mode"),
    )
}

fun parseCorrectionDnfPayload(payloadJson: String): CorrectionDnfPayload {
    val obj = JSONObject(payloadJson)
    return CorrectionDnfPayload(obj.optString("correction_id"), obj.getInt("logical_id"))
}

fun parseCorrectionClearDnfPayload(payloadJson: String): CorrectionClearDnfPayload {
    val obj = JSONObject(payloadJson)
    return CorrectionClearDnfPayload(obj.getInt("logical_id"))
}

fun parseCorrectionCopyPunchesPayload(payloadJson: String): CorrectionCopyPunchesPayload {
    val obj = JSONObject(payloadJson)
    return CorrectionCopyPunchesPayload(
        correctionId = obj.optString("correction_id"),
        logicalId = obj.getInt("logical_id"),
        fromLogicalId = obj.getInt("from_logical_id"),
    )
}

fun parseCorrectionAssignCoursePayload(payloadJson: String): CorrectionAssignCoursePayload {
    val obj = JSONObject(payloadJson)
    return CorrectionAssignCoursePayload(
        correctionId = obj.optString("correction_id"),
        logicalId = obj.getInt("logical_id"),
        fromCourse = obj.optString("from_course"),
        toCourse = obj.optString("to_course").ifBlank { obj.optString("course") },
    )
}

fun parseCorrectionDeletePayload(payloadJson: String): CorrectionDeletePayload {
    val obj = JSONObject(payloadJson)
    return CorrectionDeletePayload(obj.optString("correction_id"))
}

fun correctionIdFromLocalPayload(payloadJson: String): String =
    try {
        JSONObject(payloadJson).optString("correction_id").trim()
    } catch (_: Exception) {
        ""
    }

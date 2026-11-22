package ru.rogein.chip.parse.field

import org.json.JSONArray
import org.json.JSONObject
import ru.rogein.chip.parse.Punch

/**
 * Контракт полевого журнала (docs/field-sync.md §3–4, §6).
 * Этап 4: команды и правки (+ ранее chip_issue / chip_read + LAN).
 */
const val FIELD_OP_SCHEMA = 1
const val FIELD_PROTOCOL_VERSION = 1
const val FIELD_DEFAULT_PORT = 8787

object FieldOpType {
    const val CHIP_ISSUE = "chip_issue"
    const val CHIP_READ = "chip_read"
    const val PERSON_UPSERT = "person_upsert"
    const val PERSON_DELETE = "person_delete"
    const val PERSON_COURSE = "person_course"
    const val PERSON_TEAM_JOIN = "person_team_join"
    const val PERSON_TEAM_LEAVE = "person_team_leave"
    const val PERSON_TEAM_ASSIGN = "person_team_assign"
    const val CORRECTION_ADD_CP = "correction_add_cp"
    const val CORRECTION_REMOVE_CP = "correction_remove_cp"
    const val CORRECTION_DNF = "correction_dnf"
    const val CORRECTION_CLEAR_DNF = "correction_clear_dnf"
    const val CORRECTION_COPY_PUNCHES = "correction_copy_punches"
    const val CORRECTION_ASSIGN_COURSE = "correction_assign_course"
    const val CORRECTION_DELETE = "correction_delete"
    const val EVENT_CONFIG = "event_config"
    const val QUEUE_DROP = "queue_drop"
    const val FINISH_REASSIGN = "finish_reassign"
    const val COURSE_UPSERT = "course_upsert"
    const val COURSE_DELETE = "course_delete"
    const val EXCLUSION_UPSERT = "exclusion_upsert"
    const val EXCLUSION_DELETE = "exclusion_delete"
}

object FieldSyncMode {
    const val OFF = "off"
    const val LAN = "lan"
    /** Журнал через API сайта; fold на телефоне; live PUT — только издатель. */
    const val CLOUD = "cloud"
}

object FieldSyncRole {
    const val HOST = "host"
    const val CLIENT = "client"
}

/** Канал клиента → хост. Хост всегда принимает и WS, и HTTP. */
object FieldTransport {
    const val AUTO = "auto"
    const val WEBSOCKET = "websocket"
    const val HTTP = "http"
}

/**
 * Куда слать live / API сайта при LAN (роутер с интернетом или без).
 * `auto` — Wi‑Fi, если у сети есть интернет; иначе LTE.
 */
object FieldSiteUplink {
    const val AUTO = "auto"
    const val CELLULAR = "cellular"
    const val WIFI = "wifi"
}

data class FieldOpPunch(
    val cp: Int,
    val seconds: Int,
)

data class FieldOpEnvelope(
    val schema: Int = FIELD_OP_SCHEMA,
    val opId: String,
    val deviceId: String,
    val deviceSeq: Long,
    val eventSlug: String,
    val clockMs: Long,
    val actor: String,
    val type: String,
    val payloadJson: String,
)

data class ChipIssuePayload(
    val logicalId: Int,
    val uid: String,
    val course: String,
    val cleared: Boolean,
    val overwrite: Boolean,
)

data class ChipReadPayload(
    val queueId: String,
    val uid: String,
    val logicalId: Int?,
    val punches: List<FieldOpPunch>,
    val readAtMs: Long,
    val courseHint: String,
    val replace: Boolean,
    val syntheticFinish: Boolean,
    val skipMissingFinish: Boolean,
    val source: String,
    val blockCount: Int?,
)

fun chipIssuePayloadJson(
    logicalId: Int,
    uid: String,
    course: String,
    cleared: Boolean = true,
    overwrite: Boolean = false,
): String {
    val clearedFlag = if (cleared) "true" else "false"
    val overwriteFlag = if (overwrite) "true" else "false"
    return buildString {
        append('{')
        append("\"logical_id\":").append(logicalId).append(',')
        append("\"uid\":").append(jsonString(uid)).append(',')
        append("\"course\":").append(jsonString(course)).append(',')
        append("\"cleared\":").append(clearedFlag).append(',')
        append("\"overwrite\":").append(overwriteFlag)
        append('}')
    }
}

fun chipReadPayloadJson(
    queueId: String,
    uid: String,
    logicalId: Int?,
    punches: List<FieldOpPunch>,
    readAtMs: Long,
    courseHint: String,
    replace: Boolean = false,
    syntheticFinish: Boolean = false,
    skipMissingFinish: Boolean = false,
    source: String = "nfc",
    blockCount: Int? = null,
): String {
    return buildString {
        append('{')
        append("\"queue_id\":").append(jsonString(queueId)).append(',')
        append("\"uid\":").append(jsonString(uid)).append(',')
        if (logicalId != null) {
            append("\"logical_id\":").append(logicalId).append(',')
        } else {
            append("\"logical_id\":null,")
        }
        append("\"punches\":[")
        punches.forEachIndexed { index, punch ->
            if (index > 0) append(',')
            append("{\"cp\":").append(punch.cp)
            append(",\"seconds\":").append(punch.seconds).append('}')
        }
        append("],")
        append("\"read_at\":").append(readAtMs).append(',')
        append("\"course_hint\":").append(jsonString(courseHint)).append(',')
        append("\"replace\":").append(if (replace) "true" else "false").append(',')
        append("\"synthetic_finish\":").append(if (syntheticFinish) "true" else "false").append(',')
        append("\"skip_missing_finish\":").append(if (skipMissingFinish) "true" else "false").append(',')
        append("\"source\":").append(jsonString(source))
        if (blockCount != null) {
            append(",\"block_count\":").append(blockCount)
        }
        append('}')
    }
}

fun punchDaySeconds(hour: Int, minute: Int, second: Int): Int =
    hour * 3600 + minute * 60 + second

fun punchFromDaySeconds(cp: Int, seconds: Int): Punch {
    val s = seconds.coerceAtLeast(0)
    return Punch(cp, s / 3600, (s % 3600) / 60, s % 60)
}

fun fieldOpEnvelopeToJson(op: FieldOpEnvelope): JSONObject =
    JSONObject()
        .put("schema", op.schema)
        .put("op_id", op.opId)
        .put("device_id", op.deviceId)
        .put("device_seq", op.deviceSeq)
        .put("event_slug", op.eventSlug)
        .put("clock_ms", op.clockMs)
        .put("actor", op.actor)
        .put("type", op.type)
        .put("payload", JSONObject(op.payloadJson))

fun fieldOpEnvelopeFromJson(obj: JSONObject): FieldOpEnvelope {
    val payload = obj.opt("payload")
    val payloadJson = when (payload) {
        is JSONObject -> payload.toString()
        is String -> payload
        else -> "{}"
    }
    return FieldOpEnvelope(
        schema = obj.optInt("schema", FIELD_OP_SCHEMA),
        opId = obj.getString("op_id"),
        deviceId = obj.getString("device_id"),
        deviceSeq = obj.getLong("device_seq"),
        eventSlug = obj.optString("event_slug"),
        clockMs = obj.getLong("clock_ms"),
        actor = obj.optString("actor"),
        type = obj.getString("type"),
        payloadJson = payloadJson,
    )
}

fun parseChipIssuePayload(payloadJson: String): ChipIssuePayload {
    val obj = JSONObject(payloadJson)
    return ChipIssuePayload(
        logicalId = obj.getInt("logical_id"),
        uid = obj.optString("uid"),
        course = obj.optString("course"),
        cleared = obj.optBoolean("cleared", true),
        overwrite = obj.optBoolean("overwrite", false),
    )
}

fun parseChipReadPayload(payloadJson: String): ChipReadPayload {
    val obj = JSONObject(payloadJson)
    val punchesArr = obj.optJSONArray("punches") ?: JSONArray()
    val punches = buildList {
        for (i in 0 until punchesArr.length()) {
            val p = punchesArr.getJSONObject(i)
            add(FieldOpPunch(p.getInt("cp"), p.getInt("seconds")))
        }
    }
    val logicalId = if (!obj.has("logical_id") || obj.isNull("logical_id")) {
        null
    } else {
        obj.getInt("logical_id")
    }
    return ChipReadPayload(
        queueId = obj.getString("queue_id"),
        uid = obj.optString("uid"),
        logicalId = logicalId,
        punches = punches,
        readAtMs = obj.optLong("read_at", 0L),
        courseHint = obj.optString("course_hint"),
        replace = obj.optBoolean("replace", false),
        syntheticFinish = obj.optBoolean("synthetic_finish", false),
        skipMissingFinish = obj.optBoolean("skip_missing_finish", false),
        source = obj.optString("source", "nfc"),
        blockCount = if (obj.has("block_count")) obj.optInt("block_count") else null,
    )
}

fun parseCursorsJson(raw: String?): Map<String, Long> {
    if (raw.isNullOrBlank()) return emptyMap()
    val obj = JSONObject(raw)
    val out = linkedMapOf<String, Long>()
    for (key in obj.keys()) {
        out[key] = obj.optLong(key, 0L)
    }
    return out
}

fun cursorsToJson(cursors: Map<String, Long>): String {
    val obj = JSONObject()
    cursors.forEach { (id, seq) -> obj.put(id, seq) }
    return obj.toString()
}

fun opsBundleJson(ops: List<FieldOpEnvelope>): String {
    val arr = JSONArray()
    ops.forEach { arr.put(fieldOpEnvelopeToJson(it)) }
    return JSONObject().put("ops", arr).toString()
}

fun parseOpsBundle(raw: String): List<FieldOpEnvelope> {
    val obj = JSONObject(raw)
    val arr = obj.optJSONArray("ops") ?: JSONArray()
    return buildList {
        for (i in 0 until arr.length()) {
            add(fieldOpEnvelopeFromJson(arr.getJSONObject(i)))
        }
    }
}

private fun jsonString(value: String): String {
    val escaped = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
    return "\"$escaped\""
}

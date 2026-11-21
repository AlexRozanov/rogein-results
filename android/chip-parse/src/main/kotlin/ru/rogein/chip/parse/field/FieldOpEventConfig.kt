package ru.rogein.chip.parse.field

import org.json.JSONObject

/**
 * Частичный снимок настроек старта (§4.1). Без publish-токена и URL сайта.
 * Слияние: ключи из payload перекрывают локальные (last-write-wins на уровне операции).
 */
fun eventConfigPayloadJson(
    controlMinutes: Int? = null,
    startCp: Int? = null,
    finishCp: Int? = null,
    penaltyPerMinute: Int? = null,
    dqMinutes: Int? = null,
    startMode: String? = null,
    competitionStartTime: String? = null,
    competitionDate: String? = null,
    title: String? = null,
    sportKind: String? = null,
    formatSettingsJson: String? = null,
    awardGroupsJson: String? = null,
    legendsJson: String? = null,
    mapPointsJson: String? = null,
): String {
    val obj = JSONObject()
    if (controlMinutes != null) obj.put("control_minutes", controlMinutes)
    if (startCp != null) obj.put("start_cp", startCp)
    if (finishCp != null) obj.put("finish_cp", finishCp)
    if (penaltyPerMinute != null) obj.put("penalty_per_minute", penaltyPerMinute)
    if (dqMinutes != null) obj.put("dq_minutes", dqMinutes)
    if (startMode != null) obj.put("start_mode", startMode)
    if (competitionStartTime != null) obj.put("competition_start_time", competitionStartTime)
    if (competitionDate != null) obj.put("competition_date", competitionDate)
    if (title != null) obj.put("title", title)
    if (sportKind != null) obj.put("sport_kind", sportKind)
    if (formatSettingsJson != null) obj.put("format_settings", formatSettingsJson)
    if (awardGroupsJson != null) obj.put("award_groups", awardGroupsJson)
    if (legendsJson != null) obj.put("cp_legends", legendsJson)
    if (mapPointsJson != null) obj.put("map_points", mapPointsJson)
    return obj.toString()
}

fun parseEventConfigPayload(payloadJson: String): JSONObject =
    try {
        JSONObject(payloadJson)
    } catch (_: Exception) {
        JSONObject()
    }

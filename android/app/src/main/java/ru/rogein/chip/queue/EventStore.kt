package ru.rogein.chip.queue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import ru.rogein.chip.parse.AwardCategory
import ru.rogein.chip.parse.AwardGroupCsvRow
import ru.rogein.chip.parse.AwardPerson
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.EventPackAwardGroupFile
import ru.rogein.chip.parse.EventPackFile
import ru.rogein.chip.parse.EventPackFormatFile
import ru.rogein.chip.parse.EventPackLegendFile
import ru.rogein.chip.parse.FormatCsvRow
import ru.rogein.chip.parse.LegendCsvRow
import ru.rogein.chip.parse.SPORT_ORIENT
import ru.rogein.chip.parse.ChipCellLog
import ru.rogein.chip.parse.isRogaine
import ru.rogein.chip.parse.normalizeSportKind
import ru.rogein.chip.parse.parseEventPackJson
import java.io.File
import ru.rogein.chip.parse.OrientCalc
import ru.rogein.chip.parse.OrientExclusion
import ru.rogein.chip.parse.OrientMark
import ru.rogein.chip.parse.OrientSettings
import ru.rogein.chip.parse.RogaineCpTypeRule
import ru.rogein.chip.parse.RogaineFormatOverride
import ru.rogein.chip.parse.RogaineSettings
import ru.rogein.chip.parse.STATUS_OK
import ru.rogein.chip.parse.applyRogaineFormat
import ru.rogein.chip.parse.assignPlaces
import ru.rogein.chip.parse.assignRogaineTeamPlaces
import ru.rogein.chip.parse.calculateOrientResult
import ru.rogein.chip.parse.COPY_PUNCHES
import ru.rogein.chip.parse.DID_NOT_FINISH
import ru.rogein.chip.parse.ROGAINE_REMOVE_LEGS
import ru.rogein.chip.parse.ROGAINE_REMOVE_POINTS
import ru.rogein.chip.parse.STATUS_DNF
import ru.rogein.chip.parse.calculateRogaineResult
import ru.rogein.chip.parse.FinishQueueRef
import ru.rogein.chip.parse.courseKey
import ru.rogein.chip.parse.numberConflictIds
import ru.rogein.chip.parse.filterCourseCps
import ru.rogein.chip.parse.remapCourseLegMeters
import ru.rogein.chip.parse.orientCourseLegs
import ru.rogein.chip.parse.personAwardAge
import ru.rogein.chip.parse.personAwardCategories
import ru.rogein.chip.parse.protocolStatusRank
import ru.rogein.chip.parse.storedBirthDate
import ru.rogein.chip.parse.parseDaySeconds
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

data class EventSettings(
    val startCp: Int = CP_START,
    val finishCp: Int = CP_FINISH,
    val controlMinutes: Int = 120,
    val penaltyPerMinute: Int = 1,
    val dqMinutes: Int = 15,
    val startMode: String = "station",
    val competitionStartTime: String = "",
    val competitionDate: String = LocalDate.now().toString(),
    val apiBaseUrl: String = "",
    val publishToken: String = "",
    val slug: String = "",
    val title: String = "",
    val liveNote: String = "",
    val livePending: Boolean = false,
    val sportKind: String = SPORT_ORIENT,
    val metersPerPixel: Double? = null,
    val mapWidth: Int? = null,
    val mapHeight: Int? = null,
    val mapPointsJson: String = "",
    val formatSettingsJson: String = "",
    val legendsJson: String = "",
    val awardGroupsJson: String = "",
)

data class EventPackDownload(
    val pack: EventPackFile,
    val mapBytes: ByteArray?,
    val mapFileName: String,
)

data class AwardGroupView(
    val name: String,
    val genderMode: String,
    val minAge: Int?,
    val maxAge: Int? = null,
    val formatNames: List<String>,
)

data class LegendView(
    val cp: Int,
    val name: String,
    val type: String,
)

data class ScoredResult(
    val queueId: String,
    val logicalId: Int?,
    val name: String,
    val course: String,
    val uid: String,
    val punches: List<OrientMark>,
    val calc: OrientCalc,
    val place: Int?,
    val chipHint: String? = null,
    val teamId: Int = 0,
    val numberConflict: Boolean = false,
)

data class ResultGroup(
    val key: String,
    val name: String,
    val rows: List<ScoredResult>,
)

class EventStore(
    private val filesDir: File,
    private val eventDao: EventDao,
) {
    private val liveSending = AtomicBoolean(false)
    val settings: Flow<EventSettings> = eventDao.observeSettings().map { it.toSettings() }
    val corrections: Flow<List<CorrectionEntity>> = eventDao.observeCorrections()
    val exclusions: Flow<List<ExclusionEntity>> = eventDao.observeExclusions()

    suspend fun settingsNow(): EventSettings = eventDao.listSettings().toSettings()

    suspend fun correctionsNow(): List<CorrectionEntity> = eventDao.listCorrections()

    suspend fun exclusionsNow(): List<ExclusionEntity> = eventDao.listExclusions()

    suspend fun saveSettings(next: EventSettings) {
        eventDao.upsertSetting(SettingEntity("start_cp", next.startCp.toString()))
        eventDao.upsertSetting(SettingEntity("finish_cp", next.finishCp.toString()))
        eventDao.upsertSetting(SettingEntity("control_minutes", next.controlMinutes.toString()))
        eventDao.upsertSetting(SettingEntity("penalty_per_minute", next.penaltyPerMinute.toString()))
        eventDao.upsertSetting(SettingEntity("dq_minutes", next.dqMinutes.toString()))
        eventDao.upsertSetting(SettingEntity("start_mode", next.startMode.trim().ifBlank { "station" }))
        eventDao.upsertSetting(SettingEntity("competition_start_time", next.competitionStartTime.trim()))
        eventDao.upsertSetting(SettingEntity("competition_date", next.competitionDate.trim()))
        eventDao.upsertSetting(SettingEntity("api_base_url", next.apiBaseUrl.trim()))
        eventDao.upsertSetting(SettingEntity("publish_token", next.publishToken.trim()))
        eventDao.upsertSetting(SettingEntity("slug", next.slug.trim()))
        eventDao.upsertSetting(SettingEntity("title", next.title.trim()))
        eventDao.upsertSetting(SettingEntity("sport_kind", normalizeSportKind(next.sportKind)))
        eventDao.upsertSetting(SettingEntity("meters_per_pixel", next.metersPerPixel?.toString().orEmpty()))
        eventDao.upsertSetting(SettingEntity("map_image_width", next.mapWidth?.toString().orEmpty()))
        eventDao.upsertSetting(SettingEntity("map_image_height", next.mapHeight?.toString().orEmpty()))
    }

    fun saveChipLog(text: String, logicalId: Int?, uid: String): File {
        val dir = File(filesDir, "chip-logs").apply { mkdirs() }
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val who = logicalId?.toString()
            ?: uid.replace(":", "").ifBlank { "chip" }
        val file = File(dir, "chip-log-$who-$stamp.txt")
        file.writeText(text)
        val files = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        if (files.size > 30) {
            files.take(files.size - 30).forEach { it.delete() }
        }
        return file
    }

    suspend fun addRemovedCp(cp: Int) {
        val exists = eventDao.listCorrections().any {
            it.type == "remove_cp" && it.logicalId == null && payloadInt(it.payloadJson, "cp_number") == cp
        }
        if (exists) return
        eventDao.insertCorrection(
            CorrectionEntity(
                type = "remove_cp",
                logicalId = null,
                courseName = "",
                payloadJson = JSONObject().put("cp_number", cp).put("remove_mode", "from_course").toString(),
            ),
        )
    }

    suspend fun addMissingCp(logicalId: Int, courseName: String, cp: Int) {
        eventDao.insertCorrection(
            CorrectionEntity(
                type = "add_cp",
                logicalId = logicalId,
                courseName = courseName,
                payloadJson = JSONObject().put("cp_number", cp).toString(),
            ),
        )
    }

    suspend fun addRogainePersonalCp(logicalId: Int, cp: Int) {
        if (cp <= 0) error("Укажите номер КП")
        val exists = eventDao.listCorrections().any {
            it.type == "add_cp" && it.logicalId == logicalId && payloadInt(it.payloadJson, "cp_number") == cp
        }
        if (exists) error("Для № $logicalId корректировка добавления КП $cp уже есть")
        eventDao.insertCorrection(
            CorrectionEntity(
                type = "add_cp",
                logicalId = logicalId,
                courseName = "",
                payloadJson = JSONObject().put("cp_number", cp).toString(),
            ),
        )
    }

    suspend fun addRogaineRemoveCp(logicalId: Int?, cp: Int, removeMode: String) {
        if (cp <= 0) error("Укажите номер КП")
        val mode = if (removeMode == ROGAINE_REMOVE_POINTS) ROGAINE_REMOVE_POINTS else ROGAINE_REMOVE_LEGS
        val exists = eventDao.listCorrections().any {
            it.type == "remove_cp" && it.logicalId == logicalId && payloadInt(it.payloadJson, "cp_number") == cp
        }
        if (exists) {
            val who = logicalId?.let { "для № $it" } ?: "для всех"
            error("Корректировка удаления КП $cp $who уже есть")
        }
        eventDao.insertCorrection(
            CorrectionEntity(
                type = "remove_cp",
                logicalId = logicalId,
                courseName = "",
                payloadJson = JSONObject().put("cp_number", cp).put("remove_mode", mode).toString(),
            ),
        )
    }

    suspend fun setDidNotFinish(logicalId: Int) {
        if (logicalId <= 0) return
        if (isDidNotFinish(logicalId, eventDao.listCorrections())) return
        eventDao.insertCorrection(
            CorrectionEntity(
                type = DID_NOT_FINISH,
                logicalId = logicalId,
                courseName = "",
                payloadJson = "{}",
            ),
        )
    }

    suspend fun clearDidNotFinish(logicalId: Int) {
        eventDao.listCorrections()
            .filter { it.type == DID_NOT_FINISH && it.logicalId == logicalId }
            .forEach { eventDao.deleteCorrection(it.id) }
    }

    suspend fun setCopyPunchesNote(logicalId: Int, fromLogicalId: Int) {
        if (logicalId <= 0 || fromLogicalId <= 0 || logicalId == fromLogicalId) return
        eventDao.listCorrections()
            .filter { it.type == COPY_PUNCHES && it.logicalId == logicalId }
            .forEach { eventDao.deleteCorrection(it.id) }
        eventDao.insertCorrection(
            CorrectionEntity(
                type = COPY_PUNCHES,
                logicalId = logicalId,
                courseName = "",
                payloadJson = JSONObject().put("from_logical_id", fromLogicalId).toString(),
            ),
        )
    }

    suspend fun assignCourse(logicalId: Int, fromCourse: String, toCourse: String) {
        eventDao.insertCorrection(
            CorrectionEntity(
                type = "assign_course",
                logicalId = logicalId,
                courseName = fromCourse,
                payloadJson = JSONObject().put("course", toCourse).toString(),
            ),
        )
    }

    suspend fun deleteCorrection(id: Long) = eventDao.deleteCorrection(id)

    suspend fun addExclusion(courseName: String, fromCp: Int, toCp: Int, maxLegSeconds: Int?) {
        val exists = eventDao.listExclusions().any {
            it.courseName.equals(courseName, true) && it.fromCp == fromCp && it.toCp == toCp
        }
        if (exists) error("Такой перегон уже исключён")
        eventDao.insertExclusion(
            ExclusionEntity(courseName = courseName, fromCp = fromCp, toCp = toCp, maxLegSeconds = maxLegSeconds),
        )
    }

    suspend fun deleteExclusion(id: Long) = eventDao.deleteExclusion(id)

    suspend fun clearEventRules() {
        eventDao.clearCorrections()
        eventDao.clearExclusions()
        eventDao.upsertSetting(SettingEntity("slug", ""))
        eventDao.upsertSetting(SettingEntity("title", ""))
        clearLiveState()
    }

    suspend fun markLivePending() {
        eventDao.upsertSetting(SettingEntity("live_pending", "1"))
    }

    suspend fun setLiveNote(note: String) {
        eventDao.upsertSetting(SettingEntity("live_note", note))
    }

    suspend fun fetchEventPack(settings: EventSettings): EventPackDownload {
        val base = normalizeBase(settings.apiBaseUrl)
        val slug = settings.slug.trim()
        val token = settings.publishToken.trim()
        if (base.isEmpty() || slug.isEmpty() || token.isEmpty()) {
            error("Укажите адрес сайта, токен и slug в настройках")
        }
        val (code, body) = request("GET", "$base/api/packs/$slug", null, token)
        when (code) {
            401, 403 -> error("Нет доступа к пакету. Проверьте токен.")
            404 -> error("Пакет не найден. Отправьте его с компьютера на сайт.")
            else -> if (code !in 200..299) error("Сайт ответил $code: ${body.take(180)}")
        }
        val pack = parseEventPackJson(body)
        if (!isRogaine(pack.sport)) {
            error("Это пакет ориентирования. Начните старт «Ориентирование».")
        }
        val map = requestBytes("GET", "$base/api/packs/$slug/map", token)
        return EventPackDownload(
            pack = pack,
            mapBytes = map?.bytes,
            mapFileName = pack.mapFileName.ifBlank { map?.fileName.orEmpty() },
        )
    }

    suspend fun applyPackMeta(pack: EventPackFile) {
        val current = settingsNow()
        saveSettings(
            current.copy(
                startCp = pack.settings.startCp.takeIf { it > 0 } ?: current.startCp,
                finishCp = pack.settings.finishCp.takeIf { it > 0 } ?: current.finishCp,
                controlMinutes = pack.settings.controlMinutes.takeIf { it > 0 } ?: current.controlMinutes,
                penaltyPerMinute = pack.settings.penaltyPerMinute,
                dqMinutes = pack.settings.dqMinutes,
                startMode = pack.settings.startMode.ifBlank { current.startMode },
                competitionStartTime = pack.settings.competitionStartTime,
                competitionDate = pack.settings.competitionDate.ifBlank { current.competitionDate },
                title = pack.title.ifBlank { current.title },
                metersPerPixel = pack.metersPerPixel ?: current.metersPerPixel,
                mapWidth = pack.mapWidth ?: current.mapWidth,
                mapHeight = pack.mapHeight ?: current.mapHeight,
            ),
        )
        eventDao.upsertSetting(SettingEntity("award_groups", awardGroupsJson(pack.awardGroups)))
        eventDao.upsertSetting(SettingEntity("cp_legends", legendsJson(pack.legends)))
        eventDao.upsertSetting(SettingEntity("format_settings", formatsJson(pack.formats)))
        eventDao.upsertSetting(
            SettingEntity(
                "map_points",
                JSONArray().apply {
                    pack.mapPoints.forEach { point ->
                        put(
                            JSONObject()
                                .put("kind", point.kind)
                                .put("cp", point.cp ?: JSONObject.NULL)
                                .put("name", point.name)
                                .put("mapX", point.mapX)
                                .put("mapY", point.mapY),
                        )
                    }
                }.toString(),
            ),
        )
        eventDao.clearExclusions()
        for (rule in pack.exclusions) {
            eventDao.insertExclusion(
                ExclusionEntity(
                    courseName = rule.course,
                    fromCp = rule.fromCp,
                    toCp = rule.toCp,
                    maxLegSeconds = rule.maxLegSeconds,
                ),
            )
        }
    }

    suspend fun saveCourseMap(bytes: ByteArray, fileName: String) {
        if (bytes.isEmpty()) return
        val dest = File(filesDir, COURSE_MAP_FILE)
        dest.writeBytes(bytes)
        eventDao.upsertSetting(SettingEntity("course_map_file_name", fileName.trim().ifBlank { "map.bin" }))
    }

    suspend fun mergeFormatSettings(rows: List<FormatCsvRow>) {
        val root = JSONArray()
        for (row in rows) {
            root.put(
                JSONObject()
                    .put("name", row.name)
                    .put("controlMinutes", row.controlMinutes ?: JSONObject.NULL)
                    .put("penaltyPerMinute", row.penaltyPerMinute ?: JSONObject.NULL)
                    .put("dqMinutes", row.dqMinutes ?: JSONObject.NULL),
            )
        }
        eventDao.upsertSetting(SettingEntity("format_settings", root.toString()))
    }

    suspend fun mergeAwardGroups(rows: List<AwardGroupCsvRow>) {
        val root = JSONArray()
        for (row in rows) {
            root.put(
                JSONObject()
                    .put("name", row.name)
                    .put("genderMode", row.gender)
                    .put("minAge", row.minAge ?: JSONObject.NULL)
                    .put("maxAge", row.maxAge ?: JSONObject.NULL)
                    .put("formatNames", JSONArray(row.formats)),
            )
        }
        eventDao.upsertSetting(SettingEntity("award_groups", root.toString()))
    }

    suspend fun mergeLegends(rows: List<LegendCsvRow>) {
        val root = JSONArray()
        for (row in rows) {
            root.put(
                JSONObject()
                    .put("cp", row.cp)
                    .put("name", row.name)
                    .put("type", row.type),
            )
        }
        eventDao.upsertSetting(SettingEntity("cp_legends", root.toString()))
    }

    suspend fun clearLiveState() {
        eventDao.upsertSetting(SettingEntity("live_pending", "0"))
        eventDao.upsertSetting(SettingEntity("live_note", ""))
    }

    fun isLiveSending(): Boolean = liveSending.get()

    suspend fun sendLiveSnapshot(
        settings: EventSettings,
        people: List<StartPerson>,
        courses: List<StartCourse>,
        items: List<QueuedChipEntity>,
        corrections: List<CorrectionEntity>,
        exclusions: List<ExclusionEntity>,
    ): Boolean {
        if (!liveReady(settings)) {
            setLiveNote("online не настроен")
            eventDao.upsertSetting(SettingEntity("live_pending", "0"))
            return true
        }
        if (!liveSending.compareAndSet(false, true)) {
            markLivePending()
            return false
        }
        return try {
            eventDao.upsertSetting(SettingEntity("live_pending", "0"))
            setLiveNote("отправляю…")
            val note = publishLive(settings, people, courses, items, corrections, exclusions)
            setLiveNote(note)
            !settingsNow().livePending
        } catch (e: Exception) {
            markLivePending()
            setLiveNote(e.message ?: "нет связи")
            false
        } finally {
            liveSending.set(false)
        }
    }

    private suspend fun publishLive(
        settings: EventSettings,
        people: List<StartPerson>,
        courses: List<StartCourse>,
        items: List<QueuedChipEntity>,
        corrections: List<CorrectionEntity>,
        exclusions: List<ExclusionEntity>,
    ): String {
        val slug = settings.slug.trim()
        val title = settings.title.trim()
        val scored = score(items, people, courses, settings, corrections, exclusions)
        val removed = removedCps(corrections)
        val payload = buildPublishJson(
            settings,
            title,
            people,
            courses,
            scored,
            removed,
            requireResults = false,
        )
        val url = "${normalizeBase(settings.apiBaseUrl)}/api/live/$slug"
        val (code, body) = request("PUT", url, payload, settings.publishToken)
        if (code == 409) error("этот старт уже в архиве сайта")
        if (code !in 200..299) error("online $code: ${body.take(200)}")
        return "online: ${scored.size} результатов"
    }

    suspend fun testSite(baseUrl: String): String {
        val base = normalizeBase(baseUrl)
        val code = request("GET", "$base/api/health", null, null).first
        if (code !in 200..299) error("сайт ответил $code")
        return base
    }

    suspend fun eventExists(baseUrl: String, slug: String): Boolean {
        val code = request("GET", "${normalizeBase(baseUrl)}/api/events/${slug.trim()}", null, null).first
        return code == 200
    }

    suspend fun publish(
        settings: EventSettings,
        people: List<StartPerson>,
        courses: List<StartCourse>,
        items: List<QueuedChipEntity>,
        corrections: List<CorrectionEntity>,
        exclusions: List<ExclusionEntity>,
    ): String {
        val slug = settings.slug.trim()
        val title = settings.title.trim()
        if (slug.isEmpty()) error("Укажите slug")
        if (title.isEmpty()) error("Укажите название старта")
        if (settings.apiBaseUrl.isBlank()) error("Укажите адрес сайта")
        if (settings.publishToken.isBlank()) error("Укажите токен публикации")
        val scored = score(items, people, courses, settings, corrections, exclusions)
        if (scored.isEmpty()) error("Нет считанных результатов")
        val removed = removedCps(corrections)
        val payload = buildPublishJson(settings, title, people, courses, scored, removed)
        val url = "${normalizeBase(settings.apiBaseUrl)}/api/events/$slug"
        val (code, body) = request("PUT", url, payload, settings.publishToken)
        if (code !in 200..299) error("публикация $code: ${body.take(200)}")
        return "Опубликовано: ${scored.size} результатов, ${courses.size} дистанций."
    }

    companion object {
        fun score(
            items: List<QueuedChipEntity>,
            people: List<StartPerson>,
            courses: List<StartCourse>,
            settings: EventSettings,
            corrections: List<CorrectionEntity>,
            exclusions: List<ExclusionEntity>,
        ): List<ScoredResult> {
            val courseMap = courses.associate { courseKey(it.name) to it.controls }
            val removed = removedCps(corrections)
            val orientSettings = OrientSettings(settings.startCp, settings.finishCp, settings.controlMinutes)
            val rules = exclusions.map {
                OrientExclusion(courseKey(it.courseName), it.fromCp, it.toCp, it.maxLegSeconds)
            }
            val rogaine = isRogaine(settings.sportKind)
            val formatOverrides = parseFormatOverrides(settings.formatSettingsJson)
            val globalRogaine = RogaineSettings(
                startCp = settings.startCp,
                finishCp = settings.finishCp,
                controlMinutes = settings.controlMinutes,
                penaltyPerMinute = settings.penaltyPerMinute,
                dqMinutes = settings.dqMinutes,
                startMode = settings.startMode,
                competitionStartTime = settings.competitionStartTime,
            )
            val conflictIds = numberConflictIds(
                items.map { item ->
                    FinishQueueRef(item.id, item.uid, item.logicalId, item.courseName)
                },
            )
            val raw = items.map { item ->
                val person = item.logicalId?.let { id -> people.find { it.id == id } }
                val assigned = assignedCourse(item.logicalId, corrections)
                val course = assigned.ifBlank { item.courseName.ifBlank { person?.currentCourse.orEmpty() } }
                val punches = parsePunches(item.punchesJson)
                val adds = addCpCounts(item.logicalId, course, corrections)
                val dnf = isDidNotFinish(item.logicalId, corrections)
                val calc = if (dnf) {
                    OrientCalc(STATUS_DNF, 0, 0, listOf(DID_NOT_FINISH))
                } else if (rogaine) {
                    val override = formatOverrides[courseKey(course)]
                    calculateRogaineResult(
                        punches,
                        applyRogaineFormat(globalRogaine, override),
                        rogaineRemovedPointCps(item.logicalId, corrections),
                        rogaineAddedCps(item.logicalId, corrections),
                        rules,
                        courseKey(course).takeIf { it.isNotEmpty() },
                        rogaineRemoveLegsCps(item.logicalId, corrections),
                        legendTypeByCp(settings),
                        override?.cpTypeRules.orEmpty(),
                    )
                } else {
                    calculateOrientResult(
                        punches,
                        orientSettings,
                        course,
                        courseMap,
                        removed,
                        rules,
                        adds,
                    )
                }
                ScoredResult(
                    queueId = item.id,
                    logicalId = item.logicalId,
                    name = item.personName.ifBlank { person?.name.orEmpty() }.ifBlank { item.logicalId?.let { "№ $it" } ?: "без номера" },
                    course = course,
                    uid = item.uid,
                    punches = punches,
                    calc = calc,
                    place = null,
                    chipHint = ChipCellLog.hintFromRawHex(item.rawHex),
                    teamId = if (rogaine) person?.teamId ?: 0 else 0,
                    numberConflict = item.id in conflictIds,
                )
            }
            val byCourse = raw.groupBy { courseKey(it.course) }
            val placed = ArrayList<ScoredResult>(raw.size)
            for (group in byCourse.values) {
                val ranked = group.filter { !it.numberConflict }
                val skipped = group.filter { it.numberConflict }
                val places = if (rogaine) {
                    assignRogaineTeamPlaces(ranked.map { it.calc }, ranked.map { it.teamId })
                } else {
                    assignPlaces(ranked.map { it.calc })
                }
                ranked.forEachIndexed { i, row -> placed += row.copy(place = places[i]) }
                skipped.forEach { row -> placed += row.copy(place = null) }
            }
            val teamBestPlace = placed
                .filter { it.teamId > 0 && !it.numberConflict }
                .groupBy { it.teamId to courseKey(it.course) }
                .mapValues { (_, rows) -> rows.mapNotNull { it.place }.minOrNull() ?: Int.MAX_VALUE }
            val teamBestStatus = placed
                .filter { it.teamId > 0 && !it.numberConflict }
                .groupBy { it.teamId to courseKey(it.course) }
                .mapValues { (_, rows) -> rows.minOf { protocolStatusRank(it.calc.status) } }
            return placed.sortedWith(
                compareBy<ScoredResult> { courseKey(it.course) }
                    .thenBy {
                        teamBestStatus[it.teamId to courseKey(it.course)]
                            ?: protocolStatusRank(it.calc.status)
                    }
                    .thenBy { teamBestPlace[it.teamId to courseKey(it.course)] ?: it.place ?: Int.MAX_VALUE }
                    .thenBy { if (it.teamId > 0) 0 else 1 }
                    .thenBy { it.teamId }
                    .thenBy { it.place ?: Int.MAX_VALUE }
                    .thenBy { it.logicalId ?: Int.MAX_VALUE },
            )
        }

        fun legsFor(course: StartCourse, settings: EventSettings, removed: Set<Int>): List<Pair<Int, Int>> =
            orientCourseLegs(course.controls, settings.startCp, settings.finishCp, removed)

        fun removedCps(corrections: List<CorrectionEntity>): Set<Int> =
            corrections.filter { it.type == "remove_cp" && it.logicalId == null }
                .mapNotNull { payloadInt(it.payloadJson, "cp_number") }
                .toSet()

        fun correctionCp(row: CorrectionEntity): Int? = payloadInt(row.payloadJson, "cp_number")

        fun correctionRemoveMode(row: CorrectionEntity): String =
            payloadString(row.payloadJson, "remove_mode").ifBlank { ROGAINE_REMOVE_LEGS }

        fun correctionCourse(row: CorrectionEntity): String = payloadString(row.payloadJson, "course")

        fun isDidNotFinish(logicalId: Int?, corrections: List<CorrectionEntity>): Boolean {
            if (logicalId == null) return false
            return corrections.any { it.type == DID_NOT_FINISH && it.logicalId == logicalId }
        }

        fun copiedFromLogicalId(logicalId: Int?, corrections: List<CorrectionEntity>): Int? {
            if (logicalId == null) return null
            return corrections.lastOrNull { it.type == COPY_PUNCHES && it.logicalId == logicalId }
                ?.let { payloadInt(it.payloadJson, "from_logical_id") }
        }

        fun rogaineAddedCps(logicalId: Int?, corrections: List<CorrectionEntity>): Set<Int> {
            if (logicalId == null) return emptySet()
            return corrections.mapNotNull { row ->
                if (row.type == "add_cp" && row.logicalId == logicalId) correctionCp(row) else null
            }.toSet()
        }

        fun rogaineRemovedPointCps(logicalId: Int?, corrections: List<CorrectionEntity>): Set<Int> =
            corrections.mapNotNull { row ->
                if (row.type == "remove_cp" && (row.logicalId == null || row.logicalId == logicalId)) {
                    correctionCp(row)
                } else {
                    null
                }
            }.toSet()

        fun rogaineRemoveLegsCps(logicalId: Int?, corrections: List<CorrectionEntity>): Set<Int> =
            corrections.mapNotNull { row ->
                if (row.type == "remove_cp" &&
                    (row.logicalId == null || row.logicalId == logicalId) &&
                    correctionRemoveMode(row) == ROGAINE_REMOVE_LEGS
                ) {
                    correctionCp(row)
                } else {
                    null
                }
            }.toSet()

        private fun assignedCourse(logicalId: Int?, corrections: List<CorrectionEntity>): String {
            if (logicalId == null) return ""
            return corrections.lastOrNull { it.type == "assign_course" && it.logicalId == logicalId }
                ?.let { payloadString(it.payloadJson, "course") }
                .orEmpty()
        }

        fun addCpCounts(logicalId: Int?, courseName: String, corrections: List<CorrectionEntity>): Map<Int, Int> {
            if (logicalId == null) return emptyMap()
            val out = HashMap<Int, Int>()
            for (row in corrections) {
                if (row.type != "add_cp" || row.logicalId != logicalId) continue
                if (row.courseName.isNotBlank() && !row.courseName.equals(courseName, true)) continue
                val cp = payloadInt(row.payloadJson, "cp_number") ?: continue
                out[cp] = (out[cp] ?: 0) + 1
            }
            return out
        }

        private fun parsePunches(json: String): List<OrientMark> {
            return try {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val cp = obj.optInt("cp")
                    val seconds = parseDaySeconds(obj.optString("time")) ?: return@mapNotNull null
                    if (cp <= 0) null else OrientMark(cp, seconds, i)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun payloadInt(json: String, key: String): Int? =
            try {
                JSONObject(json).optInt(key).takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }

        private fun payloadString(json: String, key: String): String =
            try {
                JSONObject(json).optString(key).trim()
            } catch (_: Exception) {
                ""
            }

        private fun List<SettingEntity>.toSettings(): EventSettings {
            val map = associate { it.key to it.value }
            return EventSettings(
                startCp = map["start_cp"]?.toIntOrNull() ?: CP_START,
                finishCp = map["finish_cp"]?.toIntOrNull() ?: CP_FINISH,
                controlMinutes = map["control_minutes"]?.toIntOrNull() ?: 120,
                penaltyPerMinute = map["penalty_per_minute"]?.toIntOrNull() ?: 1,
                dqMinutes = map["dq_minutes"]?.toIntOrNull() ?: 15,
                startMode = map["start_mode"].orEmpty().ifBlank { "station" },
                competitionStartTime = map["competition_start_time"].orEmpty(),
                competitionDate = map["competition_date"].orEmpty().ifBlank { LocalDate.now().toString() },
                apiBaseUrl = map["api_base_url"].orEmpty(),
                publishToken = map["publish_token"].orEmpty(),
                slug = map["slug"].orEmpty(),
                title = map["title"].orEmpty(),
                liveNote = map["live_note"].orEmpty(),
                livePending = map["live_pending"] == "1",
                sportKind = normalizeSportKind(map["sport_kind"]),
                metersPerPixel = map["meters_per_pixel"]?.toDoubleOrNull(),
                mapWidth = map["map_image_width"]?.toIntOrNull()?.takeIf { it > 0 },
                mapHeight = map["map_image_height"]?.toIntOrNull()?.takeIf { it > 0 },
                mapPointsJson = map["map_points"].orEmpty(),
                formatSettingsJson = map["format_settings"].orEmpty(),
                legendsJson = map["cp_legends"].orEmpty(),
                awardGroupsJson = map["award_groups"].orEmpty(),
            )
        }

        fun legends(settings: EventSettings): List<LegendView> {
            return try {
                val array = JSONArray(settings.legendsJson.ifBlank { "[]" })
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val cp = item.optInt("cp", 0).takeIf { it > 0 }
                        ?: item.optInt("cpNumber", 0).takeIf { it > 0 }
                        ?: return@mapNotNull null
                    LegendView(
                        cp = cp,
                        name = item.optString("name").trim(),
                        type = item.optString("type").ifBlank { item.optString("cpType") }.trim(),
                    )
                }.distinctBy { it.cp }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun legendCps(settings: EventSettings): List<Int> = legends(settings).map { it.cp }

        fun legendTypeByCp(settings: EventSettings): Map<Int, String> =
            legends(settings).associate { it.cp to it.type }

        fun displayResultGroups(
            results: List<ScoredResult>,
            people: List<StartPerson>,
            sportKind: String,
            competitionDate: String,
            awardGroups: List<AwardGroupView>,
            emptyFormatLabel: String,
        ): List<ResultGroup> {
            return resultBuckets(
                scored = results,
                people = people,
                courses = emptyList(),
                sportKind = sportKind,
                competitionDate = competitionDate,
                awardGroups = awardGroups,
                removed = emptySet(),
                includeEmptyAwardGroups = false,
                includeUnmatched = true,
                emptyFormatLabel = emptyFormatLabel,
            ).map { ResultGroup(it.key, it.name, it.rows) }
        }

        fun awardGroups(settings: EventSettings): List<AwardGroupView> {
            return try {
                val array = JSONArray(settings.awardGroupsJson.ifBlank { "[]" })
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = item.optString("name").trim()
                    if (name.isEmpty()) return@mapNotNull null
                    val formats = item.optJSONArray("formatNames") ?: item.optJSONArray("format_names")
                    AwardGroupView(
                        name = name,
                        genderMode = item.optString("genderMode").ifBlank { item.optString("gender_mode") },
                        minAge = item.optInt("minAge").takeIf { item.has("minAge") && !item.isNull("minAge") && it > 0 }
                            ?: item.optInt("min_age").takeIf { item.has("min_age") && !item.isNull("min_age") && it > 0 },
                        maxAge = item.optInt("maxAge").takeIf { item.has("maxAge") && !item.isNull("maxAge") && it > 0 }
                            ?: item.optInt("max_age").takeIf { item.has("max_age") && !item.isNull("max_age") && it > 0 },
                        formatNames = if (formats == null) {
                            emptyList()
                        } else {
                            (0 until formats.length()).mapNotNull { i ->
                                formats.optString(i).trim().takeIf { it.isNotEmpty() }
                            }
                        },
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun formatTypeRules(settings: EventSettings): Map<String, List<RogaineCpTypeRule>> =
            parseFormatOverrides(settings.formatSettingsJson).mapValues { it.value.cpTypeRules }

        fun parseFormatOverrides(json: String): Map<String, RogaineFormatOverride> {
            if (json.isBlank()) return emptyMap()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = obj.optString("name").trim()
                    if (name.isEmpty()) return@mapNotNull null
                    courseKey(name) to RogaineFormatOverride(
                        controlMinutes = obj.optIntOrNull("controlMinutes"),
                        penaltyPerMinute = obj.optIntOrNull("penaltyPerMinute"),
                        dqMinutes = obj.optIntOrNull("dqMinutes"),
                        finishCp = obj.optIntOrNull("finishCp"),
                        competitionStartTime = obj.optString("competitionStartTime").trim(),
                        cpTypeRules = parseCpTypeRules(obj.optJSONArray("cpTypeRules") ?: obj.optJSONArray("cp_type_rules")),
                    )
                }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun JSONObject.optIntOrNull(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            return optInt(key).takeIf { opt(key) != null }
        }

        private fun parseCpTypeRules(array: JSONArray?): List<RogaineCpTypeRule> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val type = obj.optString("type").ifBlank { obj.optString("cpType") }.trim()
                if (type.isEmpty()) return@mapNotNull null
                RogaineCpTypeRule(
                    type = type,
                    maxCount = (obj.optIntOrNull("maxCount") ?: obj.optIntOrNull("max_count"))?.takeIf { it > 0 },
                )
            }
        }

        fun liveReady(settings: EventSettings): Boolean =
            settings.apiBaseUrl.isNotBlank() &&
                settings.publishToken.isNotBlank() &&
                settings.slug.isNotBlank() &&
                settings.title.isNotBlank()

        fun liveViewerUrl(baseUrl: String, slug: String): String? {
            val base = baseUrl.trim().trimEnd('/')
            val id = slug.trim()
            if (base.isEmpty() || id.isEmpty()) return null
            return "$base/live/$id"
        }

        fun liveViewerUrl(settings: EventSettings): String? =
            liveViewerUrl(settings.apiBaseUrl, settings.slug)

        fun liveShareText(settings: EventSettings): String? {
            val url = liveViewerUrl(settings) ?: return null
            val title = settings.title.trim()
            return if (title.isEmpty()) url else "$title\n$url"
        }

        private const val COURSE_MAP_FILE = "course_map.bin"

        private fun awardGroupsJson(rows: List<EventPackAwardGroupFile>): String {
            val root = JSONArray()
            for (row in rows) {
                root.put(
                    JSONObject()
                        .put("name", row.name)
                        .put("genderMode", row.genderMode)
                        .put("minAge", row.minAge ?: JSONObject.NULL)
                        .put("maxAge", row.maxAge ?: JSONObject.NULL)
                        .put("sortOrder", row.sortOrder)
                        .put("formatNames", JSONArray(row.formatNames)),
                )
            }
            return root.toString()
        }

        private fun legendsJson(rows: List<EventPackLegendFile>): String {
            val root = JSONArray()
            for (row in rows) {
                root.put(
                    JSONObject()
                        .put("cp", row.cp)
                        .put("name", row.name)
                        .put("type", row.type)
                        .put("mapX", row.mapX ?: JSONObject.NULL)
                        .put("mapY", row.mapY ?: JSONObject.NULL),
                )
            }
            return root.toString()
        }

        private fun formatsJson(rows: List<EventPackFormatFile>): String {
            val root = JSONArray()
            for (row in rows) {
                root.put(
                    JSONObject()
                        .put("name", row.name)
                        .put("controlMinutes", row.controlMinutes ?: JSONObject.NULL)
                        .put("penaltyPerMinute", row.penaltyPerMinute ?: JSONObject.NULL)
                        .put("dqMinutes", row.dqMinutes ?: JSONObject.NULL)
                        .put("finishCp", row.finishCp ?: JSONObject.NULL)
                        .put("competitionStartTime", row.competitionStartTime)
                        .put(
                            "cpTypeRules",
                            JSONArray().apply {
                                row.cpTypeRules.forEach { rule ->
                                    put(
                                        JSONObject()
                                            .put("type", rule.type)
                                            .put("maxCount", rule.maxCount ?: JSONObject.NULL),
                                    )
                                }
                            },
                        ),
                )
            }
            return root.toString()
        }

        private fun normalizeBase(url: String): String = url.trim().trimEnd('/')

        private data class ByteDownload(val bytes: ByteArray, val fileName: String)

        private fun requestBytes(method: String, url: String, token: String): ByteDownload? {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            return try {
                val code = connection.responseCode
                if (code == 404) return null
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.readBytes() ?: ByteArray(0)
                if (code !in 200..299) {
                    error("Сайт ответил $code: ${bytes.toString(Charsets.UTF_8).take(180)}")
                }
                val name = connection.getHeaderField("X-Map-File-Name").orEmpty()
                ByteDownload(bytes, name)
            } finally {
                connection.disconnect()
            }
        }

        private fun request(method: String, url: String, body: String?, token: String?): Pair<Int, String> {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12_000
                readTimeout = 20_000
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            return try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                code to text
            } finally {
                connection.disconnect()
            }
        }

        private data class ResultBucket(
            val id: Int,
            val key: String,
            val name: String,
            val genderMode: String,
            val minAge: Int?,
            val maxAge: Int? = null,
            val sortOrder: Int,
            val controls: List<Int>,
            val rows: List<ScoredResult>,
            val lengthMeters: Int? = null,
            val climbMeters: Int? = null,
            val legMeters: List<Int?> = emptyList(),
        )

        private fun resultBuckets(
            scored: List<ScoredResult>,
            people: List<StartPerson>,
            courses: List<StartCourse>,
            sportKind: String,
            competitionDate: String,
            awardGroups: List<AwardGroupView>,
            removed: Set<Int>,
            includeEmptyAwardGroups: Boolean,
            includeUnmatched: Boolean,
            emptyFormatLabel: String = "без дистанции",
            startCp: Int = CP_START,
            finishCp: Int = CP_FINISH,
        ): List<ResultBucket> {
            val award = awardGroups
            if (isRogaine(sportKind) && award.isNotEmpty()) {
                val categories = award.map { it.toAwardCategory() }
                val membership = scored.associate { row ->
                    row.queueId to matchingAwardGroupNames(row, people, categories, competitionDate)
                }
                val buckets = ArrayList<ResultBucket>(award.size + 1)
                award.forEachIndexed { index, group ->
                    val rows = scored.filter { group.name in membership[it.queueId].orEmpty() }
                    if (rows.isEmpty() && !includeEmptyAwardGroups) return@forEachIndexed
                    buckets += ResultBucket(
                        id = index + 1,
                        key = "award-${index + 1}",
                        name = group.name,
                        genderMode = group.genderMode.ifBlank { "any" },
                        minAge = group.minAge,
                        maxAge = group.maxAge,
                        sortOrder = index,
                        controls = emptyList(),
                        rows = rankRogaineGroup(rows),
                    )
                }
                if (includeUnmatched) {
                    val unmatched = scored.filter { membership[it.queueId].orEmpty().isEmpty() }
                    if (unmatched.isNotEmpty()) {
                        buckets += ResultBucket(
                            id = award.size + 1,
                            key = "ungrouped",
                            name = "без группы",
                            genderMode = "any",
                            minAge = null,
                            maxAge = null,
                            sortOrder = award.size,
                            controls = emptyList(),
                            rows = rankRogaineGroup(unmatched),
                        )
                    }
                }
                return buckets
            }
            val byCourse = scored.groupBy { courseKey(it.course) }
            val buckets = ArrayList<ResultBucket>()
            val publishedKeys = HashSet<String>()
            courses.forEachIndexed { index, course ->
                val key = courseKey(course.name)
                publishedKeys += key
                val rows = byCourse[key].orEmpty()
                if (rows.isEmpty()) return@forEachIndexed
                val published = filterCourseCps(course.controls, removed)
                buckets += ResultBucket(
                    id = index + 1,
                    key = key,
                    name = course.name,
                    genderMode = "any",
                    minAge = null,
                    sortOrder = index,
                    controls = published,
                    rows = rows,
                    lengthMeters = course.lengthMeters,
                    climbMeters = course.climbMeters,
                    legMeters = remapCourseLegMeters(
                        startCp = startCp,
                        finishCp = finishCp,
                        fullControls = course.controls,
                        publishedControls = published,
                        legs = course.legMeters,
                    ),
                )
            }
            byCourse.entries
                .filter { it.key !in publishedKeys && it.value.isNotEmpty() }
                .forEachIndexed { extraIndex, (_, rows) ->
                    val sortOrder = courses.size + extraIndex
                    buckets += ResultBucket(
                        id = sortOrder + 1,
                        key = "extra-$sortOrder",
                        name = rows.firstOrNull()?.course?.ifBlank { emptyFormatLabel } ?: emptyFormatLabel,
                        genderMode = "any",
                        minAge = null,
                        sortOrder = sortOrder,
                        controls = emptyList(),
                        rows = rows,
                    )
                }
            if (courses.isEmpty() && buckets.isEmpty()) {
                scored.groupBy { courseKey(it.course) }.forEach { (key, rows) ->
                    buckets += ResultBucket(
                        id = buckets.size + 1,
                        key = key.ifBlank { "empty" },
                        name = rows.first().course.ifBlank { emptyFormatLabel },
                        genderMode = "any",
                        minAge = null,
                        sortOrder = buckets.size,
                        controls = emptyList(),
                        rows = rows,
                    )
                }
            }
            return buckets
        }

        private fun matchingAwardGroupNames(
            row: ScoredResult,
            people: List<StartPerson>,
            groups: List<AwardCategory>,
            competitionDate: String,
        ): List<String> {
            val person = row.logicalId?.let { id -> people.find { it.id == id } }
            val teammates = if ((person?.teamId ?: row.teamId) > 0) {
                val teamId = person?.teamId ?: row.teamId
                people.filter { it.teamId == teamId && it.id != person?.id }
                    .map { AwardPerson(it.gender, it.birthDate) }
            } else {
                emptyList()
            }
            return personAwardCategories(
                format = row.course,
                gender = person?.gender.orEmpty(),
                birthDate = person?.birthDate.orEmpty(),
                competitionDate = competitionDate,
                groups = groups,
                teammates = teammates,
            )
        }

        private fun rankRogaineGroup(rows: List<ScoredResult>): List<ScoredResult> {
            if (rows.isEmpty()) return rows
            val places = assignRogaineTeamPlaces(rows.map { it.calc }, rows.map { it.teamId })
            val placed = rows.mapIndexed { index, row -> row.copy(place = places[index]) }
            val teamBestPlace = placed
                .filter { it.teamId > 0 }
                .groupBy { it.teamId }
                .mapValues { (_, teamRows) -> teamRows.mapNotNull { it.place }.minOrNull() ?: Int.MAX_VALUE }
            val teamBestStatus = placed
                .filter { it.teamId > 0 }
                .groupBy { it.teamId }
                .mapValues { (_, teamRows) -> teamRows.minOf { protocolStatusRank(it.calc.status) } }
            return placed.sortedWith(
                compareBy<ScoredResult> {
                    teamBestStatus[it.teamId] ?: protocolStatusRank(it.calc.status)
                }
                    .thenBy { teamBestPlace[it.teamId] ?: it.place ?: Int.MAX_VALUE }
                    .thenBy { if (it.teamId > 0) 0 else 1 }
                    .thenBy { it.teamId }
                    .thenBy { it.place ?: Int.MAX_VALUE }
                    .thenBy { it.logicalId ?: Int.MAX_VALUE },
            )
        }

        private fun AwardGroupView.toAwardCategory(): AwardCategory =
            AwardCategory(
                name = name,
                genderMode = genderMode,
                minAge = minAge,
                formatNames = formatNames,
                maxAge = maxAge,
            )

        internal fun liveMapPoints(mapPointsJson: String, legendsJson: String): JSONArray {
            val fromPoints = parseLiveMapPoints(mapPointsJson)
            if (fromPoints.length() > 0) return fromPoints
            return parseLegendMapPoints(legendsJson)
        }

        internal fun livePath(
            punches: List<OrientMark>,
            mapPoints: JSONArray,
            startCp: Int,
            finishCp: Int,
        ): JSONArray {
            val byCp = HashMap<Int, Triple<Double, Double, String>>()
            var startXy: Triple<Double, Double, String>? = null
            var finishXy: Triple<Double, Double, String>? = null
            for (i in 0 until mapPoints.length()) {
                val point = mapPoints.optJSONObject(i) ?: continue
                val x = point.optDouble("map_x")
                val y = point.optDouble("map_y")
                if (!x.isFinite() || !y.isFinite()) continue
                val kind = point.optString("kind").trim().ifBlank { "cp" }
                val xy = Triple(x, y, kind)
                val cp = if (point.isNull("cp_number")) null else point.optInt("cp_number").takeIf { it > 0 }
                if (cp != null) byCp[cp] = xy
                if (kind == "start") startXy = xy
                if (kind == "finish") finishXy = xy
            }
            fun lookup(cp: Int): Triple<Double, Double, String>? =
                byCp[cp] ?: when (cp) {
                    startCp -> startXy
                    finishCp -> finishXy
                    else -> null
                }
            val path = JSONArray()
            for (punch in punches) {
                val xy = lookup(punch.cp) ?: continue
                if (path.length() > 0) {
                    val last = path.getJSONObject(path.length() - 1)
                    if (abs(last.optDouble("x") - xy.first) < 1e-9 &&
                        abs(last.optDouble("y") - xy.second) < 1e-9
                    ) {
                        continue
                    }
                }
                val kind = when (punch.cp) {
                    startCp -> "start"
                    finishCp -> "finish"
                    else -> xy.third
                }
                path.put(
                    JSONObject()
                        .put("x", xy.first)
                        .put("y", xy.second)
                        .put("cp_number", punch.cp)
                        .put("kind", kind),
                )
            }
            return path
        }

        internal fun liveDistanceM(
            path: JSONArray,
            width: Double?,
            height: Double?,
            metersPerPixel: Double?,
        ): Double? {
            if (path.length() < 2) return null
            val w = width?.takeIf { it > 0 } ?: return null
            val h = height?.takeIf { it > 0 } ?: return null
            val mpp = metersPerPixel?.takeIf { it > 0 } ?: return null
            var distance = 0.0
            for (i in 0 until path.length() - 1) {
                val a = path.getJSONObject(i)
                val b = path.getJSONObject(i + 1)
                val dx = (b.optDouble("x") - a.optDouble("x")) * w
                val dy = (b.optDouble("y") - a.optDouble("y")) * h
                distance += hypot(dx, dy) * mpp
            }
            return distance.takeIf { it.isFinite() && it > 0 }
        }

        private fun parseLiveMapPoints(raw: String): JSONArray {
            val out = JSONArray()
            val array = try {
                JSONArray(raw.ifBlank { "[]" })
            } catch (_: Exception) {
                return out
            }
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val x = jsonCoord(item, "map_x", "mapX") ?: continue
                val y = jsonCoord(item, "map_y", "mapY") ?: continue
                val kind = item.optString("kind").trim().ifBlank { "cp" }
                val cp = jsonPositiveInt(item, "cp_number", "cp")
                out.put(
                    JSONObject()
                        .put("kind", kind)
                        .put("cp_number", cp ?: JSONObject.NULL)
                        .put("name", item.optString("name"))
                        .put("map_x", x)
                        .put("map_y", y),
                )
            }
            return out
        }

        private fun parseLegendMapPoints(raw: String): JSONArray {
            val out = JSONArray()
            val array = try {
                JSONArray(raw.ifBlank { "[]" })
            } catch (_: Exception) {
                return out
            }
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val x = jsonCoord(item, "mapX", "map_x") ?: continue
                val y = jsonCoord(item, "mapY", "map_y") ?: continue
                val cp = jsonPositiveInt(item, "cp", "cpNumber", "cp_number") ?: continue
                out.put(
                    JSONObject()
                        .put("kind", "cp")
                        .put("cp_number", cp)
                        .put("name", item.optString("name"))
                        .put("map_x", x)
                        .put("map_y", y),
                )
            }
            return out
        }

        private fun jsonCoord(obj: JSONObject, vararg keys: String): Double? {
            for (key in keys) {
                if (!obj.has(key) || obj.isNull(key)) continue
                val value = obj.optDouble(key)
                if (value.isFinite()) return value
            }
            return null
        }

        private fun jsonPositiveInt(obj: JSONObject, vararg keys: String): Int? {
            for (key in keys) {
                if (!obj.has(key) || obj.isNull(key)) continue
                val parsed = when (val value = obj.opt(key)) {
                    is Number -> value.toInt()
                    is String -> value.trim().toIntOrNull()
                    else -> null
                }
                if (parsed != null && parsed > 0) return parsed
            }
            return null
        }

        private fun buildPublishJson(
            settings: EventSettings,
            title: String,
            people: List<StartPerson>,
            courses: List<StartCourse>,
            scored: List<ScoredResult>,
            removed: Set<Int>,
            requireResults: Boolean = true,
        ): String {
            val groups = JSONArray()
            val results = JSONArray()
            val participants = JSONArray()
            val usedSourceIds = HashSet<Long>()
            val sourceByQueue = HashMap<String, Long>()
            val mapPoints = liveMapPoints(settings.mapPointsJson, settings.legendsJson)
            val buckets = resultBuckets(
                scored = scored,
                people = people,
                courses = courses,
                sportKind = settings.sportKind,
                competitionDate = settings.competitionDate,
                awardGroups = awardGroups(settings),
                removed = removed,
                includeEmptyAwardGroups = isRogaine(settings.sportKind),
                includeUnmatched = false,
                startCp = settings.startCp,
                finishCp = settings.finishCp,
            )
            fun participantSource(row: ScoredResult): Long {
                sourceByQueue[row.queueId]?.let { return it }
                val sourceId = uniqueParticipantSourceId(row, usedSourceIds)
                sourceByQueue[row.queueId] = sourceId
                val person = row.logicalId?.let { id -> people.find { it.id == id } }
                val birth = storedBirthDate(person?.birthDate.orEmpty())
                    .takeIf { it.matches(Regex("""^\d{4}-\d{2}-\d{2}$""")) }
                val marks = JSONArray()
                row.punches.forEachIndexed { seq, punch ->
                    val h = punch.seconds / 3600
                    val m = (punch.seconds % 3600) / 60
                    val s = punch.seconds % 60
                    marks.put(
                        JSONObject()
                            .put("seq", seq + 1)
                            .put("cp_number", punch.cp)
                            .put("mark_time", "%sT%02d:%02d:%02d".format(Locale.US, settings.competitionDate, h, m, s)),
                    )
                }
                val path = livePath(row.punches, mapPoints, settings.startCp, settings.finishCp)
                val distanceM = liveDistanceM(
                    path,
                    settings.mapWidth?.toDouble(),
                    settings.mapHeight?.toDouble(),
                    settings.metersPerPixel,
                )
                participants.put(
                    JSONObject()
                        .put("source_id", sourceId)
                        .put("bib", row.logicalId?.toString() ?: "")
                        .put("chip_physical", row.uid)
                        .put("chip_logical", row.logicalId?.toString() ?: JSONObject.NULL)
                        .put("name", row.name)
                        .put("gender", person?.gender?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                        .put("birth_date", birth ?: JSONObject.NULL)
                        .put("age", personAwardAge(person?.birthDate.orEmpty(), settings.competitionDate) ?: JSONObject.NULL)
                        .put("team_id", if (row.teamId > 0) row.teamId else JSONObject.NULL)
                        .put("format_name", row.course)
                        .put("marks", marks)
                        .put("path", path)
                        .put("distance_m", distanceM ?: JSONObject.NULL)
                        .put("added_cps", JSONObject()),
                )
                return sourceId
            }
            for (bucket in buckets) {
                groups.put(
                    JSONObject()
                        .put("source_id", bucket.id)
                        .put("name", bucket.name)
                        .put("gender_mode", bucket.genderMode.ifBlank { "any" })
                        .put("min_age", bucket.minAge ?: JSONObject.NULL)
                        .put("max_age", bucket.maxAge ?: JSONObject.NULL)
                        .put("sort_order", bucket.sortOrder)
                        .put("course_cps", JSONArray(bucket.controls))
                        .put("length_meters", bucket.lengthMeters ?: JSONObject.NULL)
                        .put("climb_meters", bucket.climbMeters ?: JSONObject.NULL)
                        .put("leg_meters", optionalIntJson(bucket.legMeters)),
                )
                for (row in bucket.rows) {
                    results.put(
                        JSONObject()
                            .put("participant_source_id", participantSource(row))
                            .put("award_group_source_id", bucket.id)
                            .put("place", row.place ?: JSONObject.NULL)
                            .put("points_raw", if (isRogaine(settings.sportKind)) row.calc.pointsRaw else row.calc.taken)
                            .put("penalty_points", row.calc.penaltyPoints)
                            .put("points_final", row.calc.pointsFinal)
                            .put("elapsed_seconds", row.calc.elapsedSeconds)
                            .put("status", row.calc.status)
                            .put("diagnostics", JSONArray(row.calc.diagnostics)),
                    )
                }
            }
            if (requireResults && results.length() == 0) {
                error("Нет результатов для публикации")
            }
            return JSONObject()
                .put("title", title)
                .put("competition_date", settings.competitionDate)
                .put("sport_kind", normalizeSportKind(settings.sportKind))
                .put("start_cp", settings.startCp)
                .put("finish_cp", settings.finishCp)
                .put("award_groups", groups)
                .put("participants", participants)
                .put("results", results)
                .put("map_points", mapPoints)
                .put("meters_per_pixel", settings.metersPerPixel ?: JSONObject.NULL)
                .put("map_width", settings.mapWidth ?: JSONObject.NULL)
                .put("map_height", settings.mapHeight ?: JSONObject.NULL)
                .toString()
        }

        private fun optionalIntJson(values: List<Int?>): JSONArray {
            val array = JSONArray()
            for (value in values) {
                if (value == null) array.put(JSONObject.NULL) else array.put(value)
            }
            return array
        }

        /** One finish = one site participant. Same chip on D1 and D2 must not share source_id. */
        internal fun uniqueParticipantSourceId(row: ScoredResult, used: MutableSet<Long>): Long {
            var id = stableParticipantSourceId(row.queueId, row.logicalId)
            while (id <= 0L || !used.add(id)) {
                id += 1L
                if (id <= 0L) id = 1L
            }
            return id
        }

        internal fun stableParticipantSourceId(queueId: String, logicalId: Int?): Long {
            val uuid = runCatching { UUID.fromString(queueId) }.getOrNull()
            val hashed = if (uuid != null) {
                uuid.mostSignificantBits xor uuid.leastSignificantBits
            } else {
                queueId.hashCode().toLong()
            }
            // Keep ids inside JS Number.MAX_SAFE_INTEGER so live links survive JSON.parse.
            val positive = hashed and 0x7FFF_FFFFL
            if (positive > 0L) return positive
            val chip = logicalId?.toLong() ?: 0L
            return if (chip > 0L) chip else 1L
        }
    }
}

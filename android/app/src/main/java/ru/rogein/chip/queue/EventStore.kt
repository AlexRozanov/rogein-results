package ru.rogein.chip.queue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.OrientCalc
import ru.rogein.chip.parse.OrientExclusion
import ru.rogein.chip.parse.OrientMark
import ru.rogein.chip.parse.OrientSettings
import ru.rogein.chip.parse.STATUS_OK
import ru.rogein.chip.parse.assignPlaces
import ru.rogein.chip.parse.calculateOrientResult
import ru.rogein.chip.parse.courseKey
import ru.rogein.chip.parse.filterCourseCps
import ru.rogein.chip.parse.orientCourseLegs
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

data class EventSettings(
    val startCp: Int = CP_START,
    val finishCp: Int = CP_FINISH,
    val controlMinutes: Int = 120,
    val competitionDate: String = LocalDate.now().toString(),
    val apiBaseUrl: String = "",
    val publishToken: String = "",
    val slug: String = "",
    val title: String = "",
    val liveNote: String = "",
    val livePending: Boolean = false,
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
)

class EventStore(
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
        eventDao.upsertSetting(SettingEntity("competition_date", next.competitionDate.trim()))
        eventDao.upsertSetting(SettingEntity("api_base_url", next.apiBaseUrl.trim()))
        eventDao.upsertSetting(SettingEntity("publish_token", next.publishToken.trim()))
        eventDao.upsertSetting(SettingEntity("slug", next.slug.trim()))
        eventDao.upsertSetting(SettingEntity("title", next.title.trim()))
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
        if (scored.isEmpty()) error("Нет считанных результатов")
        val removed = removedCps(corrections)
        val payload = buildPublishJson(settings, title, courses, scored, removed)
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
        val payload = buildPublishJson(settings, title, courses, scored, removed)
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
            val raw = items.map { item ->
                val person = item.logicalId?.let { id -> people.find { it.id == id } }
                val assigned = assignedCourse(item.logicalId, corrections)
                val course = assigned.ifBlank { item.courseName.ifBlank { person?.currentCourse.orEmpty() } }
                val punches = parsePunches(item.punchesJson)
                val adds = addCpCounts(item.logicalId, course, corrections)
                val calc = calculateOrientResult(
                    punches,
                    orientSettings,
                    course,
                    courseMap,
                    removed,
                    rules,
                    adds,
                )
                ScoredResult(
                    queueId = item.id,
                    logicalId = item.logicalId,
                    name = item.personName.ifBlank { person?.name.orEmpty() }.ifBlank { item.logicalId?.let { "№ $it" } ?: "без номера" },
                    course = course,
                    uid = item.uid,
                    punches = punches,
                    calc = calc,
                    place = null,
                )
            }
            val byCourse = raw.groupBy { courseKey(it.course) }
            val placed = ArrayList<ScoredResult>(raw.size)
            for (group in byCourse.values) {
                val places = assignPlaces(group.map { it.calc })
                group.forEachIndexed { i, row -> placed += row.copy(place = places[i]) }
            }
            return placed.sortedWith(
                compareBy<ScoredResult> { courseKey(it.course) }
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

        fun correctionCourse(row: CorrectionEntity): String = payloadString(row.payloadJson, "course")

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
                competitionDate = map["competition_date"].orEmpty().ifBlank { LocalDate.now().toString() },
                apiBaseUrl = map["api_base_url"].orEmpty(),
                publishToken = map["publish_token"].orEmpty(),
                slug = map["slug"].orEmpty(),
                title = map["title"].orEmpty(),
                liveNote = map["live_note"].orEmpty(),
                livePending = map["live_pending"] == "1",
            )
        }

        fun liveReady(settings: EventSettings): Boolean =
            settings.apiBaseUrl.isNotBlank() &&
                settings.publishToken.isNotBlank() &&
                settings.slug.isNotBlank() &&
                settings.title.isNotBlank()

        private fun normalizeBase(url: String): String = url.trim().trimEnd('/')

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

        private fun buildPublishJson(
            settings: EventSettings,
            title: String,
            courses: List<StartCourse>,
            scored: List<ScoredResult>,
            removed: Set<Int>,
        ): String {
            val groups = JSONArray()
            val results = JSONArray()
            val participants = JSONArray()
            val usedSourceIds = HashSet<Long>()
            val byCourse = scored.groupBy { courseKey(it.course) }
            fun addGroup(groupId: Int, name: String, sortOrder: Int, controls: List<Int>, rows: List<ScoredResult>) {
                if (rows.isEmpty()) return
                groups.put(
                    JSONObject()
                        .put("source_id", groupId)
                        .put("name", name)
                        .put("gender_mode", "any")
                        .put("min_age", JSONObject.NULL)
                        .put("sort_order", sortOrder)
                        .put("course_cps", JSONArray(controls)),
                )
                for (row in rows) {
                    val sourceId = uniqueParticipantSourceId(row, usedSourceIds)
                    results.put(
                        JSONObject()
                            .put("participant_source_id", sourceId)
                            .put("award_group_source_id", groupId)
                            .put("place", row.place ?: JSONObject.NULL)
                            .put("points_raw", row.calc.taken)
                            .put("penalty_points", 0)
                            .put("points_final", 0)
                            .put("elapsed_seconds", row.calc.elapsedSeconds)
                            .put("status", row.calc.status)
                            .put("diagnostics", JSONArray(row.calc.diagnostics)),
                    )
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
                    participants.put(
                        JSONObject()
                            .put("source_id", sourceId)
                            .put("bib", row.logicalId?.toString() ?: "")
                            .put("chip_physical", row.uid)
                            .put("chip_logical", row.logicalId?.toString() ?: JSONObject.NULL)
                            .put("name", row.name)
                            .put("format_name", row.course)
                            .put("marks", marks)
                            .put("path", JSONArray())
                            .put("added_cps", JSONObject()),
                    )
                }
            }
            val publishedKeys = HashSet<String>()
            courses.forEachIndexed { index, course ->
                val key = courseKey(course.name)
                publishedKeys += key
                addGroup(
                    groupId = index + 1,
                    name = course.name,
                    sortOrder = index,
                    controls = filterCourseCps(course.controls, removed),
                    rows = byCourse[key].orEmpty(),
                )
            }
            byCourse.entries
                .filter { it.key !in publishedKeys }
                .forEachIndexed { extraIndex, (_, rows) ->
                    val sortOrder = courses.size + extraIndex
                    addGroup(
                        groupId = sortOrder + 1,
                        name = rows.firstOrNull()?.course?.ifBlank { "без дистанции" } ?: "без дистанции",
                        sortOrder = sortOrder,
                        controls = emptyList(),
                        rows = rows,
                    )
                }
            if (results.length() == 0) error("Нет результатов для публикации")
            return JSONObject()
                .put("title", title)
                .put("competition_date", settings.competitionDate)
                .put("sport_kind", "orient")
                .put("start_cp", settings.startCp)
                .put("finish_cp", settings.finishCp)
                .put("award_groups", groups)
                .put("participants", participants)
                .put("results", results)
                .put("map_points", JSONArray())
                .toString()
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
            val positive = hashed and Long.MAX_VALUE
            if (positive > 0L) return positive
            val chip = logicalId?.toLong() ?: 0L
            return if (chip > 0L) chip else 1L
        }
    }
}

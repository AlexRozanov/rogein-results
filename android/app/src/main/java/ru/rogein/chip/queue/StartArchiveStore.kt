package ru.rogein.chip.queue

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.CourseCsvRow
import ru.rogein.chip.parse.CsvExport
import ru.rogein.chip.parse.FinishCsvPunch
import ru.rogein.chip.parse.FinishCsvRow
import ru.rogein.chip.parse.ProtocolCsvRow
import java.time.LocalDate

data class SavedStart(
    val id: Long,
    val label: String,
    val date: String,
    val slug: String,
    val chipCount: Int,
    val personCount: Int,
    val current: Boolean,
    val updatedAt: Long,
)

class StartArchiveStore(
    private val db: ChipQueueDatabase,
) {
    private val starts = db.startArchiveDao()
    private val chips = db.dao()
    private val startList = db.startListDao()
    private val event = db.eventDao()

    fun observe(): Flow<List<SavedStart>> =
        starts.observeAll().map { rows -> rows.map { it.toSaved() } }

    suspend fun ensureCurrent() {
        if (starts.current() != null) return
        db.withTransaction { insertCurrentFromWorkingLocked() }
    }

    suspend fun createNewStart(): String {
        ensureCurrent()
        if (workingIsEmpty()) {
            return "Текущий старт уже пуст."
        }
        db.withTransaction {
            persistCurrentLocked()
            starts.clearCurrent()
            clearWorkingLocked()
            insertEmptyCurrentLocked()
        }
        return "Текущий старт сохранён. Его можно открыть в «Ещё → Старты»."
    }

    suspend fun openStart(id: Long): String {
        ensureCurrent()
        var label = ""
        db.withTransaction {
            val target = starts.byId(id) ?: error("Старт не найден")
            if (target.isCurrent) {
                label = target.toSaved().label
                return@withTransaction
            }
            persistCurrentLocked()
            starts.clearCurrent()
            loadPayloadLocked(target.payloadJson)
            starts.update(target.copy(isCurrent = true, updatedAt = System.currentTimeMillis()))
            label = target.toSaved().label
        }
        return "Открыт старт «$label»."
    }

    suspend fun deleteStart(id: Long): String {
        ensureCurrent()
        var message = "Старт удалён."
        db.withTransaction {
            val row = starts.byId(id) ?: error("Старт не найден")
            if (row.isCurrent) error("Нельзя удалить текущий старт. Сначала откройте другой или начните новый.")
            val label = row.toSaved().label
            starts.delete(id)
            message = "Старт «$label» удалён."
        }
        return message
    }

    suspend fun exportCsv(startId: Long? = null): List<Pair<String, String>> {
        ensureCurrent()
        val currentId = starts.current()?.id
        val id = startId ?: currentId
        val snapshot = if (id == null || id == currentId) {
            workingSnapshot()
        } else {
            val row = starts.byId(id) ?: error("Старт не найден")
            decodePayload(row.payloadJson).copy(
                title = row.title,
                competitionDate = row.competitionDate.ifBlank { decodePayload(row.payloadJson).competitionDate },
                slug = row.slug,
            )
        }
        return snapshot.toCsvFiles()
    }

    private suspend fun persistCurrentLocked() {
        val current = starts.current() ?: insertCurrentFromWorkingLocked()
        val snap = workingSnapshot()
        starts.update(
            current.copy(
                title = snap.title,
                competitionDate = snap.competitionDate,
                slug = snap.slug,
                updatedAt = System.currentTimeMillis(),
                chipCount = snap.chips.size,
                personCount = snap.people.size,
                isCurrent = true,
                payloadJson = snap.toJson(),
            ),
        )
    }

    private suspend fun insertCurrentFromWorkingLocked(): StartArchiveEntity {
        val snap = workingSnapshot()
        val now = System.currentTimeMillis()
        val id = starts.insert(
            StartArchiveEntity(
                title = snap.title,
                competitionDate = snap.competitionDate,
                slug = snap.slug,
                createdAt = now,
                updatedAt = now,
                chipCount = snap.chips.size,
                personCount = snap.people.size,
                isCurrent = true,
                payloadJson = "",
            ),
        )
        return starts.byId(id) ?: error("не удалось создать текущий старт")
    }

    private suspend fun insertEmptyCurrentLocked() {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        event.upsertSetting(SettingEntity("competition_date", today))
        starts.insert(
            StartArchiveEntity(
                title = "",
                competitionDate = today,
                slug = "",
                createdAt = now,
                updatedAt = now,
                chipCount = 0,
                personCount = 0,
                isCurrent = true,
                payloadJson = "",
            ),
        )
    }

    private suspend fun clearWorkingLocked() {
        chips.clear()
        startList.clearPeople()
        startList.clearCourses()
        startList.clearMeta()
        event.clearCorrections()
        event.clearExclusions()
        event.upsertSetting(SettingEntity("slug", ""))
        event.upsertSetting(SettingEntity("title", ""))
        event.upsertSetting(SettingEntity("live_pending", "0"))
        event.upsertSetting(SettingEntity("live_note", ""))
        event.upsertSetting(SettingEntity("competition_date", LocalDate.now().toString()))
    }

    private suspend fun workingIsEmpty(): Boolean {
        return chips.listAll().isEmpty() &&
            startList.listPeople().isEmpty() &&
            startList.listCourses().isEmpty() &&
            event.listCorrections().isEmpty() &&
            event.listExclusions().isEmpty() &&
            setting("title").isBlank() &&
            setting("slug").isBlank()
    }

    private suspend fun workingSnapshot(): EventSnapshot {
        val map = event.listSettings().associate { it.key to it.value }
        return EventSnapshot(
            startCp = map["start_cp"]?.toIntOrNull() ?: CP_START,
            finishCp = map["finish_cp"]?.toIntOrNull() ?: CP_FINISH,
            controlMinutes = map["control_minutes"]?.toIntOrNull() ?: 120,
            competitionDate = map["competition_date"].orEmpty().ifBlank { LocalDate.now().toString() },
            slug = map["slug"].orEmpty(),
            title = map["title"].orEmpty(),
            liveNote = map["live_note"].orEmpty(),
            livePending = map["live_pending"] == "1",
            people = startList.listPeople(),
            courses = startList.listCourses(),
            meta = startList.meta(),
            chips = chips.listAll(),
            corrections = event.listCorrections(),
            exclusions = event.listExclusions(),
        )
    }

    private suspend fun loadPayloadLocked(json: String) {
        clearWorkingLocked()
        if (json.isBlank()) return
        val snap = decodePayload(json)
        event.upsertSetting(SettingEntity("start_cp", snap.startCp.toString()))
        event.upsertSetting(SettingEntity("finish_cp", snap.finishCp.toString()))
        event.upsertSetting(SettingEntity("control_minutes", snap.controlMinutes.toString()))
        event.upsertSetting(SettingEntity("competition_date", snap.competitionDate))
        event.upsertSetting(SettingEntity("slug", snap.slug))
        event.upsertSetting(SettingEntity("title", snap.title))
        event.upsertSetting(SettingEntity("live_note", snap.liveNote))
        event.upsertSetting(SettingEntity("live_pending", if (snap.livePending) "1" else "0"))
        for (person in snap.people) startList.upsertPerson(person)
        for (course in snap.courses) startList.upsertCourse(course)
        snap.meta?.let { startList.upsertMeta(it) }
        for (chip in snap.chips) chips.insert(chip)
        for (row in snap.corrections) {
            event.insertCorrection(row.copy(id = 0))
        }
        for (row in snap.exclusions) {
            event.insertExclusion(row.copy(id = 0))
        }
    }

    private suspend fun setting(key: String): String =
        event.listSettings().find { it.key == key }?.value.orEmpty()

    companion object {
        fun label(title: String, slug: String, date: String): String {
            title.trim().ifNotBlank { return it }
            slug.trim().ifNotBlank { return it }
            return if (date.isNotBlank()) "Старт $date" else "Старт без названия"
        }
    }
}

private data class EventSnapshot(
    val startCp: Int,
    val finishCp: Int,
    val controlMinutes: Int,
    val competitionDate: String,
    val slug: String,
    val title: String,
    val liveNote: String,
    val livePending: Boolean,
    val people: List<StartPersonEntity>,
    val courses: List<StartCourseEntity>,
    val meta: StartMetaEntity?,
    val chips: List<QueuedChipEntity>,
    val corrections: List<CorrectionEntity>,
    val exclusions: List<ExclusionEntity>,
)

private fun EventSnapshot.toJson(): String {
    val root = JSONObject()
    root.put(
        "settings",
        JSONObject()
            .put("startCp", startCp)
            .put("finishCp", finishCp)
            .put("controlMinutes", controlMinutes)
            .put("competitionDate", competitionDate)
            .put("slug", slug)
            .put("title", title)
            .put("liveNote", liveNote)
            .put("livePending", livePending),
    )
    val peopleJson = JSONArray()
    for (person in people) {
        peopleJson.put(
            JSONObject()
                .put("logicalId", person.logicalId)
                .put("name", person.name)
                .put("currentCourse", person.currentCourse)
                .put("coursesJson", person.coursesJson)
                .put("origin", person.origin)
                .put("localCourseTouchedAt", person.localCourseTouchedAt)
                .put("issuedAt", person.issuedAt)
                .put("issuedCourse", person.issuedCourse),
        )
    }
    root.put("people", peopleJson)
    val coursesJson = JSONArray()
    for (course in courses) {
        coursesJson.put(JSONObject().put("name", course.name).put("controlsJson", course.controlsJson))
    }
    root.put("courses", coursesJson)
    if (meta != null) {
        root.put("meta", JSONObject().put("revision", meta.revision).put("appliedAt", meta.appliedAt))
    }
    val chipsJson = JSONArray()
    for (chip in chips) {
        chipsJson.put(
            JSONObject()
                .put("id", chip.id)
                .put("uid", chip.uid)
                .put("logicalId", chip.logicalId ?: JSONObject.NULL)
                .put("clearedAt", chip.clearedAt ?: JSONObject.NULL)
                .put("punchCount", chip.punchCount)
                .put("firstTime", chip.firstTime ?: JSONObject.NULL)
                .put("lastTime", chip.lastTime ?: JSONObject.NULL)
                .put("punchesJson", chip.punchesJson)
                .put("rawHex", chip.rawHex)
                .put("createdAt", chip.createdAt)
                .put("ackedAt", chip.ackedAt ?: JSONObject.NULL)
                .put("personName", chip.personName)
                .put("courseName", chip.courseName),
        )
    }
    root.put("chips", chipsJson)
    val correctionsJson = JSONArray()
    for (row in corrections) {
        correctionsJson.put(
            JSONObject()
                .put("type", row.type)
                .put("logicalId", row.logicalId ?: JSONObject.NULL)
                .put("courseName", row.courseName)
                .put("payloadJson", row.payloadJson),
        )
    }
    root.put("corrections", correctionsJson)
    val exclusionsJson = JSONArray()
    for (row in exclusions) {
        exclusionsJson.put(
            JSONObject()
                .put("courseName", row.courseName)
                .put("fromCp", row.fromCp)
                .put("toCp", row.toCp)
                .put("maxLegSeconds", row.maxLegSeconds ?: JSONObject.NULL),
        )
    }
    root.put("exclusions", exclusionsJson)
    return root.toString()
}

private fun decodePayload(json: String): EventSnapshot {
    if (json.isBlank()) {
        return EventSnapshot(
            startCp = CP_START,
            finishCp = CP_FINISH,
            controlMinutes = 120,
            competitionDate = LocalDate.now().toString(),
            slug = "",
            title = "",
            liveNote = "",
            livePending = false,
            people = emptyList(),
            courses = emptyList(),
            meta = null,
            chips = emptyList(),
            corrections = emptyList(),
            exclusions = emptyList(),
        )
    }
    val root = JSONObject(json)
    val settings = root.optJSONObject("settings") ?: JSONObject()
    val people = jsonObjects(root.optJSONArray("people")).map { obj ->
        StartPersonEntity(
            logicalId = obj.optInt("logicalId"),
            name = obj.optString("name"),
            currentCourse = obj.optString("currentCourse"),
            coursesJson = obj.optString("coursesJson").ifBlank { "[]" },
            origin = obj.optString("origin").ifBlank { "phone" },
            localCourseTouchedAt = obj.optLong("localCourseTouchedAt"),
            issuedAt = obj.optLong("issuedAt"),
            issuedCourse = obj.optString("issuedCourse"),
        )
    }
    val courses = jsonObjects(root.optJSONArray("courses")).map { obj ->
        StartCourseEntity(
            name = obj.optString("name"),
            controlsJson = obj.optString("controlsJson").ifBlank { "[]" },
        )
    }
    val metaObj = root.optJSONObject("meta")
    val meta = metaObj?.let {
        StartMetaEntity(revision = it.optLong("revision"), appliedAt = it.optLong("appliedAt"))
    }
    val chips = jsonObjects(root.optJSONArray("chips")).map { obj ->
        QueuedChipEntity(
            id = obj.optString("id"),
            uid = obj.optString("uid"),
            logicalId = obj.nullInt("logicalId"),
            clearedAt = obj.nullString("clearedAt"),
            punchCount = obj.optInt("punchCount"),
            firstTime = obj.nullString("firstTime"),
            lastTime = obj.nullString("lastTime"),
            punchesJson = obj.optString("punchesJson").ifBlank { "[]" },
            rawHex = obj.optString("rawHex"),
            createdAt = obj.optLong("createdAt"),
            ackedAt = if (obj.isNull("ackedAt")) null else obj.optLong("ackedAt"),
            personName = obj.optString("personName"),
            courseName = obj.optString("courseName"),
        )
    }
    val corrections = jsonObjects(root.optJSONArray("corrections")).map { obj ->
        CorrectionEntity(
            type = obj.optString("type"),
            logicalId = obj.nullInt("logicalId"),
            courseName = obj.optString("courseName"),
            payloadJson = obj.optString("payloadJson").ifBlank { "{}" },
        )
    }
    val exclusions = jsonObjects(root.optJSONArray("exclusions")).map { obj ->
        ExclusionEntity(
            courseName = obj.optString("courseName"),
            fromCp = obj.optInt("fromCp"),
            toCp = obj.optInt("toCp"),
            maxLegSeconds = if (obj.isNull("maxLegSeconds")) null else obj.optInt("maxLegSeconds"),
        )
    }
    return EventSnapshot(
        startCp = settings.optInt("startCp", CP_START),
        finishCp = settings.optInt("finishCp", CP_FINISH),
        controlMinutes = settings.optInt("controlMinutes", 120),
        competitionDate = settings.optString("competitionDate").ifBlank { LocalDate.now().toString() },
        slug = settings.optString("slug"),
        title = settings.optString("title"),
        liveNote = settings.optString("liveNote"),
        livePending = settings.optBoolean("livePending"),
        people = people,
        courses = courses,
        meta = meta,
        chips = chips,
        corrections = corrections,
        exclusions = exclusions,
    )
}

private fun EventSnapshot.toCsvFiles(): List<Pair<String, String>> {
    val prefix = CsvExport.filePrefix(competitionDate, title, slug)
    val files = ArrayList<Pair<String, String>>(3)
    val finishRows = chips.mapNotNull { chip ->
        val bib = chip.logicalId ?: return@mapNotNull null
        val punches = punchesFromJson(chip.punchesJson)
        if (punches.isEmpty()) return@mapNotNull null
        val person = people.find { it.logicalId == chip.logicalId }
        FinishCsvRow(
            chipRawId = chip.uid,
            bibId = bib.toString(),
            name = chip.personName.ifBlank { person?.name.orEmpty() }.ifBlank { "без ФИО" },
            course = chip.courseName.ifBlank { person?.currentCourse.orEmpty() },
            punches = punches,
        )
    }
    if (finishRows.isNotEmpty()) {
        files += "${prefix}-finish.csv" to CsvExport.finishDump(competitionDate, startCp, finishRows)
    }
    val protocolRows = ArrayList<ProtocolCsvRow>()
    for (person in people) {
        val names = parseJsonNames(person.coursesJson)
        val coursesForPerson = names.ifEmpty {
            listOfNotNull(person.currentCourse.trim().takeIf { it.isNotEmpty() })
        }
        if (coursesForPerson.isEmpty()) {
            protocolRows += ProtocolCsvRow(person.logicalId, person.name.ifBlank { "без ФИО" }, "")
        } else {
            for (course in coursesForPerson) {
                protocolRows += ProtocolCsvRow(person.logicalId, person.name.ifBlank { "без ФИО" }, course)
            }
        }
    }
    if (protocolRows.isNotEmpty()) {
        files += "${prefix}-protocol.csv" to CsvExport.protocol(protocolRows)
    }
    val courseRows = courses.mapNotNull { course ->
        val controls = parseJsonInts(course.controlsJson)
        if (controls.isEmpty()) null else CourseCsvRow(course.name, controls)
    }
    if (courseRows.isNotEmpty()) {
        files += "${prefix}-courses.csv" to CsvExport.courses(courseRows)
    }
    if (files.isEmpty()) error("Нечего экспортировать: нет протокола, дистанций и считанных чипов")
    return files
}

private fun punchesFromJson(json: String): List<FinishCsvPunch> {
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val cp = obj.optInt("cp")
            val time = obj.optString("time").trim()
            if (cp <= 0 || time.isEmpty()) null else FinishCsvPunch(cp, time)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseJsonNames(json: String): List<String> {
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { name -> name.isNotEmpty() } }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseJsonInts(json: String): List<Int> {
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            when (val value = array.opt(i)) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun StartArchiveEntity.toSaved(): SavedStart = SavedStart(
    id = id,
    label = StartArchiveStore.label(title, slug, competitionDate),
    date = competitionDate,
    slug = slug,
    chipCount = chipCount,
    personCount = personCount,
    current = isCurrent,
    updatedAt = updatedAt,
)

private fun jsonObjects(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

private fun JSONObject.nullInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key).takeIf { it != 0 || opt(key) == 0 }

private fun JSONObject.nullString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private inline fun String.ifNotBlank(block: (String) -> Unit) {
    if (isNotBlank()) block(this)
}


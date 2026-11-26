package ru.rogein.chip.startlist

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import ru.rogein.chip.parse.AwardGroupCsvRow
import ru.rogein.chip.parse.CourseCsvRow
import ru.rogein.chip.parse.CourseOrderMismatchException
import ru.rogein.chip.parse.CsvImport
import ru.rogein.chip.parse.CourseOrderSnapshot
import ru.rogein.chip.parse.coursesNeedReplace
import ru.rogein.chip.parse.parseClusterGroups
import ru.rogein.chip.parse.looksLikeIofCourseXml
import ru.rogein.chip.parse.mergeCourseGeometry
import ru.rogein.chip.parse.parseIofCourseData
import ru.rogein.chip.parse.EventPackCpTypeRuleFile
import ru.rogein.chip.parse.EventPackExclusionFile
import ru.rogein.chip.parse.EventPackFile
import ru.rogein.chip.parse.FormatCsvRow
import ru.rogein.chip.parse.LegendCsvRow
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.normalizeChipUid
import ru.rogein.chip.parse.SPORT_ORIENT
import ru.rogein.chip.parse.SPORT_ROGAINE
import ru.rogein.chip.parse.isRogaine
import ru.rogein.chip.parse.normalizeSportKind
import ru.rogein.chip.parse.StartProtocolSnapshotPerson
import ru.rogein.chip.parse.buildStartProtocolSnapshotJson
import ru.rogein.chip.parse.parseEventPackJson
import ru.rogein.chip.field.FieldJournal
import ru.rogein.chip.queue.ChipQueueDao
import ru.rogein.chip.queue.StartCourseEntity
import ru.rogein.chip.queue.StartListDao
import ru.rogein.chip.queue.StartMetaEntity
import ru.rogein.chip.queue.StartPersonEntity
import java.io.File

class StartListStore(
    private val context: Context,
    private val dao: StartListDao,
    private val fieldJournal: FieldJournal? = null,
    private val eventSlug: suspend () -> String = { "" },
    private val chipQueue: ChipQueueDao? = null,
    private val applyMapGeometry: suspend (Int?, Map<Int, Pair<Double, Double>>) -> Unit = { _, _ -> },
    private val applyExclusions: suspend (List<EventPackExclusionFile>) -> Unit = {},
) {
    val people: Flow<List<StartPerson>> = dao.observePeople().map { rows -> rows.map { it.toPerson() } }
    val courses: Flow<List<StartCourse>> = dao.observeCourses().map { rows ->
        rows.map { it.toStartCourse() }
    }

    suspend fun person(id: Int): StartPerson? = dao.person(id)?.toPerson()

    suspend fun peopleNow(): List<StartPerson> = dao.listPeople().map { it.toPerson() }

    suspend fun coursesNow(): List<StartCourse> = dao.listCourses().map { it.toStartCourse() }

    suspend fun reloadFromUsb(quiet: Boolean = false, sport: String = SPORT_ORIENT): String? {
        val hasPeople = dao.listPeople().isNotEmpty()
        val hasCourses = dao.listCourses().isNotEmpty()
        if (quiet && (hasPeople || hasCourses)) return null
        if (isRogaine(sport)) {
            val loaded = openUsbEventPack()
            val pack = loaded.pack ?: return if (quiet) null else loaded.error
            usbSportMismatch(pack.sport, sport)?.let { return if (quiet) null else it }
            applyEventPack(pack, replace = true)
            return eventPackLoadedMessage(pack)
        }
        val loaded = openUsbPack()
        val pack = loaded.pack ?: return if (quiet) null else loaded.error
        usbSportMismatch(pack.sport, sport)?.let { return if (quiet) null else it }
        merge(pack)
        return "Протокол обновлён: ${pack.people.size} участников, ${pack.courses.size} дистанций."
    }

    suspend fun loadProtocolFromUsb(replace: Boolean, sport: String = SPORT_ORIENT): String {
        if (isRogaine(sport)) {
            return loadEventPackFromUsb(replace, sport)
        }
        val loaded = openUsbPack()
        val pack = loaded.pack ?: return loaded.error.orEmpty()
        usbSportMismatch(pack.sport, sport)?.let { return it }
        applyPeople(pack, replace)
        applyStartPackGeometry(pack)
        applyStartPackExclusions(pack)
        return "Протокол с компьютера: ${pack.people.size} участников."
    }

    suspend fun loadEventPackFromUsb(replace: Boolean, sport: String): String {
        val loaded = openUsbEventPack()
        val pack = loaded.pack ?: return loaded.error.orEmpty()
        usbSportMismatch(pack.sport, sport)?.let { return it }
        applyEventPack(pack, replace)
        return eventPackLoadedMessage(pack)
    }

    fun readUsbEventPack(): Pair<EventPackFile?, String?> {
        val loaded = openUsbEventPack()
        return loaded.pack to loaded.error
    }

    fun readEventPackJson(text: String): EventPackFile = parseEventPackJson(text)

    suspend fun applyEventPack(pack: EventPackFile, replace: Boolean) {
        val formatNames = linkedSetOf<String>()
        pack.formats.forEach { formatNames += it.name }
        pack.people.map { it.format.trim() }.filter { it.isNotEmpty() }.forEach { formatNames += it }
        if (replace) {
            val keep = formatNames.map { it.lowercase() }.toSet()
            for (old in dao.listCourses()) {
                if (old.name.lowercase() !in keep) {
                    dao.deleteCourse(old.name)
                    rememberCourseDelete(old.name)
                }
            }
            dao.clearMeta()
        }
        for (name in formatNames) {
            val existing = dao.listCourses().find { it.name.equals(name, true) }
            val format = pack.formats.find { it.name.equals(name, true) }
            val fromFormat = format?.controls.orEmpty().filter { it > 0 }
            val fromLegends = pack.innerLegendCps(format?.cpTypeRules.orEmpty())
            val nextControls = when {
                fromFormat.isNotEmpty() -> fromFormat
                fromLegends.isNotEmpty() -> fromLegends
                existing != null && !replace -> parseInts(existing.controlsJson)
                else -> emptyList()
            }
            val nextRandom = when {
                fromFormat.isNotEmpty() -> parseClusterGroups(format?.randomOrder, nextControls.size)
                existing != null && !replace -> parseGroups(existing.randomOrderJson)
                else -> emptyList()
            }
            val entity = StartCourseEntity(
                name = name,
                controlsJson = JSONArray(nextControls).toString(),
                lengthMeters = existing?.lengthMeters,
                climbMeters = existing?.climbMeters,
                legsJson = existing?.legsJson ?: "[]",
                randomOrderJson = JSONArray(nextRandom).toString(),
            )
            dao.upsertCourse(entity)
            rememberCourseEntity(entity)
        }
        val peoplePack = StartPack(
            revision = pack.revision,
            sport = pack.sport,
            courses = emptyList(),
            people = pack.people.map { person ->
                StartPerson(
                    id = person.id,
                    name = person.name,
                    courses = listOfNotNull(person.format.takeIf { it.isNotBlank() }),
                    currentCourse = person.format,
                    gender = person.gender,
                    birthDate = person.birthDate,
                    teamId = person.teamId,
                )
            },
        )
        applyPeople(peoplePack, replace)
    }

    private suspend fun applyStartPackGeometry(pack: StartPack) {
        if (pack.mapScaleDenominator == null && pack.controlPositionsMm.isEmpty()) return
        applyMapGeometry(pack.mapScaleDenominator, pack.controlPositionsMm)
    }

    private suspend fun applyStartPackExclusions(pack: StartPack) {
        val rules = pack.exclusions ?: return
        applyExclusions(rules)
    }

    suspend fun loadCoursesFromUsb(replace: Boolean): String {
        val loaded = openUsbPack()
        val pack = loaded.pack ?: return loaded.error.orEmpty()
        applyCourses(pack, replace)
        applyStartPackGeometry(pack)
        applyStartPackExclusions(pack)
        val names = pack.courses.map { it.name }.filter { it.isNotBlank() }
        val exclNote = pack.exclusions?.let { " исключений перегонов: ${it.size}." } ?: ""
        return if (names.isEmpty()) {
            "Дистанции с компьютера: 0.$exclNote"
        } else {
            "Дистанции с компьютера: ${names.joinToString(", ")} (${names.size}).$exclNote"
        }
    }

    suspend fun merge(pack: StartPack) {
        applyCourses(pack, replace = true)
        applyPeople(pack, replace = true)
        applyStartPackGeometry(pack)
        applyStartPackExclusions(pack)
        val incomingIds = pack.people.map { it.id }.toSet()
        for (local in dao.listPeople()) {
            if (local.origin == ORIGIN_DESKTOP && local.logicalId !in incomingIds) {
                dao.deletePerson(local.logicalId)
            }
        }
    }

    fun sportMismatch(packSport: String, localSport: String): String? = usbSportMismatch(packSport, localSport)

    private fun usbSportMismatch(packSport: String, localSport: String): String? {
        val pack = normalizeSportKind(packSport.ifBlank { SPORT_ORIENT })
        val local = normalizeSportKind(localSport)
        if (pack == local) return null
        return if (isRogaine(pack)) {
            "На компьютере пакет рогейна. Начните старт «Рогейн»."
        } else {
            "На компьютере пакет ориентирования. Начните старт «Ориентирование»."
        }
    }

    private fun openUsbPack(): UsbPackLoad {
        val text = try {
            readPackText(FILE_NAME)
        } catch (e: Exception) {
            return UsbPackLoad(error = shortPackReadError(e))
        } ?: return UsbPackLoad(
            error = "Файл протокола не найден. Отправьте его с компьютера по USB.",
        )
        return try {
            UsbPackLoad(pack = parseStartPack(text))
        } catch (e: Exception) {
            UsbPackLoad(error = shortPackReadError(e))
        }
    }

    private suspend fun applyPeople(pack: StartPack, replace: Boolean) {
        val packIds = pack.people.map { it.id }.toSet()
        if (replace) {
            for (person in dao.listPeople()) {
                if (person.origin != ORIGIN_PHONE && person.logicalId !in packIds) {
                    dao.deletePerson(person.logicalId)
                    rememberPersonDelete(person.logicalId)
                }
            }
            dao.clearMeta()
        }
        val meta = dao.meta()
        val appliedAt = meta?.appliedAt ?: 0L
        val existing = dao.listPeople()
        for (person in pack.people) {
            val local = existing.find { it.logicalId == person.id }
            val union = linkedSetOf<String>()
            if (local != null) union.addAll(parseNames(local.coursesJson))
            union.addAll(person.courses)
            if (person.currentCourse.isNotBlank()) union.add(person.currentCourse)
            val keepLocalCurrent = local != null && local.localCourseTouchedAt > appliedAt
            val current = when {
                keepLocalCurrent && local!!.currentCourse.isNotBlank() -> local.currentCourse
                person.currentCourse.isNotBlank() -> person.currentCourse
                else -> union.firstOrNull().orEmpty()
            }
            val savedName = person.name.ifBlank { local?.name.orEmpty() }
            val savedGender = person.gender.ifBlank { local?.gender.orEmpty() }
            val savedBirth = person.birthDate.ifBlank { local?.birthDate.orEmpty() }
            val courses = union.toList()
            dao.upsertPerson(
                StartPersonEntity(
                    logicalId = person.id,
                    name = savedName,
                    currentCourse = current,
                    coursesJson = JSONArray(courses).toString(),
                    origin = ORIGIN_DESKTOP,
                    localCourseTouchedAt = if (keepLocalCurrent) local!!.localCourseTouchedAt else 0L,
                    issuedAt = local?.issuedAt ?: 0L,
                    issuedCourse = local?.issuedCourse.orEmpty(),
                    gender = savedGender,
                    birthDate = savedBirth,
                    teamId = if (person.teamId > 0) person.teamId else local?.teamId ?: 0,
                    issuedUid = local?.issuedUid.orEmpty(),
                ),
            )
            rememberPersonUpsert(person.id, savedName, savedGender, savedBirth, current, courses)
        }
        dao.upsertMeta(
            StartMetaEntity(
                revision = pack.revision,
                appliedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun applyCourses(pack: StartPack, replace: Boolean) {
        if (replace) {
            val keep = pack.courses.map { it.name.lowercase() }.toSet()
            for (old in dao.listCourses()) {
                if (old.name.lowercase() !in keep) {
                    dao.deleteCourse(old.name)
                    rememberCourseDelete(old.name)
                }
            }
        }
        for (course in pack.courses) {
            val existing = if (replace) {
                null
            } else {
                dao.listCourses().find { it.name.equals(course.name, true) }
            }
            val entity = courseEntity(course.toCsvRow(), existing)
            dao.upsertCourse(entity)
            rememberCourseEntity(entity)
        }
    }

    suspend fun importProtocolCsv(text: String, reset: Boolean, sport: String = SPORT_ORIENT): String {
        if (isRogaine(sport)) {
            if (CsvImport.detectProtocolKind(text) == SPORT_ORIENT) {
                error("Это протокол ориентирования. Начните старт «Рогейн» или загрузите CSV с колонками формат, пол, дата рождения.")
            }
            return importRogaineProtocolCsv(text, reset)
        }
        if (CsvImport.detectProtocolKind(text) == SPORT_ROGAINE) {
            error("Это протокол рогейна. Начните старт «Рогейн», ориентирование его не принимает.")
        }
        val rows = CsvImport.protocol(text)
        val previousIds = if (reset) dao.listPeople().map { it.logicalId } else emptyList()
        if (reset) {
            dao.clearPeople()
            dao.clearMeta()
        }
        var imported = 0
        var skipped = 0
        var missingCourse = 0
        val importedIds = linkedSetOf<Int>()
        for (row in rows) {
            if (row.id !in 1..MAX_LOGICAL_ID) {
                skipped += 1
                continue
            }
            if (row.course.isBlank()) missingCourse += 1
            val existing = if (reset) null else dao.person(row.id)
            val union = linkedSetOf<String>()
            if (existing != null) union.addAll(parseNames(existing.coursesJson))
            if (row.course.isNotBlank()) union.add(row.course)
            val courses = union.toList()
            val current = if (reset || existing == null || existing.currentCourse.isBlank()) {
                row.course
            } else {
                existing.currentCourse
            }
            val gender = existing?.gender.orEmpty()
            val birth = existing?.birthDate.orEmpty()
            dao.upsertPerson(
                StartPersonEntity(
                    logicalId = row.id,
                    name = row.name,
                    currentCourse = current,
                    coursesJson = JSONArray(courses).toString(),
                    origin = existing?.origin ?: ORIGIN_PHONE,
                    localCourseTouchedAt = existing?.localCourseTouchedAt ?: 0L,
                    issuedAt = existing?.issuedAt ?: 0L,
                    issuedCourse = existing?.issuedCourse.orEmpty(),
                    gender = gender,
                    birthDate = birth,
                    teamId = existing?.teamId ?: 0,
                    issuedUid = existing?.issuedUid.orEmpty(),
                ),
            )
            rememberPersonUpsert(row.id, row.name, gender, birth, current, courses)
            importedIds += row.id
            imported += 1
        }
        if (reset) {
            for (id in previousIds) {
                if (id !in importedIds) rememberPersonDelete(id)
            }
        }
        val base = if (skipped == 0) {
            "Протокол: $imported участников."
        } else {
            "Протокол: $imported участников, пропущено $skipped (номер вне 1…$MAX_LOGICAL_ID)."
        }
        return if (missingCourse == 0) {
            base
        } else {
            "$base Внимание: у $missingCourse участников не указана дистанция."
        }
    }

    private suspend fun importRogaineProtocolCsv(text: String, reset: Boolean): String {
        val rows = CsvImport.rogaineProtocol(text)
        val previousIds = if (reset) dao.listPeople().map { it.logicalId } else emptyList()
        if (reset) {
            dao.clearPeople()
            dao.clearMeta()
        }
        var imported = 0
        var skipped = 0
        var missingFormat = 0
        val importedIds = linkedSetOf<Int>()
        for (row in rows) {
            if (row.id !in 1..MAX_LOGICAL_ID) {
                skipped += 1
                continue
            }
            if (row.format.isBlank()) missingFormat += 1
            val existing = if (reset) null else dao.person(row.id)
            val union = linkedSetOf<String>()
            if (existing != null) union.addAll(parseNames(existing.coursesJson))
            if (row.format.isNotBlank()) union.add(row.format)
            val courses = union.toList()
            val current = if (reset || existing == null || existing.currentCourse.isBlank()) {
                row.format
            } else {
                existing.currentCourse
            }
            val gender = row.gender.ifBlank { existing?.gender.orEmpty() }
            val birth = row.birth.ifBlank { existing?.birthDate.orEmpty() }
            dao.upsertPerson(
                StartPersonEntity(
                    logicalId = row.id,
                    name = row.name,
                    currentCourse = current,
                    coursesJson = JSONArray(courses).toString(),
                    origin = existing?.origin ?: ORIGIN_PHONE,
                    localCourseTouchedAt = existing?.localCourseTouchedAt ?: 0L,
                    issuedAt = existing?.issuedAt ?: 0L,
                    issuedCourse = existing?.issuedCourse.orEmpty(),
                    gender = gender,
                    birthDate = birth,
                    teamId = existing?.teamId ?: 0,
                    issuedUid = existing?.issuedUid.orEmpty(),
                ),
            )
            rememberPersonUpsert(row.id, row.name, gender, birth, current, courses)
            importedIds += row.id
            imported += 1
        }
        if (reset) {
            for (id in previousIds) {
                if (id !in importedIds) rememberPersonDelete(id)
            }
        }
        val base = if (skipped == 0) {
            "Протокол: $imported участников."
        } else {
            "Протокол: $imported участников, пропущено $skipped (номер вне 1…$MAX_LOGICAL_ID)."
        }
        return if (missingFormat == 0) {
            base
        } else {
            "$base Внимание: у $missingFormat участников не указан формат."
        }
    }

    data class CoursesImportResult(
        val message: String,
        val mapScaleDenominator: Int? = null,
        val controlPositionsMm: Map<Int, Pair<Double, Double>> = emptyMap(),
    )

    suspend fun importCoursesCsv(text: String, reset: Boolean): CoursesImportResult {
        val iof = if (looksLikeIofCourseXml(text)) parseIofCourseData(text) else null
        val rows = iof?.courses ?: CsvImport.courses(text)
        val mapExtras = CoursesImportResult(
            message = "",
            mapScaleDenominator = iof?.mapScaleDenominator,
            controlPositionsMm = iof?.controlPositionsMm.orEmpty(),
        )
        val existing = dao.listCourses()
        if (iof != null && !reset && existing.isNotEmpty()) {
            val pairs = existing.map {
                CourseOrderSnapshot(
                    name = it.name,
                    controls = parseInts(it.controlsJson),
                    randomOrder = parseGroups(it.randomOrderJson),
                )
            }
            if (coursesNeedReplace(pairs, rows)) {
                throw CourseOrderMismatchException()
            }
            for (row in rows) {
                val old = existing.find { it.name.equals(row.name, true) }
                if (old == null) {
                    val entity = courseEntity(row, null)
                    dao.upsertCourse(entity)
                    rememberCourseEntity(entity)
                    continue
                }
                val merged = mergeCourseGeometry(old.toCsvRow(), row)
                val entity = courseEntity(merged, old)
                dao.upsertCourse(entity)
                rememberCourseEntity(entity)
            }
            return mapExtras.copy(message = "Дистанции: длины и перегоны дописаны для ${rows.size}.")
        }
        if (reset) {
            val keep = rows.map { it.name.lowercase() }.toSet()
            for (old in existing) {
                if (old.name.lowercase() !in keep) {
                    dao.deleteCourse(old.name)
                    rememberCourseDelete(old.name)
                }
            }
        }
        for (row in rows) {
            val old = if (reset) null else dao.listCourses().find { it.name.equals(row.name, true) }
            val entity = courseEntity(row, old)
            dao.upsertCourse(entity)
            rememberCourseEntity(entity)
        }
        return mapExtras.copy(message = "Дистанции: ${rows.size}, КП загружены.")
    }

    suspend fun importFormatsCsv(text: String, reset: Boolean): Pair<String, List<FormatCsvRow>> {
        val rows = CsvImport.formats(text)
        if (reset) {
            val keep = rows.map { it.name.lowercase() }.toSet()
            for (old in dao.listCourses()) {
                if (old.name.lowercase() !in keep) {
                    dao.deleteCourse(old.name)
                    rememberCourseDelete(old.name)
                }
            }
        }
        for (row in rows) {
            val existing = dao.listCourses().find { it.name.equals(row.name, true) }
            val entity = StartCourseEntity(
                name = row.name,
                controlsJson = existing?.controlsJson ?: JSONArray().toString(),
                lengthMeters = existing?.lengthMeters,
                climbMeters = existing?.climbMeters,
                legsJson = existing?.legsJson ?: "[]",
            )
            dao.upsertCourse(entity)
            rememberCourseEntity(entity)
        }
        return "Форматы: ${rows.size}." to rows
    }

    fun parseAwardGroupsCsv(text: String): List<AwardGroupCsvRow> = CsvImport.awardGroups(text)

    fun parseLegendsCsv(text: String): List<LegendCsvRow> = CsvImport.legends(text)

    suspend fun fillEmptyFormatControls(cps: List<Int>) {
        val next = cps.filter { it > 0 }.distinct()
        if (next.isEmpty()) return
        for (course in dao.listCourses()) {
            if (parseInts(course.controlsJson).isNotEmpty()) continue
            val entity = course.copy(controlsJson = JSONArray(next).toString())
            dao.upsertCourse(entity)
            rememberCourseEntity(entity)
        }
    }

    suspend fun deletePerson(id: Int) {
        dao.deletePerson(id)
        rememberPersonDelete(id)
    }

    suspend fun saveCourse(
        name: String,
        controls: List<Int>,
        previousName: String? = null,
        randomOrder: List<Int> = emptyList(),
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) error("Укажите название дистанции")
        if (controls.any { it <= 0 }) error("Номер КП должен быть больше 0")
        val previous = previousName?.trim().orEmpty()
        val renaming = previous.isNotEmpty() && !previous.equals(trimmed, ignoreCase = true)
        if (renaming) {
            val clash = dao.listCourses().any { it.name.equals(trimmed, ignoreCase = true) }
            if (clash) error("Дистанция «$trimmed» уже есть")
        }
        val lookup = if (previous.isNotEmpty()) previous else trimmed
        val existing = dao.listCourses().find { it.name.equals(lookup, true) }
        val groups = parseClusterGroups(randomOrder, controls.size)
        // Длины/перегоны зависят от последовательности КП, не от флагов грозди.
        val sameControls = existing != null && parseInts(existing.controlsJson) == controls
        dao.upsertCourse(
            StartCourseEntity(
                name = trimmed,
                controlsJson = JSONArray(controls).toString(),
                lengthMeters = if (sameControls) existing?.lengthMeters else null,
                climbMeters = if (sameControls) existing?.climbMeters else null,
                legsJson = if (sameControls) existing?.legsJson?.ifBlank { "[]" } ?: "[]" else "[]",
                randomOrderJson = JSONArray(groups).toString(),
            ),
        )
        if (renaming) {
            for (person in dao.listPeople()) {
                val names = parseNames(person.coursesJson).map { if (it.equals(previous, true)) trimmed else it }
                val current = if (person.currentCourse.equals(previous, true)) trimmed else person.currentCourse
                val issued = if (person.issuedCourse.equals(previous, true)) trimmed else person.issuedCourse
                dao.upsertPerson(
                    person.copy(
                        currentCourse = current,
                        issuedCourse = issued,
                        coursesJson = JSONArray(names).toString(),
                    ),
                )
            }
            dao.deleteCourse(previous)
        }
        val saved = dao.listCourses().find { it.name.equals(trimmed, true) }
        if (saved != null) {
            rememberCourseUpsert(
                name = saved.name,
                previousName = if (renaming) previous else "",
                controls = parseInts(saved.controlsJson),
                lengthMeters = saved.lengthMeters,
                climbMeters = saved.climbMeters,
                legsJson = saved.legsJson.ifBlank { "[]" },
                randomOrder = parseGroups(saved.randomOrderJson),
            )
        }
    }

    suspend fun copyCourse(sourceName: String, newName: String): String {
        val source = dao.listCourses().find { it.name.equals(sourceName.trim(), true) }
            ?: error("Дистанция «$sourceName» не найдена")
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) error("Укажите название дистанции")
        val clash = dao.listCourses().any { it.name.equals(trimmed, ignoreCase = true) }
        if (clash) error("Дистанция «$trimmed» уже есть")
        dao.upsertCourse(source.copy(name = trimmed))
        rememberCourseUpsert(
            name = trimmed,
            previousName = "",
            controls = parseInts(source.controlsJson),
            lengthMeters = source.lengthMeters,
            climbMeters = source.climbMeters,
            legsJson = source.legsJson.ifBlank { "[]" },
            randomOrder = parseGroups(source.randomOrderJson),
        )
        return trimmed
    }

    suspend fun deleteCourse(name: String) {
        val trimmed = name.trim()
        dao.deleteCourse(trimmed)
        rememberCourseDelete(trimmed)
    }

    suspend fun applyCourseUpsert(
        name: String,
        previousName: String,
        controls: List<Int>,
        lengthMeters: Int?,
        climbMeters: Int?,
        legsJson: String,
        randomOrder: List<Int> = emptyList(),
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val previous = previousName.trim()
        val renaming = previous.isNotEmpty() && !previous.equals(trimmed, ignoreCase = true)
        dao.upsertCourse(
            StartCourseEntity(
                name = trimmed,
                controlsJson = JSONArray(controls).toString(),
                lengthMeters = lengthMeters,
                climbMeters = climbMeters,
                legsJson = legsJson.ifBlank { "[]" },
                randomOrderJson = JSONArray(parseClusterGroups(randomOrder, controls.size)).toString(),
            ),
        )
        if (renaming) {
            for (person in dao.listPeople()) {
                val names = parseNames(person.coursesJson).map { if (it.equals(previous, true)) trimmed else it }
                val current = if (person.currentCourse.equals(previous, true)) trimmed else person.currentCourse
                val issued = if (person.issuedCourse.equals(previous, true)) trimmed else person.issuedCourse
                dao.upsertPerson(
                    person.copy(
                        currentCourse = current,
                        issuedCourse = issued,
                        coursesJson = JSONArray(names).toString(),
                    ),
                )
            }
            dao.deleteCourse(previous)
        }
    }

    suspend fun applyCourseDelete(name: String) {
        dao.deleteCourse(name.trim())
    }

    private suspend fun rememberCourseEntity(entity: StartCourseEntity) {
        rememberCourseUpsert(
            name = entity.name,
            previousName = "",
            controls = parseInts(entity.controlsJson),
            lengthMeters = entity.lengthMeters,
            climbMeters = entity.climbMeters,
            legsJson = entity.legsJson,
            randomOrder = parseGroups(entity.randomOrderJson),
        )
    }

    private suspend fun rememberCourseUpsert(
        name: String,
        previousName: String,
        controls: List<Int>,
        lengthMeters: Int?,
        climbMeters: Int?,
        legsJson: String,
        randomOrder: List<Int> = emptyList(),
    ) {
        val journal = fieldJournal ?: return
        runCatching {
            journal.recordCourseUpsert(
                name = name,
                previousName = previousName,
                controls = controls,
                lengthMeters = lengthMeters,
                climbMeters = climbMeters,
                legsJson = legsJson,
                randomOrder = randomOrder,
                eventSlug = eventSlug(),
            )
        }
    }

    private suspend fun rememberCourseDelete(name: String) {
        val journal = fieldJournal ?: return
        runCatching { journal.recordCourseDelete(name, eventSlug()) }
    }

    suspend fun upsertLocal(
        id: Int,
        name: String,
        course: String,
        extraCourses: List<String> = emptyList(),
        gender: String? = null,
        birthDate: String? = null,
    ) {
        val existing = dao.person(id)
        val union = linkedSetOf<String>()
        if (existing != null) union.addAll(parseNames(existing.coursesJson))
        union.addAll(extraCourses)
        if (course.isNotBlank()) union.add(course)
        val courses = union.toList()
        val savedName = name.trim()
        val savedCourse = course.trim()
        val savedGender = gender ?: existing?.gender.orEmpty()
        val savedBirth = birthDate ?: existing?.birthDate.orEmpty()
        dao.upsertPerson(
            StartPersonEntity(
                logicalId = id,
                name = savedName,
                currentCourse = savedCourse,
                coursesJson = JSONArray(courses).toString(),
                origin = existing?.origin ?: ORIGIN_PHONE,
                localCourseTouchedAt = System.currentTimeMillis(),
                issuedAt = existing?.issuedAt ?: 0L,
                issuedCourse = existing?.issuedCourse.orEmpty(),
                gender = savedGender,
                birthDate = savedBirth,
                teamId = existing?.teamId ?: 0,
                issuedUid = existing?.issuedUid.orEmpty(),
            ),
        )
        rememberPersonUpsert(id, savedName, savedGender, savedBirth, savedCourse, courses)
    }

    suspend fun joinTeam(id: Int, otherId: Int): Int {
        if (id == otherId) error("Нужен другой номер для команды")
        if (id !in 1..MAX_LOGICAL_ID || otherId !in 1..MAX_LOGICAL_ID) {
            error("Номер чипа вне 1…$MAX_LOGICAL_ID")
        }
        val first = dao.person(id) ?: error("№ $id нет в протоколе")
        val second = dao.person(otherId) ?: error("№ $otherId нет в протоколе")
        var droppedTeamId = 0
        val teamId = when {
            first.teamId > 0 && second.teamId > 0 -> minOf(first.teamId, second.teamId).also { keep ->
                val drop = maxOf(first.teamId, second.teamId)
                if (keep != drop) {
                    droppedTeamId = drop
                    for (person in dao.listPeople()) {
                        if (person.teamId == drop) {
                            dao.upsertPerson(person.copy(teamId = keep))
                        }
                    }
                }
            }
            first.teamId > 0 -> first.teamId
            second.teamId > 0 -> second.teamId
            else -> (dao.listPeople().maxOfOrNull { it.teamId } ?: 0) + 1
        }
        if (first.teamId != teamId) dao.upsertPerson(first.copy(teamId = teamId))
        val secondNow = dao.person(otherId) ?: second
        if (secondNow.teamId != teamId) dao.upsertPerson(secondNow.copy(teamId = teamId))
        rememberTeamJoin(id, otherId, teamId, droppedTeamId)
        return teamId
    }

    suspend fun assignTeam(id: Int, teamId: Int): Int {
        if (teamId <= 0) error("Номер команды должен быть больше 0")
        val person = dao.person(id) ?: error("№ $id нет в протоколе")
        if (person.teamId != teamId) {
            dao.upsertPerson(person.copy(teamId = teamId))
        }
        rememberTeamAssign(id, teamId)
        return teamId
    }

    suspend fun leaveTeam(id: Int): Int {
        val person = dao.person(id) ?: error("№ $id нет в протоколе")
        if (person.teamId <= 0) return 0
        val teamId = person.teamId
        dao.upsertPerson(person.copy(teamId = 0))
        rememberTeamLeave(id, teamId)
        return teamId
    }

    /** Применить чужой join без повторной записи в журнал. */
    suspend fun applyTeamJoin(logicalId: Int, otherLogicalId: Int, teamId: Int, droppedTeamId: Int) {
        if (teamId <= 0) return
        if (droppedTeamId > 0 && droppedTeamId != teamId) {
            for (person in dao.listPeople()) {
                if (person.teamId == droppedTeamId) {
                    dao.upsertPerson(person.copy(teamId = teamId))
                }
            }
        }
        dao.person(logicalId)?.let { if (it.teamId != teamId) dao.upsertPerson(it.copy(teamId = teamId)) }
        dao.person(otherLogicalId)?.let { if (it.teamId != teamId) dao.upsertPerson(it.copy(teamId = teamId)) }
    }

    suspend fun applyTeamAssign(logicalId: Int, teamId: Int) {
        if (teamId <= 0) return
        val person = dao.person(logicalId) ?: return
        if (person.teamId != teamId) dao.upsertPerson(person.copy(teamId = teamId))
    }

    suspend fun applyTeamLeave(logicalId: Int) {
        val person = dao.person(logicalId) ?: return
        if (person.teamId > 0) dao.upsertPerson(person.copy(teamId = 0))
    }

    private suspend fun rememberTeamJoin(
        logicalId: Int,
        otherLogicalId: Int,
        teamId: Int,
        droppedTeamId: Int,
    ) {
        val journal = fieldJournal ?: return
        runCatching {
            journal.recordPersonTeamJoin(
                logicalId = logicalId,
                otherLogicalId = otherLogicalId,
                teamId = teamId,
                droppedTeamId = droppedTeamId,
                eventSlug = eventSlug(),
            )
        }
    }

    private suspend fun rememberTeamAssign(logicalId: Int, teamId: Int) {
        val journal = fieldJournal ?: return
        runCatching {
            journal.recordPersonTeamAssign(logicalId, teamId, eventSlug())
        }
    }

    private suspend fun rememberTeamLeave(logicalId: Int, teamId: Int) {
        val journal = fieldJournal ?: return
        runCatching {
            journal.recordPersonTeamLeave(logicalId, teamId, eventSlug())
        }
    }

    suspend fun protocolSnapshotJson(): String {
        val people = dao.listPeople().map { person ->
            StartProtocolSnapshotPerson(
                id = person.logicalId,
                name = person.name,
                format = person.currentCourse,
                gender = person.gender,
                birthDate = person.birthDate,
                teamId = person.teamId,
                origin = person.origin,
            )
        }
        return buildStartProtocolSnapshotJson(people, slug = eventSlug())
    }

    suspend fun markIssued(id: Int, course: String = "", uid: String = "") {
        val normalized = normalizeChipUid(uid)
        if (normalized.isNotEmpty()) {
            for (person in dao.listPeople()) {
                if (person.logicalId != id && normalizeChipUid(person.issuedUid) == normalized) {
                    dao.upsertPerson(person.copy(issuedUid = ""))
                }
            }
        }
        val existing = dao.person(id) ?: return
        dao.upsertPerson(
            existing.copy(
                issuedAt = System.currentTimeMillis(),
                issuedCourse = course.trim().ifBlank { existing.issuedCourse.ifBlank { existing.currentCourse } },
                issuedUid = if (normalized.isNotEmpty()) uid.trim() else existing.issuedUid,
            ),
        )
    }

    suspend fun rebindIssuedUid(fromId: Int?, toId: Int, uid: String) {
        val normalized = normalizeChipUid(uid)
        if (normalized.isEmpty()) return
        if (fromId != null && fromId != toId) {
            val from = dao.person(fromId)
            if (from != null && normalizeChipUid(from.issuedUid) == normalized) {
                dao.upsertPerson(from.copy(issuedUid = ""))
            }
        }
        val to = dao.person(toId) ?: return
        dao.upsertPerson(to.copy(issuedUid = uid.trim()))
    }

    suspend fun setCurrentCourse(id: Int, course: String) {
        val existing = dao.person(id) ?: return
        val union = parseNames(existing.coursesJson).toMutableList()
        if (course.isNotBlank() && union.none { it.equals(course, ignoreCase = true) }) {
            union.add(course)
        }
        val savedCourse = course.trim()
        dao.upsertPerson(
            existing.copy(
                currentCourse = savedCourse,
                coursesJson = JSONArray(union).toString(),
                localCourseTouchedAt = System.currentTimeMillis(),
            ),
        )
        rememberPersonCourse(id, savedCourse, union)
    }

    /** Применить чужой upsert без повторной записи в журнал (issued* не трогаем). */
    suspend fun applyPersonUpsert(
        logicalId: Int,
        name: String,
        gender: String,
        birthDate: String,
        course: String,
        courses: List<String>,
    ) {
        val existing = dao.person(logicalId)
        val union = linkedSetOf<String>()
        courses.forEach { if (it.isNotBlank()) union.add(it) }
        if (course.isNotBlank()) union.add(course)
        if (union.isEmpty() && existing != null) union.addAll(parseNames(existing.coursesJson))
        val savedName = name.trim()
        dao.upsertPerson(
            StartPersonEntity(
                logicalId = logicalId,
                name = savedName,
                currentCourse = course.trim().ifBlank { existing?.currentCourse.orEmpty() },
                coursesJson = JSONArray(union.toList()).toString(),
                origin = existing?.origin ?: ORIGIN_PHONE,
                localCourseTouchedAt = System.currentTimeMillis(),
                issuedAt = existing?.issuedAt ?: 0L,
                issuedCourse = existing?.issuedCourse.orEmpty(),
                gender = gender.trim().ifBlank { existing?.gender.orEmpty() },
                birthDate = birthDate.trim().ifBlank { existing?.birthDate.orEmpty() },
                teamId = existing?.teamId ?: 0,
                issuedUid = existing?.issuedUid.orEmpty(),
            ),
        )
        syncFinishPersonName(logicalId, savedName)
    }

    /** ФИО в финишной очереди — снимок; держим в синхроне со стартовым протоколом. */
    private suspend fun syncFinishPersonName(logicalId: Int, name: String) {
        if (name.isBlank()) return
        runCatching { chipQueue?.updatePersonNameByLogicalId(logicalId, name) }
    }

    suspend fun applyPersonCourse(logicalId: Int, course: String, courses: List<String>) {
        val existing = dao.person(logicalId) ?: return
        val union = linkedSetOf<String>()
        courses.forEach { if (it.isNotBlank()) union.add(it) }
        if (course.isNotBlank()) union.add(course)
        if (union.isEmpty()) union.addAll(parseNames(existing.coursesJson))
        dao.upsertPerson(
            existing.copy(
                currentCourse = course.trim().ifBlank { existing.currentCourse },
                coursesJson = JSONArray(union.toList()).toString(),
                localCourseTouchedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun applyPersonDelete(logicalId: Int) {
        dao.deletePerson(logicalId)
    }

    private suspend fun rememberPersonUpsert(
        logicalId: Int,
        name: String,
        gender: String,
        birthDate: String,
        course: String,
        courses: List<String>,
    ) {
        syncFinishPersonName(logicalId, name)
        val journal = fieldJournal ?: return
        runCatching {
            journal.recordPersonUpsert(
                logicalId = logicalId,
                name = name,
                gender = gender,
                birthDate = birthDate,
                course = course,
                courses = courses,
                eventSlug = eventSlug(),
            )
        }
    }

    private suspend fun rememberPersonCourse(
        logicalId: Int,
        course: String,
        courses: List<String>,
    ) {
        val journal = fieldJournal ?: return
        runCatching {
            journal.recordPersonCourse(logicalId, course, courses, eventSlug())
        }
    }

    private suspend fun rememberPersonDelete(logicalId: Int) {
        val journal = fieldJournal ?: return
        runCatching {
            journal.recordPersonDelete(logicalId, eventSlug())
        }
    }

    suspend fun resetAll() {
        dao.clearPeople()
        dao.clearCourses()
        dao.clearMeta()
        deletePackFiles()
    }

    fun forgetUsbPack() {
        deletePackFiles()
    }

    private fun packFileCandidates(name: String): List<File> {
        val files = linkedSetOf<File>()
        // Каталог приложения — всегда доступен самому приложению.
        files += File(context.filesDir, name)
        context.getExternalFilesDir(null)?.let { files += File(it, name) }
        files += File(
            Environment.getExternalStorageDirectory(),
            "Android/data/${context.packageName}/files/$name",
        )
        // Сюда чаще всего кладёт adb push с компьютера (Android 11+ режет Android/data).
        files += File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "rogein/$name",
        )
        return files.toList()
    }

    private data class PackRead(val text: String, val modifiedMs: Long)

    /**
     * Читает start-pack / event-pack из всех доступных мест и берёт самый новый
     * (иначе устаревший файл в Android/data перекрывает свежий Download/rogein).
     */
    private fun readPackText(name: String): String? {
        val found = ArrayList<PackRead>()
        var lastAccessError: Exception? = null
        for (file in packFileCandidates(name)) {
            if (!file.isFile || file.length() <= 0L) continue
            try {
                found += PackRead(file.readText(Charsets.UTF_8), file.lastModified().coerceAtLeast(0L))
            } catch (e: Exception) {
                lastAccessError = e
            }
        }
        found += readPackViaMediaStore(name)
        val best = found.maxByOrNull { it.modifiedMs }
        if (best != null) return best.text
        if (lastAccessError != null) throw lastAccessError
        return null
    }

    private fun readPackViaMediaStore(name: String): List<PackRead> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val out = ArrayList<PackRead>()
        val downloadFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "rogein/$name",
        )
        if (downloadFile.isFile && downloadFile.length() > 0L) {
            scanMediaFileBlocking(downloadFile.absolutePath)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val relativePaths = listOf(
            "${Environment.DIRECTORY_DOWNLOADS}/rogein/",
            "${Environment.DIRECTORY_DOWNLOADS}/rogein",
        )
        fun consume(cursor: android.database.Cursor, requireRogeinPath: Boolean) {
            while (cursor.moveToNext()) {
                val relative = cursor.getString(2).orEmpty()
                if (requireRogeinPath &&
                    !relative.contains("rogein", ignoreCase = true) &&
                    relative.isNotEmpty()
                ) {
                    continue
                }
                val id = cursor.getLong(0)
                val modifiedSec = cursor.getLong(1).coerceAtLeast(0L)
                val uri = ContentUris.withAppendedId(collection, id)
                val text = readUriText(uri) ?: continue
                out += PackRead(text, modifiedSec * 1000L)
            }
        }
        for (relative in relativePaths) {
            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, relative),
                "${MediaStore.Downloads._ID} DESC",
            )?.use { consume(it, requireRogeinPath = false) }
        }
        // adb push иногда индексируется без RELATIVE_PATH — ищем по имени.
        resolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name),
            "${MediaStore.Downloads._ID} DESC",
        )?.use { consume(it, requireRogeinPath = true) }
        return out
    }

    private fun readUriText(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val text = reader.readText()
                text.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scanMediaFileBlocking(path: String) {
        val latch = CountDownLatch(1)
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("application/json")) { _, _ ->
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // сканер недоступен — всё равно пробуем MediaStore
        }
    }

    private fun deletePackFiles() {
        for (name in listOf(FILE_NAME, EVENT_FILE_NAME)) {
            for (file in packFileCandidates(name)) {
                runCatching { file.delete() }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val relative = "${Environment.DIRECTORY_DOWNLOADS}/rogein/"
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, relative),
                )
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/rogein"),
                )
            }
        }
    }

    private fun openUsbEventPack(): UsbEventPackLoad {
        val text = try {
            readPackText(EVENT_FILE_NAME)
        } catch (e: Exception) {
            return UsbEventPackLoad(error = shortPackReadError(e))
        } ?: return UsbEventPackLoad(
            error = "Файл пакета не найден. Отправьте его с компьютера по USB или с сайта.",
        )
        return try {
            UsbEventPackLoad(pack = parseEventPackJson(text))
        } catch (e: Exception) {
            UsbEventPackLoad(error = shortPackReadError(e))
        }
    }

    companion object {
        const val FILE_NAME = "start-pack.json"
        const val EVENT_FILE_NAME = "event-pack.json"
        const val ORIGIN_DESKTOP = "desktop"
        const val ORIGIN_PHONE = "phone"
    }
}

private data class UsbPackLoad(
    val pack: StartPack? = null,
    val error: String? = null,
)

private data class UsbEventPackLoad(
    val pack: EventPackFile? = null,
    val error: String? = null,
)

internal fun eventPackLoadedMessage(pack: EventPackFile, prefix: String = "Пакет старта"): String {
    val orient = !isRogaine(pack.sport)
    if (orient) {
        return "$prefix: ${pack.people.size} участников, ${pack.formats.size} дистанций."
    }
    val cps = pack.legends.size.takeIf { it > 0 } ?: pack.innerLegendCps().size
    val groups = pack.awardGroups.size
    val cpNote = if (cps > 0) "$cps КП" else "без списка КП"
    val groupNote = if (groups > 0) "$groups групп" else "без групп награждения"
    return "$prefix: ${pack.people.size} участников, ${pack.formats.size} форматов, $cpNote, $groupNote."
}

private fun EventPackFile.innerLegendCps(
    rules: List<EventPackCpTypeRuleFile> = emptyList(),
): List<Int> {
    val start = settings.startCp
    val finish = settings.finishCp
    val allowed = rules.map { it.type.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return legends.mapNotNull { legend ->
        if (legend.cp <= 0 || legend.cp == start || legend.cp == finish) return@mapNotNull null
        if (allowed.isNotEmpty()) {
            val type = legend.type.trim().lowercase()
            if (type.isEmpty() || type !in allowed) return@mapNotNull null
        }
        legend.cp
    }.distinct()
}

private fun shortPackReadError(e: Exception): String {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("EACCES", ignoreCase = true) ||
            msg.contains("Permission denied", ignoreCase = true) ->
            "Нет доступа к файлу протокола по USB"
        msg.contains("open failed", ignoreCase = true) ->
            "Не удалось открыть файл протокола"
        else -> "Не удалось прочитать протокол"
    }
}

private fun StartPersonEntity.toPerson(): StartPerson {
    val courses = parseNames(coursesJson)
    return StartPerson(
        id = logicalId,
        name = name,
        courses = if (courses.isEmpty() && currentCourse.isNotBlank()) listOf(currentCourse) else courses,
        currentCourse = currentCourse,
        issued = issuedAt > 0L,
        issuedCourse = issuedCourse.ifBlank { if (issuedAt > 0L) currentCourse else "" },
        gender = gender,
        birthDate = birthDate,
        teamId = teamId,
        issuedUid = issuedUid,
    )
}

private fun parseNames(json: String): List<String> {
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { name -> name.isNotEmpty() } }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseInts(json: String): List<Int> {
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            if (array.isNull(index)) null else array.optInt(index)
        }.filter { it > 0 }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseOptionalInts(json: String): List<Int?> {
    if (json.isBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            if (array.isNull(index)) null else array.optInt(index).takeIf { it > 0 }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun encodeOptionalInts(values: List<Int?>): String {
    val array = JSONArray()
    for (value in values) {
        if (value == null) array.put(JSONObject.NULL) else array.put(value)
    }
    return array.toString()
}

private fun StartCourseEntity.toStartCourse() = StartCourse(
    name = name,
    controls = parseInts(controlsJson),
    lengthMeters = lengthMeters,
    climbMeters = climbMeters,
    legMeters = parseOptionalInts(legsJson),
    randomOrder = parseGroups(randomOrderJson),
)

private fun StartCourseEntity.toCsvRow() = CourseCsvRow(
    name = name,
    controls = parseInts(controlsJson),
    randomOrder = parseGroups(randomOrderJson),
    lengthMeters = lengthMeters,
    climbMeters = climbMeters,
    legMeters = parseOptionalInts(legsJson),
)

private fun StartCourse.toCsvRow() = CourseCsvRow(
    name = name,
    controls = controls,
    randomOrder = randomOrder,
    lengthMeters = lengthMeters,
    climbMeters = climbMeters,
    legMeters = legMeters,
)

private fun courseEntity(row: CourseCsvRow, existing: StartCourseEntity?): StartCourseEntity {
    val groups = parseClusterGroups(row.randomOrder, row.controls.size)
    val sameControls = existing != null && parseInts(existing.controlsJson) == row.controls
    val length = row.lengthMeters ?: if (sameControls) existing?.lengthMeters else null
    val climb = row.climbMeters ?: if (sameControls) existing?.climbMeters else null
    val legs = if (row.legMeters.any { it != null }) {
        row.legMeters
    } else if (sameControls) {
        parseOptionalInts(existing?.legsJson.orEmpty())
    } else {
        emptyList()
    }
    return StartCourseEntity(
        name = row.name,
        controlsJson = JSONArray(row.controls).toString(),
        lengthMeters = length,
        climbMeters = climb,
        legsJson = encodeOptionalInts(legs),
        randomOrderJson = JSONArray(groups).toString(),
    )
}

/** Старый JSON из boolean, 0/1 или id групп грозди. */
private fun parseGroups(json: String): List<Int> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        val raw = List<Any?>(array.length()) { array.get(it) }
        parseClusterGroups(raw, raw.size)
    }.getOrDefault(emptyList())
}

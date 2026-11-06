package ru.rogein.chip.startlist

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import ru.rogein.chip.parse.AwardGroupCsvRow
import ru.rogein.chip.parse.CsvImport
import ru.rogein.chip.parse.EventPackCpTypeRuleFile
import ru.rogein.chip.parse.EventPackFile
import ru.rogein.chip.parse.FormatCsvRow
import ru.rogein.chip.parse.LegendCsvRow
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.SPORT_ORIENT
import ru.rogein.chip.parse.SPORT_ROGAINE
import ru.rogein.chip.parse.isRogaine
import ru.rogein.chip.parse.normalizeSportKind
import ru.rogein.chip.parse.StartProtocolSnapshotPerson
import ru.rogein.chip.parse.buildStartProtocolSnapshotJson
import ru.rogein.chip.parse.parseEventPackJson
import ru.rogein.chip.queue.StartCourseEntity
import ru.rogein.chip.queue.StartListDao
import ru.rogein.chip.queue.StartMetaEntity
import ru.rogein.chip.queue.StartPersonEntity
import java.io.File

class StartListStore(
    private val context: Context,
    private val dao: StartListDao,
) {
    val people: Flow<List<StartPerson>> = dao.observePeople().map { rows -> rows.map { it.toPerson() } }
    val courses: Flow<List<StartCourse>> = dao.observeCourses().map { rows ->
        rows.map { StartCourse(it.name, parseInts(it.controlsJson)) }
    }

    suspend fun person(id: Int): StartPerson? = dao.person(id)?.toPerson()

    suspend fun peopleNow(): List<StartPerson> = dao.listPeople().map { it.toPerson() }

    suspend fun coursesNow(): List<StartCourse> = dao.listCourses().map {
        StartCourse(it.name, parseInts(it.controlsJson))
    }

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
        if (replace) {
            dao.clearCourses()
            dao.clearMeta()
        }
        val formatNames = linkedSetOf<String>()
        pack.formats.forEach { formatNames += it.name }
        pack.people.map { it.format.trim() }.filter { it.isNotEmpty() }.forEach { formatNames += it }
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
            dao.upsertCourse(
                StartCourseEntity(
                    name = name,
                    controlsJson = JSONArray(nextControls).toString(),
                ),
            )
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

    suspend fun loadCoursesFromUsb(replace: Boolean): String {
        val loaded = openUsbPack()
        val pack = loaded.pack ?: return loaded.error.orEmpty()
        applyCourses(pack, replace)
        return "Дистанции с компьютера: ${pack.courses.size}."
    }

    suspend fun merge(pack: StartPack) {
        applyCourses(pack, replace = true)
        applyPeople(pack, replace = true)
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
        val file = findPackFile()
            ?: return UsbPackLoad(error = "Файл протокола не найден. Отправьте его с компьютера по USB.")
        return try {
            UsbPackLoad(pack = parseStartPack(file.readText(Charsets.UTF_8)))
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
            dao.upsertPerson(
                StartPersonEntity(
                    logicalId = person.id,
                    name = person.name.ifBlank { local?.name.orEmpty() },
                    currentCourse = current,
                    coursesJson = JSONArray(union.toList()).toString(),
                    origin = ORIGIN_DESKTOP,
                    localCourseTouchedAt = if (keepLocalCurrent) local!!.localCourseTouchedAt else 0L,
                    issuedAt = local?.issuedAt ?: 0L,
                    issuedCourse = local?.issuedCourse.orEmpty(),
                    gender = person.gender.ifBlank { local?.gender.orEmpty() },
                    birthDate = person.birthDate.ifBlank { local?.birthDate.orEmpty() },
                    teamId = if (person.teamId > 0) person.teamId else local?.teamId ?: 0,
                ),
            )
        }
        dao.upsertMeta(
            StartMetaEntity(
                revision = pack.revision,
                appliedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun applyCourses(pack: StartPack, replace: Boolean) {
        if (replace) dao.clearCourses()
        for (course in pack.courses) {
            dao.upsertCourse(
                StartCourseEntity(
                    name = course.name,
                    controlsJson = JSONArray(course.controls).toString(),
                ),
            )
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
        if (reset) {
            dao.clearPeople()
            dao.clearMeta()
        }
        var imported = 0
        var skipped = 0
        var missingCourse = 0
        for (row in rows) {
            if (row.id !in 1..MAX_LOGICAL_ID) {
                skipped += 1
                continue
            }
            if (row.course.isBlank()) missingCourse += 1
            val existing = dao.person(row.id)
            val union = linkedSetOf<String>()
            if (existing != null) union.addAll(parseNames(existing.coursesJson))
            if (row.course.isNotBlank()) union.add(row.course)
            dao.upsertPerson(
                StartPersonEntity(
                    logicalId = row.id,
                    name = row.name,
                    currentCourse = if (reset || existing == null || existing.currentCourse.isBlank()) {
                        row.course
                    } else {
                        existing.currentCourse
                    },
                    coursesJson = JSONArray(union.toList()).toString(),
                    origin = existing?.origin ?: ORIGIN_PHONE,
                    localCourseTouchedAt = existing?.localCourseTouchedAt ?: 0L,
                    issuedAt = existing?.issuedAt ?: 0L,
                    issuedCourse = existing?.issuedCourse.orEmpty(),
                    gender = existing?.gender.orEmpty(),
                    birthDate = existing?.birthDate.orEmpty(),
                    teamId = existing?.teamId ?: 0,
                ),
            )
            imported += 1
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
        if (reset) {
            dao.clearPeople()
            dao.clearMeta()
        }
        var imported = 0
        var skipped = 0
        var missingFormat = 0
        for (row in rows) {
            if (row.id !in 1..MAX_LOGICAL_ID) {
                skipped += 1
                continue
            }
            if (row.format.isBlank()) missingFormat += 1
            val existing = dao.person(row.id)
            val union = linkedSetOf<String>()
            if (existing != null) union.addAll(parseNames(existing.coursesJson))
            if (row.format.isNotBlank()) union.add(row.format)
            dao.upsertPerson(
                StartPersonEntity(
                    logicalId = row.id,
                    name = row.name,
                    currentCourse = if (reset || existing == null || existing.currentCourse.isBlank()) {
                        row.format
                    } else {
                        existing.currentCourse
                    },
                    coursesJson = JSONArray(union.toList()).toString(),
                    origin = existing?.origin ?: ORIGIN_PHONE,
                    localCourseTouchedAt = existing?.localCourseTouchedAt ?: 0L,
                    issuedAt = existing?.issuedAt ?: 0L,
                    issuedCourse = existing?.issuedCourse.orEmpty(),
                    gender = row.gender.ifBlank { existing?.gender.orEmpty() },
                    birthDate = row.birth.ifBlank { existing?.birthDate.orEmpty() },
                    teamId = existing?.teamId ?: 0,
                ),
            )
            imported += 1
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

    suspend fun importCoursesCsv(text: String, reset: Boolean): String {
        val rows = CsvImport.courses(text)
        if (reset) dao.clearCourses()
        for (row in rows) {
            dao.upsertCourse(
                StartCourseEntity(name = row.name, controlsJson = JSONArray(row.controls).toString()),
            )
        }
        return "Дистанции: ${rows.size}, КП загружены."
    }

    suspend fun importFormatsCsv(text: String, reset: Boolean): Pair<String, List<FormatCsvRow>> {
        val rows = CsvImport.formats(text)
        if (reset) dao.clearCourses()
        for (row in rows) {
            val existing = dao.listCourses().find { it.name.equals(row.name, true) }
            dao.upsertCourse(
                StartCourseEntity(
                    name = row.name,
                    controlsJson = existing?.controlsJson ?: JSONArray().toString(),
                ),
            )
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
            dao.upsertCourse(course.copy(controlsJson = JSONArray(next).toString()))
        }
    }

    suspend fun deletePerson(id: Int) {
        dao.deletePerson(id)
    }

    suspend fun saveCourse(name: String, controls: List<Int>, previousName: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) error("Укажите название дистанции")
        if (controls.any { it <= 0 }) error("Номер КП должен быть больше 0")
        val previous = previousName?.trim().orEmpty()
        val renaming = previous.isNotEmpty() && !previous.equals(trimmed, ignoreCase = true)
        if (renaming) {
            val clash = dao.listCourses().any { it.name.equals(trimmed, ignoreCase = true) }
            if (clash) error("Дистанция «$trimmed» уже есть")
        }
        dao.upsertCourse(
            StartCourseEntity(name = trimmed, controlsJson = JSONArray(controls).toString()),
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

    suspend fun deleteCourse(name: String) {
        dao.deleteCourse(name.trim())
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
        dao.upsertPerson(
            StartPersonEntity(
                logicalId = id,
                name = name.trim(),
                currentCourse = course.trim(),
                coursesJson = JSONArray(union.toList()).toString(),
                origin = existing?.origin ?: ORIGIN_PHONE,
                localCourseTouchedAt = System.currentTimeMillis(),
                issuedAt = existing?.issuedAt ?: 0L,
                issuedCourse = existing?.issuedCourse.orEmpty(),
                gender = gender ?: existing?.gender.orEmpty(),
                birthDate = birthDate ?: existing?.birthDate.orEmpty(),
                teamId = existing?.teamId ?: 0,
            ),
        )
    }

    suspend fun joinTeam(id: Int, otherId: Int): Int {
        if (id == otherId) error("Нужен другой номер для команды")
        if (id !in 1..MAX_LOGICAL_ID || otherId !in 1..MAX_LOGICAL_ID) {
            error("Номер чипа вне 1…$MAX_LOGICAL_ID")
        }
        val first = dao.person(id) ?: error("№ $id нет в протоколе")
        val second = dao.person(otherId) ?: error("№ $otherId нет в протоколе")
        val teamId = when {
            first.teamId > 0 && second.teamId > 0 -> minOf(first.teamId, second.teamId).also { keep ->
                val drop = maxOf(first.teamId, second.teamId)
                if (keep != drop) {
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
        return teamId
    }

    suspend fun assignTeam(id: Int, teamId: Int): Int {
        if (teamId <= 0) error("Номер команды должен быть больше 0")
        val person = dao.person(id) ?: error("№ $id нет в протоколе")
        if (person.teamId != teamId) {
            dao.upsertPerson(person.copy(teamId = teamId))
        }
        return teamId
    }

    suspend fun leaveTeam(id: Int): Int {
        val person = dao.person(id) ?: error("№ $id нет в протоколе")
        if (person.teamId <= 0) return 0
        val teamId = person.teamId
        dao.upsertPerson(person.copy(teamId = 0))
        return teamId
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
        return buildStartProtocolSnapshotJson(people)
    }

    suspend fun markIssued(id: Int, course: String = "") {
        val existing = dao.person(id) ?: return
        dao.upsertPerson(
            existing.copy(
                issuedAt = System.currentTimeMillis(),
                issuedCourse = course.trim().ifBlank { existing.issuedCourse.ifBlank { existing.currentCourse } },
            ),
        )
    }

    suspend fun setCurrentCourse(id: Int, course: String) {
        val existing = dao.person(id) ?: return
        val union = parseNames(existing.coursesJson).toMutableList()
        if (course.isNotBlank() && union.none { it.equals(course, ignoreCase = true) }) {
            union.add(course)
        }
        dao.upsertPerson(
            existing.copy(
                currentCourse = course.trim(),
                coursesJson = JSONArray(union).toString(),
                localCourseTouchedAt = System.currentTimeMillis(),
            ),
        )
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

    private fun findPackFile(name: String = FILE_NAME): File? {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), name),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "rogein/$name",
            ),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun deletePackFiles() {
        for (name in listOf(FILE_NAME, EVENT_FILE_NAME)) {
            File(context.getExternalFilesDir(null), name).delete()
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "rogein/$name",
            ).delete()
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
        val file = findPackFile(EVENT_FILE_NAME)
            ?: return UsbEventPackLoad(error = "Файл пакета не найден. Отправьте его с компьютера по USB или с сайта.")
        return try {
            UsbEventPackLoad(pack = parseEventPackJson(file.readText(Charsets.UTF_8)))
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

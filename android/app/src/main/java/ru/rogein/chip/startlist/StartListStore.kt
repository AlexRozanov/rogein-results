package ru.rogein.chip.startlist

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
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

    suspend fun reloadFromUsb(quiet: Boolean = false): String? {
        val file = findPackFile()
            ?: return if (quiet) null else "Файл протокола не найден. Отправьте его с компьютера по USB."
        val pack = try {
            parseStartPack(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return "Не удалось прочитать протокол: ${e.message ?: "ошибка файла"}"
        }
        if (quiet) {
            val meta = dao.meta()
            if (meta != null && meta.revision == pack.revision) {
                return null
            }
        }
        merge(pack)
        return "Протокол обновлён: ${pack.people.size} участников, ${pack.courses.size} дистанций."
    }

    suspend fun merge(pack: StartPack) {
        val meta = dao.meta()
        val appliedAt = meta?.appliedAt ?: 0L
        val incomingIds = pack.people.map { it.id }.toSet()
        val existing = dao.listPeople()

        dao.clearCourses()
        for (course in pack.courses) {
            dao.upsertCourse(
                StartCourseEntity(
                    name = course.name,
                    controlsJson = JSONArray(course.controls).toString(),
                ),
            )
        }

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
                    name = person.name,
                    currentCourse = current,
                    coursesJson = JSONArray(union.toList()).toString(),
                    origin = ORIGIN_DESKTOP,
                    localCourseTouchedAt = if (keepLocalCurrent) local!!.localCourseTouchedAt else 0L,
                    issuedAt = local?.issuedAt ?: 0L,
                ),
            )
        }

        for (local in existing) {
            if (local.origin == ORIGIN_DESKTOP && local.logicalId !in incomingIds) {
                dao.deletePerson(local.logicalId)
            }
        }
        dao.upsertMeta(
            StartMetaEntity(
                revision = pack.revision,
                appliedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun upsertLocal(id: Int, name: String, course: String, extraCourses: List<String> = emptyList()) {
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
            ),
        )
    }

    suspend fun markIssued(id: Int) {
        val existing = dao.person(id) ?: return
        if (existing.issuedAt > 0L) return
        dao.upsertPerson(existing.copy(issuedAt = System.currentTimeMillis()))
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

    private fun findPackFile(): File? {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), FILE_NAME),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "rogein/$FILE_NAME",
            ),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun deletePackFiles() {
        File(context.getExternalFilesDir(null), FILE_NAME).delete()
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "rogein/$FILE_NAME",
        ).delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/rogein/"
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(FILE_NAME, relative),
            )
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(FILE_NAME, "${Environment.DIRECTORY_DOWNLOADS}/rogein"),
            )
        }
    }

    companion object {
        const val FILE_NAME = "start-pack.json"
        const val ORIGIN_DESKTOP = "desktop"
        const val ORIGIN_PHONE = "phone"
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

package ru.rogein.chip.queue

import android.content.Context
import androidx.room.Room
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.parse.CsvImport
import ru.rogein.chip.parse.toChipDump
import ru.rogein.chip.printer.PrinterStore
import ru.rogein.chip.startlist.StartListStore
import java.util.UUID

sealed class EnqueueResult {
    data class Saved(val snapshotError: String?) : EnqueueResult()
    data class Duplicate(val logicalId: Int?, val courseName: String) : EnqueueResult()
}

data class PendingFinishImport(
    val dump: ChipDump,
    val personName: String,
    val courseName: String,
)

data class FinishCsvImportPreview(
    val fresh: List<PendingFinishImport>,
    val conflicts: List<PendingFinishImport>,
)

data class FinishCsvImportStats(
    val added: Int,
    val overwritten: Int,
)

class ChipQueueRepository(
    private val dao: ChipQueueDao,
    private val snapshot: ChipQueueSnapshotWriter,
    val startList: StartListStore,
    val event: EventStore,
    private val archive: StartArchiveStore,
    val printer: PrinterStore,
) {
    fun observeAll() = dao.observeAll()

    suspend fun itemsNow() = dao.listAll()

    fun observeStarts() = archive.observe()

    suspend fun ensureCurrentStart() = archive.ensureCurrent()

    /** Saved — чип в очереди. Duplicate — этот номер уже считан на этой дистанции. */
    suspend fun enqueue(
        dump: ChipDump,
        rawHex: String = "",
        personName: String = "",
        courseName: String = "",
        replace: Boolean = false,
        publish: Boolean = true,
    ): EnqueueResult {
        val course = courseName.trim()
        val chipId = dump.logicalId
        if (chipId != null && dao.existsByChipAndCourse(chipId, course)) {
            if (!replace) {
                return EnqueueResult.Duplicate(chipId, course)
            }
            dao.deleteByChipAndCourse(chipId, course)
        }
        dao.insert(
            QueuedChipEntity(
                id = UUID.randomUUID().toString(),
                uid = dump.uid,
                logicalId = dump.logicalId,
                clearedAt = dump.clearedAt,
                punchCount = dump.punches.size,
                firstTime = dump.punches.firstOrNull()?.time,
                lastTime = dump.punches.lastOrNull()?.time,
                punchesJson = dump.punches.joinToString(prefix = "[", postfix = "]") { punch ->
                    """{"cp":${punch.cp},"time":"${punch.time}"}"""
                },
                rawHex = rawHex,
                createdAt = System.currentTimeMillis(),
                ackedAt = null,
                personName = personName,
                courseName = course,
            ),
        )
        return EnqueueResult.Saved(if (publish) publishSnapshot() else null)
    }

    suspend fun previewFinishCsv(text: String): FinishCsvImportPreview {
        val rows = CsvImport.finishDump(text)
        val fresh = ArrayList<PendingFinishImport>()
        val conflicts = ArrayList<PendingFinishImport>()
        for (row in rows) {
            val dump = row.toChipDump()
            val id = dump.logicalId ?: continue
            val pending = PendingFinishImport(
                dump = dump,
                personName = row.name,
                courseName = row.course.trim(),
            )
            if (dao.existsByChipAndCourse(id, pending.courseName)) {
                conflicts += pending
            } else {
                fresh += pending
            }
        }
        if (fresh.isEmpty() && conflicts.isEmpty()) {
            error("В файле нет финишных отметок")
        }
        return FinishCsvImportPreview(fresh, conflicts)
    }

    suspend fun importFinishCsv(
        preview: FinishCsvImportPreview,
        overwrite: Boolean,
    ): FinishCsvImportStats {
        val rows = if (overwrite) preview.fresh + preview.conflicts else preview.fresh
        for (row in rows) {
            enqueue(
                dump = row.dump,
                personName = row.personName,
                courseName = row.courseName,
                replace = overwrite,
                publish = false,
            )
        }
        publishSnapshot()
        event.markLivePending()
        return FinishCsvImportStats(
            added = preview.fresh.size,
            overwritten = if (overwrite) preview.conflicts.size else 0,
        )
    }

    suspend fun deleteQueued(id: String): String? {
        dao.deleteById(id)
        event.markLivePending()
        return publishSnapshot()
    }

    suspend fun clear(): String? {
        dao.clear()
        return publishSnapshot()
    }

    suspend fun resetForNewStart(): String {
        val message = archive.createNewStart()
        startList.forgetUsbPack()
        publishSnapshot()
        return message
    }

    suspend fun openStart(id: Long): String {
        val message = archive.openStart(id)
        publishSnapshot()
        event.markLivePending()
        return message
    }

    suspend fun deleteStart(id: Long): String {
        val message = archive.deleteStart(id)
        publishSnapshot()
        event.markLivePending()
        return message
    }

    suspend fun exportCsv(startId: Long? = null): List<Pair<String, String>> =
        archive.exportCsv(startId)

    suspend fun requestLiveSend() {
        event.markLivePending()
        flushLiveOnce()
    }

    suspend fun flushLiveNow() {
        event.markLivePending()
        flushLiveOnce()
    }

    suspend fun flushLiveIfPending() {
        val settings = event.settingsNow()
        if (!settings.livePending && !event.isLiveSending()) return
        flushLiveOnce()
    }

    private suspend fun flushLiveOnce() {
        val settings = event.settingsNow()
        event.sendLiveSnapshot(
            settings,
            startList.peopleNow(),
            startList.coursesNow(),
            dao.listAll(),
            event.correctionsNow(),
            event.exclusionsNow(),
        )
    }

    suspend fun republishSnapshot(): String? = publishSnapshot()

    private suspend fun publishSnapshot(): String? {
        return try {
            snapshot.write(dao.listAll())
            null
        } catch (e: Exception) {
            e.message ?: "не удалось записать снимок"
        }
    }

    companion object {
        fun create(context: Context): ChipQueueRepository {
            val appContext = context.applicationContext
            val db = Room.databaseBuilder(
                appContext,
                ChipQueueDatabase::class.java,
                "chip-queue.db",
            )
                .addMigrations(
                    ChipQueueDatabase.MIGRATION_1_2,
                    ChipQueueDatabase.MIGRATION_2_3,
                    ChipQueueDatabase.MIGRATION_3_4,
                    ChipQueueDatabase.MIGRATION_4_5,
                    ChipQueueDatabase.MIGRATION_5_6,
                )
                .build()
            return ChipQueueRepository(
                db.dao(),
                ChipQueueSnapshotWriter(appContext),
                StartListStore(appContext, db.startListDao()),
                EventStore(db.eventDao()),
                StartArchiveStore(db),
                PrinterStore(appContext),
            )
        }
    }
}

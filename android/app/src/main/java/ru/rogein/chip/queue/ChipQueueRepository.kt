package ru.rogein.chip.queue

import android.content.Context
import androidx.room.Room
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.parse.CsvImport
import ru.rogein.chip.parse.EventPackFile
import ru.rogein.chip.parse.FinishEnqueueKind
import ru.rogein.chip.parse.FinishQueueRef
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.canChangeFinishNumber
import ru.rogein.chip.parse.classifyFinishEnqueue
import ru.rogein.chip.parse.normalizeChipUid
import ru.rogein.chip.parse.numberConflictIds
import ru.rogein.chip.parse.toChipDump
import ru.rogein.chip.printer.PrinterStore
import ru.rogein.chip.startlist.StartListStore
import ru.rogein.chip.startlist.eventPackLoadedMessage
import java.util.UUID

sealed class EnqueueResult {
    data class Saved(
        val snapshotError: String?,
        val numberConflict: Boolean = false,
        val otherUid: String = "",
    ) : EnqueueResult()
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
    private val appContext: Context,
    val startList: StartListStore,
    val event: EventStore,
    private val archive: StartArchiveStore,
    val printer: PrinterStore,
) {
    fun observeAll() = dao.observeAll()

    suspend fun itemsNow() = dao.listAll()

    fun observeStarts() = archive.observe()

    suspend fun applyRogaineEventPack(pack: EventPackFile, replace: Boolean): String {
        startList.applyEventPack(pack, replace)
        event.applyPackMeta(pack)
        publishProtocolSnapshot()
        return eventPackLoadedMessage(pack)
    }

    suspend fun ensureCurrentStart() = archive.ensureCurrent()

    /** Saved — чип в очереди. Duplicate — этот же физический чип уже считан на этой дистанции. */
    suspend fun enqueue(
        dump: ChipDump,
        rawHex: String = "",
        personName: String = "",
        courseName: String = "",
        replace: Boolean = false,
        publish: Boolean = true,
    ): EnqueueResult {
        val course = courseName.trim()
        val items = dao.listAll()
        val refs = items.map { it.toFinishRef() }
        val kind = classifyFinishEnqueue(dump.uid, dump.logicalId, course, refs)
        if (kind == FinishEnqueueKind.SameChip && !replace) {
            return EnqueueResult.Duplicate(dump.logicalId, course)
        }
        val keep = if (kind == FinishEnqueueKind.SameChip) matchExistingFinish(dump, course, items) else null
        val id = keep?.id ?: UUID.randomUUID().toString()
        dao.insert(
            QueuedChipEntity(
                id = id,
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
                createdAt = keep?.createdAt ?: System.currentTimeMillis(),
                ackedAt = null,
                personName = personName,
                courseName = course,
            ),
        )
        val nextRefs = refs.filter { it.id != id } + FinishQueueRef(id, dump.uid, dump.logicalId, course)
        val numberConflict = id in numberConflictIds(nextRefs)
        val otherUid = nextRefs.find { row ->
            row.id != id && row.logicalId != null && row.logicalId == dump.logicalId
        }?.uid.orEmpty()
        return EnqueueResult.Saved(
            snapshotError = if (publish) publishSnapshot() else null,
            numberConflict = numberConflict,
            otherUid = otherUid,
        )
    }

    suspend fun reassignFinishNumber(queueId: String, newLogicalId: Int): String? {
        if (newLogicalId !in 1..MAX_LOGICAL_ID) {
            error("Номер вне 1…$MAX_LOGICAL_ID")
        }
        val item = dao.byId(queueId) ?: error("Запись финиша не найдена")
        val current = item.logicalId
        if (current == newLogicalId) error("Укажите другой номер")
        val items = dao.listAll()
        val people = startList.peopleNow()
        val sameCount = items.count { it.logicalId != null && it.logicalId == current }
        val issuedUid = people.find { it.id == current }?.issuedUid
        if (!canChangeFinishNumber(current, item.uid, sameCount, issuedUid)) {
            error("Сменить номер можно, если он есть у двух чипов или UID не совпал с выдачей")
        }
        val clash = items.find { row ->
            row.id != queueId &&
                row.logicalId == newLogicalId &&
                normalizeChipUid(row.uid) != normalizeChipUid(item.uid)
        }
        if (clash != null) {
            error("№ $newLogicalId уже есть в финише с другого чипа")
        }
        val person = startList.person(newLogicalId)
        val name = person?.name?.takeIf { it.isNotBlank() } ?: item.personName
        val course = item.courseName.ifBlank { person?.currentCourse.orEmpty() }
        dao.updateFinishIdentity(queueId, newLogicalId, name, course)
        startList.rebindIssuedUid(current, newLogicalId, item.uid)
        event.markLivePending()
        return publishSnapshot()
    }

    suspend fun previewFinishCsv(text: String): FinishCsvImportPreview {
        val rows = CsvImport.finishDump(text)
        val existing = dao.listAll().map { it.toFinishRef() }
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
            when (classifyFinishEnqueue(dump.uid, id, pending.courseName, existing)) {
                FinishEnqueueKind.SameChip -> conflicts += pending
                FinishEnqueueKind.OtherChipSameNumber, FinishEnqueueKind.Fresh -> fresh += pending
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

    suspend fun resetForNewStart(sport: String): String {
        val message = archive.createNewStart(sport)
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

    suspend fun republishSnapshot(): String? {
        val queueError = publishSnapshot()
        val protocolError = publishProtocolSnapshot()
        return queueError ?: protocolError
    }

    suspend fun publishProtocolSnapshot(): String? {
        return try {
            RogeinDownloadJson.write(
                appContext,
                PROTOCOL_FILE_NAME,
                startList.protocolSnapshotJson(),
            )
            null
        } catch (e: Exception) {
            e.message ?: "не удалось записать протокол"
        }
    }

    private suspend fun publishSnapshot(): String? {
        return try {
            snapshot.write(dao.listAll())
            null
        } catch (e: Exception) {
            e.message ?: "не удалось записать снимок"
        }
    }

    private fun matchExistingFinish(
        dump: ChipDump,
        course: String,
        items: List<QueuedChipEntity>,
    ): QueuedChipEntity? {
        val nuid = normalizeChipUid(dump.uid)
        if (nuid.isNotEmpty()) {
            items.find { normalizeChipUid(it.uid) == nuid && it.courseName.equals(course, ignoreCase = true) }
                ?.let { return it }
        }
        val logicalId = dump.logicalId ?: return null
        return items.find { it.logicalId == logicalId && it.courseName.equals(course, ignoreCase = true) }
    }

    companion object {
        const val PROTOCOL_FILE_NAME = "start-protocol.json"

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
                    ChipQueueDatabase.MIGRATION_6_7,
                    ChipQueueDatabase.MIGRATION_7_8,
                    ChipQueueDatabase.MIGRATION_8_9,
                )
                .build()
            return ChipQueueRepository(
                db.dao(),
                ChipQueueSnapshotWriter(appContext),
                appContext,
                StartListStore(appContext, db.startListDao()),
                EventStore(appContext.filesDir, db.eventDao()),
                StartArchiveStore(db),
                PrinterStore(appContext),
            )
        }
    }
}

private fun QueuedChipEntity.toFinishRef() = FinishQueueRef(
    id = id,
    uid = uid,
    logicalId = logicalId,
    courseName = courseName,
)

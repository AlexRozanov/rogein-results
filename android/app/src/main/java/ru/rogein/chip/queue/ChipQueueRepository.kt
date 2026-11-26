package ru.rogein.chip.queue

import android.content.Context
import androidx.room.Room
import ru.rogein.chip.field.FieldJournal
import ru.rogein.chip.field.FieldOpApplier
import ru.rogein.chip.field.FieldSyncEngine
import ru.rogein.chip.parse.COPY_PUNCHES
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.parse.CsvImport
import ru.rogein.chip.parse.EventPackFile
import ru.rogein.chip.parse.FinishEnqueueKind
import ru.rogein.chip.parse.FinishQueueRef
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.canChangeFinishNumber
import ru.rogein.chip.parse.classifyFinishEnqueue
import ru.rogein.chip.parse.dnfUid
import ru.rogein.chip.parse.isDnfUid
import ru.rogein.chip.parse.isSyntheticFinishUid
import ru.rogein.chip.parse.isTeamCopyUid
import ru.rogein.chip.parse.normalizeChipUid
import ru.rogein.chip.parse.numberConflictIds
import ru.rogein.chip.parse.teamCopyUid
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
    val fieldJournal: FieldJournal,
    val fieldSync: FieldSyncEngine,
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
        source: String = "nfc",
    ): EnqueueResult {
        val course = courseName.trim()
        val items = dao.listAll()
        val refs = items.map { it.toFinishRef() }
        var kind = classifyFinishEnqueue(dump.uid, dump.logicalId, course, refs)
        val synthetic = matchSyntheticCopy(dump.logicalId, course, items)
        if (kind == FinishEnqueueKind.OtherChipSameNumber && synthetic != null) {
            if (dump.punches.size < synthetic.punchCount) {
                dao.insert(
                    synthetic.copy(
                        uid = dump.uid.ifBlank { synthetic.uid },
                        rawHex = rawHex.ifBlank { synthetic.rawHex },
                    ),
                )
                rememberChipRead(synthetic.id, dump, course, replace, source)
                return savedEnqueue(synthetic.id, dump.uid, dump.logicalId, course, refs, publish)
            }
            kind = FinishEnqueueKind.SameChip
        }
        if (kind == FinishEnqueueKind.SameChip && !replace) {
            return EnqueueResult.Duplicate(dump.logicalId, course)
        }
        val keep = if (kind == FinishEnqueueKind.SameChip) {
            matchExistingFinish(dump, course, items) ?: synthetic
        } else {
            null
        }
        if (keep != null && shouldKeepCopiedPunches(keep, dump.punches.size)) {
            val uid = dump.uid.ifBlank { keep.uid }
            dao.insert(
                keep.copy(
                    uid = uid,
                    rawHex = rawHex.ifBlank { keep.rawHex },
                ),
            )
            rememberChipRead(keep.id, dump, course, replace, source)
            return savedEnqueue(keep.id, uid, keep.logicalId, course, refs, publish)
        }
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
        if (dump.punches.isNotEmpty()) {
            dump.logicalId?.let { event.clearDidNotFinish(it) }
        }
        rememberChipRead(id, dump, course, replace, source)
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

    suspend fun recordChipIssue(
        logicalId: Int,
        uid: String,
        course: String,
        overwrite: Boolean,
    ) {
        runCatching {
            fieldJournal.recordChipIssue(
                logicalId = logicalId,
                uid = uid,
                course = course,
                eventSlug = event.settingsNow().slug,
                overwrite = overwrite,
            )
        }
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
        runCatching {
            fieldJournal.recordFinishReassign(
                queueId = queueId,
                oldLogicalId = current,
                newLogicalId = newLogicalId,
                uid = item.uid,
                eventSlug = event.settingsNow().slug,
            )
        }
        event.markLivePending()
        return publishSnapshot()
    }

    suspend fun applyFinishReassign(
        queueId: String,
        oldLogicalId: Int?,
        newLogicalId: Int,
        uid: String,
    ) {
        val item = dao.byId(queueId) ?: return
        val person = startList.person(newLogicalId)
        val name = person?.name?.takeIf { it.isNotBlank() } ?: item.personName
        val course = item.courseName.ifBlank { person?.currentCourse.orEmpty() }
        dao.updateFinishIdentity(queueId, newLogicalId, name, course)
        startList.rebindIssuedUid(oldLogicalId ?: item.logicalId, newLogicalId, uid.ifBlank { item.uid })
    }

    suspend fun copyTeamPunches(sourceQueueId: String, targetLogicalId: Int): String {
        if (targetLogicalId !in 1..MAX_LOGICAL_ID) {
            error("Номер вне 1…$MAX_LOGICAL_ID")
        }
        val source = dao.byId(sourceQueueId) ?: error("Запись финиша не найдена")
        if (source.punchCount <= 0) error("У напарника нет отметок")
        val sourceId = source.logicalId
        if (sourceId != null && sourceId == targetLogicalId) {
            error("Нельзя скопировать отметки на того же участника")
        }
        val person = startList.person(targetLogicalId)
            ?: error("№ $targetLogicalId нет в стартовом протоколе")
        val items = dao.listAll()
        val existing = items.filter { it.logicalId == targetLogicalId }
        if (existing.size > 1) {
            error("У № $targetLogicalId несколько записей финиша — сначала разберите конфликт номеров")
        }
        val target = existing.singleOrNull()
        val created = target == null
        val id = target?.id ?: UUID.randomUUID().toString()
        dao.insert(
            QueuedChipEntity(
                id = id,
                uid = target?.uid?.takeIf { it.isNotBlank() && !isTeamCopyUid(it) } ?: teamCopyUid(targetLogicalId),
                logicalId = targetLogicalId,
                clearedAt = target?.clearedAt ?: source.clearedAt,
                punchCount = source.punchCount,
                firstTime = source.firstTime,
                lastTime = source.lastTime,
                punchesJson = source.punchesJson,
                rawHex = target?.rawHex.orEmpty(),
                createdAt = target?.createdAt ?: System.currentTimeMillis(),
                ackedAt = null,
                personName = person.name.ifBlank { target?.personName.orEmpty() }.ifBlank { "№ $targetLogicalId" },
                courseName = target?.courseName?.ifBlank { null }
                    ?: source.courseName.ifBlank { person.currentCourse },
            ),
        )
        event.clearDidNotFinish(targetLogicalId)
        if (sourceId != null) {
            event.setCopyPunchesNote(targetLogicalId, sourceId)
        }
        event.markLivePending()
        val snapshotError = publishSnapshot()
        val who = person.name.ifBlank { "№ $targetLogicalId" }
        val from = sourceId?.let { "№ $it" } ?: "напарника"
        val base = if (created) {
            "№ $targetLogicalId $who добавлен в финиш с отметками $from (${source.punchCount} КП)."
        } else {
            "Отметки $from скопированы на № $targetLogicalId $who (${source.punchCount} КП)."
        }
        return if (snapshotError == null) base else "$base Снимок в Загрузках не обновлён: $snapshotError"
    }

    suspend fun markDidNotFinish(logicalId: Int): String {
        if (logicalId !in 1..MAX_LOGICAL_ID) {
            error("Номер вне 1…$MAX_LOGICAL_ID")
        }
        val person = startList.person(logicalId)
            ?: error("№ $logicalId нет в стартовом протоколе")
        val items = dao.listAll()
        val existing = items.filter { it.logicalId == logicalId }
        if (existing.size > 1) {
            error("У № $logicalId несколько записей финиша — сначала разберите конфликт номеров")
        }
        val current = existing.singleOrNull()
        if (current != null && current.punchCount > 0 && !isDnfUid(current.uid)) {
            error("У № $logicalId уже есть отметки. Сначала удалите финиш, если это ошибка.")
        }
        val id = current?.id ?: UUID.randomUUID().toString()
        dao.insert(
            QueuedChipEntity(
                id = id,
                uid = current?.uid?.takeIf { it.isNotBlank() && !isTeamCopyUid(it) } ?: dnfUid(logicalId),
                logicalId = logicalId,
                clearedAt = current?.clearedAt,
                punchCount = 0,
                firstTime = null,
                lastTime = null,
                punchesJson = "[]",
                rawHex = current?.rawHex.orEmpty(),
                createdAt = current?.createdAt ?: System.currentTimeMillis(),
                ackedAt = null,
                personName = person.name.ifBlank { current?.personName.orEmpty() }.ifBlank { "№ $logicalId" },
                courseName = current?.courseName?.ifBlank { null } ?: person.currentCourse,
            ),
        )
        event.setDidNotFinish(logicalId)
        event.markLivePending()
        val snapshotError = publishSnapshot()
        val who = person.name.ifBlank { "№ $logicalId" }
        val base = "№ $logicalId $who добавлен в финиш со статусом «не финишировал»."
        return if (snapshotError == null) base else "$base Снимок в Загрузках не обновлён: $snapshotError"
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
                source = "csv",
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
        runCatching {
            fieldJournal.recordQueueDrop(id, event.settingsNow().slug)
        }
        event.markLivePending()
        return publishSnapshot()
    }

    suspend fun applyQueueDrop(queueId: String) {
        dao.deleteById(queueId)
    }

    suspend fun resetForNewStart(sport: String): String {
        val message = archive.createNewStart(sport)
        startList.forgetUsbPack()
        clearFieldJournal("новый старт")
        publishSnapshot()
        return message
    }

    suspend fun clearFieldJournal(reason: String = "вручную"): String {
        fieldJournal.clearAll()
        fieldSync.onJournalCleared()
        return "Журнал обмена очищен ($reason)."
    }

    suspend fun exportFieldJournal(): Pair<String, String> {
        val ops = fieldJournal.listAllEnvelopes()
        val arr = org.json.JSONArray()
        ops.forEach { arr.put(ru.rogein.chip.parse.field.fieldOpEnvelopeToJson(it)) }
        val body = org.json.JSONObject()
            .put("schema", ru.rogein.chip.parse.field.FIELD_OP_SCHEMA)
            .put("device_id", fieldJournal.deviceId())
            .put("exported_at", System.currentTimeMillis())
            .put("op_count", ops.size)
            .put("ops", arr)
            .toString(2)
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "field-journal-$stamp.json" to body
    }

    suspend fun openStart(id: Long): String {
        if (fieldJournal.device.fieldSyncEnabled) {
            error("Пока включён полевой обмен, нельзя открыть другой старт из архива")
        }
        val message = archive.openStart(id)
        publishSnapshot()
        event.markLivePending()
        return message
    }

    suspend fun deleteStart(id: Long): String {
        if (fieldJournal.device.fieldSyncEnabled) {
            error("Пока включён полевой обмен, нельзя удалить старт из архива")
        }
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
        val device = fieldJournal.device
        // При lan live шлёт только хост — иначе два PUT затирают друг друга.
        if (device.lanEnabled && !device.isHost) {
            event.setLiveNote("при обмене live шлёт только хост")
            return
        }
        // Cloud: только издатель.
        if (device.cloudEnabled && !device.cloudPublisher) {
            event.setLiveNote("при облачном обмене live шлёт только издатель")
            return
        }
        val settings = event.settingsNow()
        if (!settings.livePublishEnabled) {
            event.setLiveNote("публикация на сайт выключена")
            return
        }
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
            snapshot.write(dao.listAll(), slug = event.settingsNow().slug)
            null
        } catch (e: Exception) {
            e.message ?: "не удалось записать снимок"
        }
    }

    private suspend fun rememberChipRead(
        queueId: String,
        dump: ChipDump,
        course: String,
        replace: Boolean,
        source: String = "nfc",
    ) {
        runCatching {
            fieldJournal.recordChipRead(
                queueId = queueId,
                dump = dump,
                courseHint = course,
                eventSlug = event.settingsNow().slug,
                replace = replace,
                source = source,
            )
        }
    }

    private suspend fun savedEnqueue(
        id: String,
        uid: String,
        logicalId: Int?,
        course: String,
        refs: List<FinishQueueRef>,
        publish: Boolean,
    ): EnqueueResult.Saved {
        val nextRefs = refs.filter { it.id != id } + FinishQueueRef(id, uid, logicalId, course)
        val numberConflict = id in numberConflictIds(nextRefs)
        val otherUid = nextRefs.find { row ->
            row.id != id && row.logicalId != null && row.logicalId == logicalId
        }?.uid.orEmpty()
        return EnqueueResult.Saved(
            snapshotError = if (publish) publishSnapshot() else null,
            numberConflict = numberConflict,
            otherUid = otherUid,
        )
    }

    private suspend fun shouldKeepCopiedPunches(keep: QueuedChipEntity, incomingCount: Int): Boolean {
        if (incomingCount >= keep.punchCount) return false
        if (isTeamCopyUid(keep.uid)) return true
        val logicalId = keep.logicalId ?: return false
        return event.correctionsNow().any { it.type == COPY_PUNCHES && it.logicalId == logicalId }
    }

    private fun matchSyntheticCopy(
        logicalId: Int?,
        course: String,
        items: List<QueuedChipEntity>,
    ): QueuedChipEntity? {
        if (logicalId == null) return null
        return items.find { row ->
            row.logicalId == logicalId &&
                row.courseName.equals(course, ignoreCase = true) &&
                isSyntheticFinishUid(row.uid)
        }
    }

    private fun matchExistingFinish(
        dump: ChipDump,
        course: String,
        items: List<QueuedChipEntity>,
    ): QueuedChipEntity? {
        val nuid = normalizeChipUid(dump.uid)
        val logicalId = dump.logicalId
        if (nuid.isNotEmpty()) {
            items.find { row ->
                normalizeChipUid(row.uid) == nuid &&
                    row.courseName.equals(course, ignoreCase = true) &&
                    (logicalId == null || row.logicalId == null || row.logicalId == logicalId)
            }?.let { return it }
        }
        if (logicalId == null) return null
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
                    ChipQueueDatabase.MIGRATION_9_10,
                    ChipQueueDatabase.MIGRATION_10_11,
                    ChipQueueDatabase.MIGRATION_11_12,
                    ChipQueueDatabase.MIGRATION_12_13,
                )
                .build()
            val fieldJournal = FieldJournal.create(appContext, db.fieldJournalDao())
            // Dual-path: LAN-обмен по Wi‑Fi; сайт — по siteUplink (auto/LTE/Wi‑Fi).
            val paths = ru.rogein.chip.field.FieldNetworkPaths.get(appContext)
            paths.siteUplink = fieldJournal.device.siteUplink
            paths.ensureCellular()
            val event = EventStore(appContext.filesDir, db.eventDao(), fieldJournal)
            val startList = StartListStore(
                appContext,
                db.startListDao(),
                fieldJournal,
                eventSlug = { event.settingsNow().slug },
                chipQueue = db.dao(),
                applyMapGeometry = { scale, positions ->
                    event.applyIofMapGeometry(scale, positions)
                },
                applyExclusions = { rules ->
                    event.replaceExclusions(rules)
                },
            )
            val fieldSync = FieldSyncEngine(
                context = appContext,
                journal = fieldJournal,
                applier = FieldOpApplier(startList, db.dao(), event),
                eventSlug = { event.settingsNow().slug },
                siteBaseUrl = { event.settingsNow().apiBaseUrl },
                publishToken = { event.settingsNow().publishToken },
            )
            return ChipQueueRepository(
                db.dao(),
                ChipQueueSnapshotWriter(appContext),
                appContext,
                startList,
                event,
                StartArchiveStore(db),
                PrinterStore(appContext),
                fieldJournal,
                fieldSync,
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

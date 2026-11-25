package ru.rogein.chip.field

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.parse.field.FIELD_OP_SCHEMA
import ru.rogein.chip.parse.field.FieldOpEnvelope
import ru.rogein.chip.parse.field.FieldOpPunch
import ru.rogein.chip.parse.field.FieldOpType
import ru.rogein.chip.parse.field.chipIssuePayloadJson
import ru.rogein.chip.parse.field.chipReadPayloadJson
import ru.rogein.chip.parse.field.correctionAddCpPayloadJson
import ru.rogein.chip.parse.field.correctionAssignCoursePayloadJson
import ru.rogein.chip.parse.field.correctionClearDnfPayloadJson
import ru.rogein.chip.parse.field.correctionCopyPunchesPayloadJson
import ru.rogein.chip.parse.field.correctionDeletePayloadJson
import ru.rogein.chip.parse.field.correctionDnfPayloadJson
import ru.rogein.chip.parse.field.correctionRemoveCpPayloadJson
import ru.rogein.chip.parse.field.courseDeletePayloadJson
import ru.rogein.chip.parse.field.courseUpsertPayloadJson
import ru.rogein.chip.parse.field.eventConfigPayloadJson
import ru.rogein.chip.parse.field.exclusionDeletePayloadJson
import ru.rogein.chip.parse.field.exclusionUpsertPayloadJson
import ru.rogein.chip.parse.field.finishReassignPayloadJson
import ru.rogein.chip.parse.field.personCoursePayloadJson
import ru.rogein.chip.parse.field.personDeletePayloadJson
import ru.rogein.chip.parse.field.personTeamAssignPayloadJson
import ru.rogein.chip.parse.field.personTeamJoinPayloadJson
import ru.rogein.chip.parse.field.personTeamLeavePayloadJson
import ru.rogein.chip.parse.field.personUpsertPayloadJson
import ru.rogein.chip.parse.field.punchDaySeconds
import ru.rogein.chip.parse.field.queueDropPayloadJson
import java.util.UUID

@Entity(
    tableName = "field_ops",
    indices = [Index(value = ["deviceId", "deviceSeq"], unique = true)],
)
data class FieldOpEntity(
    @PrimaryKey val opId: String,
    val schemaVersion: Int,
    val deviceId: String,
    val deviceSeq: Long,
    val eventSlug: String,
    val clockMs: Long,
    val actor: String,
    val type: String,
    val payloadJson: String,
    val createdAt: Long,
)

@Entity(tableName = "field_meta")
data class FieldMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface FieldJournalDao {
    @Query("SELECT * FROM field_ops WHERE opId = :opId LIMIT 1")
    suspend fun byOpId(opId: String): FieldOpEntity?

    @Query("SELECT * FROM field_ops ORDER BY clockMs ASC, deviceId ASC, deviceSeq ASC")
    suspend fun listAll(): List<FieldOpEntity>

    @Query("SELECT * FROM field_ops ORDER BY clockMs DESC, deviceId DESC, deviceSeq DESC LIMIT :limit")
    suspend fun listNewest(limit: Int): List<FieldOpEntity>

    @Query("SELECT COUNT(*) FROM field_ops")
    suspend fun count(): Long

    @Query("SELECT value FROM field_meta WHERE `key` = :key LIMIT 1")
    suspend fun metaValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(row: FieldMetaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: FieldOpEntity): Long

    @Query("DELETE FROM field_ops")
    suspend fun clearOps()

    @Query("DELETE FROM field_meta")
    suspend fun clearMeta()

    companion object {
        const val KEY_DEVICE_SEQ = "device_seq"
    }
}

/**
 * Append-only журнал. Свои операции — [append]; чужие — [acceptRemote] (без сдвига своего seq).
 */
class FieldJournal(
    private val dao: FieldJournalDao,
    val device: FieldDeviceStore,
) {
    private val opListeners = java.util.concurrent.CopyOnWriteArrayList<(FieldOpEnvelope) -> Unit>()

    fun addOpListener(listener: (FieldOpEnvelope) -> Unit) {
        opListeners.addIfAbsent(listener)
    }

    fun removeOpListener(listener: (FieldOpEnvelope) -> Unit) {
        opListeners.remove(listener)
    }

    private fun notifyOp(op: FieldOpEnvelope) {
        opListeners.forEach { runCatching { it(op) } }
    }

    suspend fun append(
        type: String,
        payloadJson: String,
        eventSlug: String,
        opId: String = UUID.randomUUID().toString(),
        clockMs: Long = System.currentTimeMillis(),
    ): FieldOpEnvelope {
        dao.byOpId(opId)?.let { existing ->
            return existing.toEnvelope()
        }
        val currentSeq = dao.metaValue(FieldJournalDao.KEY_DEVICE_SEQ)?.toLongOrNull() ?: 0L
        val nextSeq = currentSeq + 1L
        val row = FieldOpEntity(
            opId = opId,
            schemaVersion = FIELD_OP_SCHEMA,
            deviceId = device.deviceId,
            deviceSeq = nextSeq,
            eventSlug = eventSlug.trim(),
            clockMs = clockMs,
            actor = device.deviceName,
            type = type,
            payloadJson = payloadJson,
            createdAt = System.currentTimeMillis(),
        )
        val inserted = dao.insertIgnore(row)
        if (inserted == -1L) {
            return dao.byOpId(opId)?.toEnvelope()
                ?: error("field op $opId conflict without row")
        }
        dao.upsertMeta(FieldMetaEntity(FieldJournalDao.KEY_DEVICE_SEQ, nextSeq.toString()))
        val env = row.toEnvelope()
        notifyOp(env)
        return env
    }

    /**
     * Принять чужую (или свою повторно) операцию. true — впервые записана в журнал.
     */
    suspend fun acceptRemote(op: FieldOpEnvelope): Boolean {
        if (op.schema > FIELD_OP_SCHEMA) return false
        if (op.opId.isBlank() || op.deviceId.isBlank() || op.deviceSeq <= 0L) return false
        dao.byOpId(op.opId)?.let { return false }
        val row = FieldOpEntity(
            opId = op.opId,
            schemaVersion = op.schema.coerceAtLeast(1),
            deviceId = op.deviceId,
            deviceSeq = op.deviceSeq,
            eventSlug = op.eventSlug.trim(),
            clockMs = op.clockMs,
            actor = op.actor,
            type = op.type,
            payloadJson = op.payloadJson,
            createdAt = System.currentTimeMillis(),
        )
        val inserted = dao.insertIgnore(row) != -1L
        if (inserted) notifyOp(op)
        return inserted
    }

    suspend fun recordChipIssue(
        logicalId: Int,
        uid: String,
        course: String,
        eventSlug: String,
        overwrite: Boolean,
        cleared: Boolean = true,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CHIP_ISSUE,
        payloadJson = chipIssuePayloadJson(
            logicalId = logicalId,
            uid = uid,
            course = course,
            cleared = cleared,
            overwrite = overwrite,
        ),
        eventSlug = eventSlug,
    )

    suspend fun recordChipRead(
        queueId: String,
        dump: ChipDump,
        courseHint: String,
        eventSlug: String,
        replace: Boolean,
        syntheticFinish: Boolean = false,
        skipMissingFinish: Boolean = false,
        source: String = "nfc",
    ): FieldOpEnvelope {
        val punches = dump.punches.map { punch ->
            FieldOpPunch(punch.cp, punchDaySeconds(punch.hour, punch.minute, punch.second))
        }
        return append(
            type = FieldOpType.CHIP_READ,
            payloadJson = chipReadPayloadJson(
                queueId = queueId,
                uid = dump.uid,
                logicalId = dump.logicalId,
                punches = punches,
                readAtMs = System.currentTimeMillis(),
                courseHint = courseHint,
                replace = replace,
                syntheticFinish = syntheticFinish,
                skipMissingFinish = skipMissingFinish,
                source = source,
                blockCount = dump.blockCount,
            ),
            eventSlug = eventSlug,
        )
    }

    suspend fun recordPersonUpsert(
        logicalId: Int,
        name: String,
        gender: String,
        birthDate: String,
        course: String,
        courses: List<String>,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.PERSON_UPSERT,
        payloadJson = personUpsertPayloadJson(logicalId, name, gender, birthDate, course, courses),
        eventSlug = eventSlug,
    )

    suspend fun recordPersonDelete(
        logicalId: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.PERSON_DELETE,
        payloadJson = personDeletePayloadJson(logicalId),
        eventSlug = eventSlug,
    )

    suspend fun recordPersonCourse(
        logicalId: Int,
        course: String,
        courses: List<String>,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.PERSON_COURSE,
        payloadJson = personCoursePayloadJson(logicalId, course, courses),
        eventSlug = eventSlug,
    )

    suspend fun recordPersonTeamJoin(
        logicalId: Int,
        otherLogicalId: Int,
        teamId: Int,
        droppedTeamId: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.PERSON_TEAM_JOIN,
        payloadJson = personTeamJoinPayloadJson(logicalId, otherLogicalId, teamId, droppedTeamId),
        eventSlug = eventSlug,
    )

    suspend fun recordPersonTeamLeave(
        logicalId: Int,
        teamId: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.PERSON_TEAM_LEAVE,
        payloadJson = personTeamLeavePayloadJson(logicalId, teamId),
        eventSlug = eventSlug,
    )

    suspend fun recordPersonTeamAssign(
        logicalId: Int,
        teamId: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.PERSON_TEAM_ASSIGN,
        payloadJson = personTeamAssignPayloadJson(logicalId, teamId),
        eventSlug = eventSlug,
    )

    suspend fun recordCorrectionAddCp(
        correctionId: String,
        logicalId: Int,
        cp: Int,
        course: String,
        time: String,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CORRECTION_ADD_CP,
        payloadJson = correctionAddCpPayloadJson(correctionId, logicalId, cp, course, time),
        eventSlug = eventSlug,
    )

    suspend fun recordCorrectionRemoveCp(
        correctionId: String,
        logicalId: Int?,
        cp: Int,
        removeMode: String,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CORRECTION_REMOVE_CP,
        payloadJson = correctionRemoveCpPayloadJson(correctionId, logicalId, cp, removeMode),
        eventSlug = eventSlug,
    )

    suspend fun recordCorrectionDnf(
        correctionId: String,
        logicalId: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CORRECTION_DNF,
        payloadJson = correctionDnfPayloadJson(correctionId, logicalId),
        eventSlug = eventSlug,
    )

    suspend fun recordCorrectionClearDnf(
        logicalId: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CORRECTION_CLEAR_DNF,
        payloadJson = correctionClearDnfPayloadJson(logicalId),
        eventSlug = eventSlug,
    )

    suspend fun recordCorrectionCopyPunches(
        correctionId: String,
        logicalId: Int,
        fromLogicalId: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CORRECTION_COPY_PUNCHES,
        payloadJson = correctionCopyPunchesPayloadJson(correctionId, logicalId, fromLogicalId),
        eventSlug = eventSlug,
    )

    suspend fun recordCorrectionAssignCourse(
        correctionId: String,
        logicalId: Int,
        fromCourse: String,
        toCourse: String,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CORRECTION_ASSIGN_COURSE,
        payloadJson = correctionAssignCoursePayloadJson(correctionId, logicalId, fromCourse, toCourse),
        eventSlug = eventSlug,
    )

    suspend fun recordCorrectionDelete(
        correctionId: String,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.CORRECTION_DELETE,
        payloadJson = correctionDeletePayloadJson(correctionId),
        eventSlug = eventSlug,
    )

    suspend fun recordEventConfig(
        eventSlug: String,
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
    ): FieldOpEnvelope = append(
        type = FieldOpType.EVENT_CONFIG,
        payloadJson = eventConfigPayloadJson(
            controlMinutes = controlMinutes,
            startCp = startCp,
            finishCp = finishCp,
            penaltyPerMinute = penaltyPerMinute,
            dqMinutes = dqMinutes,
            startMode = startMode,
            competitionStartTime = competitionStartTime,
            competitionDate = competitionDate,
            title = title,
            sportKind = sportKind,
            formatSettingsJson = formatSettingsJson,
            awardGroupsJson = awardGroupsJson,
            legendsJson = legendsJson,
            mapPointsJson = mapPointsJson,
        ),
        eventSlug = eventSlug,
    )

    suspend fun recordQueueDrop(queueId: String, eventSlug: String): FieldOpEnvelope = append(
        type = FieldOpType.QUEUE_DROP,
        payloadJson = queueDropPayloadJson(queueId),
        eventSlug = eventSlug,
    )

    suspend fun recordFinishReassign(
        queueId: String,
        oldLogicalId: Int?,
        newLogicalId: Int,
        uid: String,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.FINISH_REASSIGN,
        payloadJson = finishReassignPayloadJson(queueId, oldLogicalId, newLogicalId, uid),
        eventSlug = eventSlug,
    )

    suspend fun recordCourseUpsert(
        name: String,
        previousName: String,
        controls: List<Int>,
        lengthMeters: Int?,
        climbMeters: Int?,
        legsJson: String,
        randomOrder: List<Boolean> = emptyList(),
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.COURSE_UPSERT,
        payloadJson = courseUpsertPayloadJson(
            name, previousName, controls, lengthMeters, climbMeters, legsJson, randomOrder,
        ),
        eventSlug = eventSlug,
    )

    suspend fun recordCourseDelete(name: String, eventSlug: String): FieldOpEnvelope = append(
        type = FieldOpType.COURSE_DELETE,
        payloadJson = courseDeletePayloadJson(name),
        eventSlug = eventSlug,
    )

    suspend fun recordExclusionUpsert(
        exclusionId: String,
        courseName: String,
        fromCp: Int,
        toCp: Int,
        maxLegSeconds: Int?,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.EXCLUSION_UPSERT,
        payloadJson = exclusionUpsertPayloadJson(
            exclusionId, courseName, fromCp, toCp, maxLegSeconds,
        ),
        eventSlug = eventSlug,
    )

    suspend fun recordExclusionDelete(
        exclusionId: String,
        courseName: String,
        fromCp: Int,
        toCp: Int,
        eventSlug: String,
    ): FieldOpEnvelope = append(
        type = FieldOpType.EXCLUSION_DELETE,
        payloadJson = exclusionDeletePayloadJson(exclusionId, courseName, fromCp, toCp),
        eventSlug = eventSlug,
    )

    /** Полная очистка журнала (новый старт / кнопка). device_id не трогаем; device_seq → 0. */
    suspend fun clearAll() {
        dao.clearOps()
        dao.clearMeta()
        dao.upsertMeta(FieldMetaEntity(FieldJournalDao.KEY_DEVICE_SEQ, "0"))
    }

    suspend fun opCount(): Long = dao.count()

    suspend fun listNewest(limit: Int = 200): List<FieldOpEntity> = dao.listNewest(limit)

    suspend fun listAllEnvelopes(): List<FieldOpEnvelope> =
        dao.listAll().map { it.toEnvelope() }

    /** Курсор: max device_seq по каждому устройству. */
    suspend fun cursors(): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        for (row in dao.listAll()) {
            val prev = out[row.deviceId] ?: 0L
            if (row.deviceSeq > prev) out[row.deviceId] = row.deviceSeq
        }
        return out
    }

    suspend fun opsAfter(cursors: Map<String, Long>): List<FieldOpEnvelope> {
        return dao.listAll()
            .asSequence()
            .filter { row ->
                val seen = cursors[row.deviceId] ?: 0L
                row.deviceSeq > seen
            }
            .map { it.toEnvelope() }
            .toList()
    }

    suspend fun ownOpsAfter(afterSeq: Long): List<FieldOpEnvelope> {
        val id = device.deviceId
        return dao.listAll()
            .asSequence()
            .filter { it.deviceId == id && it.deviceSeq > afterSeq }
            .map { it.toEnvelope() }
            .toList()
    }

    fun deviceId(): String = device.deviceId

    fun deviceName(): String = device.deviceName

    companion object {
        fun create(context: Context, dao: FieldJournalDao): FieldJournal =
            FieldJournal(dao, FieldDeviceStore(context))
    }
}

private fun FieldOpEntity.toEnvelope() = FieldOpEnvelope(
    schema = schemaVersion,
    opId = opId,
    deviceId = deviceId,
    deviceSeq = deviceSeq,
    eventSlug = eventSlug,
    clockMs = clockMs,
    actor = actor,
    type = type,
    payloadJson = payloadJson,
)

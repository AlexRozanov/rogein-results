package ru.rogein.chip.field

import ru.rogein.chip.parse.BlockOrder
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.parse.field.FieldOpEnvelope
import ru.rogein.chip.parse.field.FieldOpType
import ru.rogein.chip.parse.field.parseChipIssuePayload
import ru.rogein.chip.parse.field.parseChipReadPayload
import ru.rogein.chip.parse.field.parseCorrectionAddCpPayload
import ru.rogein.chip.parse.field.parseCorrectionAssignCoursePayload
import ru.rogein.chip.parse.field.parseCorrectionClearDnfPayload
import ru.rogein.chip.parse.field.parseCorrectionCopyPunchesPayload
import ru.rogein.chip.parse.field.parseCorrectionDeletePayload
import ru.rogein.chip.parse.field.parseCorrectionDnfPayload
import ru.rogein.chip.parse.field.parseCorrectionRemoveCpPayload
import ru.rogein.chip.parse.field.parseCourseDeletePayload
import ru.rogein.chip.parse.field.parseCourseUpsertPayload
import ru.rogein.chip.parse.field.parseExclusionDeletePayload
import ru.rogein.chip.parse.field.parseExclusionUpsertPayload
import ru.rogein.chip.parse.field.parseFinishReassignPayload
import ru.rogein.chip.parse.field.parsePersonCoursePayload
import ru.rogein.chip.parse.field.parsePersonDeletePayload
import ru.rogein.chip.parse.field.parsePersonTeamAssignPayload
import ru.rogein.chip.parse.field.parsePersonTeamJoinPayload
import ru.rogein.chip.parse.field.parsePersonTeamLeavePayload
import ru.rogein.chip.parse.field.parsePersonUpsertPayload
import ru.rogein.chip.parse.field.parseQueueDropPayload
import ru.rogein.chip.parse.field.punchFromDaySeconds
import ru.rogein.chip.queue.ChipQueueDao
import ru.rogein.chip.queue.EventStore
import ru.rogein.chip.queue.QueuedChipEntity
import ru.rogein.chip.startlist.StartListStore

/**
 * Материализует принятую операцию в локальные таблицы (без повторной записи в журнал).
 */
class FieldOpApplier(
    private val startList: StartListStore,
    private val queueDao: ChipQueueDao,
    private val event: EventStore,
) {
    suspend fun apply(op: FieldOpEnvelope) {
        when (op.type) {
            FieldOpType.CHIP_ISSUE -> applyChipIssue(op)
            FieldOpType.CHIP_READ -> applyChipRead(op)
            FieldOpType.PERSON_UPSERT -> {
                val p = parsePersonUpsertPayload(op.payloadJson)
                startList.applyPersonUpsert(
                    logicalId = p.logicalId,
                    name = p.name,
                    gender = p.gender,
                    birthDate = p.birthDate,
                    course = p.course,
                    courses = p.courses,
                )
            }
            FieldOpType.PERSON_DELETE -> {
                val p = parsePersonDeletePayload(op.payloadJson)
                startList.applyPersonDelete(p.logicalId)
            }
            FieldOpType.PERSON_COURSE -> {
                val p = parsePersonCoursePayload(op.payloadJson)
                startList.applyPersonCourse(p.logicalId, p.course, p.courses)
            }
            FieldOpType.PERSON_TEAM_JOIN -> {
                val p = parsePersonTeamJoinPayload(op.payloadJson)
                startList.applyTeamJoin(p.logicalId, p.otherLogicalId, p.teamId, p.droppedTeamId)
            }
            FieldOpType.PERSON_TEAM_LEAVE -> {
                val p = parsePersonTeamLeavePayload(op.payloadJson)
                startList.applyTeamLeave(p.logicalId)
            }
            FieldOpType.PERSON_TEAM_ASSIGN -> {
                val p = parsePersonTeamAssignPayload(op.payloadJson)
                startList.applyTeamAssign(p.logicalId, p.teamId)
            }
            FieldOpType.CORRECTION_ADD_CP -> {
                val p = parseCorrectionAddCpPayload(op.payloadJson)
                event.applyCorrectionAddCp(p.correctionId, p.logicalId, p.cp, p.course, p.time)
            }
            FieldOpType.CORRECTION_REMOVE_CP -> {
                val p = parseCorrectionRemoveCpPayload(op.payloadJson)
                event.applyCorrectionRemoveCp(p.correctionId, p.logicalId, p.cp, p.removeMode)
            }
            FieldOpType.CORRECTION_DNF -> {
                val p = parseCorrectionDnfPayload(op.payloadJson)
                event.applyCorrectionDnf(p.correctionId, p.logicalId)
            }
            FieldOpType.CORRECTION_CLEAR_DNF -> {
                val p = parseCorrectionClearDnfPayload(op.payloadJson)
                event.applyCorrectionClearDnf(p.logicalId)
            }
            FieldOpType.CORRECTION_COPY_PUNCHES -> {
                val p = parseCorrectionCopyPunchesPayload(op.payloadJson)
                event.applyCorrectionCopyPunches(p.correctionId, p.logicalId, p.fromLogicalId)
            }
            FieldOpType.CORRECTION_ASSIGN_COURSE -> {
                val p = parseCorrectionAssignCoursePayload(op.payloadJson)
                event.applyCorrectionAssignCourse(
                    p.correctionId,
                    p.logicalId,
                    p.fromCourse,
                    p.toCourse,
                    p.queueId,
                )
                if (p.queueId.isNotBlank() && p.toCourse.isNotBlank()) {
                    queueDao.updateFinishCourse(p.queueId, p.toCourse)
                }
            }
            FieldOpType.CORRECTION_DELETE -> {
                val p = parseCorrectionDeletePayload(op.payloadJson)
                event.applyCorrectionDelete(p.correctionId)
            }
            FieldOpType.EVENT_CONFIG -> event.applyEventConfig(op.payloadJson)
            FieldOpType.QUEUE_DROP -> {
                val p = parseQueueDropPayload(op.payloadJson)
                queueDao.deleteById(p.queueId)
            }
            FieldOpType.FINISH_REASSIGN -> applyFinishReassign(op)
            FieldOpType.COURSE_UPSERT -> {
                val p = parseCourseUpsertPayload(op.payloadJson)
                startList.applyCourseUpsert(
                    name = p.name,
                    previousName = p.previousName,
                    controls = p.controls,
                    lengthMeters = p.lengthMeters,
                    climbMeters = p.climbMeters,
                    legsJson = p.legsJson,
                    randomOrder = p.randomOrder,
                )
            }
            FieldOpType.COURSE_DELETE -> {
                val p = parseCourseDeletePayload(op.payloadJson)
                startList.applyCourseDelete(p.name)
            }
            FieldOpType.EXCLUSION_UPSERT -> {
                val p = parseExclusionUpsertPayload(op.payloadJson)
                event.applyExclusionUpsert(
                    p.exclusionId, p.courseName, p.fromCp, p.toCp, p.maxLegSeconds,
                )
            }
            FieldOpType.EXCLUSION_DELETE -> {
                val p = parseExclusionDeletePayload(op.payloadJson)
                event.applyExclusionDelete(p.exclusionId, p.courseName, p.fromCp, p.toCp)
            }
            else -> Unit
        }
        if (op.type != FieldOpType.EVENT_CONFIG) {
            runCatching { event.markLivePending() }
        }
    }

    private suspend fun applyFinishReassign(op: FieldOpEnvelope) {
        val p = parseFinishReassignPayload(op.payloadJson)
        val item = queueDao.byId(p.queueId) ?: return
        val person = startList.person(p.newLogicalId)
        val name = person?.name?.takeIf { it.isNotBlank() } ?: item.personName
        val course = item.courseName.ifBlank { person?.currentCourse.orEmpty() }
        queueDao.updateFinishIdentity(p.queueId, p.newLogicalId, name, course)
        startList.rebindIssuedUid(
            p.oldLogicalId ?: item.logicalId,
            p.newLogicalId,
            p.uid.ifBlank { item.uid },
        )
    }

    private suspend fun applyChipIssue(op: FieldOpEnvelope) {
        val payload = parseChipIssuePayload(op.payloadJson)
        startList.markIssued(
            id = payload.logicalId,
            course = payload.course,
            uid = payload.uid,
        )
    }

    private suspend fun applyChipRead(op: FieldOpEnvelope) {
        val payload = parseChipReadPayload(op.payloadJson)
        if (payload.queueId.isBlank()) return
        val punches = payload.punches.map { punchFromDaySeconds(it.cp, it.seconds) }
        val dump = ChipDump(
            uid = payload.uid,
            logicalId = payload.logicalId,
            clearedAt = null,
            punches = punches,
            blockOrder = BlockOrder.Hid,
            pointerEnd = null,
            blockCount = payload.blockCount ?: 0,
        )
        val existing = queueDao.byId(payload.queueId)
        if (existing != null && !payload.replace && existing.punchCount >= dump.punches.size) {
            return
        }
        val personName = payload.logicalId?.let { startList.person(it)?.name }.orEmpty()
            .ifBlank { existing?.personName.orEmpty() }
        val course = payload.courseHint.ifBlank { existing?.courseName.orEmpty() }
        queueDao.insert(
            QueuedChipEntity(
                id = payload.queueId,
                uid = dump.uid,
                logicalId = dump.logicalId,
                clearedAt = dump.clearedAt,
                punchCount = dump.punches.size,
                firstTime = dump.punches.firstOrNull()?.time,
                lastTime = dump.punches.lastOrNull()?.time,
                punchesJson = dump.punches.joinToString(prefix = "[", postfix = "]") { punch ->
                    """{"cp":${punch.cp},"time":"${punch.time}"}"""
                },
                rawHex = existing?.rawHex.orEmpty(),
                createdAt = existing?.createdAt
                    ?: payload.readAtMs.takeIf { it > 0L }
                    ?: op.clockMs,
                ackedAt = existing?.ackedAt,
                personName = personName,
                courseName = course,
            ),
        )
    }
}

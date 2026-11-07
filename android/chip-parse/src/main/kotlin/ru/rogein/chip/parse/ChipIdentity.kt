package ru.rogein.chip.parse

fun normalizeChipUid(raw: String?): String =
    raw.orEmpty().trim().uppercase().filter { it.isLetterOrDigit() }

data class FinishQueueRef(
    val id: String,
    val uid: String,
    val logicalId: Int?,
    val courseName: String,
)

enum class FinishEnqueueKind {
    Fresh,
    SameChip,
    OtherChipSameNumber,
}

fun classifyFinishEnqueue(
    uid: String,
    logicalId: Int?,
    courseName: String,
    existing: List<FinishQueueRef>,
): FinishEnqueueKind {
    val nuid = normalizeChipUid(uid)
    val course = courseName.trim()
    fun sameCourse(row: FinishQueueRef) = row.courseName.trim().equals(course, ignoreCase = true)
    if (nuid.isNotEmpty()) {
        if (existing.any { normalizeChipUid(it.uid) == nuid && sameCourse(it) }) {
            return FinishEnqueueKind.SameChip
        }
    } else if (logicalId != null) {
        if (existing.any { it.logicalId == logicalId && sameCourse(it) }) {
            return FinishEnqueueKind.SameChip
        }
    }
    if (logicalId != null && nuid.isNotEmpty()) {
        val other = existing.any { row ->
            row.logicalId == logicalId &&
                sameCourse(row) &&
                normalizeChipUid(row.uid) != nuid
        }
        if (other) return FinishEnqueueKind.OtherChipSameNumber
    }
    return FinishEnqueueKind.Fresh
}

fun numberConflictIds(rows: List<FinishQueueRef>): Set<String> {
    return rows
        .filter { it.logicalId != null }
        .groupBy { it.logicalId }
        .filter { (_, group) ->
            group.map { normalizeChipUid(it.uid) }.filter { it.isNotEmpty() }.toSet().size > 1
        }
        .values
        .flatten()
        .map { it.id }
        .toSet()
}

data class IssuedChipRef(
    val id: Int,
    val issued: Boolean,
    val issuedUid: String,
)

enum class IssueUidConflict {
    None,
    NumberBoundOtherChip,
    ChipBoundOtherNumber,
    NumberIssuedUnknownChip,
}

data class IssueUidConflictInfo(
    val kind: IssueUidConflict,
    val otherId: Int? = null,
    val boundUid: String = "",
)

fun classifyIssueUid(
    number: Int,
    chipUid: String,
    people: List<IssuedChipRef>,
): IssueUidConflictInfo {
    val nuid = normalizeChipUid(chipUid)
    if (nuid.isNotEmpty()) {
        val other = people.find { person ->
            person.id != number && normalizeChipUid(person.issuedUid) == nuid
        }
        if (other != null) {
            return IssueUidConflictInfo(IssueUidConflict.ChipBoundOtherNumber, other.id, other.issuedUid)
        }
    }
    val person = people.find { it.id == number } ?: return IssueUidConflictInfo(IssueUidConflict.None)
    val bound = normalizeChipUid(person.issuedUid)
    if (bound.isNotEmpty() && nuid.isNotEmpty() && bound != nuid) {
        return IssueUidConflictInfo(IssueUidConflict.NumberBoundOtherChip, person.id, person.issuedUid)
    }
    if (person.issued && bound.isEmpty() && nuid.isNotEmpty()) {
        return IssueUidConflictInfo(IssueUidConflict.NumberIssuedUnknownChip)
    }
    return IssueUidConflictInfo(IssueUidConflict.None)
}

fun canChangeFinishNumber(
    logicalId: Int?,
    uid: String,
    sameNumberCount: Int,
    issuedUid: String?,
): Boolean {
    if (logicalId == null) return false
    if (sameNumberCount >= 2) return true
    val bound = normalizeChipUid(issuedUid)
    val chip = normalizeChipUid(uid)
    return bound.isNotEmpty() && chip.isNotEmpty() && bound != chip
}

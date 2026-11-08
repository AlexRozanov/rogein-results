package ru.rogein.chip.parse

const val COPY_PUNCHES = "copy_punches"
const val TEAM_COPY_UID_PREFIX = "COPY"
const val DNF_UID_PREFIX = "DNF"

fun teamCopyUid(logicalId: Int): String = "$TEAM_COPY_UID_PREFIX$logicalId"

fun dnfUid(logicalId: Int): String = "$DNF_UID_PREFIX$logicalId"

fun isPrefixedUid(uid: String?, prefix: String): Boolean {
    val n = normalizeChipUid(uid)
    if (!n.startsWith(prefix)) return false
    val rest = n.removePrefix(prefix)
    return rest.isNotEmpty() && rest.all { it.isDigit() }
}

fun isTeamCopyUid(uid: String?): Boolean = isPrefixedUid(uid, TEAM_COPY_UID_PREFIX)

fun isDnfUid(uid: String?): Boolean = isPrefixedUid(uid, DNF_UID_PREFIX)

fun isSyntheticFinishUid(uid: String?): Boolean = isTeamCopyUid(uid) || isDnfUid(uid)

data class PunchCopyPerson(
    val logicalId: Int,
    val name: String,
    val teamId: Int,
)

data class PunchCopyFinish(
    val queueId: String,
    val logicalId: Int?,
    val punchCount: Int,
)

data class PunchCopyCandidate(
    val logicalId: Int,
    val name: String,
    val queueId: String?,
    val punchCount: Int,
) {
    val createNew: Boolean get() = queueId == null
}

fun punchCopySources(
    targetLogicalId: Int?,
    targetTeamId: Int,
    people: List<PunchCopyPerson>,
    finishes: List<PunchCopyFinish>,
): List<PunchCopyCandidate> {
    return punchCopyPeople(targetLogicalId, targetTeamId, people).mapNotNull { person ->
        val finish = finishes.find { it.logicalId == person.logicalId } ?: return@mapNotNull null
        if (finish.punchCount <= 0) return@mapNotNull null
        PunchCopyCandidate(person.logicalId, person.name, finish.queueId, finish.punchCount)
    }
}

fun punchCopyTargets(
    sourceLogicalId: Int?,
    sourceTeamId: Int,
    people: List<PunchCopyPerson>,
    finishes: List<PunchCopyFinish>,
): List<PunchCopyCandidate> {
    return punchCopyPeople(sourceLogicalId, sourceTeamId, people).map { person ->
        val finish = finishes.find { it.logicalId == person.logicalId }
        PunchCopyCandidate(
            logicalId = person.logicalId,
            name = person.name,
            queueId = finish?.queueId,
            punchCount = finish?.punchCount ?: 0,
        )
    }
}

fun punchCopyCandidateByNumber(
    logicalId: Int,
    skipLogicalId: Int?,
    people: List<PunchCopyPerson>,
    finishes: List<PunchCopyFinish>,
): PunchCopyCandidate? {
    if (logicalId <= 0 || logicalId == skipLogicalId) return null
    val person = people.find { it.logicalId == logicalId } ?: return null
    val finish = finishes.find { it.logicalId == person.logicalId }
    return PunchCopyCandidate(
        logicalId = person.logicalId,
        name = person.name,
        queueId = finish?.queueId,
        punchCount = finish?.punchCount ?: 0,
    )
}

private fun punchCopyPeople(
    selfLogicalId: Int?,
    teamId: Int,
    people: List<PunchCopyPerson>,
): List<PunchCopyPerson> {
    if (teamId <= 0) return emptyList()
    return people
        .filter { it.teamId == teamId && it.logicalId != selfLogicalId }
        .sortedBy { it.logicalId }
}

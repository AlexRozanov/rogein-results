package ru.rogein.chip.parse

enum class ProtocolRunStatus {
    NotStarted,
    OnCourse,
    Finished,
    DidNotFinish,
    ;

    val label: String
        get() = when (this) {
            NotStarted -> "не стартовал"
            OnCourse -> "на дистанции"
            Finished -> "финишировал"
            DidNotFinish -> "не финишировал"
        }
}

fun protocolRunStatus(
    issued: Boolean,
    issuedCourse: String,
    currentCourse: String,
    finishedCourses: List<String>,
): ProtocolRunStatus {
    val finishedKeys = finishedCourses.map { courseKey(it) }.toSet()
    if (issued) {
        val issuedKey = courseKey(issuedCourse.ifBlank { currentCourse })
        if (issuedKey.isEmpty()) {
            return if (finishedKeys.isEmpty()) ProtocolRunStatus.OnCourse else ProtocolRunStatus.Finished
        }
        if (issuedKey !in finishedKeys) return ProtocolRunStatus.OnCourse
    }
    if (finishedKeys.isNotEmpty()) return ProtocolRunStatus.Finished
    return ProtocolRunStatus.NotStarted
}

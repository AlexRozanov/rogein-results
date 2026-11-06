package ru.rogein.chip.parse

enum class RogaineSplitKind { Start, Control, Finish }

data class RogaineSplitRow(
    val kind: RogaineSplitKind,
    val seq: Int?,
    val cp: Int?,
    val seconds: Int,
    val legSeconds: Int?,
    val added: Boolean = false,
)

fun rogaineSplitRows(
    marks: List<OrientMark>,
    startCp: Int,
    finishCp: Int,
    fallbackStartSeconds: Int? = null,
): List<RogaineSplitRow> {
    val sorted = orientMarkOrder(marks)
    val startPunch = sorted.firstOrNull { it.cp == startCp }
    val finishPunch = sorted.lastOrNull { it.cp == finishCp }
    val startSeconds = startPunch?.seconds ?: fallbackStartSeconds
    val inner = sorted.filter { mark ->
        mark.cp != startCp && mark.cp != finishCp
    }
    val rows = ArrayList<RogaineSplitRow>(inner.size + 2)
    var previous = startSeconds
    if (startSeconds != null) {
        rows += RogaineSplitRow(
            kind = RogaineSplitKind.Start,
            seq = null,
            cp = startCp.takeIf { it > 0 },
            seconds = startSeconds,
            legSeconds = null,
            added = startPunch?.added == true,
        )
    }
    inner.forEachIndexed { index, mark ->
        val leg = previous?.let { punchLegSeconds(it, mark.seconds) }
        rows += RogaineSplitRow(
            kind = RogaineSplitKind.Control,
            seq = index + 1,
            cp = mark.cp,
            seconds = mark.seconds,
            legSeconds = leg,
            added = mark.added,
        )
        previous = mark.seconds
    }
    if (finishPunch != null) {
        val leg = previous?.let { punchLegSeconds(it, finishPunch.seconds) }
        rows += RogaineSplitRow(
            kind = RogaineSplitKind.Finish,
            seq = null,
            cp = finishCp,
            seconds = finishPunch.seconds,
            legSeconds = leg,
            added = finishPunch.added,
        )
    }
    return rows
}

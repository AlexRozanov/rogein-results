package ru.rogein.chip.parse

enum class TeamGroupPos {
    None,
    Start,
    Mid,
    End,
    Only,
}

fun teamGroupPos(teamId: Int, prevTeamId: Int, nextTeamId: Int): TeamGroupPos {
    if (teamId <= 0) return TeamGroupPos.None
    val samePrev = prevTeamId == teamId
    val sameNext = nextTeamId == teamId
    return when {
        !samePrev && !sameNext -> TeamGroupPos.Only
        !samePrev && sameNext -> TeamGroupPos.Start
        samePrev && sameNext -> TeamGroupPos.Mid
        else -> TeamGroupPos.End
    }
}

fun teamGroupPosAt(teamIds: List<Int>, index: Int): TeamGroupPos {
    val teamId = teamIds.getOrNull(index) ?: return TeamGroupPos.None
    return teamGroupPos(
        teamId,
        teamIds.getOrNull(index - 1) ?: 0,
        teamIds.getOrNull(index + 1) ?: 0,
    )
}

fun <T> sortByChipKeepingTeams(
    items: List<T>,
    chipId: (T) -> Int,
    teamId: (T) -> Int,
): List<T> {
    val teamMin = items
        .filter { teamId(it) > 0 }
        .groupBy { teamId(it) }
        .mapValues { (_, group) -> group.minOf { chipId(it) } }
    return items.sortedWith(
        compareBy<T> { item ->
            val tid = teamId(item)
            if (tid > 0) teamMin[tid] ?: chipId(item) else chipId(item)
        }
            .thenBy { teamId(it) }
            .thenBy { chipId(it) },
    )
}

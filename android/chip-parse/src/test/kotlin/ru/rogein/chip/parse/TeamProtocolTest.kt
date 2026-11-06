package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TeamProtocolTest {
    private data class P(val id: Int, val teamId: Int = 0)

    @Test
    fun individualsStayInChipOrder() {
        val rows = listOf(P(4), P(1), P(2))
        assertEquals(
            listOf(1, 2, 4),
            sortByChipKeepingTeams(rows, { it.id }, { it.teamId }).map { it.id },
        )
    }

    @Test
    fun teamSitsAtLowestChip() {
        val rows = listOf(P(1), P(2), P(4), P(3, 7), P(103, 7))
        assertEquals(
            listOf(1, 2, 3, 103, 4),
            sortByChipKeepingTeams(rows, { it.id }, { it.teamId }).map { it.id },
        )
    }

    @Test
    fun twoTeamsKeepOwnBlocks() {
        val rows = listOf(P(10, 2), P(1, 1), P(11, 2), P(2, 1), P(3))
        assertEquals(
            listOf(1, 2, 3, 10, 11),
            sortByChipKeepingTeams(rows, { it.id }, { it.teamId }).map { it.id },
        )
    }

    @Test
    fun bracePositionsConnectConsecutiveTeamMembers() {
        val ids = listOf(0, 7, 7, 7, 0, 9)
        assertEquals(TeamGroupPos.None, teamGroupPosAt(ids, 0))
        assertEquals(TeamGroupPos.Start, teamGroupPosAt(ids, 1))
        assertEquals(TeamGroupPos.Mid, teamGroupPosAt(ids, 2))
        assertEquals(TeamGroupPos.End, teamGroupPosAt(ids, 3))
        assertEquals(TeamGroupPos.None, teamGroupPosAt(ids, 4))
        assertEquals(TeamGroupPos.Only, teamGroupPosAt(ids, 5))
    }
}

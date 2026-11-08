package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamPunchCopyTest {
    private val people = listOf(
        PunchCopyPerson(10, "Анна", 1),
        PunchCopyPerson(11, "Борис", 1),
        PunchCopyPerson(12, "Вика", 2),
        PunchCopyPerson(13, "Глеб", 0),
    )
    private val finishes = listOf(
        PunchCopyFinish("q10", 10, 8),
        PunchCopyFinish("q11", 11, 0),
        PunchCopyFinish("q12", 12, 3),
    )

    @Test
    fun copyUidIsSyntheticAndNotARealChip() {
        assertEquals("COPY11", teamCopyUid(11))
        assertTrue(isTeamCopyUid("COPY11"))
        assertTrue(isTeamCopyUid("copy11"))
        assertFalse(isTeamCopyUid("04AABB11"))
        assertFalse(isTeamCopyUid("COPY"))
        assertFalse(isTeamCopyUid(""))
        assertEquals("DNF11", dnfUid(11))
        assertTrue(isDnfUid("DNF11"))
        assertTrue(isSyntheticFinishUid("DNF11"))
        assertFalse(isDnfUid("COPY11"))
    }

    @Test
    fun sourcesAreTeammatesWithPunches() {
        val sources = punchCopySources(11, 1, people, finishes)
        assertEquals(listOf(10), sources.map { it.logicalId })
        assertEquals("q10", sources.single().queueId)
        assertEquals(8, sources.single().punchCount)
        assertFalse(sources.single().createNew)
    }

    @Test
    fun emptyTeammateIsNotASource() {
        val sources = punchCopySources(10, 1, people, finishes)
        assertTrue(sources.isEmpty())
    }

    @Test
    fun targetsIncludeTeammateWithoutFinish() {
        val targets = punchCopyTargets(10, 1, people, finishes)
        assertEquals(listOf(11), targets.map { it.logicalId })
        assertEquals("q11", targets.single().queueId)
        assertEquals(0, targets.single().punchCount)
        assertFalse(targets.single().createNew)
    }

    @Test
    fun soloHasNoTeamTargets() {
        assertTrue(punchCopyTargets(13, 0, people, finishes).isEmpty())
        assertTrue(punchCopySources(13, 0, people, finishes).isEmpty())
    }

    @Test
    fun numberPicksAnyoneFromStartList() {
        val missing = punchCopyCandidateByNumber(13, 10, people, finishes)
        assertEquals(13, missing?.logicalId)
        assertTrue(missing?.createNew == true)
        assertNull(punchCopyCandidateByNumber(10, 10, people, finishes))
        assertNull(punchCopyCandidateByNumber(99, 10, people, finishes))
    }
}

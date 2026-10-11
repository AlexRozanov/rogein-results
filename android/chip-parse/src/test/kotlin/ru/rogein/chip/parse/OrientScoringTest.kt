package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OrientScoringTest {
    @Test
    fun subsequenceAllowsExtrasAndAdds() {
        assertEquals(5, courseSubsequenceTaken(listOf(1, 1, 2, 4, 3, 4, 5), listOf(1, 2, 3, 4, 5)))
        assertEquals(4, courseSubsequenceTaken(listOf(241, 240), listOf(241, 250, 250, 240), mapOf(250 to 2)))
    }

    @Test
    fun filterDropsRemoved() {
        assertEquals(listOf(31, 33), filterCourseCps(listOf(31, 32, 33), setOf(32)))
    }

    @Test
    fun legsIncludeStartFinishAndRepeats() {
        assertEquals(
            listOf(241 to 58, 58 to 68, 68 to 58, 58 to 250),
            orientCourseLegs(listOf(58, 68, 58), 241, 250, emptySet()),
        )
    }

    @Test
    fun exclusionSubtractsForwardCrossings() {
        val window = listOf(
            mark(241, 10, 0),
            mark(32, 10, 10),
            mark(99, 10, 12),
            mark(45, 10, 20),
            mark(32, 10, 25),
            mark(45, 10, 40),
            mark(240, 11, 0),
        )
        val elapsed = applyOrientExclusionRules(
            window,
            listOf(32, 45),
            241,
            240,
            null,
            listOf(OrientExclusion(null, 32, 0)),
            3600,
        )
        assertEquals(2100, elapsed)
    }

    @Test
    fun completeCourseIsOk() {
        val marks = listOf(
            mark(241, 10, 0),
            mark(31, 10, 5),
            mark(32, 10, 10),
            mark(240, 10, 20),
        )
        val calc = calculateOrientResult(
            marks,
            OrientSettings(241, 240, 120),
            "D1",
            mapOf("d1" to listOf(31, 32)),
            emptySet(),
            emptyList(),
            emptyMap(),
        )
        assertEquals(STATUS_OK, calc.status)
        assertEquals(2, calc.taken)
        assertEquals(20 * 60, calc.elapsedSeconds)
    }

    @Test
    fun missingControlIsDsq() {
        val marks = listOf(
            mark(241, 10, 0),
            mark(31, 10, 5),
            mark(240, 10, 20),
        )
        val calc = calculateOrientResult(
            marks,
            OrientSettings(),
            "D1",
            mapOf("d1" to listOf(31, 32)),
            emptySet(),
            emptyList(),
            emptyMap(),
        )
        assertEquals(STATUS_DQ, calc.status)
        assertEquals(true, calc.diagnostics.contains("course_incomplete"))
    }

    private fun mark(cp: Int, hour: Int, minute: Int) =
        OrientMark(cp, hour * 3600 + minute * 60)
}

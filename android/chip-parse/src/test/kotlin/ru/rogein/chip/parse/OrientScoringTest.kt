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
    fun courseProgressCountsRepeatMissesSeparately() {
        val progress = courseProgress(listOf(241, 240), listOf(241, 250, 250, 240))
        assertEquals(listOf(true, false, false, true), progress.map { it.taken })
        assertEquals(listOf(250 to 2), addableCourseCps(progress))
    }

    @Test
    fun courseProgressOneRepeatTakenFromChip() {
        val progress = courseProgress(listOf(241, 250, 240), listOf(241, 250, 250, 240))
        assertEquals(listOf(true, true, false, true), progress.map { it.taken })
        assertEquals(listOf(250 to 1), addableCourseCps(progress))
    }

    @Test
    fun classifyMarksExtraAndOutOfOrder() {
        val marks = listOf(
            mark(241, 10, 0),
            mark(99, 10, 5),
            mark(32, 10, 8),
            mark(31, 10, 10),
            mark(240, 10, 20),
        )
        val classified = classifyActualPunches(marks, listOf(31, 32), 241, 240)
        assertEquals(
            listOf(
                PunchKind.Start,
                PunchKind.Extra,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
            ),
            classified.map { it.kind },
        )
        assertEquals(listOf(false, false, true, true, true), classified.map { it.wrong })
    }

    @Test
    fun orientOrderRequiresSubsequenceNotJustPresence() {
        val course = listOf(241, 30, 35, 67, 78, 240)
        fun run(cps: List<Int>) = calculateOrientResult(
            cps.mapIndexed { i, cp -> mark(cp, 10, i) },
            OrientSettings(241, 240, 120),
            "D1",
            mapOf("d1" to course),
            emptySet(),
            emptyList(),
            emptyMap(),
        )
        assertEquals(STATUS_DQ, run(listOf(241, 35, 67, 78, 240)).status)
        assertEquals(STATUS_DQ, run(listOf(241, 30, 67, 35, 78, 240)).status)
        assertEquals(STATUS_OK, run(listOf(241, 30, 35, 30, 67, 78, 240)).status)
        assertEquals(STATUS_OK, run(listOf(241, 30, 35, 67, 67, 30, 78, 240)).status)
        assertEquals(STATUS_OK, run(listOf(241, 30, 35, 67, 78, 240)).status)
    }

    @Test
    fun swappedFirstControlsAreDsqOnRoschaD1() {
        val course = listOf(
            36, 31, 33, 32, 34, 59, 58, 57, 56, 39, 55, 54, 42, 46, 50,
            51, 52, 53, 49, 48, 44, 60, 45, 43, 40, 41, 47, 37, 38,
        )
        fun run(inner: List<Int>) = calculateOrientResult(
            (listOf(241) + inner + listOf(240)).mapIndexed { i, cp -> mark(cp, 10, i) },
            OrientSettings(241, 240, 120),
            "D1",
            mapOf("d1" to course),
            emptySet(),
            emptyList(),
            emptyMap(),
        )
        assertEquals(STATUS_OK, run(course).status)
        assertEquals(STATUS_DQ, run(listOf(31, 36) + course.drop(2)).status)
    }

    @Test
    fun classifyMarksKeepsAllowedExtrasAndFlagsEarlyCourseCps() {
        val course = listOf(241, 30, 35, 67, 78, 240)
        fun kinds(cps: List<Int>) = classifyActualPunches(
            cps.mapIndexed { i, cp -> mark(cp, 10, i) },
            course,
            241,
            240,
        ).map { it.kind }

        assertEquals(
            listOf(
                PunchKind.Start,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
            ),
            kinds(listOf(241, 35, 67, 78, 240)),
        )
        assertEquals(
            listOf(
                PunchKind.Start,
                PunchKind.Ok,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
                PunchKind.OutOfOrder,
            ),
            kinds(listOf(241, 30, 67, 35, 78, 240)),
        )
        assertEquals(
            listOf(
                PunchKind.Start,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Finish,
            ),
            kinds(listOf(241, 30, 35, 30, 67, 78, 240)),
        )
        assertEquals(
            listOf(
                PunchKind.Start,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Finish,
            ),
            kinds(listOf(241, 30, 35, 67, 67, 30, 78, 240)),
        )
    }

    @Test
    fun courseProgressAddCoversOneRepeat() {
        val progress = courseProgress(listOf(241, 240), listOf(241, 250, 250, 240), mapOf(250 to 1))
        assertEquals(listOf(true, true, false, true), progress.map { it.taken })
        assertEquals(true, progress[1].added)
        assertEquals(listOf(250 to 1), addableCourseCps(progress))
    }

    @Test
    fun addCpInsertsAtFirstCorrectPosition() {
        val course = listOf(36, 31, 44, 56)
        val punches = listOf(31, 36, 44, 45)
        val expanded = applyAddedCps(
            punches.mapIndexed { i, cp -> mark(cp, 10, i) },
            course,
            mapOf(31 to 1),
        )
        assertEquals(listOf(31, 36, 31, 44, 45), expanded.map { it.cp })
        assertEquals(listOf(false, false, true, false, false), expanded.map { it.added })
    }

    @Test
    fun addCpAfterEarlyWrongOrderIsOkWhenCourseComplete() {
        val marks = listOf(
            mark(241, 10, 0),
            mark(31, 10, 5),
            mark(36, 10, 6),
            mark(44, 10, 8),
            mark(240, 10, 20),
        )
        val without = calculateOrientResult(
            marks,
            OrientSettings(241, 240, 120),
            "D1",
            mapOf("d1" to listOf(36, 31, 44)),
            emptySet(),
            emptyList(),
            emptyMap(),
        )
        assertEquals(STATUS_DQ, without.status)
        val withAdd = calculateOrientResult(
            marks,
            OrientSettings(241, 240, 120),
            "D1",
            mapOf("d1" to listOf(36, 31, 44)),
            emptySet(),
            emptyList(),
            mapOf(31 to 1),
        )
        assertEquals(STATUS_OK, withAdd.status)
        assertEquals(3, withAdd.taken)
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

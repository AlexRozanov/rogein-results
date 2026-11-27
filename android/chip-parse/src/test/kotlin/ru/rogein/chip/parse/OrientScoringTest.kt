package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrientScoringTest {
    @Test
    fun protocolStatusRankPutsDnfAfterDq() {
        assertEquals(0, protocolStatusRank(STATUS_OK))
        assertEquals(1, protocolStatusRank(STATUS_DQ))
        assertEquals(2, protocolStatusRank(STATUS_DNF))
        assertEquals(3, protocolStatusRank(STATUS_ERR))
        assertEquals(4, protocolStatusRank(STATUS_DNS))
    }

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
    fun legsKeepClusterEntryAndExitSkipInside() {
        val controls = listOf(31, 32, 33, 34, 35, 36)
        val random = listOf(0, 0, 1, 1, 1, 0)
        assertEquals(
            listOf(241 to 31, 31 to 32, 32 to 33, 35 to 36, 36 to 240),
            orientCourseLegs(controls, 241, 240, emptySet(), random),
        )
        assertEquals(
            "32 → [33·34·35]",
            formatOrientLegLabel(32, 33, controls, 241, 240, random),
        )
        assertEquals(
            "[33·34·35] → 36",
            formatOrientLegLabel(35, 36, controls, 241, 240, random),
        )
    }

    @Test
    fun adjacentClustersAddLegBetweenThem() {
        val controls = (71..79).toList()
        val groups = listOf(0, 0, 0, 1, 1, 1, 2, 2, 0)
        assertEquals(
            listOf(241 to 71, 71 to 72, 72 to 73, 73 to 74, 76 to 77, 78 to 79, 79 to 240),
            orientCourseLegs(controls, 241, 240, emptySet(), groups),
        )
        assertEquals(
            "[74·75·76] → [77·78]",
            formatOrientLegLabel(76, 77, controls, 241, 240, groups),
        )
        val (from, to) = exclusionEndpointSets(75, 78, controls, 241, 240, groups)
        assertEquals(setOf(74, 75, 76), from)
        assertEquals(setOf(77, 78), to)
    }

    @Test
    fun adjacentClustersEarlyPunchOfSecondClusterIsOutOfOrder() {
        val controls = (71..79).toList()
        val groups = listOf(0, 0, 0, 1, 1, 1, 2, 2, 0)
        val ok = listOf(241, 71, 72, 73, 75, 74, 76, 78, 77, 79, 240)
            .mapIndexed { i, cp -> mark(cp, 10, i) }
        assertTrue(classifyActualPunches(ok, controls, 241, 240, groups).none { it.wrong })
        val early = listOf(241, 71, 72, 73, 74, 75, 77, 76, 78, 79, 240)
            .mapIndexed { i, cp -> mark(cp, 10, i) }
        val kinds = classifyActualPunches(early, controls, 241, 240, groups)
        assertTrue(kinds.first { it.mark.cp == 77 }.wrong)
        // Early 77 skipped for matching; first cluster finishes; no fresh 77 → 7/9.
        assertEquals(
            7,
            courseSubsequenceTaken(early.map { it.cp }.filter { it != 241 && it != 240 }, controls, randomOrder = groups),
        )
    }

    @Test
    fun exclusionIntoClusterUsesFirstClusterPunch() {
        val controls = listOf(31, 32, 33, 34)
        val random = listOf(0, 1, 1, 0)
        val window = listOf(
            mark(241, 10, 0),
            mark(31, 10, 10),
            mark(33, 10, 20),
            mark(32, 10, 25),
            mark(34, 10, 40),
            mark(240, 11, 0),
        )
        // 31 → гроздь: 10:10→10:20 = 600s (вход на 33, не на 32)
        val elapsed = applyOrientExclusionRules(
            window,
            controls,
            241,
            240,
            null,
            listOf(OrientExclusion(null, 31, 32)),
            3600,
            random,
        )
        assertEquals(3000, elapsed)
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
    fun courseProgressClusterAnyOrderFullyTaken() {
        val required = listOf(31, 32, 33, 34)
        val random = listOf(0, 1, 1, 0)
        // В грозди порядок 33→32, не как в списке.
        val punches = listOf(241, 31, 33, 32, 34, 240)
        val progress = courseProgress(punches, required, randomOrder = random)
        assertTrue(progress.all { it.taken })
        assertEquals(emptyList<Pair<Int, Int>>(), addableCourseCps(progress))
    }

    @Test
    fun classifyClusterAnyOrderNotWrong() {
        val marks = listOf(
            mark(241, 10, 0),
            mark(31, 10, 5),
            mark(33, 10, 8),
            mark(32, 10, 10),
            mark(34, 10, 15),
            mark(240, 10, 20),
        )
        val classified = classifyActualPunches(
            marks,
            listOf(31, 32, 33, 34),
            241,
            240,
            listOf(0, 1, 1, 0),
        )
        assertTrue(classified.none { it.wrong })
        assertEquals(
            listOf(
                PunchKind.Start,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Ok,
                PunchKind.Finish,
            ),
            classified.map { it.kind },
        )
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
    fun addCpCoversMissingCpInsideClusterAnyOrder() {
        // 71→72→[74 75 76]→79, punches took 76 then 74, missing 75 → add 75
        val controls = listOf(71, 72, 74, 75, 76, 79)
        val groups = listOf(0, 0, 1, 1, 1, 0)
        val marks = listOf(
            mark(241, 10, 0),
            mark(71, 10, 1),
            mark(72, 10, 2),
            mark(76, 10, 3),
            mark(74, 10, 4),
            mark(79, 10, 5),
            mark(240, 10, 20),
        )
        val without = calculateOrientResult(
            marks,
            OrientSettings(241, 240, 120),
            "D1",
            mapOf("d1" to controls),
            emptySet(),
            emptyList(),
            emptyMap(),
            courseRandomOrder = mapOf("d1" to groups),
        )
        assertEquals(STATUS_DQ, without.status)
        val withAdd = calculateOrientResult(
            marks,
            OrientSettings(241, 240, 120),
            "D1",
            mapOf("d1" to controls),
            emptySet(),
            emptyList(),
            mapOf(75 to 1),
            courseRandomOrder = mapOf("d1" to groups),
        )
        assertEquals(STATUS_OK, withAdd.status)
        assertEquals(6, withAdd.taken)
        val expanded = applyAddedCps(
            marks,
            controls,
            mapOf(75 to 1),
            241,
            240,
            emptyMap(),
            groups,
        )
        assertTrue(expanded.any { it.cp == 75 && it.added })
        assertEquals(
            6,
            courseSubsequenceTaken(expanded.map { it.cp }, controls, randomOrder = groups),
        )
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

    @Test
    fun missingStartWithoutAddIsDns() {
        val marks = listOf(
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
        assertEquals(STATUS_DNS, calc.status)
        assertEquals(true, calc.diagnostics.contains("start_missing"))
    }

    @Test
    fun missingStartWithManualTimeIsOk() {
        val marks = listOf(
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
            mapOf(241 to 1),
            mapOf(241 to 10 * 3600),
        )
        assertEquals(STATUS_OK, calc.status)
        assertEquals(2, calc.taken)
        assertEquals(20 * 60, calc.elapsedSeconds)
    }

    @Test
    fun uniqueCourseCopyNameAddsCopySuffix() {
        assertEquals("D1 (копия)", uniqueCourseCopyName("D1", listOf("D1", "D2")))
        assertEquals("D1 (копия 2)", uniqueCourseCopyName("D1", listOf("D1", "D1 (копия)")))
        assertEquals("D1 (копия 3)", uniqueCourseCopyName("D1", listOf("D1", "D1 (копия)", "D1 (копия 2)")))
    }

    private fun mark(cp: Int, hour: Int, minute: Int) =
        OrientMark(cp, hour * 3600 + minute * 60)
}

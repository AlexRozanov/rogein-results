package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RogaineScoringTest {
    private val settings = RogaineSettings(
        startCp = 241,
        finishCp = 240,
        controlMinutes = 60,
        penaltyPerMinute = 1,
        dqMinutes = 15,
        startMode = "station",
    )

    @Test
    fun uniqueControlsScoreCpDiv10() {
        val calc = calculateRogaineResult(
            listOf(
                OrientMark(241, 10 * 3600),
                OrientMark(31, 10 * 3600 + 300),
                OrientMark(31, 10 * 3600 + 400),
                OrientMark(42, 10 * 3600 + 800),
                OrientMark(240, 10 * 3600 + 1200),
            ),
            settings,
        )
        assertEquals(STATUS_OK, calc.status)
        assertEquals(2, calc.taken)
        assertEquals(3 + 4, calc.pointsRaw)
        assertEquals(0, calc.penaltyPoints)
        assertEquals(7, calc.pointsFinal)
        assertEquals(1200, calc.elapsedSeconds)
    }

    @Test
    fun overtimeAddsPenaltyAndDqAfterLimit() {
        val late = calculateRogaineResult(
            listOf(
                OrientMark(241, 10 * 3600),
                OrientMark(51, 10 * 3600 + 90),
                OrientMark(240, 10 * 3600 + 61 * 60),
            ),
            settings,
        )
        assertEquals(STATUS_OK, late.status)
        assertEquals(5, late.pointsRaw)
        assertEquals(1, late.penaltyMinutes)
        assertEquals(4, late.pointsFinal)

        val dq = calculateRogaineResult(
            listOf(
                OrientMark(241, 10 * 3600),
                OrientMark(51, 10 * 3600 + 90),
                OrientMark(240, 10 * 3600 + 76 * 60),
            ),
            settings,
        )
        assertEquals(STATUS_DQ, dq.status)
    }

    @Test
    fun timeModeUsesConfiguredStartClock() {
        val calc = calculateRogaineResult(
            listOf(
                OrientMark(31, 10 * 3600 + 120),
                OrientMark(240, 10 * 3600 + 600),
            ),
            settings.copy(startMode = "time", startCp = 0, competitionStartTime = "10:00:00"),
        )
        assertEquals(STATUS_OK, calc.status)
        assertEquals(600, calc.elapsedSeconds)
        assertEquals(3, calc.pointsRaw)
    }

    @Test
    fun missingStartIsError() {
        val calc = calculateRogaineResult(
            listOf(OrientMark(31, 100), OrientMark(240, 200)),
            settings,
        )
        assertEquals(STATUS_ERR, calc.status)
        assertTrue(calc.diagnostics.contains("start_missing"))
    }

    @Test
    fun randomTestSequenceHasStartFinishAndUniqueControls() {
        val seq = TestCoursePlan.randomRogaineSequence(241, 240, count = 5, random = kotlin.random.Random(1))
        assertEquals(241, seq.first())
        assertEquals(240, seq.last())
        assertEquals(7, seq.size)
        assertEquals(seq.drop(1).dropLast(1).toSet().size, seq.size - 2)
    }

    @Test
    fun addedCpGivesPointsWithoutChangingTime() {
        val marks = listOf(
            OrientMark(241, 10 * 3600),
            OrientMark(31, 10 * 3600 + 300),
            OrientMark(240, 10 * 3600 + 1200),
        )
        val base = calculateRogaineResult(marks, settings)
        val added = calculateRogaineResult(marks, settings, addedCps = setOf(51))
        assertEquals(3, base.pointsRaw)
        assertEquals(8, added.pointsRaw)
        assertEquals(base.elapsedSeconds, added.elapsedSeconds)
    }

    @Test
    fun pointsOnlyRemoveKeepsElapsed() {
        val marks = listOf(
            OrientMark(241, 10 * 3600),
            OrientMark(31, 10 * 3600 + 15 * 60),
            OrientMark(42, 10 * 3600 + 45 * 60),
            OrientMark(240, 11 * 3600),
        )
        val calc = calculateRogaineResult(marks, settings, removedCps = setOf(31))
        assertEquals(4, calc.pointsRaw)
        assertEquals(3600, calc.elapsedSeconds)
    }

    @Test
    fun removeLegsSubtractsAdjacentSplits() {
        val marks = listOf(
            OrientMark(241, 10 * 3600),
            OrientMark(31, 10 * 3600 + 15 * 60),
            OrientMark(42, 10 * 3600 + 45 * 60),
            OrientMark(240, 11 * 3600),
        )
        val calc = calculateRogaineResult(
            marks,
            settings,
            removedCps = setOf(31),
            removeLegsCps = setOf(31),
        )
        assertEquals(4, calc.pointsRaw)
        assertEquals(900, calc.elapsedSeconds)
    }

    @Test
    fun formatTypeRulesKeepAllowedTypesAndMaxCount() {
        val marks = listOf(
            OrientMark(241, 10 * 3600),
            OrientMark(31, 10 * 3600 + 60),
            OrientMark(51, 10 * 3600 + 120),
            OrientMark(32, 10 * 3600 + 180),
            OrientMark(240, 10 * 3600 + 600),
        )
        val calc = calculateRogaineResult(
            marks,
            settings,
            cpTypeByNumber = mapOf(31 to "обычный", 32 to "обычный", 51 to "бонус"),
            cpTypeRules = listOf(RogaineCpTypeRule("обычный", maxCount = 1)),
        )
        assertEquals(3, calc.pointsRaw)
        assertEquals(1, calc.taken)
    }

    @Test
    fun assignPlacesByPointsThenTime() {
        val a = OrientCalc(STATUS_OK, 2, 1000, emptyList(), 10, 0, 10)
        val b = OrientCalc(STATUS_OK, 2, 800, emptyList(), 10, 0, 10)
        val c = OrientCalc(STATUS_OK, 1, 700, emptyList(), 8, 0, 8)
        val places = assignRogainePlaces(listOf(a, b, c))
        assertEquals(2, places[0])
        assertEquals(1, places[1])
        assertEquals(3, places[2])
    }

    @Test
    fun teamSharesPlaceFromBestMember() {
        val stronger = OrientCalc(STATUS_OK, 2, 1000, emptyList(), 10, 0, 10)
        val weaker = OrientCalc(STATUS_OK, 2, 1200, emptyList(), 8, 0, 8)
        val solo = OrientCalc(STATUS_OK, 1, 900, emptyList(), 9, 0, 9)
        val places = assignRogaineTeamPlaces(listOf(stronger, weaker, solo), listOf(1, 1, 0))
        assertEquals(1, places[0])
        assertEquals(1, places[1])
        assertEquals(2, places[2])
    }
}

package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StationCourseCheckTest {
    @Test
    fun acceptsStartFinishAndEveryControlWithTimes() {
        val report = StationCourseCheck.check(
            controls = listOf(31, 32),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 0, 15),
                punch(32, 10, 0, 40),
                punch(CP_FINISH, 10, 1, 0),
            ),
        )
        assertTrue(report.ok)
        assertNull(report.duplicateNote)
        assertEquals("Старт  10:00:00", report.rows[0].text)
        assertEquals("КП 31  10:00:15", report.rows[1].text)
        assertEquals("КП 32  10:00:40", report.rows[2].text)
        assertEquals("Финиш  10:01:00", report.rows[3].text)
    }

    @Test
    fun reportsMissingStartFinishAndControl() {
        val report = StationCourseCheck.check(
            controls = listOf(31, 32),
            punches = listOf(punch(31, 10, 1, 0)),
        )
        assertFalse(report.ok)
        assertTrue(report.rows.any { it.mark == StationCheckMark.Missing && it.text == "Нет: Старт" })
        assertTrue(report.rows.any { it.mark == StationCheckMark.Missing && it.text == "Нет: КП 32" })
        assertTrue(report.rows.any { it.mark == StationCheckMark.Missing && it.text == "Нет: Финиш" })
    }

    @Test
    fun repeatedLogicalNumberIsARemarkNotAnError() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 9, 0, 0),
                punch(31, 9, 0, 10),
                punch(31, 9, 0, 25),
                punch(CP_FINISH, 9, 0, 40),
            ),
        )
        assertTrue(report.ok)
        assertTrue(report.attention)
        val repeat = report.rows.single { it.mark == StationCheckMark.Repeat }
        assertEquals("КП 31 ×2  09:00:10, 09:00:25", repeat.text)
        assertEquals(StationCourseCheck.DUPLICATE_NOTE, report.duplicateNote)
    }

    @Test
    fun courseThatVisitsOneControlTwiceNeedsTwoPunches() {
        val once = StationCourseCheck.check(
            controls = listOf(31, 31),
            punches = listOf(
                punch(CP_START, 8, 0, 0),
                punch(31, 8, 1, 0),
                punch(CP_FINISH, 8, 2, 0),
            ),
        )
        assertFalse(once.ok)
        assertTrue(once.rows.any { it.text.startsWith("КП 31: нужно 2") })

        val twice = StationCourseCheck.check(
            controls = listOf(31, 31),
            punches = listOf(
                punch(CP_START, 8, 0, 0),
                punch(31, 8, 0, 20),
                punch(31, 8, 0, 40),
                punch(CP_FINISH, 8, 1, 0),
            ),
        )
        assertTrue(twice.ok)
        assertNull(twice.duplicateNote)
    }

    @Test
    fun backwardClockIsAPossibleError() {
        val report = StationCourseCheck.check(
            controls = listOf(57),
            punches = listOf(
                punch(CP_START, 12, 27, 33),
                punch(85, 16, 21, 11),
                punch(57, 6, 57, 26),
                punch(CP_FINISH, 16, 42, 52),
            ),
        )
        assertFalse(report.ok)
        val clock = report.rows.single { it.mark == StationCheckMark.Clock && it.text.contains("раньше") }
        assertTrue(clock.text.contains("КП 57"))
        assertTrue(clock.text.contains("06:57:26"))
        assertTrue(clock.text.contains("КП 85"))
        assertTrue(clock.text.contains("9 ч"))
    }

    @Test
    fun gapLongerThanAMinuteIsAWarning() {
        val report = StationCourseCheck.check(
            controls = listOf(31, 32),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 0, 30),
                punch(32, 10, 2, 5),
                punch(CP_FINISH, 10, 2, 40),
            ),
        )
        assertFalse(report.ok)
        val gap = report.rows.single { it.mark == StationCheckMark.Clock }
        assertTrue(gap.text.contains("Интервал больше минуты"))
        assertTrue(gap.text.contains("КП 32"))
        assertTrue(gap.text.contains("10:02:05"))
        assertTrue(gap.text.contains("КП 31"))
    }

    @Test
    fun gapOfOneMinuteIsAllowed() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 1, 0),
                punch(CP_FINISH, 10, 2, 0),
            ),
        )
        assertTrue(report.ok)
        assertTrue(report.rows.none { it.mark == StationCheckMark.Clock })
    }

    @Test
    fun smallClockDriftIsNotAnError() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 0, 20),
                punch(CP_FINISH, 10, 0, 5),
            ),
        )
        assertTrue(report.ok)
        assertTrue(report.rows.none { it.mark == StationCheckMark.Clock })
    }

    @Test
    fun clearStationAmongPunchesIsAnError() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 0, 15),
                punch(CP_CLEAR, 10, 0, 30),
                punch(CP_FINISH, 10, 0, 45),
            ),
            nowSeconds = 10 * 3600 + 50,
        )
        assertFalse(report.ok)
        val clear = report.rows.single { it.mark == StationCheckMark.Extra && it.text.contains("243") }
        assertTrue(clear.text.contains("10:00:30"))
    }

    @Test
    fun threeDifferentControlsAtOneSecondMeansTheClockIsStopped() {
        val report = StationCourseCheck.check(
            controls = listOf(31, 32),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 0, 0),
                punch(32, 10, 0, 0),
                punch(CP_FINISH, 10, 0, 20),
            ),
            nowSeconds = 10 * 3600 + 30,
        )
        assertFalse(report.ok)
        val frozen = report.rows.single { it.text.contains("Одинаковое время") }
        assertTrue(frozen.text.contains("старт"))
        assertTrue(frozen.text.contains("КП 31"))
        assertTrue(frozen.text.contains("КП 32"))
    }

    @Test
    fun twoControlsInTheSameSecondAreAllowed() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 0, 0),
                punch(CP_FINISH, 10, 0, 20),
            ),
            nowSeconds = 10 * 3600 + 30,
        )
        assertTrue(report.ok)
        assertTrue(report.rows.none { it.text.contains("Одинаковое время") })
    }

    @Test
    fun punchesFarFromThePhoneAreAShift() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 6, 57, 0),
                punch(31, 6, 57, 20),
                punch(CP_FINISH, 6, 57, 40),
            ),
            nowSeconds = 16 * 3600 + 21 * 60,
            clearedAt = "16:20:40",
        )
        assertFalse(report.ok)
        val shift = report.rows.single { it.text.contains("времени телефона") }
        assertTrue(shift.text.contains("финиш"))
        assertTrue(shift.text.contains("06:57:40"))
        assertTrue(shift.text.contains("16:21:00"))
        assertTrue(shift.text.contains("16:20:40"))
        assertTrue(shift.text.contains("9 ч"))
    }

    @Test
    fun punchesWithinTwoMinutesOfThePhoneAreOk() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 10, 0, 0),
                punch(31, 10, 0, 20),
                punch(CP_FINISH, 10, 0, 40),
            ),
            nowSeconds = 10 * 3600 + 2 * 60,
        )
        assertTrue(report.ok)
        assertFalse(report.attention)
        assertTrue(report.rows.none { it.text.contains("времени телефона") })
    }

    @Test
    fun unexpectedNumberIsListed() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 8, 0, 0),
                punch(31, 8, 1, 0),
                punch(99, 8, 2, 0),
                punch(CP_FINISH, 8, 3, 0),
            ),
        )
        assertFalse(report.ok)
        assertTrue(report.rows.any { it.mark == StationCheckMark.Extra && it.text.contains("99") })
    }

    private fun punch(cp: Int, hour: Int, minute: Int, second: Int) =
        Punch(cp, hour, minute, second)
}

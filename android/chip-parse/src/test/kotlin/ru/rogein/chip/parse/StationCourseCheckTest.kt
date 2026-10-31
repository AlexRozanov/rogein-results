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
                punch(31, 10, 5, 12),
                punch(32, 10, 11, 3),
                punch(CP_FINISH, 10, 20, 0),
            ),
        )
        assertTrue(report.ok)
        assertNull(report.duplicateNote)
        assertEquals("Старт  10:00:00", report.rows[0].text)
        assertEquals("КП 31  10:05:12", report.rows[1].text)
        assertEquals("КП 32  10:11:03", report.rows[2].text)
        assertEquals("Финиш  10:20:00", report.rows[3].text)
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
    fun repeatedLogicalNumberIsNotProofOfTwoStations() {
        val report = StationCourseCheck.check(
            controls = listOf(31),
            punches = listOf(
                punch(CP_START, 9, 0, 0),
                punch(31, 9, 1, 0),
                punch(31, 9, 4, 30),
                punch(CP_FINISH, 9, 8, 0),
            ),
        )
        assertFalse(report.ok)
        val repeat = report.rows.single { it.mark == StationCheckMark.Repeat }
        assertEquals("КП 31 ×2  09:01:00, 09:04:30", repeat.text)
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
                punch(31, 8, 1, 0),
                punch(31, 8, 3, 0),
                punch(CP_FINISH, 8, 4, 0),
            ),
        )
        assertTrue(twice.ok)
        assertNull(twice.duplicateNote)
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

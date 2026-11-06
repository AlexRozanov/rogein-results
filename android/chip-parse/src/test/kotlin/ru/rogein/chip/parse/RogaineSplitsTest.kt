package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RogaineSplitsTest {
    @Test
    fun startAndFinishHaveNoSeqInnerCpsAreNumbered() {
        val rows = rogaineSplitRows(
            marks = listOf(
                OrientMark(CP_START, 10 * 3600),
                OrientMark(31, 10 * 3600 + 15 * 60),
                OrientMark(45, 11 * 3600),
                OrientMark(CP_FINISH, 14 * 3600),
            ),
            startCp = CP_START,
            finishCp = CP_FINISH,
        )
        assertEquals(listOf(RogaineSplitKind.Start, RogaineSplitKind.Control, RogaineSplitKind.Control, RogaineSplitKind.Finish), rows.map { it.kind })
        assertNull(rows[0].seq)
        assertEquals(1, rows[1].seq)
        assertEquals(2, rows[2].seq)
        assertNull(rows.last().seq)
        assertNull(rows[0].legSeconds)
        assertEquals(15 * 60, rows[1].legSeconds)
        assertEquals(45 * 60, rows[2].legSeconds)
        assertEquals(3 * 3600, rows.last().legSeconds)
    }

    @Test
    fun timeStartWithoutPunchUsesFallbackClock() {
        val rows = rogaineSplitRows(
            marks = listOf(
                OrientMark(31, 10 * 3600 + 20 * 60),
                OrientMark(CP_FINISH, 12 * 3600),
            ),
            startCp = CP_START,
            finishCp = CP_FINISH,
            fallbackStartSeconds = 10 * 3600,
        )
        assertEquals(RogaineSplitKind.Start, rows.first().kind)
        assertEquals(10 * 3600, rows.first().seconds)
        assertEquals(1, rows[1].seq)
        assertEquals(20 * 60, rows[1].legSeconds)
    }
}

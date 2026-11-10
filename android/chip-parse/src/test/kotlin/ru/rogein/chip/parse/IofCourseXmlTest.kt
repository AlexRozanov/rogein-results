package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IofCourseXmlTest {
    @Test
    fun parseOcadCourseDataLengthClimbAndLegs() {
        val text = javaClass.classLoader.getResource("iof-courses.xml")!!.readText()
        val rows = CsvImport.courses(text)
        assertEquals(2, rows.size)

        val d1 = rows[0]
        assertEquals("D1", d1.name)
        assertEquals(4820, d1.lengthMeters)
        assertEquals(0, d1.climbMeters)
        assertEquals(28, d1.controls.size)
        assertEquals(listOf(32, 43, 36), d1.controls.take(3))
        assertEquals(33, d1.controls.last())
        assertEquals(29, d1.legMeters.size)
        assertEquals(133, d1.legMeters.first())
        assertEquals(274, d1.legMeters.last())
        assertEquals(177, d1.legMeters[1])

        val d2 = rows[1]
        assertEquals("D2", d2.name)
        assertEquals(2310, d2.lengthMeters)
        assertEquals(14, d2.controls.size)
        assertEquals(listOf(31, 33, 45), d2.controls.take(3))
        assertEquals(32, d2.controls.last())
        assertEquals(15, d2.legMeters.size)
        assertEquals(131, d2.legMeters.first())
        assertEquals(141, d2.legMeters.last())
    }

    @Test
    fun mergeFillsMissingLengthWhenControlsMatch() {
        val incoming = CsvImport.courses(javaClass.classLoader.getResource("iof-courses.xml")!!.readText())
        val existing = CourseCsvRow("D1", incoming[0].controls)
        val merged = mergeCourseGeometry(existing, incoming[0])
        assertEquals(4820, merged.lengthMeters)
        assertEquals(incoming[0].legMeters, merged.legMeters)
    }

    @Test
    fun mismatchWhenControlOrderDiffers() {
        val incoming = CsvImport.courses(javaClass.classLoader.getResource("iof-courses.xml")!!.readText())
        val existing = listOf("D1" to listOf(31, 32, 33))
        assertTrue(coursesNeedReplace(existing, incoming))
        assertFalse(coursesNeedReplace(listOf("D1" to incoming[0].controls), incoming))
        assertFalse(coursesNeedReplace(emptyList(), incoming))
    }

    @Test
    fun splitSpeedUsesOfficialAdjacentLegs() {
        val meters = listOf(133, 177, 151)
        assertEquals(8.0, splitSpeedKmh(80, 36)!!, 0.01)
        assertEquals("8 км/ч", formatSpeedKmh(8.0))
        assertEquals("5,3 км/ч", formatSpeedKmh(177 * 3.6 / 120.0))
        assertEquals("7:30/км", formatPaceMinPerKm(80, 36))
        assertEquals("0:01:28", formatRaceTime(88))
        assertEquals("01:28", formatSplitMmSs(88))
        assertEquals("( 32)", formatCpParen(32))
        assertEquals(
            " 1 ( 32)  0:00:50 00:50  6:31/км",
            punchSplitLine("1", formatCpParen(32), "0:00:50", "00:50", "6:31/км"),
        )
        assertEquals(133, courseLegMeters(listOf(32, 43), meters.map { it }, 241, 32, 241, 240))
        assertEquals(177, courseLegMeters(listOf(32, 43), meters.map { it }, 32, 43, 241, 240))
        assertNull(courseLegMeters(listOf(32, 43), meters.map { it }, 32, 36, 241, 240))
        assertTrue(hasCourseDistances(4820, meters.map { it }))
        assertFalse(hasCourseDistances(null, emptyList()))
    }
}

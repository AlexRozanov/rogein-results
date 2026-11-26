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
        val existing = listOf(CourseOrderSnapshot("D1", listOf(31, 32, 33)))
        assertTrue(coursesNeedReplace(existing, incoming))
        assertFalse(
            coursesNeedReplace(
                listOf(
                    CourseOrderSnapshot(
                        "D1",
                        incoming[0].controls,
                        incoming[0].randomOrder,
                    ),
                ),
                incoming,
            ),
        )
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
        assertEquals("(32)", formatCpParen(32))
        assertEquals(
            " 1 (32) 0:00:50 00:50  6:31/км",
            punchSplitLine("1", formatCpParen(32), "0:00:50", "00:50", "6:31/км"),
        )
        val slow = punchSplitLine("2", formatCpParen(31), "0:04:53", "03:11", "10:57/км")
        assertEquals(" 2 (31) 0:04:53 03:11  10:57/км", slow)
        assertTrue(slow.length <= ReceiptText.WIDTH)
        val verySlow = punchSplitLine("10", formatCpParen(38), "0:40:04", "06:58", "84:58/км")
        assertEquals("10 (38) 0:40:04 06:58  84:58/км", verySlow)
        assertTrue(verySlow.length <= ReceiptText.WIDTH)
        val finish = punchSplitLine("", "Финиш:", "1:21:47", "02:21", "10:24/км")
        assertEquals("Финиш:  1:21:47 02:21  10:24/км", finish)
        assertTrue(finish.startsWith("Финиш:"))
        assertTrue(finish.length <= ReceiptText.WIDTH)
        val cpLine = punchSplitLine("1", formatCpParen(32), "0:00:50", "00:50", "6:31/км")
        assertEquals(cpLine.indexOf("0:00:50"), finish.indexOf("1:21:47"))
        assertEquals(cpLine.lastIndexOf("00:50"), finish.indexOf("02:21"))
        assertEquals(cpLine.indexOf("6:31/км"), finish.indexOf("10:24/км"))
        assertEquals(133, courseLegMeters(listOf(32, 43), meters.map { it }, 241, 32, 241, 240))
        assertEquals(177, courseLegMeters(listOf(32, 43), meters.map { it }, 32, 43, 241, 240))
        assertNull(courseLegMeters(listOf(32, 43), meters.map { it }, 32, 36, 241, 240))
        assertTrue(hasCourseDistances(4820, meters.map { it }))
        assertFalse(hasCourseDistances(null, emptyList()))
    }

    @Test
    fun courseLegMetersClusterEntryAndExit() {
        // start→31, 31→32, 32→33, 33→34, 34→finish
        val controls = listOf(31, 32, 33, 34)
        val random = listOf(0, 1, 1, 0)
        val legs = listOf(100, 200, 50, 60, 80)
        // Вход в гроздь на 33 (не первый в списке) — метры перегона 31→32.
        assertEquals(200, courseLegMeters(controls, legs, 31, 33, 241, 240, random))
        // Выход из грозди с 32 — метры 33→34.
        assertEquals(60, courseLegMeters(controls, legs, 32, 34, 241, 240, random))
        // Внутри грозди без координат — null.
        assertNull(courseLegMeters(controls, legs, 33, 32, 241, 240, random))
    }

    @Test
    fun courseLegMetersFindsSecondOccurrenceOfDuplicateCp() {
        // Как D2: 45 встречается дважды — поздний перегон 39→45 не должен брать первую 45.
        val controls = listOf(46, 35, 45, 31, 39, 45)
        val legs = listOf(120, 92, 195, 79, 178, 210, 108)
        assertEquals(210, courseLegMeters(controls, legs, 39, 45, 241, 240))
        assertEquals(108, courseLegMeters(controls, legs, 45, 240, 241, 240))
        assertEquals(195, courseLegMeters(controls, legs, 35, 45, 241, 240))
    }

    @Test
    fun courseLegMetersClusterShuffleUsesMapStraightLine() {
        val controls = listOf(46, 35, 45, 31)
        val random = listOf(1, 1, 1, 0)
        val legs = listOf(120, 92, 195, 79, 178)
        val positions = mapOf(
            46 to (0.0 to 0.0),
            45 to (30.0 to 40.0), // 50 units
            35 to (0.0 to 40.0),
        )
        // Порядок не как в XML: 46→45 — только по координатам.
        assertEquals(
            200,
            courseLegMeters(controls, legs, 46, 45, 241, 240, random, positions, 4.0),
        )
        // Вход в гроздь 65 нет; старт→46 — соседство в seq.
        assertEquals(120, courseLegMeters(controls, legs, 241, 46, 241, 240, random, positions, 4.0))
    }

    @Test
    fun courseLegMetersFallsBackToMapStraightLine() {
        val controls = listOf(31, 32, 33)
        val random = listOf(0, 1, 1)
        val positions = mapOf(
            32 to (0.0 to 0.0),
            33 to (3.0 to 4.0), // 5 units
        )
        assertEquals(
            500,
            courseLegMeters(
                controls,
                emptyList(),
                32,
                33,
                241,
                240,
                random,
                positions,
                metersPerUnit = 100.0,
            ),
        )
    }

    @Test
    fun parseIofCourseDataReadsScaleAndMapPositions() {
        val text = javaClass.classLoader.getResource("iof-courses.xml")!!.readText()
        val data = parseIofCourseData(text)
        assertEquals(4000, data.mapScaleDenominator)
        assertEquals(-13.5 to 78.2, data.controlPositionsMm[31])
        assertTrue(data.courses.isNotEmpty())
    }

    @Test
    fun buildOrientSplitPrintShowsPaceOnClusterPath() {
        val controls = listOf(31, 32, 33, 34)
        val random = listOf(0, 1, 1, 0)
        val legs = listOf(100, 200, 50, 60, 80)
        val positions = mapOf(
            32 to (0.0 to 0.0),
            33 to (30.0 to 40.0), // 50 units → 200 m at mpu=4
        )
        val punches = listOf(
            OrientMark(241, 10 * 3600),
            OrientMark(31, 10 * 3600 + 60),
            OrientMark(33, 10 * 3600 + 120),
            OrientMark(32, 10 * 3600 + 180),
            OrientMark(34, 10 * 3600 + 240),
            OrientMark(240, 10 * 3600 + 300),
        )
        val print = buildOrientSplitPrint(
            punches = punches,
            required = controls,
            startCp = 241,
            finishCp = 240,
            elapsedSeconds = 300,
            status = STATUS_OK,
            courseControls = controls,
            lengthMeters = 4820,
            legMeters = legs,
            randomOrder = random,
            courseRandomOrder = random,
            cpPositions = positions,
            metersPerUnit = 4.0,
        )
        requireNotNull(print)
        assertTrue(print.rows.none { it.wrong })
        // Вход 31→33 и выход 32→34 из LegLength; внутри 33→32 по координатам.
        assertTrue(print.rows.any { it.text.contains("/км") })
        assertTrue(print.rows.count { it.text.contains("/км") } >= 3)
    }

    @Test
    fun remapCourseLegMetersKeepsOfficialAdjacentLegs() {
        val full = listOf(31, 32, 33)
        val legs = listOf(100, 200, 150, 80)
        assertEquals(legs, remapCourseLegMeters(241, 240, full, full, legs))
        assertEquals(
            listOf(100, null, 80),
            remapCourseLegMeters(241, 240, full, listOf(31, 33), legs),
        )
    }
}

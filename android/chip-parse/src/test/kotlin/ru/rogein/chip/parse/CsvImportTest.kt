package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CsvImportTest {
    @Test
    fun protocolSkipsHeaderAndReadsBibNameCourse() {
        val rows = CsvImport.protocol("номер;ФИО;дистанция\n8;Иванов Иван;D1\n9;Петров;D2\n")
        assertEquals(2, rows.size)
        assertEquals(8, rows[0].id)
        assertEquals("Иванов Иван", rows[0].name)
        assertEquals("D1", rows[0].course)
    }

    @Test
    fun protocolAllowsMissingCourseColumn() {
        val rows = CsvImport.protocol("номер;ФИО\n8;Иванов Иван\n9;Петров;D2\n")
        assertEquals(2, rows.size)
        assertEquals("", rows[0].course)
        assertEquals("D2", rows[1].course)
    }

    @Test
    fun protocolAllowsBlankCourseCell() {
        val rows = CsvImport.protocol("номер;ФИО;дистанция\n8;Иванов Иван;\n9;Петров;D2\n")
        assertEquals("", rows[0].course)
        assertEquals("D2", rows[1].course)
    }

    @Test
    fun coursesAcceptSemicolonAndSpaces() {
        val rows = CsvImport.courses("D1;31;32;33\nD2;41 42,43\n")
        assertEquals(listOf(31, 32, 33), rows[0].controls)
        assertEquals(listOf(41, 42, 43), rows[1].controls)
    }

    @Test
    fun coursesReadWinOrientDashPath() {
        val text = javaClass.classLoader.getResource("roscha-courses.txt")!!.readText()
        val rows = CsvImport.courses(text)
        assertEquals(2, rows.size)
        assertEquals("D1", rows[0].name)
        assertEquals(30, rows[0].controls.size)
        assertEquals(listOf(36, 31, 33), rows[0].controls.take(3))
        assertEquals(35, rows[0].controls.last())
        assertEquals("D2", rows[1].name)
        assertEquals(listOf(56, 57, 58, 33, 31, 36, 38, 40, 41, 43, 45, 44, 50, 46, 42, 54, 55), rows[1].controls)
    }
}

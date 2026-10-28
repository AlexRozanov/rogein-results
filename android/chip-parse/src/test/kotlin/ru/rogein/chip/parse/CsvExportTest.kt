package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CsvExportTest {
    @Test
    fun finishDumpMatchesDesktopColumnLayout() {
        val csv = CsvExport.finishDump(
            competitionDate = "2026-10-27",
            startCp = 241,
            rows = listOf(
                FinishCsvRow(
                    chipRawId = "AA 11",
                    bibId = "8",
                    name = "Иванов Иван",
                    course = "D1",
                    punches = listOf(
                        FinishCsvPunch(241, "10:00:00"),
                        FinishCsvPunch(31, "10:05:01"),
                        FinishCsvPunch(240, "10:30:00"),
                    ),
                ),
            ),
        )
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        val header = lines[0].split(';')
        assertTrue(header.size >= 11)
        assertEquals("system", header[0])
        assertEquals("chip raw id", header[1])
        assertEquals("id", header[2])
        assertEquals("team name", header[3])
        assertEquals("course", header[4])
        assertEquals("start st id", header[7])
        assertEquals("Start time", header[8])
        assertEquals("st1", header[9])
        assertEquals("time1", header[10])
        val row = lines[1].split(';')
        assertEquals("sfr", row[0])
        assertEquals("8", row[2])
        assertEquals("Иванов Иван", row[3])
        assertEquals("D1", row[4])
        assertEquals("241", row[7])
        assertEquals("2026-10-27 10:00:00", row[8])
        assertEquals("241", row[9])
        assertEquals("2026-10-27 10:00:00", row[10])
        assertEquals("31", row[11])
        assertEquals("2026-10-27 10:05:01", row[12])
        assertEquals("240", row[13])
        assertEquals("2026-10-27 10:30:00", row[14])
    }

    @Test
    fun finishDumpQuotesNameWithSemicolon() {
        val csv = CsvExport.finishDump(
            "2026-10-27",
            241,
            listOf(
                FinishCsvRow("A", "1", "Иванов; Пётр", "D1", listOf(FinishCsvPunch(241, "9:00:00"))),
            ),
        )
        assertTrue(csv.contains("\"Иванов; Пётр\""))
        assertTrue(csv.contains("2026-10-27 09:00:00"))
    }

    @Test
    fun protocolHasHeaderDesktopExpects() {
        val csv = CsvExport.protocol(
            listOf(
                ProtocolCsvRow(8, "Иванов Иван", "D1"),
                ProtocolCsvRow(8, "Иванов Иван", "D2"),
            ),
        )
        assertEquals(
            listOf("номер;фио;дистанция", "8;Иванов Иван;D1", "8;Иванов Иван;D2"),
            csv.trim().lines(),
        )
    }

    @Test
    fun coursesAreOneLinePerCourse() {
        val csv = CsvExport.courses(
            listOf(
                CourseCsvRow("D1", listOf(31, 32, 33)),
                CourseCsvRow("D2", listOf(41, 42)),
            ),
        )
        assertEquals(listOf("D1;31;32;33", "D2;41;42"), csv.trim().lines())
    }
}

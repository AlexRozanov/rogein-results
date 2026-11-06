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
    fun protocolReadsExcelUtf8BomAndCommas() {
        val rows = CsvImport.protocol("\uFEFF1,Асылхузин Ринат,D1\r\n2,Атлетова Мария,D1\r\n")
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].id)
        assertEquals("Асылхузин Ринат", rows[0].name)
        assertEquals("D1", rows[0].course)
        assertEquals(2, rows[1].id)
    }

    @Test
    fun protocolAllowsBlankCourseCell() {
        val rows = CsvImport.protocol("номер;ФИО;дистанция\n8;Иванов Иван;\n9;Петров;D2\n")
        assertEquals("", rows[0].course)
        assertEquals("D2", rows[1].course)
    }

    @Test
    fun detectProtocolKindSeparatesOrientAndRogaineHeaders() {
        assertEquals(SPORT_ORIENT, CsvImport.detectProtocolKind("номер;ФИО;дистанция\n8;Иванов;D1\n"))
        assertEquals(SPORT_ROGAINE, CsvImport.detectProtocolKind("номер;ФИО;формат;пол;дата_рождения\n8;Иванов;4ч;мужской;1990-01-01\n"))
    }

    @Test
    fun rogaineProtocolReadsFiveColumns() {
        val rows = CsvImport.rogaineProtocol(
            "номер;ФИО;формат;пол;дата_рождения\n8;Иванов Иван;4ч;мужской;01.01.1990\n9;Петрова;2ч;женский;\n",
        )
        assertEquals(2, rows.size)
        assertEquals(8, rows[0].id)
        assertEquals("Иванов Иван", rows[0].name)
        assertEquals("4ч", rows[0].format)
        assertEquals("мужской", rows[0].gender)
        assertEquals("01.01.1990", rows[0].birth)
        assertEquals("2ч", rows[1].format)
        assertEquals("женский", rows[1].gender)
        assertEquals("", rows[1].birth)
    }

    @Test
    fun formatsReadNameAndTimingColumns() {
        val rows = CsvImport.formats("название;минуты;штраф;dq\n4ч;240;1;15\n2ч;120;;\n")
        assertEquals(2, rows.size)
        assertEquals("4ч", rows[0].name)
        assertEquals(240, rows[0].controlMinutes)
        assertEquals(1, rows[0].penaltyPerMinute)
        assertEquals(15, rows[0].dqMinutes)
        assertEquals("2ч", rows[1].name)
        assertEquals(120, rows[1].controlMinutes)
        assertEquals(null, rows[1].penaltyPerMinute)
    }

    @Test
    fun awardGroupsReadGenderAgeAndFormats() {
        val rows = CsvImport.awardGroups("имя;пол;мин_возраст;форматы\nМО;мужской;18;4ч,8ч\nЖО;женский;;2ч\n")
        assertEquals(2, rows.size)
        assertEquals("МО", rows[0].name)
        assertEquals("мужской", rows[0].gender)
        assertEquals(18, rows[0].minAge)
        assertEquals(listOf("4ч", "8ч"), rows[0].formats)
        assertEquals("ЖО", rows[1].name)
        assertEquals(null, rows[1].minAge)
        assertEquals(listOf("2ч"), rows[1].formats)
        assertEquals(null, rows[0].maxAge)
        val juniors = CsvImport.awardGroups("имя;пол;мин_возраст;форматы;макс_возраст\nЮниоры;мужской;;4ч;18\n")
        assertEquals(18, juniors[0].maxAge)
        assertEquals(null, juniors[0].minAge)
    }

    @Test
    fun legendsReadCpNameAndType() {
        val rows = CsvImport.legends("номер;название;тип\n31;Камень;обычный\n32;Яма;\n")
        assertEquals(2, rows.size)
        assertEquals(31, rows[0].cp)
        assertEquals("Камень", rows[0].name)
        assertEquals("обычный", rows[0].type)
        assertEquals(32, rows[1].cp)
        assertEquals("", rows[1].type)
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

    @Test
    fun finishDumpReadsQuotedSfrExport() {
        val csv = """
            system;chip raw id;id;team name;course;result;brief;start st id;Start time;st1;time1;st2;time2
            sfr;B8 34 CF 2A 00 00 07 E0 ;18;"Лыжков Дмитрий";"D1";OK;"29 КП за 1:26:15";241;2026-10-29 19:14:07;241;2026-10-29 19:14:07;33;2026-10-29 19:19:20;240;2026-10-29 20:40:22
            sfr;6F 85 54 9F DD 58 02 E0 ;37;"Логвиненко Андрей";"D1";OK;"29 КП за 1:28:19";241;2026-10-29 19:17:25;241;2026-10-29 19:17:25;33;2026-10-29 19:20:13;240;2026-10-29 20:45:44
        """.trimIndent()
        val rows = CsvImport.finishDump(csv)
        assertEquals(2, rows.size)
        assertEquals("18", rows[0].bibId)
        assertEquals("Лыжков Дмитрий", rows[0].name)
        assertEquals("D1", rows[0].course)
        assertEquals("B8 34 CF 2A 00 00 07 E0", rows[0].chipRawId)
        assertEquals(
            listOf(
                FinishCsvPunch(241, "19:14:07"),
                FinishCsvPunch(33, "19:19:20"),
                FinishCsvPunch(240, "20:40:22"),
            ),
            rows[0].punches,
        )
        val dump = rows[0].toChipDump()
        assertEquals(18, dump.logicalId)
        assertEquals("19:14:07", dump.punches.first().time)
        assertEquals(240, dump.punches.last().cp)
    }

    @Test
    fun finishDumpRoundtripsPhoneExport() {
        val exported = CsvExport.finishDump(
            "2026-10-29",
            241,
            listOf(
                FinishCsvRow(
                    "AA 11",
                    "8",
                    "Иванов Иван",
                    "D1",
                    listOf(
                        FinishCsvPunch(241, "10:00:00"),
                        FinishCsvPunch(31, "10:05:01"),
                        FinishCsvPunch(240, "10:30:00"),
                    ),
                ),
            ),
        )
        val rows = CsvImport.finishDump(exported)
        assertEquals(1, rows.size)
        assertEquals("8", rows[0].bibId)
        assertEquals("Иванов Иван", rows[0].name)
        assertEquals("D1", rows[0].course)
        assertEquals(
            listOf(
                FinishCsvPunch(241, "10:00:00"),
                FinishCsvPunch(31, "10:05:01"),
                FinishCsvPunch(240, "10:30:00"),
            ),
            rows[0].punches,
        )
    }

    @Test
    fun finishDumpSkipsBrokenRowWithoutPunches() {
        val csv = """
            system;chip raw id;id;team name;course;result;brief;start st id;Start time;st1;time1
            sfr;AA;8;"Иванов";"D1";OK;ok;241;2026-10-29 10:00:00;241;2026-10-29 10:00:00
            sfr;6F BC 53 03 00 00 07 E0 ;38;"Котов Николай";"D1";DSQ;"10 КП за 1:01:52;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
        """.trimIndent()
        val rows = CsvImport.finishDump(csv)
        assertEquals(1, rows.size)
        assertEquals("8", rows[0].bibId)
    }

    @Test
    fun finishDumpLastRowWinsOnSameBibAndCourse() {
        val csv = """
            system;chip raw id;id;team name;course;result;brief;start st id;Start time;st1;time1;st2;time2
            sfr;A;8;Иванов;D1;OK;ok;241;2026-10-29 10:00:00;241;2026-10-29 10:00:00;240;2026-10-29 11:00:00
            sfr;B;8;Иванов;D1;OK;ok;241;2026-10-29 10:05:00;241;2026-10-29 10:05:00;240;2026-10-29 11:10:00
        """.trimIndent()
        val rows = CsvImport.finishDump(csv)
        assertEquals(1, rows.size)
        assertEquals("B", rows[0].chipRawId)
        assertEquals("10:05:00", rows[0].punches.first().clock)
    }
}

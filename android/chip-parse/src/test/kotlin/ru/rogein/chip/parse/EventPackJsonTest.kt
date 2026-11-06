package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventPackJsonTest {
    @Test
    fun eventPackFixtureParsesRogainePeopleAndFormats() {
        val pack = parseEventPackJson(fixture("rogaine/event-pack.json"))
        assertEquals(SPORT_ROGAINE, pack.sport)
        assertEquals("Тестовый рогейн", pack.title)
        assertEquals(240, pack.settings.controlMinutes)
        assertEquals(1, pack.formats.size)
        assertEquals("4ч", pack.formats[0].name)
        assertEquals(240, pack.formats[0].controlMinutes)
        assertEquals(listOf(31), pack.formats[0].controls)
        assertTrue(pack.formats[0].cpTypeRules.isEmpty())
        assertEquals(1, pack.awardGroups.size)
        assertEquals("МО", pack.awardGroups[0].name)
        assertEquals(listOf("4ч"), pack.awardGroups[0].formatNames)
        assertEquals(1, pack.people.size)
        assertEquals(8, pack.people[0].id)
        assertEquals("Иванов Иван", pack.people[0].name)
        assertEquals("4ч", pack.people[0].format)
        assertEquals("мужской", pack.people[0].gender)
        assertEquals("1990-01-01", pack.people[0].birthDate)
        assertEquals(0, pack.people[0].teamId)
        assertEquals(31, pack.legends[0].cp)
        assertEquals(0.4, pack.legends[0].mapX)
        assertEquals(31, pack.exclusions[0].fromCp)
        assertEquals("start", pack.mapPoints[0].kind)
        assertEquals(1.5, pack.metersPerPixel)
        assertEquals("map.jpg", pack.mapFileName)
    }

    @Test
    fun eventPackIgnoresUnknownKeysAndBlankPeople() {
        val json = """
            {
              "revision": 3,
              "sport": "rogaine",
              "extra": {"x": 1},
              "settings": {"controlMinutes": 90},
              "formats": [{"name": "2ч"}, {"name": ""}],
              "people": [
                {"id": 1, "name": "Тест", "format": "2ч", "gender": "женский"},
                {"id": 0, "name": "пропуск"},
                {"id": 2, "name": ""}
              ]
            }
        """.trimIndent()
        val pack = parseEventPackJson(json)
        assertEquals(SPORT_ROGAINE, pack.sport)
        assertEquals(90, pack.settings.controlMinutes)
        assertEquals(listOf("2ч"), pack.formats.map { it.name })
        assertEquals(1, pack.people.size)
        assertEquals("2ч", pack.people[0].format)
        assertTrue(pack.awardGroups.isEmpty())
        assertTrue(pack.legends.isEmpty())
    }

    @Test
    fun eventPackReadsSnakeCaseGroupsAndLegends() {
        val json = """
            {
              "sport": "rogaine",
              "settings": {"control_minutes": 90, "start_cp": 241, "finish_cp": 240},
              "formats": [{"name": "2ч", "controls": [31, 32]}],
              "award_groups": [{"name": "ЖО", "gender_mode": "female", "format_names": ["2ч"]}],
              "people": [{"id": 1, "name": "Тест", "format": "2ч"}],
              "legends": [{"cp_number": 31, "name": "Камень", "map_x": 0.2, "map_y": 0.3}]
            }
        """.trimIndent()
        val pack = parseEventPackJson(json)
        assertEquals(90, pack.settings.controlMinutes)
        assertEquals(listOf(31, 32), pack.formats[0].controls)
        assertEquals("ЖО", pack.awardGroups[0].name)
        assertEquals("female", pack.awardGroups[0].genderMode)
        assertEquals(listOf("2ч"), pack.awardGroups[0].formatNames)
        assertEquals(31, pack.legends[0].cp)
        assertEquals(0.2, pack.legends[0].mapX)
    }

    @Test
    fun eventPackReadsTeamId() {
        val json = """
            {
              "sport": "rogaine",
              "people": [
                {"id": 8, "name": "Иванов", "format": "4ч", "teamId": 3, "team": "9 Петрова"},
                {"id": 9, "name": "Петрова", "format": "4ч", "team_id": 3, "team": "8 Иванов"}
              ]
            }
        """.trimIndent()
        val pack = parseEventPackJson(json)
        assertEquals(3, pack.people[0].teamId)
        assertEquals(3, pack.people[1].teamId)
        assertEquals("9 Петрова", pack.people[0].team)
    }

    @Test
    fun eventPackReadsFormatCpTypeRules() {
        val json = """
            {
              "sport": "rogaine",
              "formats": [{
                "name": "4ч",
                "controls": [31, 32],
                "cpTypeRules": [{"type": "обычный", "maxCount": 2}]
              }],
              "legends": [{"cp": 31, "name": "Камень", "type": "обычный"}]
            }
        """.trimIndent()
        val pack = parseEventPackJson(json)
        assertEquals(1, pack.formats[0].cpTypeRules.size)
        assertEquals("обычный", pack.formats[0].cpTypeRules[0].type)
        assertEquals(2, pack.formats[0].cpTypeRules[0].maxCount)
    }

    private fun fixture(path: String): String =
        javaClass.classLoader.getResource(path)!!.readText()
}

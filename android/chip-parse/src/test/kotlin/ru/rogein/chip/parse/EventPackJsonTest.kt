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
        assertEquals(1, pack.awardGroups.size)
        assertEquals("МО", pack.awardGroups[0].name)
        assertEquals(listOf("4ч"), pack.awardGroups[0].formatNames)
        assertEquals(1, pack.people.size)
        assertEquals(8, pack.people[0].id)
        assertEquals("Иванов Иван", pack.people[0].name)
        assertEquals("4ч", pack.people[0].format)
        assertEquals("мужской", pack.people[0].gender)
        assertEquals("1990-01-01", pack.people[0].birthDate)
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

    private fun fixture(path: String): String =
        javaClass.classLoader.getResource(path)!!.readText()
}

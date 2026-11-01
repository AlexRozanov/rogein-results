package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrientPackFixturesTest {
    @Test
    fun startPackFixtureParsesOrientPeopleAndCourses() {
        val pack = parseStartPackJson(fixture("orient/start-pack.json"))
        assertEquals(SPORT_ORIENT, pack.sport)
        assertEquals(2, pack.courses.size)
        assertEquals("D1", pack.courses[0].name)
        assertEquals(listOf(31, 32, 33), pack.courses[0].controls)
        assertEquals("D2", pack.courses[1].name)
        assertEquals(listOf(41, 42), pack.courses[1].controls)
        assertEquals(2, pack.people.size)
        assertEquals(8, pack.people[0].id)
        assertEquals("Иванов Иван", pack.people[0].name)
        assertEquals(listOf("D1"), pack.people[0].courses)
        assertEquals("D1", pack.people[0].currentCourse)
        assertEquals(12, pack.people[1].id)
        assertEquals(listOf("D1", "D2"), pack.people[1].courses)
        assertEquals("D2", pack.people[1].currentCourse)
    }

    @Test
    fun startPackIgnoresUnknownRogaineKeys() {
        val json = """
            {
              "revision": 9,
              "sport": "orient",
              "awardGroups": [{"name": "МО"}],
              "formats": [{"name": "4ч"}],
              "courses": [{"name": "D1", "controls": [31]}],
              "people": [{
                "id": 1,
                "name": "Тест",
                "courses": ["D1"],
                "currentCourse": "D1",
                "gender": "мужской",
                "birth": "1990-01-01"
              }]
            }
        """.trimIndent()
        val pack = parseStartPackJson(json)
        assertEquals(SPORT_ORIENT, pack.sport)
        assertEquals(1, pack.people.size)
        assertEquals("D1", pack.people[0].currentCourse)
        assertEquals(listOf(31), pack.courses[0].controls)
    }

    @Test
    fun chipQueueFixtureKeepsPunchesAndBib() {
        val queue = parseChipQueueJson(fixture("orient/chip-queue.json"))
        assertEquals(1, queue.items.size)
        val item = queue.items[0]
        assertEquals("11111111-2222-3333-4444-555555555555", item.id)
        assertEquals(8, item.logicalId)
        assertEquals("D1", item.courseName)
        assertEquals("Иванов Иван", item.name)
        assertEquals(
            listOf(241, 31, 32, 33, 240),
            item.punches.map { it.cp },
        )
        assertEquals("10:00:00", item.punches.first().time)
        assertEquals("10:40:00", item.punches.last().time)
    }

    @Test
    fun fixtureCourseIsCompleteForOrientScoring() {
        val pack = parseStartPackJson(fixture("orient/start-pack.json"))
        val queue = parseChipQueueJson(fixture("orient/chip-queue.json"))
        val item = queue.items[0]
        val course = pack.courses.first { it.name == item.courseName }
        val calc = calculateOrientResult(
            item.punches.map { OrientMark(it.cp, parseDaySeconds(it.time) ?: 0) },
            OrientSettings(CP_START, CP_FINISH, 120),
            item.courseName,
            mapOf(courseKey(course.name) to course.controls),
            emptySet(),
            emptyList(),
            emptyMap(),
        )
        assertEquals(STATUS_OK, calc.status)
        assertEquals(3, calc.taken)
        assertTrue(calc.elapsedSeconds > 0)
    }

    private fun fixture(path: String): String =
        javaClass.classLoader.getResource(path)!!.readText()
}

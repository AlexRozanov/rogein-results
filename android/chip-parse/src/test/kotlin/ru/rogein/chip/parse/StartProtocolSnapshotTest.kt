package ru.rogein.chip.parse

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartProtocolSnapshotTest {
    @Test
    fun snapshotListsWalkUpTeam() {
        val json = buildStartProtocolSnapshotJson(
            listOf(
                StartProtocolSnapshotPerson(
                    id = 102,
                    name = "",
                    format = "4ч",
                    teamId = 1,
                    origin = "phone",
                ),
                StartProtocolSnapshotPerson(
                    id = 101,
                    name = "Иванов",
                    format = "4ч",
                    gender = "мужской",
                    birthDate = "2009-01-01",
                    teamId = 1,
                    origin = "phone",
                ),
            ),
        )
        val people = JSONObject(json).getJSONArray("people")
        assertEquals(2, people.length())
        assertEquals(101, people.getJSONObject(0).getInt("id"))
        assertEquals(102, people.getJSONObject(1).getInt("id"))
        assertEquals("4ч", people.getJSONObject(0).getString("format"))
        assertEquals(1, people.getJSONObject(0).getInt("teamId"))
        assertTrue(people.getJSONObject(1).getString("name").isEmpty())
    }
}

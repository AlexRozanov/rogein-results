package ru.rogein.chip.parse

import org.json.JSONArray
import org.json.JSONObject

data class StartProtocolSnapshotPerson(
    val id: Int,
    val name: String = "",
    val format: String = "",
    val gender: String = "",
    val birthDate: String = "",
    val teamId: Int = 0,
    val origin: String = "",
)

fun buildStartProtocolSnapshotJson(people: List<StartProtocolSnapshotPerson>): String {
    val array = JSONArray()
    for (person in people.sortedBy { it.id }) {
        if (person.id <= 0) continue
        array.put(
            JSONObject()
                .put("id", person.id)
                .put("name", person.name)
                .put("format", person.format)
                .put("gender", person.gender)
                .put("birthDate", person.birthDate)
                .put("teamId", if (person.teamId > 0) person.teamId else JSONObject.NULL)
                .put("origin", person.origin),
        )
    }
    return JSONObject().put("people", array).toString()
}

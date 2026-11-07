package ru.rogein.chip.startlist

import ru.rogein.chip.parse.parseStartPackJson

data class StartPack(
    val revision: Long,
    val sport: String,
    val courses: List<StartCourse>,
    val people: List<StartPerson>,
)

data class StartCourse(
    val name: String,
    val controls: List<Int>,
)

data class StartPerson(
    val id: Int,
    val name: String,
    val courses: List<String>,
    val currentCourse: String,
    val issued: Boolean = false,
    val issuedCourse: String = "",
    val gender: String = "",
    val birthDate: String = "",
    val teamId: Int = 0,
    val issuedUid: String = "",
) {
    val displayName: String get() = name.ifBlank { "без ФИО" }
    val teamLabel: String get() = if (teamId > 0) "Т$teamId" else ""
}

fun parseStartPack(json: String): StartPack {
    val file = parseStartPackJson(json)
    return StartPack(
        revision = file.revision,
        sport = file.sport,
        courses = file.courses.map { StartCourse(it.name, it.controls) },
        people = file.people.map { person ->
            StartPerson(
                id = person.id,
                name = person.name,
                courses = person.courses,
                currentCourse = person.currentCourse,
            )
        },
    )
}

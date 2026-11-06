package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AwardCategoriesTest {
    private val groups = listOf(
        AwardCategory("МО", "male", null, listOf("4ч")),
        AwardCategory("ЖО", "женский", null, listOf("4ч")),
        AwardCategory("М40", "male", 40, listOf("4ч")),
        AwardCategory("2ч", "any", null, listOf("2ч")),
        AwardCategory("Смешанные", "mixed", null, listOf("4ч")),
    )

    @Test
    fun maleOpenMatchesFormatAndGender() {
        assertEquals(
            listOf("МО"),
            personAwardCategories("4ч", "мужской", "1990-01-01", "2026-11-06", groups),
        )
    }

    @Test
    fun veteranMatchesOpenAndAgeGroup() {
        assertEquals(
            listOf("МО", "М40"),
            personAwardCategories("4ч", "m", "01.01.1980", "2026-11-06", groups),
        )
    }

    @Test
    fun tooYoungForAgeGroup() {
        assertEquals(
            listOf("МО"),
            personAwardCategories("4ч", "male", "2000-05-01", "2026-11-06", groups),
        )
    }

    @Test
    fun missingBirthSkipsAgeGroup() {
        assertEquals(
            listOf("МО"),
            personAwardCategories("4ч", "мужской", "", "2026-11-06", groups),
        )
    }

    @Test
    fun otherFormatAndMixedDoNotMatch() {
        assertEquals(
            listOf("2ч"),
            personAwardCategories("2ч", "женский", "1990-01-01", "2026-11-06", groups),
        )
        assertEquals(
            emptyList<String>(),
            personAwardCategories("8ч", "мужской", "1980-01-01", "2026-11-06", groups),
        )
    }

    @Test
    fun formatNameComparedByCourseKey() {
        assertEquals(
            listOf("МО"),
            personAwardCategories(" 4Ч ", "М", "1990-01-01", "2026-11-06", groups),
        )
    }

    @Test
    fun birthDateParsesIsoAndDotted() {
        assertEquals(BirthDateParts(1990, 1, 5), parseBirthDateParts("1990-01-05"))
        assertEquals(BirthDateParts(1990, 1, 5), parseBirthDateParts("5.1.1990"))
        assertEquals("05.01.1990", displayBirthDate("1990-01-05"))
        assertEquals("1990-01-05", storedBirthDate("5.1.1990"))
        assertEquals("мужской", storedPersonGender("М"))
        assertEquals("женский", storedPersonGender("ж"))
    }

    @Test
    fun mixedTeamMatchesMixedGroup() {
        assertEquals(
            listOf("Смешанные"),
            personAwardCategories(
                "4ч",
                "мужской",
                "1990-01-01",
                "2026-11-06",
                groups,
                listOf(AwardPerson("женский", "1992-02-02")),
            ),
        )
    }

    @Test
    fun finishersSplitByAwardGroupNotOnlyFormat() {
        val people = listOf(
            Triple("4ч", "мужской", "1980-01-01"),
            Triple("4ч", "женский", "1990-01-01"),
            Triple("4ч", "мужской", "2000-01-01"),
        )
        fun members(group: AwardCategory) = people.mapIndexedNotNull { index, person ->
            val names = personAwardCategories(
                person.first,
                person.second,
                person.third,
                "2026-11-06",
                listOf(group),
            )
            if (names.isEmpty()) null else index
        }
        assertEquals(listOf(0, 2), members(groups[0]))
        assertEquals(listOf(1), members(groups[1]))
        assertEquals(listOf(0), members(groups[2]))
    }

    @Test
    fun juniorMatchesOpenNotVeteran() {
        val groups = listOf(
            AwardCategory("МО", "male", null, listOf("4ч")),
            AwardCategory("Юниоры", "male", null, listOf("4ч"), maxAge = 18),
            AwardCategory("М45", "male", 45, listOf("4ч")),
        )
        assertEquals(
            listOf("МО", "Юниоры"),
            personAwardCategories("4ч", "мужской", "2009-01-01", "2026-11-06", groups),
        )
        assertEquals(
            listOf("МО"),
            personAwardCategories("4ч", "мужской", "2008-01-01", "2026-11-06", groups),
        )
        assertEquals(
            listOf("МО", "М45"),
            personAwardCategories("4ч", "мужской", "1970-01-01", "2026-11-06", groups),
        )
    }

    @Test
    fun juniorTeamUsesOldestMember() {
        val junior = AwardCategory("Юниоры", "male", null, listOf("4ч"), maxAge = 18)
        assertEquals(
            emptyList<String>(),
            personAwardCategories(
                "4ч",
                "мужской",
                "2009-01-01",
                "2026-11-06",
                listOf(junior),
                listOf(AwardPerson("мужской", "2008-01-01")),
            ),
        )
        assertEquals(
            listOf("Юниоры"),
            personAwardCategories(
                "4ч",
                "мужской",
                "2010-01-01",
                "2026-11-06",
                listOf(junior),
                listOf(AwardPerson("мужской", "2009-01-01")),
            ),
        )
    }

    @Test
    fun teamAgeUsesYoungestMember() {
        val veteran = AwardCategory("МВет", "male", 40, listOf("4ч"))
        assertEquals(
            emptyList<String>(),
            personAwardCategories(
                "4ч",
                "мужской",
                "1980-01-01",
                "2026-11-06",
                listOf(veteran),
                listOf(AwardPerson("мужской", "2000-01-01")),
            ),
        )
        assertEquals(
            listOf("МВет"),
            personAwardCategories(
                "4ч",
                "мужской",
                "1980-01-01",
                "2026-11-06",
                listOf(veteran),
                listOf(AwardPerson("мужской", "1975-01-01")),
            ),
        )
    }
}

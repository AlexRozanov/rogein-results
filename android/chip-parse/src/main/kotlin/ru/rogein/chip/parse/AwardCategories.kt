package ru.rogein.chip.parse

data class AwardCategory(
    val name: String,
    val genderMode: String = "any",
    val minAge: Int? = null,
    val formatNames: List<String> = emptyList(),
    val maxAge: Int? = null,
)

data class AwardPerson(
    val gender: String = "",
    val birthDate: String = "",
)

fun personAwardCategories(
    format: String,
    gender: String,
    birthDate: String,
    competitionDate: String,
    groups: List<AwardCategory>,
    teammates: List<AwardPerson> = emptyList(),
): List<String> {
    val formatKey = courseKey(format)
    if (formatKey.isEmpty() || groups.isEmpty()) return emptyList()
    val squad = listOf(AwardPerson(gender, birthDate)) + teammates
    val genders = squad.map { personGenderKind(it.gender) }
    val ages = squad.map { personAwardAge(it.birthDate, competitionDate) }
    val asTeam = teammates.isNotEmpty()
    return groups.mapNotNull { group ->
        if (awardCategoryMatches(formatKey, genders, ages, asTeam, group)) group.name else null
    }
}

internal fun awardCategoryMatches(
    formatKey: String,
    genders: List<String?>,
    ages: List<Int?>,
    asTeam: Boolean,
    group: AwardCategory,
): Boolean {
    val formats = group.formatNames.map { courseKey(it) }.filter { it.isNotEmpty() }
    if (formats.isEmpty() || formatKey !in formats) return false
    val hasMale = genders.any { it == "male" }
    val hasFemale = genders.any { it == "female" }
    when (normalizeAwardGenderMode(group.genderMode)) {
        "male" -> if (asTeam) {
            if (!hasMale || hasFemale) return false
        } else if (genders.firstOrNull() != "male") {
            return false
        }
        "female" -> if (asTeam) {
            if (!hasFemale || hasMale) return false
        } else if (genders.firstOrNull() != "female") {
            return false
        }
        "mixed" -> if (!asTeam || !hasMale || !hasFemale) return false
        else -> {}
    }
    val minAge = group.minAge?.takeIf { it > 0 }
    val maxAge = group.maxAge?.takeIf { it > 0 }
    if (minAge != null || maxAge != null) {
        if (asTeam && ages.any { it == null }) return false
        val youngest = if (asTeam) ages.filterNotNull().minOrNull() else ages.firstOrNull()
        val oldest = if (asTeam) ages.filterNotNull().maxOrNull() else ages.firstOrNull()
        if (minAge != null && (youngest == null || youngest < minAge)) return false
        if (maxAge != null && (oldest == null || oldest >= maxAge)) return false
    }
    return true
}

internal fun personGenderKind(raw: String): String? {
    val value = raw.trim().lowercase().replace('ё', 'е')
    if (value.isEmpty()) return null
    if (value.contains("муж") || value in setOf("m", "male", "м")) return "male"
    if (value.contains("жен") || value in setOf("f", "female", "ж")) return "female"
    return null
}

internal fun normalizeAwardGenderMode(raw: String): String {
    val value = raw.trim().lowercase().replace('ё', 'е')
    return when (value) {
        "male", "мужской", "мужчины", "мужские" -> "male"
        "female", "женский", "женщины", "женские" -> "female"
        "mixed", "смешанный", "смешанные" -> "mixed"
        else -> "any"
    }
}

fun personAwardAge(birthDate: String, competitionDate: String): Int? {
    val birthYear = birthYear(birthDate) ?: return null
    val competitionYear = competitionYear(competitionDate) ?: return null
    return competitionYear - birthYear
}

fun personGenderChoice(raw: String): String = personGenderKind(raw).orEmpty()

fun storedPersonGender(raw: String): String = when (personGenderKind(raw)) {
    "male" -> "мужской"
    "female" -> "женский"
    else -> raw.trim()
}

data class BirthDateParts(val year: Int, val month: Int, val day: Int)

fun parseBirthDateParts(raw: String): BirthDateParts? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val iso = ISO_DATE.matchEntire(value)
    val ymd = if (iso != null) {
        Triple(iso.groupValues[1].toInt(), iso.groupValues[2].toInt(), iso.groupValues[3].toInt())
    } else {
        val dotted = DOT_DATE.matchEntire(value) ?: return null
        Triple(dotted.groupValues[3].toInt(), dotted.groupValues[2].toInt(), dotted.groupValues[1].toInt())
    }
    return try {
        val date = java.time.LocalDate.of(ymd.first, ymd.second, ymd.third)
        BirthDateParts(date.year, date.monthValue, date.dayOfMonth)
    } catch (_: Exception) {
        null
    }
}

fun formatBirthDateDot(parts: BirthDateParts): String =
    "%02d.%02d.%04d".format(parts.day, parts.month, parts.year)

fun formatBirthDateIso(parts: BirthDateParts): String =
    "%04d-%02d-%02d".format(parts.year, parts.month, parts.day)

fun displayBirthDate(raw: String): String {
    val parts = parseBirthDateParts(raw) ?: return raw.trim()
    return formatBirthDateDot(parts)
}

fun storedBirthDate(raw: String): String {
    val parts = parseBirthDateParts(raw) ?: return raw.trim()
    return formatBirthDateIso(parts)
}

internal fun birthYear(raw: String): Int? = parseBirthDateParts(raw)?.year

internal fun competitionYear(raw: String): Int? =
    ISO_DATE.matchEntire(raw.trim())?.groupValues?.get(1)?.toIntOrNull()

private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
private val DOT_DATE = Regex("""^(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})$""")

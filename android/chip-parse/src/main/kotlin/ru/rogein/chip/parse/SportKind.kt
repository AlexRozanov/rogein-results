package ru.rogein.chip.parse

const val SPORT_ORIENT = "orient"
const val SPORT_ROGAINE = "rogaine"

fun normalizeSportKind(raw: String?): String {
    val value = raw.orEmpty().trim().lowercase()
    return if (value == SPORT_ROGAINE) SPORT_ROGAINE else SPORT_ORIENT
}

fun isRogaine(raw: String?): Boolean = normalizeSportKind(raw) == SPORT_ROGAINE

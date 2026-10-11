package ru.rogein.chip.event

private val TRANSLIT = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
    'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
    'й' to "j", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
    'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
    'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
    'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
    'э' to "e", 'ю' to "yu", 'я' to "ya", 'і' to "i", 'ї' to "i",
    'є' to "e", 'ґ' to "g",
)

fun transliterateTitle(raw: String): String {
    val out = StringBuilder()
    for (ch in raw.trim().lowercase()) {
        when {
            TRANSLIT.containsKey(ch) -> out.append(TRANSLIT.getValue(ch))
            ch in 'a'..'z' || ch in '0'..'9' -> out.append(ch)
            else -> out.append('-')
        }
    }
    return out.toString().replace(Regex("-+"), "-").trim('-')
}

fun buildEventSlug(date: String, title: String): String? {
    val iso = date.trim()
    val name = transliterateTitle(title)
    if (!iso.matches(Regex("""\d{4}-\d{2}-\d{2}""")) || name.isEmpty()) return null
    return "start-$iso-$name"
}

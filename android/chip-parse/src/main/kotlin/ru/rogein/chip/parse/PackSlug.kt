package ru.rogein.chip.parse

/**
 * Сверка slug пакета с локальным стартом на телефоне.
 * - оба заданы и разные → ошибка;
 * - пакет без slug (старый файл) → ок;
 * - локальный пустой → ок (вызывающий может принять slug из пакета).
 */
fun packSlugConflict(localSlug: String, packSlug: String): String? {
    val local = localSlug.trim()
    val pack = packSlug.trim()
    if (local.isNotEmpty() && pack.isNotEmpty() && local != pack) {
        return "Slug не совпадает: на телефоне «$local», в пакете «$pack». Пакет не применён."
    }
    return null
}

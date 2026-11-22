package ru.rogein.chip.field

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * HTTP-заголовки только ASCII. Имя аппарата (кириллица) — percent-encoded UTF-8.
 */
object FieldHttpHeaders {
    fun encodeDeviceName(raw: String): String {
        val value = raw.trim().ifBlank { FieldDeviceStore.DEFAULT_NAME }
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    fun decodeDeviceName(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return FieldDeviceStore.DEFAULT_NAME
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value).ifBlank { FieldDeviceStore.DEFAULT_NAME }
    }
}

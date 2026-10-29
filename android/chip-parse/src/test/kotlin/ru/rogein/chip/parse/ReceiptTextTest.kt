package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiptTextTest {
    @Test
    fun printsHeaderSplitsAndResult() {
        val text = ReceiptText.format(
            SplitReceipt(
                date = "2026-10-27",
                title = "Малахит-спринт",
                course = "D1",
                bib = "8",
                name = "Иванов Иван",
                marks = listOf(
                    ReceiptMark(241, 10 * 3600),
                    ReceiptMark(31, 10 * 3600 + 5 * 60 + 12),
                    ReceiptMark(240, 10 * 3600 + 72 * 60 + 5),
                ),
                startCp = 241,
                elapsedSeconds = 72 * 60 + 5,
                status = STATUS_OK,
            ),
        )
        assertTrue(text.contains("27 октября 2026"))
        assertTrue(text.contains("Малахит-спринт"))
        assertTrue(text.contains("Дистанция D1"))
        assertTrue(text.contains("№ 8  Иванов Иван"))
        assertTrue(text.contains("241"))
        assertTrue(text.contains(" 31"))
        assertTrue(text.contains("240"))
        assertTrue(text.contains("Результат  1:12:05"))
        assertTrue(text.contains("Отметка    OK"))
        assertTrue(!text.contains("/km"))
    }
}

package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun printDialogStatusIsOkOrDqReason() {
        assertEquals("OK", resultStatusLine(STATUS_OK))
        assertEquals(
            "Дисквалификация: дистанция пройдена не полностью, нет отметки финиша",
            resultStatusLine(STATUS_DQ, listOf("course_incomplete", "finish_missing")),
        )
        val text = ReceiptText.format(
            SplitReceipt(
                date = "2026-10-27",
                title = "",
                course = "D1",
                bib = "8",
                name = "Иванов",
                marks = emptyList(),
                startCp = 241,
                elapsedSeconds = 0,
                status = STATUS_DQ,
            ),
        )
        assertTrue(text.contains("Отметка    дискв."))
        assertTrue(!text.contains("превышение контрольного времени"))
    }

    @Test
    fun rogaineReceiptUsesStartFinishLabelsAndPoints() {
        val text = ReceiptText.format(
            SplitReceipt(
                date = "2026-08-02",
                title = "Тестовый рогейн",
                course = "4ч",
                bib = "8",
                name = "Иванов Иван",
                marks = listOf(
                    ReceiptMark(241, 10 * 3600),
                    ReceiptMark(31, 10 * 3600 + 15 * 60),
                    ReceiptMark(240, 14 * 3600 + 3 * 60),
                ),
                startCp = 241,
                elapsedSeconds = 4 * 3600 + 3 * 60,
                status = STATUS_OK,
                finishCp = 240,
                pointsRaw = 12,
                penaltyPoints = 3,
                pointsFinal = 9,
                rogaine = true,
            ),
        )
        assertTrue(text.contains("Формат 4ч"))
        assertTrue(text.contains("Старт           10:00:00"))
        assertTrue(text.contains(" 1  31  10:15:00 00:15:00"))
        assertTrue(text.contains("Финиш  14:03:00  03:48:00"))
        assertTrue(!text.contains(" 1 241"))
        assertTrue(text.contains("Все очки   12"))
        assertTrue(text.contains("Штрафные очки 3"))
        assertTrue(text.contains("Очки с учётом штрафных 9"))
        assertTrue(text.contains("Отметка    OK"))
    }
}

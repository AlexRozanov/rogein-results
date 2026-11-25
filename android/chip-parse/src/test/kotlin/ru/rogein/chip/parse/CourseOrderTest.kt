package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CourseOrderTest {
    @Test
    fun clusterAnyOrderOk() {
        val controls = listOf(31, 32, 33, 34, 35, 36)
        val random = listOf(false, false, true, true, true, false)
        assertEquals(
            6,
            courseTakenWithOrder(listOf(31, 32, 33, 35, 34, 36), controls, random),
        )
        assertEquals(
            6,
            courseTakenWithOrder(listOf(31, 32, 35, 33, 34, 36), controls, random),
        )
    }

    @Test
    fun earlyFixedBeforeClusterDoneFails() {
        val controls = listOf(31, 32, 33, 34, 35, 36)
        val random = listOf(false, false, true, true, true, false)
        assertEquals(
            3,
            courseTakenWithOrder(listOf(31, 32, 33, 36, 34, 35), controls, random),
        )
    }

    @Test
    fun orderedCourseStillWorks() {
        assertEquals(
            3,
            courseTakenWithOrder(listOf(31, 99, 32, 33), listOf(31, 32, 33)),
        )
        assertEquals(
            1,
            courseTakenWithOrder(listOf(31, 33), listOf(31, 32, 33)),
        )
    }
}

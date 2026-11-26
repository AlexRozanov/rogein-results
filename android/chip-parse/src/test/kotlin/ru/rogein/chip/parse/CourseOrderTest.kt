package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CourseOrderTest {
    @Test
    fun clusterAnyOrderOk() {
        val controls = listOf(31, 32, 33, 34, 35, 36)
        val random = listOf(0, 0, 1, 1, 1, 0)
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
        val random = listOf(0, 0, 1, 1, 1, 0)
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

    @Test
    fun adjacentOrderedClustersFullOrderOk() {
        val controls = (71..79).toList()
        val groups = listOf(0, 0, 0, 1, 1, 1, 2, 2, 0)
        assertEquals(
            9,
            courseTakenWithOrder(listOf(71, 72, 73, 76, 74, 75, 78, 77, 79), controls, groups),
        )
        assertEquals(
            listOf(
                CourseSegment.Fixed(71),
                CourseSegment.Fixed(72),
                CourseSegment.Fixed(73),
                CourseSegment.Cluster(listOf(74, 75, 76)),
                CourseSegment.Cluster(listOf(77, 78)),
                CourseSegment.Fixed(79),
            ),
            courseSegments(controls, groups),
        )
    }

    @Test
    fun adjacentClusterEarlyPunchOfNextClusterStops() {
        val controls = (71..79).toList()
        val groups = listOf(0, 0, 0, 1, 1, 1, 2, 2, 0)
        assertEquals(
            5,
            courseTakenWithOrder(listOf(71, 72, 73, 74, 75, 77, 76, 78, 79), controls, groups),
        )
    }
}

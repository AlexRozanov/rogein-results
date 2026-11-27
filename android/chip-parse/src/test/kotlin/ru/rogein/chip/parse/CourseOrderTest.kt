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
    fun earlyFixedBeforeClusterDoneIsSkippedLikeFixedOrder() {
        val controls = listOf(31, 32, 33, 34, 35, 36)
        val random = listOf(0, 0, 1, 1, 1, 0)
        // Early 36 ignored; cluster completes; final 36 missing → 5/6.
        assertEquals(
            5,
            courseTakenWithOrder(listOf(31, 32, 33, 36, 34, 35), controls, random),
        )
        assertEquals(
            6,
            courseTakenWithOrder(listOf(31, 32, 33, 36, 34, 35, 36), controls, random),
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
    fun adjacentClusterEarlyPunchOfNextClusterSkipped() {
        val controls = (71..79).toList()
        val groups = listOf(0, 0, 0, 1, 1, 1, 2, 2, 0)
        // Early 77 skipped; first cluster finishes; 77 already consumed → 7/9.
        assertEquals(
            7,
            courseTakenWithOrder(listOf(71, 72, 73, 74, 75, 77, 76, 78, 79), controls, groups),
        )
        // 78/77 taken before finishing first; no fresh second-cluster punches → 6/9.
        assertEquals(
            6,
            courseTakenWithOrder(listOf(71, 72, 73, 74, 75, 78, 77, 76, 79), controls, groups),
        )
    }

    @Test
    fun interveningFutureCpBetweenClustersIgnoredWhenCorrected() {
        // 71→72→73→[74 75]→[76 77 78]→[79 80 81 82]→83→84
        val controls = listOf(71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84)
        val groups = listOf(0, 0, 0, 1, 1, 2, 2, 2, 3, 3, 3, 3, 0, 0)
        assertEquals(
            14,
            courseTakenWithOrder(
                listOf(71, 72, 73, 74, 75, 84, 76, 78, 77, 80, 79, 81, 82, 83, 84),
                controls,
                groups,
            ),
        )
        assertEquals(
            14,
            courseTakenWithOrder(
                listOf(71, 72, 73, 74, 75, 76, 78, 77, 83, 80, 79, 82, 81, 83, 84),
                controls,
                groups,
            ),
        )
    }
}

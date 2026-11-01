package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProtocolStatusTest {
    @Test
    fun notIssuedAndNoFinishIsNotStarted() {
        assertEquals(
            ProtocolRunStatus.NotStarted,
            protocolRunStatus(false, "", "D1", emptyList()),
        )
    }

    @Test
    fun issuedWithoutFinishIsOnCourse() {
        assertEquals(
            ProtocolRunStatus.OnCourse,
            protocolRunStatus(true, "D1", "D1", emptyList()),
        )
    }

    @Test
    fun finishedIssuedCourseIsFinished() {
        assertEquals(
            ProtocolRunStatus.Finished,
            protocolRunStatus(true, "D1", "D1", listOf("D1")),
        )
    }

    @Test
    fun finishedD1ThenIssuedD2IsOnCourse() {
        assertEquals(
            ProtocolRunStatus.OnCourse,
            protocolRunStatus(true, "D2", "D2", listOf("D1")),
        )
    }

    @Test
    fun finishWithoutIssueIsFinished() {
        assertEquals(
            ProtocolRunStatus.Finished,
            protocolRunStatus(false, "", "D1", listOf("D1")),
        )
    }
}

package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChipIdentityTest {
    @Test
    fun normalizeStripsSeparators() {
        assertEquals("04AABB", normalizeChipUid("04:aa:bb"))
        assertEquals("04AABB", normalizeChipUid(" 04-AA BB "))
        assertEquals("", normalizeChipUid("  "))
    }

    @Test
    fun sameUidSameCourseIsReread() {
        val existing = listOf(ref("q1", "04:AA", 10, "6ч"))
        assertEquals(
            FinishEnqueueKind.SameChip,
            classifyFinishEnqueue("04AA", 10, "6ч", existing),
        )
    }

    @Test
    fun sameUidOtherCourseIsFresh() {
        val existing = listOf(ref("q1", "04AA", 10, "6ч"))
        assertEquals(
            FinishEnqueueKind.Fresh,
            classifyFinishEnqueue("04AA", 10, "8ч", existing),
        )
    }

    @Test
    fun otherUidSameNumberKept() {
        val existing = listOf(ref("q1", "04AA", 10, "6ч"))
        assertEquals(
            FinishEnqueueKind.OtherChipSameNumber,
            classifyFinishEnqueue("04BB", 10, "6ч", existing),
        )
    }

    @Test
    fun blankUidFallsBackToNumber() {
        val existing = listOf(ref("q1", "", 10, "6ч"))
        assertEquals(
            FinishEnqueueKind.SameChip,
            classifyFinishEnqueue("", 10, "6ч", existing),
        )
    }

    @Test
    fun conflictIdsForTwoPhysicalChips() {
        val ids = numberConflictIds(
            listOf(
                ref("a", "04AA", 10, "6ч"),
                ref("b", "04BB", 10, "6ч"),
                ref("c", "04CC", 11, "6ч"),
            ),
        )
        assertEquals(setOf("a", "b"), ids)
    }

    @Test
    fun issueWarnsWhenNumberBoundToOtherChip() {
        val info = classifyIssueUid(
            10,
            "04BB",
            listOf(IssuedChipRef(10, issued = true, issuedUid = "04:AA")),
        )
        assertEquals(IssueUidConflict.NumberBoundOtherChip, info.kind)
    }

    @Test
    fun issueWarnsWhenChipAlreadyIssuedAsOtherNumber() {
        val info = classifyIssueUid(
            11,
            "04AA",
            listOf(IssuedChipRef(10, issued = true, issuedUid = "04AA")),
        )
        assertEquals(IssueUidConflict.ChipBoundOtherNumber, info.kind)
        assertEquals(10, info.otherId)
    }

    @Test
    fun issueSameChipIsOk() {
        val info = classifyIssueUid(
            10,
            "04AA",
            listOf(IssuedChipRef(10, issued = true, issuedUid = "04:AA")),
        )
        assertEquals(IssueUidConflict.None, info.kind)
    }

    @Test
    fun canChangeNumberWhenDuplicatedOrUidMismatch() {
        assertTrue(canChangeFinishNumber(10, "04BB", 2, "04AA"))
        assertTrue(canChangeFinishNumber(10, "04BB", 1, "04AA"))
        assertFalse(canChangeFinishNumber(10, "04AA", 1, "04AA"))
        assertFalse(canChangeFinishNumber(10, "04AA", 1, ""))
        assertFalse(canChangeFinishNumber(null, "04AA", 1, "04AA"))
    }

    private fun ref(id: String, uid: String, logicalId: Int, course: String) =
        FinishQueueRef(id, uid, logicalId, course)
}

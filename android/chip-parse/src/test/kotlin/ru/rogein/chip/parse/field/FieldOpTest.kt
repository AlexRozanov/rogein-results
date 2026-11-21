package ru.rogein.chip.parse.field

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.json.JSONObject

class FieldOpTest {
    @Test
    fun chipIssuePayloadRoundTrips() {
        val raw = chipIssuePayloadJson(
            logicalId = 12,
            uid = "E0:02:24:01",
            course = "D1",
            cleared = true,
            overwrite = true,
        )
        val obj = JSONObject(raw)
        assertEquals(12, obj.getInt("logical_id"))
        assertEquals("E0:02:24:01", obj.getString("uid"))
        assertEquals("D1", obj.getString("course"))
        assertTrue(obj.getBoolean("cleared"))
        assertTrue(obj.getBoolean("overwrite"))
    }

    @Test
    fun chipReadPayloadIncludesPunchesAndFlags() {
        val raw = chipReadPayloadJson(
            queueId = "q-1",
            uid = "AABB",
            logicalId = 7,
            punches = listOf(FieldOpPunch(241, 3600), FieldOpPunch(240, 7200)),
            readAtMs = 1_700_000_000_000L,
            courseHint = "D2",
            replace = true,
            syntheticFinish = false,
            skipMissingFinish = true,
            source = "nfc",
            blockCount = 64,
        )
        val obj = JSONObject(raw)
        assertEquals("q-1", obj.getString("queue_id"))
        assertEquals(7, obj.getInt("logical_id"))
        assertEquals(2, obj.getJSONArray("punches").length())
        assertEquals(241, obj.getJSONArray("punches").getJSONObject(0).getInt("cp"))
        assertEquals(3600, obj.getJSONArray("punches").getJSONObject(0).getInt("seconds"))
        assertTrue(obj.getBoolean("replace"))
        assertTrue(obj.getBoolean("skip_missing_finish"))
        assertEquals(64, obj.getInt("block_count"))
    }

    @Test
    fun punchDaySecondsMatchesClock() {
        assertEquals(12 * 3600 + 34 * 60 + 56, punchDaySeconds(12, 34, 56))
    }

    @Test
    fun envelopeRoundTripsThroughJson() {
        val op = FieldOpEnvelope(
            schema = FIELD_OP_SCHEMA,
            opId = "op-1",
            deviceId = "dev-a",
            deviceSeq = 3,
            eventSlug = "test-slug",
            clockMs = 100L,
            actor = "Выдача",
            type = FieldOpType.CHIP_ISSUE,
            payloadJson = chipIssuePayloadJson(1, "AA", "D1", overwrite = true),
        )
        val parsed = fieldOpEnvelopeFromJson(fieldOpEnvelopeToJson(op))
        assertEquals(op.opId, parsed.opId)
        assertEquals(op.deviceSeq, parsed.deviceSeq)
        assertEquals(op.type, parsed.type)
        assertEquals(1, parseChipIssuePayload(parsed.payloadJson).logicalId)
    }

    @Test
    fun punchFromDaySecondsRoundTrips() {
        val punch = punchFromDaySeconds(31, punchDaySeconds(9, 8, 7))
        assertEquals(31, punch.cp)
        assertEquals(9, punch.hour)
        assertEquals(8, punch.minute)
        assertEquals(7, punch.second)
    }

    @Test
    fun opsBundleParsesList() {
        val op = FieldOpEnvelope(
            opId = "x",
            deviceId = "d",
            deviceSeq = 1,
            eventSlug = "s",
            clockMs = 1,
            actor = "a",
            type = FieldOpType.CHIP_READ,
            payloadJson = chipReadPayloadJson(
                queueId = "q",
                uid = "u",
                logicalId = null,
                punches = emptyList(),
                readAtMs = 2,
                courseHint = "",
            ),
        )
        val again = parseOpsBundle(opsBundleJson(listOf(op)))
        assertEquals(1, again.size)
        assertEquals("q", parseChipReadPayload(again[0].payloadJson).queueId)
    }

    @Test
    fun teamJoinAndCorrectionPayloadsRoundTrip() {
        val join = parsePersonTeamJoinPayload(
            personTeamJoinPayloadJson(10, 11, teamId = 3, droppedTeamId = 7),
        )
        assertEquals(10, join.logicalId)
        assertEquals(11, join.otherLogicalId)
        assertEquals(3, join.teamId)
        assertEquals(7, join.droppedTeamId)

        val add = parseCorrectionAddCpPayload(
            correctionAddCpPayloadJson("cid-1", 5, 31, course = "D1", time = "01:02:03"),
        )
        assertEquals("cid-1", add.correctionId)
        assertEquals(31, add.cp)
        assertEquals("01:02:03", add.time)

        val remove = parseCorrectionRemoveCpPayload(
            correctionRemoveCpPayloadJson("cid-2", null, 44, "from_course"),
        )
        assertEquals(null, remove.logicalId)
        assertEquals(44, remove.cp)

        val del = parseCorrectionDeletePayload(correctionDeletePayloadJson("cid-9"))
        assertEquals("cid-9", del.correctionId)
    }

    @Test
    fun eventConfigIncludesControlMinutes() {
        val raw = eventConfigPayloadJson(controlMinutes = 90, startCp = 241, finishCp = 240)
        val obj = parseEventConfigPayload(raw)
        assertEquals(90, obj.getInt("control_minutes"))
        assertEquals(241, obj.getInt("start_cp"))
        assertEquals(240, obj.getInt("finish_cp"))
    }

    @Test
    fun personUpsertAndCoursePayloadsRoundTrip() {
        val upsert = parsePersonUpsertPayload(
            personUpsertPayloadJson(
                logicalId = 12,
                name = "Иванов",
                gender = "M",
                birthDate = "2010-01-02",
                course = "D1",
                courses = listOf("D1", "D2"),
            ),
        )
        assertEquals(12, upsert.logicalId)
        assertEquals("Иванов", upsert.name)
        assertEquals(listOf("D1", "D2"), upsert.courses)

        val course = parsePersonCoursePayload(
            personCoursePayloadJson(12, "D2", listOf("D1", "D2")),
        )
        assertEquals("D2", course.course)
        assertEquals(2, course.courses.size)

        assertEquals(12, parsePersonDeletePayload(personDeletePayloadJson(12)).logicalId)
    }

    @Test
    fun queueCourseExclusionPayloadsRoundTrip() {
        assertEquals("q-1", parseQueueDropPayload(queueDropPayloadJson("q-1")).queueId)
        val re = parseFinishReassignPayload(finishReassignPayloadJson("q-1", 3, 5, "AA"))
        assertEquals(3, re.oldLogicalId)
        assertEquals(5, re.newLogicalId)
        val course = parseCourseUpsertPayload(
            courseUpsertPayloadJson("D1", "D0", listOf(31, 32), 1000, 50, "[]"),
        )
        assertEquals("D1", course.name)
        assertEquals(listOf(31, 32), course.controls)
        val ex = parseExclusionUpsertPayload(
            exclusionUpsertPayloadJson("e1", "D1", 31, 32, 90),
        )
        assertEquals(90, ex.maxLegSeconds)
    }
}

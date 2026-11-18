package com.aktcl.aron.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.record.RecordMapping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Contract 1.19.0 (train 117): the phone's own close of a forgotten day carries `closed_by = "phone"` in the check-out's payload (derived from the stored event: captured on a later Dhaka date than
 * its business date); the rep's own check-out and every check-in send NO `closed_by` (the server reads that as `rep`), so their bytes are exactly what they were before.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AttendanceClosedByPayloadTest {
    private val at = "2026-10-06T08:00:00.000Z"
    private fun payload(kind: String, capturedAt: String): JsonObject {
        val (att, fix) = TestRows.attendance()
        val e: AttendanceEventEntity = att.copy(kind = kind, meta = att.meta.copy(capturedAt = capturedAt))
        return Json.parseToJsonElement(RecordMapping.attendance(e, fix, at).payloadJson).jsonObject["payload"]!!.jsonObject
    }

    @Test fun thePhonesCloseOfAForgottenDaySaysPhone() {
        val p = payload("check_out", "2026-10-06T08:00:00.000Z") // business date 2026-10-05, captured on the next Dhaka day
        assertEquals("phone", p["closed_by"]!!.jsonPrimitive.content)
    }

    @Test fun theRepsOwnCheckOutAndEveryCheckInSendNoClosedBy() {
        assertFalse("rep's check-out", payload("check_out", "2026-10-05T11:00:00.000Z").containsKey("closed_by"))
        assertFalse("check-in", payload("check_in", "2026-10-05T04:31:07.120Z").containsKey("closed_by"))
        assertTrue("a late check-IN is never a phone close", !payload("check_in", "2026-10-06T08:00:00.000Z").containsKey("closed_by"))
    }
}

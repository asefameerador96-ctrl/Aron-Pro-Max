package com.aktcl.aron.core.sync

import com.aktcl.aron.contract.RecordAck
import com.aktcl.aron.core.database.entity.OutboxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract 1.19.0: a second close of the same day (the server answers `attendance_duplicate`) is DONE for the phone's own close, never a refusal the rep sees; every other row keeps the old rule. */
class AckRulesClosedByTest {
    private val dup = RecordAck("u", "attendance_event", "rejected", "attendance_duplicate", false)

    @Test fun aPhoneCloseTheServerAlreadyHoldsIsDone() {
        assertEquals(AckTarget(OutboxState.ACKED, null), AckRules.target(dup, 0, 5, phoneClose = true))
    }

    @Test fun anyOtherAttendanceDuplicateIsStillRefusedAsBefore() {
        assertEquals(AckTarget(OutboxState.REJECTED, "attendance_duplicate"), AckRules.target(dup, 0, 5, phoneClose = false))
    }

    @Test fun otherCodesOfAPhoneCloseKeepTheOldRule() {
        assertEquals(AckTarget(OutboxState.REJECTED, "schema_invalid"), AckRules.target(RecordAck("u", "attendance_event", "rejected", "schema_invalid", false), 0, 5, phoneClose = true))
        assertEquals(AckTarget(OutboxState.ACKED, null), AckRules.target(RecordAck("u", "attendance_event", "accepted"), 0, 5, phoneClose = true))
    }

    @Test fun thePhoneCloseIsRecognisedFromThePayload() {
        assertTrue(AckRules.isPhoneClose("attendance_event", """{"type":"attendance_event","payload":{"kind":"check_out","closed_by":"phone"}}"""))
        assertFalse(AckRules.isPhoneClose("attendance_event", """{"payload":{"kind":"check_out"}}"""))
        assertFalse(AckRules.isPhoneClose("attendance_event", """{"payload":{"kind":"check_out","closed_by":"rep"}}"""))
        assertFalse(AckRules.isPhoneClose("memo", """{"payload":{"closed_by":"phone"}}"""))
        assertFalse(AckRules.isPhoneClose("attendance_event", "not json"))
    }
}

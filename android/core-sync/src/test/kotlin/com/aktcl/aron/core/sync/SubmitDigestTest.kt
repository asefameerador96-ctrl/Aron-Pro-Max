package com.aktcl.aron.core.sync

import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.TransportFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract 1.9 row 4 shape (lane-local parser): the `digest` of GET /v1/sync/totals, and its 30 s cache. */
class SubmitDigestTest {
    private val body = """
        {"totals": {"business_date": "2026-10-24", "as_of": "2026-10-24T06:00:00Z", "by_type": {}, "money": {}}, "day_states": [],
         "digest": {"business_date": "2026-10-24", "device_uuid": "11111111-1111-4111-8111-111111111111",
           "by_type": {"memo": {"device": {"received": 4, "acknowledged": 3}, "login": {"acknowledged": 10}},
                       "visit": {"device": {"received": 3, "acknowledged": 3}, "login": {"acknowledged": 9}}},
           "rejected": [
             {"client_uuid": "22222222-2222-4222-8222-222222222222", "type": "memo_line", "business_date": "2026-10-24", "status": "rejected",
              "code": "price_invalid", "final": true, "from_this_device": false, "memo_no": "sr1001-261024-002", "outlet_id": 9001,
              "last_received_at": "2026-10-24T05:00:00Z", "future_field": 1}],
           "rejected_truncated": false}}
    """.trimIndent()

    @Test fun theDigestIsReadAndUnknownFieldsAreIgnored() {
        val d = SubmitDigest.parse(body)!!
        assertEquals(mapOf("memo" to 4, "visit" to 3), d.deviceReceived)
        val r = d.rejected.single()
        assertEquals("memo_line", r.type); assertEquals("price_invalid", r.code); assertTrue(r.final); assertFalse(r.fromThisDevice)
        assertEquals("sr1001-261024-002", r.memoNo); assertEquals(9001L, r.outletId)
        assertFalse(d.truncated)
    }

    @Test fun anOlderServerOrAWebTokenGivesNoDeviceCounts() {
        assertNull(SubmitDigest.parse("""{"totals": {}, "day_states": []}"""))
        assertNull(SubmitDigest.parse("not json"))
        val web = SubmitDigest.parse("""{"digest": {"by_type": {"memo": {"device": null, "login": {"acknowledged": 2}}}, "rejected": [], "rejected_truncated": false}}""")!!
        assertNull(web.deviceReceived)
    }

    @Test fun oneReadPer30SecondsAndNothingOfflineOrSignedOut() = runTest {
        var now = 0L
        var calls = 0
        var answer: ApiResult<SubmitDigest?> = ApiResult.Success(SubmitDigest.parse(body), com.aktcl.aron.core.network.ResponseMeta(200, null, null, null, null, null, null, null))
        val auth = object : UploadAuth { override suspend fun token(userId: Long) = "t"; override suspend fun refresh(userId: Long, rejected: String?) = false }
        val src = SubmitDigestSource(7, auth, { _, _ -> calls++; answer }, { now })
        assertEquals(4, src.digest("2026-10-24")!!.deviceReceived!!["memo"])
        now = 29_000; src.digest("2026-10-24")
        assertEquals(1, calls)
        now = 31_000; answer = ApiResult.Transport(TransportFailure.OFFLINE)
        assertNull(src.digest("2026-10-24"))
        assertEquals(2, calls)
        val out = SubmitDigestSource(7, object : UploadAuth { override suspend fun token(userId: Long): String? = null; override suspend fun refresh(userId: Long, rejected: String?) = false }, { _, _ -> calls++; answer }, { now })
        assertNull(out.digest("2026-10-24")); assertEquals(2, calls)
    }
}

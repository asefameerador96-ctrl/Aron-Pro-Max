package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutletVerifyTest {
    private fun req(uuid: String, st: String, by: Long, at: String, type: String = "new") =
        """{"request_uuid":"$uuid","request_type":"$type","status":"$st","requested_by_user_id":$by,"requested_at":"$at","outlet_name":"Shop $uuid"}"""

    private fun sup(vararg r: String) = """{"team":[],"pending_outlet_requests":[${r.joinToString(",")}]}"""

    @Test fun pendingListsOthersOldestFirstAndHidesOwnAndDecided() {
        val s = sup(req("b", "pending", 5, "2026-10-17T05:00:00Z"), req("a", "pending", 6, "2026-10-17T03:00:00Z"), req("own", "pending", 99, "2026-10-17T01:00:00Z"), req("v", "verified", 5, "2026-10-17T02:00:00Z"), req("d", "pending", 5, "2026-10-17T04:00:00Z"))
        assertEquals(listOf("a", "d", "b"), OutletVerify.pending(s, 99).map { it.uuid })
        assertEquals(listOf("a", "b"), OutletVerify.pending(s, 99, setOf("d")).map { it.uuid })
    }

    @Test fun malformedOrMissingSectionIsEmpty() {
        assertTrue(OutletVerify.pending(null, 1).isEmpty())
        assertTrue(OutletVerify.pending("not json", 1).isEmpty())
        assertTrue(OutletVerify.pending("""{"pending_outlet_requests":[{"request_uuid":"x"}]}""", 1).isEmpty())
    }

    @Test fun newOutletNeedsSubChannelAndGeoClassOnlyWhenVerifying() {
        assertEquals(setOf(VerifyError.SUB_CHANNEL_REQUIRED, VerifyError.GEO_CLASS_REQUIRED), OutletVerify.validate("new", true, null, null))
        assertEquals(setOf(VerifyError.GEO_CLASS_REQUIRED), OutletVerify.validate("new", true, 4, "Mountain"))
        assertTrue(OutletVerify.validate("new", true, 4, "Urban").isEmpty())
        assertTrue(OutletVerify.validate("new", false, null, null).isEmpty()) // Cancel makes no demands
        assertTrue(OutletVerify.validate("close", true, null, null).isEmpty())
    }

    @Test fun subChannelsKeepOnlyItemsWithAnId() {
        val j = """[{"list_key":"x","items":[]},{"list_key":"sub_channel","items":[{"id":4,"code":"gen","label_en":"General","label_bn":"সাধারণ","sort":1},{"code":"nid","label_en":"No id","sort":2}]}]"""
        assertEquals(listOf(SubChannel(4, "General", "সাধারণ")), OutletVerify.subChannels(j))
        assertTrue(OutletVerify.subChannels(null).isEmpty())
    }

    @Test fun detailReadsProposedFixPhotosAndFlags() {
        val d = OutletVerify.detail("""{"request_uuid":"u","request_type":"info","outlet_id":7,"proposed":{"name":"New","owner_name":"O"},"request_fix":{"lat":23.5,"lng":90.25},"photos":["p"],"flags":["low_accuracy"]}""")!!
        assertEquals("info", d.type); assertEquals(7L, d.outletId); assertEquals(23.5, d.lat!!, 0.0); assertEquals(1, d.photos); assertEquals(listOf("low_accuracy"), d.flags)
        assertNull(OutletVerify.detail("[]"))
        assertNull(OutletVerify.detail(null))
    }

    @Test fun changesShowOnlyProposedFieldsThatDiffer() {
        val c = OutletVerify.changes(mapOf("name" to "New", "owner_name" to "Same", "cluster_id" to "9"), mapOf("name" to "Old", "owner_name" to "Same"))
        assertEquals(listOf(FieldChange("name", "Old", "New")), c)
    }

    @Test fun teamNamesByUserId() {
        assertEquals(mapOf(7L to "Rahim"), OutletVerify.teamNames("""{"team":[{"user_id":7,"username":"sr7","full_name":"Rahim"},{"username":"x"}]}"""))
        assertTrue(OutletVerify.teamNames(null).isEmpty())
    }

    @Test fun onlyAPendingRequestOfAnotherUserCanBeDecided() {
        val base = OutletVerify.detail("""{"request_type":"new","status":"pending","requested_by_user_id":5,"proposed":{}}""")!!
        assertTrue(OutletVerify.decidable(base, 99))
        assertTrue(!OutletVerify.decidable(base, 5))
        assertTrue(!OutletVerify.decidable(base.copy(status = "verified"), 99))
        assertEquals("pending", OutletVerify.detail("""{"request_type":"new","proposed":{}}""")!!.status)
    }
}

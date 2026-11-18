package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CampaignPhotoModelTest {
    // the server's shape (BundleProgrammes.kt pending list; ids and counts are numbers)
    private val prog = """{"programmes":[],"loyalty":[],"gifts":[],"astha_targets":[],"astha_as_of":"2026-11-18T05:00:00Z","astha_truncated":false,"truncated_sections":[],"gift_assignments":[],
      "pending_gift_verifications":[{"redemption_client_uuid":"r1","outlet_id":10,"gift_id":4,"qty":3,"photos_captured":1},{"redemption_client_uuid":"bad","outlet_id":10,"gift_id":4,"qty":0,"photos_captured":0}]}"""

    @Test fun theServerListIsReadAndAnImpossibleQuantityIsDropped() {
        assertEquals(listOf(ServerPendingGift("r1", 10, 4, 3, 1)), CampaignPhotoModel.serverPending(prog))
        assertEquals(emptyList<ServerPendingGift>(), CampaignPhotoModel.serverPending(null))
    }

    @Test fun aServerGroupKeepsItsPhotosAndThisPhonesOwnPhotosAreExact() {
        val server = CampaignPhotoModel.serverPending(prog)
        assertEquals(listOf(2, 3), CampaignPhotoModel.groups(server, emptyList(), emptyList()).single().openUnits) // the server's one photo counts as the lowest unit
        val here = LocalGiftPhoto("campaign", 10, null, "r1", 2, "pending", giftId = 4) // this phone photographed unit 2, not sent yet
        assertEquals(listOf(3), CampaignPhotoModel.groups(server, emptyList(), listOf(here)).single().openUnits) // unit 1 (server) and unit 2 (here)
        // once acked the server's count of 1 IS that photo (unit 2): not counted twice, units 1 and 3 stay open
        val acked = here.copy(state = "acked")
        assertEquals(listOf(1, 3), CampaignPhotoModel.groups(server, emptyList(), listOf(acked)).single().openUnits)
    }

    @Test fun aRedemptionOnlyThisPhoneHoldsNeedsEveryUnitAndAFullyPhotographedOneLeavesTheList() {
        val local = listOf(LocalCampaignGift("r9", 11, 4, 2, "pending"), LocalCampaignGift("r8", 11, 4, 1, "rejected"))
        val g = CampaignPhotoModel.groups(emptyList(), local, emptyList()).single() // the refused redemption needs nothing
        assertEquals("r9", g.redemptionUuid); assertEquals(listOf(1, 2), g.openUnits)
        val both = listOf(LocalGiftPhoto("campaign", 11, null, "r9", 1, "pending", 4), LocalGiftPhoto("campaign", 11, null, "r9", 2, "pending", 4))
        assertEquals(emptyList<CampaignGroup>(), CampaignPhotoModel.groups(emptyList(), local, both))
        // a photo the server refused leaves its unit open again
        assertEquals(listOf(2), CampaignPhotoModel.groups(emptyList(), local, listOf(both[0], both[1].copy(state = "rejected"))).single().openUnits)
    }

    @Test fun aServerListedRedemptionIsNotAlsoTakenFromThePhone() {
        val groups = CampaignPhotoModel.groups(CampaignPhotoModel.serverPending(prog), listOf(LocalCampaignGift("r1", 10, 4, 3, "acked")), emptyList())
        assertEquals(1, groups.size)
    }

    @Test fun theUnitRecordIsACampaignPhotoOfItsRedemptionAndUnit() {
        val g = CampaignGroup("r1", 10, 4, 3, emptySet())
        val fix = kotlinx.serialization.json.buildJsonObject { put("lat", kotlinx.serialization.json.JsonPrimitive(23.79)) }
        val p = CampaignPhotoModel.planUnit(g, 2, "ph", fix, "u1")
        assertEquals("gift_photo", p.type); assertEquals("u1", p.clientUuid); assertEquals(0, p.rank)
        assertNull(com.aktcl.aron.core.database.record.ProgrammePayloads.problem("gift_photo", p.payload))
        assertEquals("2", p.payload["unit_no"].toString()); assertEquals("\"r1\"", p.payload["redemption_client_uuid"].toString())
    }
}

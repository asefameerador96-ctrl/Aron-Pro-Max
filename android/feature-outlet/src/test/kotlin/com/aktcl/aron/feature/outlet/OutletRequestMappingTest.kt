package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.CaptureMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutletRequestMappingTest {
    private val meta = CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, 7, null, "2026-10-07:1", false, 5)
    private val fix = FixReading("ok", 23.79, 90.4, 9.0, false)
    private val uuid = "00000000-0000-4000-8000-000000000001"
    private fun draft(kind: OutletRequestKind, outletId: Long? = 5, photos: List<String> = emptyList()) =
        OutletRequestDraft(uuid, kind, outletId, "Rahim Store", "Rahim", "01712345678", 7, null, "note", null, photos, null)

    @Test fun newShopProposalCarriesNameOwnerMobileClusterAndTheFixPosition() {
        val (e, f) = draft(OutletRequestKind.NEW, null, listOf("00000000-0000-4000-8000-000000000002")).toEntities(meta, fix, "00000000-0000-4000-8000-000000000003")
        val p = Json.parseToJsonElement(e.proposedJson).jsonObject
        assertEquals("Rahim Store", p["name"]!!.jsonPrimitive.content); assertEquals("01712345678", p["contact_number"]!!.jsonPrimitive.content)
        assertEquals("7", p["cluster_id"]!!.jsonPrimitive.content); assertEquals("23.79", p["lat"]!!.jsonPrimitive.content)
        assertEquals(null, e.outletId); assertEquals(f.clientUuid, e.fixClientUuid); assertEquals(uuid, f.ownerClientUuid)
        assertTrue(e.photoUuidsJson.contains("0002"))
    }

    @Test fun closeAndClusterAndRouteAddCarryOnlyTheirFacts() {
        assertEquals(setOf("close_reason_code"), Json.parseToJsonElement(draft(OutletRequestKind.CLOSE).toEntities(meta, fix).first.proposedJson).jsonObject.keys)
        assertEquals(setOf("cluster_id"), Json.parseToJsonElement(draft(OutletRequestKind.CLUSTER).toEntities(meta, fix).first.proposedJson).jsonObject.keys)
        assertTrue(Json.parseToJsonElement(draft(OutletRequestKind.ROUTE_ADD).toEntities(meta, fix).first.proposedJson).jsonObject.isEmpty())
    }

    @Test fun aFailedFixLeavesPositionOutOfTheProposal() {
        val bad = FixReading("timeout", null, null, null, false)
        val p = Json.parseToJsonElement(draft(OutletRequestKind.LOCATION).toEntities(meta, bad).first.proposedJson).jsonObject
        assertFalse("lat" in p.keys)
    }
}

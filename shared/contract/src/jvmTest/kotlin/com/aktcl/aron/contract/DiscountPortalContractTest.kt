package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract 1.12.0 (discount-programme portal, lane portal-discount-api, D-DISC-API-01 to 04; lead approval D-LEAD-CONTRACT-112-01):
 * the three new operations, the status enum, the optional members, the changelog entry and the units vocabulary. Additive only: no
 * existing required list grows (`request_uuid` is optional in the schema because a new required request member would be breaking).
 */
@Suppress("UNCHECKED_CAST")
class DiscountPortalContractTest {
    private fun map(o: Any?): Map<String, Any?> = o as Map<String, Any?>
    private fun props(schema: String): Map<String, Map<String, Any?>> = OpenApi.schema(schema)["properties"] as Map<String, Map<String, Any?>>
    private fun required(schema: String): List<String> = (OpenApi.schema(schema)["required"] as List<String>?).orEmpty()
    private fun flat(s: Any?): String = s.toString().replace(Regex("\\s+"), " ")

    @Test
    fun versionIsAtLeast112() {
        val v = map(OpenApi.doc["info"])["version"].toString().split(".").map { it.toInt() }
        assertTrue(v[0] > 1 || (v[0] == 1 && v[1] >= 12), "contract version ${v.joinToString(".")} is below 1.12.0")
    }

    @Test
    fun theSixOfferOperationsExist() {
        for (op in listOf("GET /v1/admin/offers", "POST /v1/admin/offers", "GET /v1/admin/offers/{id}", "PATCH /v1/admin/offers/{id}",
            "GET /v1/admin/offers/{id}/versions", "POST /v1/admin/offers/{id}/status")) assertTrue(op in OpenApi.operations, op)
        val ids = listOf("/v1/admin/offers" to "get", "/v1/admin/offers" to "post", "/v1/admin/offers/{id}" to "get", "/v1/admin/offers/{id}" to "patch",
            "/v1/admin/offers/{id}/versions" to "get", "/v1/admin/offers/{id}/status" to "post").map { (p, m) -> map(map(OpenApi.paths.getValue(p)).getValue(m))["operationId"] }
        assertEquals(listOf("listOffers", "createOffer", "getOffer", "updateOffer", "listOfferVersions", "setOfferStatus"), ids)
    }

    @Test
    fun theOfferStatusHasDraftAndOfferUsesIt() {
        assertEquals(listOf("draft", "active", "inactive"), OpenApi.enumOf("OfferStatus"))
        assertEquals("#/components/schemas/OfferStatus", props("Offer").getValue("status")["\$ref"])
        assertEquals(listOf("active", "inactive"), OpenApi.enumOf("ActiveStatus"), "ActiveStatus keeps its values for everything else")
        assertEquals(listOf("active", "inactive"), map(props("OfferStatusChange").getValue("to_status"))["enum"] as List<String>)
    }

    @Test
    fun requestUuidIsOptionalInTheSchemaAndRequiredOnTheStatusCall() {
        assertTrue("request_uuid" in props("OfferWrite") && "request_uuid" !in required("OfferWrite"))
        assertTrue("request_uuid" in props("OfferPatch") && "request_uuid" !in required("OfferPatch"))
        assertTrue(listOf("request_uuid", "to_status", "reason").all { it in required("OfferStatusChange") })
    }

    @Test
    fun theOfferPatchStatusMemberIsDeprecatedButNotRemoved() {
        val status = props("OfferPatch").getValue("status")
        assertEquals(true, status["deprecated"])
        assertEquals("#/components/schemas/ActiveStatus", status["\$ref"])
    }

    @Test
    fun skuUnitsUseTheContractsOneUnitVocabulary() {
        assertTrue(listOf("sku_id", "base_unit", "pack_factor", "field_unit").all { it in required("OfferSkuUnit") })
        assertEquals("#/components/schemas/QtyUnit", props("OfferSkuUnit").getValue("field_unit")["\$ref"])
        assertEquals(listOf("stick", "piece", "dozen"), map(props("OfferSkuUnit").getValue("base_unit"))["enum"] as List<String>)
        assertTrue("sku_units" in props("Offer") && "sku_units" !in required("Offer"))
        assertTrue("pending the owner's confirmation" in flat(props("OfferSkuUnit").getValue("field_unit")["description"]), "bidi in packets is flagged as pending")
    }

    @Test
    fun everyMoneyAndQuantityMemberStatesItsUnit() {
        for (schema in listOf("Offer", "OfferWrite", "OfferPatch", "OfferVersion")) {
            val p = props(schema)
            assertTrue("milli-taka per base unit" in flat(p.getValue("discount_mtk_per_unit")["description"]), "$schema.discount_mtk_per_unit")
            assertTrue("basis points" in flat(p.getValue("discount_bp")["description"]), "$schema.discount_bp")
            assertTrue("base units" in flat(p.getValue("threshold_qty_base")["description"]), "$schema.threshold_qty_base")
            assertTrue("base units" in flat(p.getValue("reward_qty_base")["description"]), "$schema.reward_qty_base")
        }
    }

    @Test
    fun theAdminModuleGainsDiscountsLast() {
        assertEquals("discounts", OpenApi.enumOf("AdminModule").last())
    }

    @Test
    fun theChangelogNamesTheDiscountPortalAdditions() {
        val info = flat(map(OpenApi.doc["info"])["description"])
        for (member in listOf("1.12.0 (discount-programme portal", "`getOffer`", "`listOfferVersions`", "`setOfferStatus`", "`OfferStatus`", "`OfferSkuUnit`",
            "`AdminModule`", "`discounts`", "D-LEAD-CONTRACT-112-01", "contract-1.12-discount-portal.md", "`StatusQuery`")) assertTrue(info.contains(member), "changelog names $member")
        assertFalse(info.contains("future-dated"), "createOffer no longer asks for a future valid_from")
    }
}

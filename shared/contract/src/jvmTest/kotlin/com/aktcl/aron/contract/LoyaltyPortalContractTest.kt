package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract 1.13.0 (loyalty-programme portal, lane portal-loyalty-api, D-LOY-API-01 to 03; lead approval D-LEAD-CONTRACT-113-01):
 * the four new operations, the status enum, the optional members, the tier threshold unit (STD as stored, never negative, no money
 * member), the module and the changelog entry. Additive only: no existing required list grows (`request_uuid` is optional in the
 * schema because a new required request member would be breaking).
 */
@Suppress("UNCHECKED_CAST")
class LoyaltyPortalContractTest {
    private fun map(o: Any?): Map<String, Any?> = o as Map<String, Any?>
    private fun props(schema: String): Map<String, Map<String, Any?>> = OpenApi.schema(schema)["properties"] as Map<String, Map<String, Any?>>
    private fun required(schema: String): List<String> = (OpenApi.schema(schema)["required"] as List<String>?).orEmpty()
    private fun flat(s: Any?): String = s.toString().replace(Regex("\\s+"), " ")

    @Test
    fun versionIsAtLeast113() {
        val v = map(OpenApi.doc["info"])["version"].toString().split(".").map { it.toInt() }
        assertTrue(v[0] > 1 || (v[0] == 1 && v[1] >= 13), "contract version ${v.joinToString(".")} is below 1.13.0")
    }

    @Test
    fun theProgrammeAndGiftOperationsExistUnderTheOldPaths() {
        val ids = listOf(
            "/v1/admin/programmes" to "get", "/v1/admin/programmes" to "post", "/v1/admin/programmes/{id}" to "get", "/v1/admin/programmes/{id}" to "patch",
            "/v1/admin/programmes/{id}/versions" to "get", "/v1/admin/programmes/{id}/status" to "post",
            "/v1/admin/programmes/{id}/enrolments" to "get", "/v1/admin/programmes/{id}/enrolments" to "put",
            "/v1/admin/gifts" to "get", "/v1/admin/gifts" to "post", "/v1/admin/gifts/{id}" to "get", "/v1/admin/gifts/{id}" to "patch",
        ).map { (p, m) -> map(map(OpenApi.paths.getValue(p)).getValue(m))["operationId"] }
        assertEquals(listOf("listProgrammes", "createProgramme", "getProgramme", "updateProgramme", "listProgrammeVersions", "setProgrammeStatus",
            "listProgrammeEnrolments", "putProgrammeEnrolments", "listGifts", "createGift", "getGift", "updateGift"), ids)
    }

    @Test
    fun theProgrammeStatusHasDraftAndProgrammeUsesIt() {
        assertEquals(listOf("draft", "active", "inactive"), OpenApi.enumOf("ProgrammeStatus"))
        assertEquals("#/components/schemas/ProgrammeStatus", props("Programme").getValue("status")["\$ref"])
        assertEquals(listOf("active", "inactive"), OpenApi.enumOf("ActiveStatus"), "ActiveStatus keeps its values for everything else")
        assertEquals(listOf("active", "inactive"), map(props("ProgrammeStatusChange").getValue("to_status"))["enum"] as List<String>)
    }

    @Test
    fun requestUuidIsOptionalInTheSchemaAndRequiredOnTheStatusCall() {
        for (s in listOf("ProgrammeWrite", "ProgrammePatch", "GiftWrite", "GiftPatch")) assertTrue("request_uuid" in props(s) && "request_uuid" !in required(s), s)
        assertTrue(listOf("request_uuid", "to_status", "reason").all { it in required("ProgrammeStatusChange") })
        assertTrue("batch_uuid" in required("ProgrammeEnrolmentWrite"), "the enrolment batch keeps its required batch_uuid")
    }

    @Test
    fun theWriteStatusMembersAreDeprecatedButNotRemoved() {
        for (s in listOf("ProgrammeWrite", "ProgrammePatch")) {
            val status = props(s).getValue("status")
            assertEquals(true, status["deprecated"], s)
            assertEquals("#/components/schemas/ActiveStatus", status["\$ref"], s)
        }
    }

    @Test
    fun theTierThresholdIsStdAsStoredNeverNegativeAndNothingIsMoney() {
        val t = props("ProgrammeTier")
        assertEquals(listOf("tier_code", "sort_order", "label_en"), required("ProgrammeTier"))
        val pattern = map(t.getValue("threshold"))["pattern"].toString()
        assertTrue(Regex(pattern).matches("1500.5") && Regex(pattern).matches("0") && !Regex(pattern).matches("-1") && !Regex(pattern).matches("1.2345"), "non-negative, up to 3 fraction digits: $pattern")
        assertEquals(listOf("std"), map(t.getValue("threshold_basis"))["enum"] as List<String>)
        assertTrue("STD" in flat(t.getValue("threshold")["description"]))
        for (schema in listOf("Programme", "ProgrammeWrite", "ProgrammePatch", "ProgrammeVersion", "ProgrammeTier", "Gift", "GiftWrite", "GiftPatch", "ProgrammeStatusChange")) {
            assertFalse(props(schema).keys.any { it.endsWith("_mtk") || it.endsWith("_float") }, "$schema carries no money member in v1 (brief Q4)")
        }
        assertEquals("integer", map(props("Gift").getValue("points_cost"))["type"].let { (it as? List<String>)?.first() ?: it })
    }

    @Test
    fun aVersionCarriesTheDefinitionTiersProductsAndScope() {
        assertTrue(listOf("programme_version_id", "programme_id", "version_no", "is_current", "tiers", "qualifying", "scope").all { it in required("ProgrammeVersion") })
        assertEquals("#/components/schemas/LoyaltyProductRef", map(props("ProgrammeVersion").getValue("qualifying")["items"])["\$ref"])
        assertEquals(listOf("wing", "division", "territory", "zone", "route", "channel", "sub_channel"), map(OpenApi.schema("LoyaltyScopeNode")["properties"])["node_type"].let { map(it)["enum"] as List<String> })
        for (m in listOf("programme_version_id", "version_no", "tiers", "qualifying", "scope", "change_reason", "created_at", "updated_at"))
            assertTrue(m in props("Programme") && m !in required("Programme"), "Programme.$m is optional (additive)")
    }

    @Test
    fun theAdminModuleGainsLoyaltyLast() {
        assertEquals("loyalty", OpenApi.enumOf("AdminModule").last())
    }

    @Test
    fun theChangelogNamesTheLoyaltyPortalAdditions() {
        val info = flat(map(OpenApi.doc["info"])["description"])
        for (member in listOf("1.13.0 (loyalty-programme portal", "`getProgramme`", "`listProgrammeVersions`", "`setProgrammeStatus`", "`getGift`", "`ProgrammeStatus`", "`ProgrammeTier`",
            "`AdminModule` gains `loyalty`", "D-LEAD-CONTRACT-113-01", "contract-1.13-loyalty-portal.md", "STD as stored")) assertTrue(info.contains(member), "changelog names $member")
    }
}

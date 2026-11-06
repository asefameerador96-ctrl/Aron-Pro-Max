package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract 1.11.0 (AMO manual build, docs/requests/amo-manual-build-2026-11.md s3), foundation train F1: the members and the
 * operation the AMO lanes build against. Everything is additive: no existing required list grows.
 */
@Suppress("UNCHECKED_CAST")
class AmoManualContractTest {
    private fun map(o: Any?): Map<String, Any?> = o as Map<String, Any?>
    private fun props(schema: String): Map<String, Map<String, Any?>> = OpenApi.schema(schema)["properties"] as Map<String, Map<String, Any?>>
    private fun required(schema: String): List<String> = (OpenApi.schema(schema)["required"] as List<String>?).orEmpty()
    private fun flat(s: Any?): String = s.toString().replace(Regex("\\s+"), " ")

    @Test
    fun versionIsAtLeast111() {
        val v = map(OpenApi.doc["info"])["version"].toString().split(".").map { it.toInt() }
        assertTrue(v[0] > 1 || (v[0] == 1 && v[1] >= 11), "contract version ${v.joinToString(".")} is below 1.11.0")
    }

    @Test
    fun theChangelogNamesTheAmoAdditions() {
        val info = flat(map(OpenApi.doc["info"])["description"])
        for (member in listOf("`VisitPayload.force_sale_reason`", "`getCallTargets`", "`OutletRequestVerificationPayload.route_id` and `cluster_id`", "`section_bn`")) assertTrue(info.contains(member), "the 1.11.0 changelog must name $member")
    }

    @Test
    fun theForceSaleReasonIsOptionalAndHasTwoValues() {
        assertFalse("force_sale_reason" in required("VisitPayload"))
        val p = props("VisitPayload").getValue("force_sale_reason")
        assertEquals(setOf("internet_problem", "location_change"), (p["enum"] as List<Any?>).filterNotNull().map { it.toString() }.toSet())
        assertTrue((p["type"] as List<*>).contains("null"))
    }

    @Test
    fun theRubricCriterionTakesSectionAndGuidanceOnReadAndWrite() {
        val read = map(map(props("RubricDef").getValue("criteria")["items"])["properties"])
        val write = map(map(props("RubricWrite").getValue("criteria")["items"])["properties"])
        for (m in listOf("section", "section_bn", "guidance_en", "guidance_bn")) {
            assertTrue(m in read, "RubricDef.criteria.$m"); assertTrue(m in write, "RubricWrite.criteria.$m")
            assertTrue((map(read[m])["type"] as List<*>).contains("null"), "$m is nullable")
        }
        val readRequired = map(map(props("RubricDef").getValue("criteria")["items"]))["required"] as List<String>
        assertEquals(listOf("criterion_id", "label_en", "answer_type"), readRequired)
    }

    @Test
    fun getCallTargetsIsDeclaredWithItsMonthParameterAndFourCards() {
        val op = map(map(OpenApi.paths["/v1/supervisor/call-targets"])["get"])
        assertEquals("getCallTargets", op["operationId"])
        val month = (op["parameters"] as List<Map<String, Any?>>).single { it["name"] == "month" }
        assertEquals("query", month["in"]); assertFalse(month["required"] == true)
        assertEquals(setOf("200", "400", "401", "403"), map(op["responses"]).keys)
        assertEquals(listOf("as_of", "business_date", "period", "today", "month", "control", "joint"), required("CallTargets"))
        assertEquals(listOf("done", "target"), required("CallTargetCard"))
        assertTrue((props("CallTargetCard").getValue("target")["type"] as List<*>).contains("null"), "a target that is not set is null")
    }

    @Test
    fun teamStockMembersAreAdditiveAndTheVerificationRecordTakesRouteAndCluster() {
        val items = map(props("TeamStockList").getValue("items")["items"])
        val ip = map(items["properties"])
        for (m in listOf("route_names", "total_outlets", "phone")) assertTrue(m in ip, "TeamStockList.items.$m")
        assertEquals(listOf("user_id", "full_name", "by_sku"), items["required"] as List<String>)
        assertTrue((map(ip["phone"])["type"] as List<*>).contains("null"), "phone is null for every reader but the AMO of the zone")
        for (m in listOf("route_id", "cluster_id")) assertTrue(m in props("OutletRequestVerificationPayload"), m)
        assertEquals(listOf("request_uuid", "decision", "fix"), required("OutletRequestVerificationPayload"))
    }
}

package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract 1.11.2 (AMO manual build, docs/requests/amo-manual-build-2026-11.md s3), foundation trains F1 and F2: the members and the
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
    fun versionIs1150() {
        assertEquals("1.18.1", map(OpenApi.doc["info"])["version"].toString(), "the exact shipped contract version (1.18.1: the master-data-fit review round, new warning and error codes in the import report; 1.18.0: master-data fit, additive; 1.15.0: the field side of loyalty and Astha; 1.14.0: SR manual train 1, additive; 1.13.1: description-only patch of the loyalty portal; 1.13.0: the loyalty portal, schema and contract; 1.12.2: optional If-Match on setOfferStatus; 1.12.1 the discount portal routes and discounts module; 1.12.0 the schema and contract)")
    }

    @Test
    fun targetRowsCarryOptionalFieldUnitMembers() {
        val members = listOf("report_unit", "target_qty_report", "achieved_qty_report", "tilldate_target_qty_report", "remaining_qty_report", "remaining_tilldate_qty_report")
        for (m in members) {
            assertTrue(m in props("TargetAchievementRow"), "TargetAchievementRow.$m")
            assertFalse(m in required("TargetAchievementRow"), "$m is optional (additive, 1.11.2)")
        }
        assertEquals(setOf("stick", "piece", "dozen", "box", "pack"), (props("TargetAchievementRow").getValue("report_unit")["enum"] as List<*>).map { it.toString() }.toSet())
        val cols = flat(OpenApi.doc)
        for (c in listOf("memos_cigarette", "memos_bidi", "memos_match", "memos_lighter")) assertTrue(c in cols, "sales-summary names $c")
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

    // ------------------------------------------------------------------------------------------------ train F2

    private val wire = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun theF2OperationsAreDeclaredWithTheirParametersAndStatuses() {
        val journey = map(map(OpenApi.paths["/v1/team/journey"])["get"])
        assertEquals("getTeamJourney", journey["operationId"])
        val params = (journey["parameters"] as List<Map<String, Any?>>)
        assertEquals(setOf("user_id", "route_id"), params.filter { it["required"] == true }.map { it["name"] }.toSet())
        assertEquals(setOf("200", "400", "401", "403", "404"), map(journey["responses"]).keys)
        assertEquals("getTeamMembers", map(map(OpenApi.paths["/v1/team/members"])["get"])["operationId"])
        val byDate = map(map(OpenApi.paths["/v1/outlets/{outlet_id}/sales-by-date"])["get"])
        assertEquals("getOutletSalesByDate", byDate["operationId"])
        assertTrue((byDate["parameters"] as List<Map<String, Any?>>).any { it["name"] == "outlet_id" && it["in"] == "path" && it["required"] == true })
        assertEquals(setOf("200", "400", "401", "403", "404"), map(byDate["responses"]).keys)
    }

    @Test
    fun theJourneyMembersAndSalesByDateSchemasKeepTheNamesThePhonesBuildAgainst() {
        assertEquals(listOf("user", "route", "business_date", "calls", "distance_m"), required("TeamJourney"))
        assertEquals(listOf("visit_uuid", "outlet", "status", "opened_at", "closed_at", "duration_s", "distance_from_prev_m", "distance_so_far_m", "gap_to_next_s"), required("JourneyCall"))
        assertEquals(listOf("new_sale", "edited_sale", "no_sale", "repeat_sale"), props("JourneyCall").getValue("status")["enum"])
        assertTrue((props("JourneyCall").getValue("distance_from_prev_m")["type"] as List<*>).contains("null"), "the first call has no previous outlet")
        assertEquals(listOf("id", "name", "code", "phone_masked", "cluster"), required("JourneyOutlet"))
        assertEquals(listOf("user_id", "full_name", "route_id", "route_code", "route_name", "route_days", "phone", "phone_masked", "total_outlets", "vacant"), required("TeamMemberRow"))
        assertTrue((props("TeamMemberRow").getValue("user_id")["type"] as List<*>).contains("null"), "a vacant route has no SR")
        assertEquals(listOf("outlet_id", "business_date", "lines", "total_qty_base", "total_value_mtk"), required("SalesByDate"))
        assertEquals(listOf("sku", "qty_report", "report_unit", "qty_base", "value_mtk"), required("SalesByDateLine"))
        assertEquals(listOf("sku_id", "sku_code", "short_name"), map(props("SalesByDateLine").getValue("sku"))["required"])
    }

    @Test
    fun loginSubmitStatusGainsOnlyOptionalAmoMembers() {
        assertEquals(listOf("as_of", "business_date", "kpis", "not_logged_in", "logged_in_not_submitted", "submitted"), required("LoginSubmitStatus"))
        for (m in listOf("geo_valid_visits", "photo_valid_visits", "visits_total", "sales_by_category")) assertTrue(m in props("LoginSubmitStatus"), m)
        assertEquals("#/components/schemas/CategoryVolume", map(props("LoginSubmitStatus").getValue("sales_by_category")["items"])["\$ref"])
    }

    @Test
    fun theF2FixturesDecodeIntoTheWireDtos() {
        val journey = wire.decodeFromString<TeamJourney>(
            """{"user":{"user_id":5,"full_name":"Test SR"},"route":{"route_id":3,"name":"Mirpur 10 Daily"},"business_date":"2027-01-03","distance_m":344.7,"calls":[
              {"visit_uuid":"00000000-0000-4000-8000-0000000f2001","outlet":{"id":42,"name":"Rahim Store","code":"OC01","phone_masked":"018*****111","cluster":"Mirpur 10 Bazar"},"status":"new_sale",
               "opened_at":"2027-01-03T04:00:00.000Z","closed_at":"2027-01-03T04:07:46.000Z","duration_s":466,"distance_from_prev_m":null,"distance_so_far_m":0.0,"gap_to_next_s":734}]}""",
        )
        assertEquals(1, journey.calls.size); assertEquals(466, journey.calls.single().durationS); assertEquals(null, journey.calls.single().distanceFromPrevM)
        val members = wire.decodeFromString<TeamMemberList>(
            """{"as_of":"2027-01-03T04:00:00.000Z","business_date":"2027-01-03","items":[
              {"user_id":null,"full_name":null,"route_id":9,"route_code":"MIR-SR-VAC","route_name":"Vacant","route_days":[1,4],"phone":null,"phone_masked":null,"total_outlets":0,"vacant":true}]}""",
        )
        assertTrue(members.items.single().vacant); assertEquals(listOf(1, 4), members.items.single().routeDays)
        val byDate = wire.decodeFromString<SalesByDate>(
            """{"outlet_id":42,"business_date":"2027-01-03","total_qty_base":100,"total_value_mtk":9000,"lines":[
              {"sku":{"sku_id":7,"sku_code":"MR20","short_name":"Marlboro 20"},"qty_report":"5.000","report_unit":"pack","qty_base":100,"value_mtk":9000}]}""",
        )
        assertEquals("5.000", byDate.lines.single().qtyReport); assertEquals("MR20", byDate.lines.single().sku.skuCode)
    }
}

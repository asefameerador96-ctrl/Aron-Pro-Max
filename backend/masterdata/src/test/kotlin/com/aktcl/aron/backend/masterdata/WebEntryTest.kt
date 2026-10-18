package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-API-050 and F-API-052: one live web entry per route-day (re-save replaces with audit), QC entries by SKU and fault type, window, unlock and Final Submit rules. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebEntryTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    // wall-clock-ok: the app under test runs on AronClock.SYSTEM, so its Dhaka "today" is the real one
    private val today = com.aktcl.aron.rules.BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate()
    private fun uuid() = UUID.randomUUID().toString()
    private val route get() = env.routeIds.getValue("MIR-SR-D")
    private val skus get() = env.scalar("SELECT string_agg(id::text, ',' ORDER BY id) FROM (SELECT id FROM app.sku ORDER BY id LIMIT 3) s")!!.split(",").map { it.toLong() }
    private fun line(sku: Long, issue: Int = 50, ret: Int = 10, memos: Int = 8) = """{"sku_id":$sku,"issue_qty_base":$issue,"return_qty_base":$ret,"memo_count":$memos}"""
    private fun entry(cu: String, lines: List<String>, calls: Int = 5, date: Any = today, why: String? = null, routeId: Long = route) =
        """{"client_uuid":"$cu","route_id":$routeId,"business_date":"$date","lines":[${lines.joinToString(",")}],"successful_calls":$calls${why?.let { ""","change_reason":"$it"""" } ?: ""}}"""

    @Test
    fun saveReplayResaveAndReadWithAuditAndTheRouteDayRules() = env.app {
        val tso = env.tok("tso1001"); val (s1, s2) = skus
        val first = uuid()
        val r1 = sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(first, listOf(line(s1), line(s2, 30, 5, 4))))
        assertEquals(HttpStatusCode.OK, r1.status, r1.bodyAsText())
        val j1 = r1.objA3()
        assertEquals(first, j1.strA3("client_uuid")); assertEquals("web_entry", j1.strA3("source"))
        assertEquals(listOf(40L, 25L), j1.getValue("lines").jsonArray.map { it.jsonObject.getValue("sale_qty_base").jsonPrimitive.content.toLong() })
        // Replay: same body, nothing new. The same uuid with other numbers is a conflict.
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(first, listOf(line(s1), line(s2, 30, 5, 4)))).status)
        assertEquals(1, env.count("SELECT count(*) FROM app.web_entry_route_day WHERE client_uuid = '$first'")); assertEquals(2, env.count("SELECT count(*) FROM app.web_entry_line WHERE entry_client_uuid = '$first'"))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(first, listOf(line(s1, 99)))).status)
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'web_entry_route_day' AND entity_id = '$first' AND action = 'create'"))
        // A re-save under a new uuid needs a reason, closes the old entry and leaves one live entry; the read returns the new one.
        val second = uuid()
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(second, listOf(line(s1, 60)))).status)
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(second, listOf(line(s1, 60)), why = "Corrected after the paper check")).status)
        assertEquals(1, env.count("SELECT count(*) FROM app.web_entry_route_day WHERE route_id = $route AND business_date = '$today' AND replaced_at IS NULL"))
        assertEquals(1, env.count("SELECT count(*) FROM app.web_entry_route_day WHERE client_uuid = '$first' AND replaced_at IS NOT NULL"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'web_entry_route_day' AND entity_id = '$second' AND action = 'resave' AND reason = 'Corrected after the paper check'"))
        val read = sendA3(HttpMethod.Get, "/v1/web-entry/route-day?route_id=$route&business_date=$today", tso).objA3()
        assertEquals(second, read.strA3("client_uuid")); assertEquals(1, read.getValue("lines").jsonArray.size)
        // Validation: return above issue, unknown SKU, duplicate SKU, calls above target.
        suspend fun post(b: String, t: String = tso) = sendA3(HttpMethod.Post, "/v1/web-entry/route-day", t, b)
        assertEquals(HttpStatusCode.BadRequest, post(entry(uuid(), listOf(line(s1, 10, 20)), why = "Return above issue test")).status)
        assertEquals(HttpStatusCode.BadRequest, post(entry(uuid(), listOf(line(987654)), why = "Unknown SKU test case")).status)
        assertEquals(HttpStatusCode.BadRequest, post(entry(uuid(), listOf(line(s1), line(s1)), why = "Duplicate SKU test case")).status)
        assertEquals(HttpStatusCode.BadRequest, post(entry(uuid(), listOf(line(s1)), calls = 100000, why = "Calls above target case")).status)
        // Scope and role: another territory's TSO and a DMO get 403; the read of an empty day has no lines.
        assertEquals(HttpStatusCode.Forbidden, post(entry(uuid(), listOf(line(s1))), env.tok("tso2001")).status)
        assertEquals(HttpStatusCode.Forbidden, post(entry(uuid(), listOf(line(s1))), env.tok("dmo1001")).status)
        // Reads follow the same reach: another territory's TSO neither reads nor learns whether the route exists.
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/web-entry/route-day?route_id=$route&business_date=$today", env.tok("tso2001")).status)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/web-entry/route-day?route_id=987654&business_date=$today", env.tok("tso2001")).status)
        assertEquals(0, sendA3(HttpMethod.Get, "/v1/web-entry/route-day?route_id=$route&business_date=${today.minusDays(30)}", tso).objA3().getValue("lines").jsonArray.size)
    }

    /** Replay first: a retry of a stored entry answers it after its route moved out of the TSO's reach; a new entry there is refused. */
    @Test
    fun aReplayAnswersAfterTheRouteLeavesTheReach() = env.app {
        val tso = env.tok("tso3001"); val s1 = skus[0]
        val rid = env.routeIds.getValue("FAR-SR-1")
        val cu = uuid()
        val body = entry(cu, listOf(line(s1, 40, 2, 3)), calls = 0, routeId = rid)
        sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, body).let { r -> assertEquals(HttpStatusCode.OK, r.status, r.objA3().toString()) }
        val home = env.scalar("SELECT zone_id FROM app.route WHERE id = $rid")!!
        env.exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z-MIR') WHERE id = $rid")
        try {
            sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, body).let { r -> assertEquals(HttpStatusCode.OK, r.status, r.objA3().toString()) }
            assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(uuid(), listOf(line(s1)), calls = 0, routeId = rid, why = "A new entry after the move")).status)
        } finally {
            env.exec("UPDATE app.route SET zone_id = $home WHERE id = $rid")
        }
    }

    @Test
    fun theWindowTheUnlockAndFinalSubmitDecideBackDatedEntry() = env.app {
        val tso = env.tok("tso1001"); val admin = env.tok("admin1001"); val s1 = skus[0]
        val old = today.minusDays(3)
        val denied = sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(uuid(), listOf(line(s1)), date = old))
        assertEquals(HttpStatusCode.Conflict, denied.status); assertEquals("ERR_ENTRY_WINDOW_CLOSED", denied.objA3().strA3("code"))
        val zone = env.scalar("SELECT zone_id FROM app.route WHERE id = $route")!!.toLong()
        val grant = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks", admin, """{"scope_type":"zone","scope_id":$zone,"from":"${old.minusDays(1)}","to":"$old","reason":"Late paper memos from the depot"}""").objA3()
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(uuid(), listOf(line(s1)), date = old)).status)
        // Expiring the grant closes the window again.
        sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks/${grant.strA3("unlock_id")}/expire", admin, "{}")
        assertEquals("ERR_ENTRY_WINDOW_CLOSED", sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(uuid(), listOf(line(s1, 20, 1)), date = old, why = "Second try after expiry")).objA3().strA3("code"))
        // Final Submit of the zone-day refuses entry and QC for that date.
        env.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, via) VALUES (CAST(:u AS uuid), :z, :d, :s, 'web')")
                .bind("u", uuid()).bind("z", zone).bind("d", today).bind("s", env.ids.getValue("tso1001")).execute()
        }
        assertEquals("ERR_DAY_ALREADY_FINAL_SUBMITTED", sendA3(HttpMethod.Post, "/v1/web-entry/route-day", tso, entry(uuid(), listOf(line(s1)))).objA3().strA3("code"))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(uuid(), "market", zone, route)).status)
    }

    private fun qc(cu: String, source: String, zone: Long, routeId: Long?, fault: String = "fault_x", reason: String? = null, sku: Long = skus[0]) =
        """{"client_uuid":"$cu","source":"$source","business_date":"$today","zone_id":$zone,"rows":[{"sku_id":$sku,"fault_type_code":"$fault","qty_base":3}]${routeId?.let { ""","route_id":$it""" } ?: ""}${reason?.let { ""","reason":"$it"""" } ?: ""}}"""

    @Test
    fun qcEntriesAreIdempotentSeparateAndWarehouseNeedsAnAdminAndAReason() = env.app {
        val tso = env.tok("tso1001"); val admin = env.tok("admin1001")
        val zone = env.scalar("SELECT zone_id FROM app.route WHERE id = $route")!!.toLong()
        val fault = env.scalar("SELECT code FROM app.code_list_item WHERE list_key = 'qc_fault_type' AND valid_to IS NULL ORDER BY id LIMIT 1")!!
        val cu = uuid()
        val r1 = sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(cu, "market", zone, route, fault))
        assertEquals(HttpStatusCode.OK, r1.status, r1.bodyAsText()); assertEquals(false, r1.objA3().getValue("replayed").jsonPrimitive.content.toBoolean())
        val r2 = sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(cu, "market", zone, route, fault))
        assertEquals(true, r2.objA3().getValue("replayed").jsonPrimitive.content.toBoolean())
        assertEquals(1, env.count("SELECT count(*) FROM app.qc_summary_entry WHERE client_uuid = '$cu'")); assertEquals(1, env.count("SELECT count(*) FROM app.qc_summary_entry_line WHERE entry_client_uuid = '$cu'"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'qc_summary_entry' AND entity_id = '$cu'"))
        // Warehouse: administrators only, with a reason and no route; a TSO and a missing reason are refused.
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(uuid(), "warehouse", zone, null, fault, "Warehouse count after the audit")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/web-entry/qc", admin, qc(uuid(), "warehouse", zone, null, fault)).status)
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/web-entry/qc", admin, qc(uuid(), "warehouse", zone, null, fault, "Warehouse count after the audit")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(uuid(), "market", zone, route, "no_such_fault")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(uuid(), "market", zone, null, fault)).status)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/web-entry/qc", env.tok("tso2001"), qc(uuid(), "market", zone, route, fault)).status)
    }
}

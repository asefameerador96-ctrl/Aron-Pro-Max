package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.rules.BusinessDate
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-TARGET-MIN-01 (T1): route x SKU monthly target upload: preview report, commit, idempotency, totals, audit, role matrix. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteTargetsTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    // wall-clock-ok: the endpoint judges "past month" against the Dhaka month of the system clock
    private val thisMonth: YearMonth = YearMonth.from(BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate())
    private val next: YearMonth = thisMonth.plusMonths(1)
    private val prev: YearMonth = thisMonth.minusMonths(1)

    private fun row(route: String, sku: String, month: Any, qty: Any) = """{"route_code":"$route","sku_code":"$sku","month":"$month","qty_base":$qty}"""
    private fun body(rows: List<String>, dry: Boolean, u: UUID = UUID.randomUUID(), reason: String? = null) =
        """{"batch_uuid":"$u","rows":[${rows.joinToString(",")}],"dry_run":$dry${if (reason != null) ""","change_reason":"$reason"""" else ""}}"""
    private val reason = "Monthly target sheet from the sales head"

    @Test
    fun previewReportsEveryRowErrorTotalsAndWritesNothing() = env.app {
        val admin = env.tok("admin1001")
        val rows = listOf(
            row("MIR-SR-D", "MaxR-10S", next, 1000), row("MIR-SR-D", "MaxR-20S", next, 500), row("MIR-SR-3F", "MaxR-10S", next, 250),
            row("NOPE", "MaxR-10S", next, 10), row("MIR-AMO-1", "MaxR-10S", next, 10), row("MIR-SR-D", "NOSKU", next, 10),
            row("MIR-SR-D", "MaxB-10S", prev, 10), row("MIR-SR-D", "MaxB-10S", "2026-13", 10), row("MIR-SR-D", "MaxB-10S", next, "12.5"),
            row("MIR-SR-D", "MaxB-10S", next, -1), row("MIR-SR-D", "MaxB-10S", next, "\"7\""), row("MIR-SR-D", "MaxB-10S", next, 1_000_000_001),
            row("MIR-SR-D", "MaxR-10S", next, 3),
        )
        val targets0 = env.count("SELECT count(*) FROM app.route_monthly_target"); val batches0 = env.count("SELECT count(*) FROM app.route_target_batch"); val audits0 = env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_target'")
        val r = sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows, true))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val o = r.objA3()
        assertEquals("13", o.strA3("rows"))
        assertEquals("2", o.strA3("routes")); assertEquals("2", o.strA3("skus")); assertEquals("1750", o.strA3("total_qty_base"), "only error-free rows are summed")
        assertEquals(listOf(next.toString()), o.getValue("months").jsonArray.map { it.jsonPrimitive.content })
        val errs = o.getValue("errors").jsonArray.map { it.jsonObject }.map { Triple(it.strA3("row").toInt(), it.strA3("column"), it.strA3("code")) }
        assertEquals(
            listOf(
                Triple(4, "route", "unknown_route"), Triple(5, "route", "not_selling_route"), Triple(6, "sku", "unknown_sku"), Triple(7, "month", "past_month"),
                Triple(8, "month", "invalid_month"), Triple(9, "quantity", "invalid_quantity"), Triple(10, "quantity", "invalid_quantity"), Triple(11, "quantity", "invalid_quantity"),
                Triple(12, "quantity", "invalid_quantity"), Triple(13, "route", "duplicate_row"),
            ),
            errs,
        )
        assertEquals(targets0, env.count("SELECT count(*) FROM app.route_monthly_target"), "a dry run writes nothing")
        assertEquals(batches0, env.count("SELECT count(*) FROM app.route_target_batch"))
        assertEquals(audits0, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_target'"))
    }

    @Test
    fun commitUpsertsOnceAuditsAndReplaysByBatchUuid() = env.app {
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        val u = UUID.randomUUID()
        val m = next.plusMonths(2)
        val rows = listOf(row("MIR-SR-D", "MaxR-10S", m, 1000), row("MIR-SR-D", "MaxR-20S", m, 500), row("MIR-SR-3F", "MaxR-10S", m, 250))
        // The reason is required, a short one is refused.
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows, false, u)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows, false, u, "short")).status)
        assertEquals(0, env.count("SELECT count(*) FROM app.route_target_batch WHERE batch_uuid = '$u'"))
        val c = sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows, false, u, reason))
        assertEquals(HttpStatusCode.OK, c.status, c.bodyAsText())
        assertEquals("3", c.objA3().strA3("rows")); assertEquals("false", c.objA3().strA3("replayed"))
        assertEquals(3, env.count("SELECT count(*) FROM app.route_monthly_target WHERE batch_uuid = '$u'"))
        assertEquals(1750, env.count("SELECT sum(qty_base) FROM app.route_monthly_target WHERE month = date '$m-01'"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_target' AND entity_id = '$u' AND action = 'upload' AND reason = '$reason'"))
        // Replay of the same content, even in another row order and after the month turned past, answers the stored result and writes nothing.
        val again = sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows.reversed(), false, u, "Another reason, same batch content"))
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText()); assertEquals("true", again.objA3().strA3("replayed"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_target' AND entity_id = '$u'"), "a replay is not audited again")
        assertEquals(3, env.count("SELECT count(*) FROM app.route_monthly_target WHERE month = date '$m-01'"))
        // Other content under the same uuid, or another user adopting it, is a conflict.
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows.drop(1), false, u, reason)).status)
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Put, "/v1/admin/route-targets", sup, body(rows, false, u, reason)).status)
        assertEquals(1750, env.count("SELECT sum(qty_base) FROM app.route_monthly_target WHERE month = date '$m-01'"))
        // A second batch overwrites by (route, SKU, month); the preview counts the replaced rows; the audit records the replaced total.
        val u2 = UUID.randomUUID()
        val rows2 = listOf(row("MIR-SR-D", "MaxR-10S", m, 1200), row("MIR-SR-2F", "MaxR-10S", m, 80))
        val pv = sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows2, true, u2)).objA3()
        assertEquals("1", pv.strA3("replaces")); assertEquals("1280", pv.strA3("total_qty_base"))
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Put, "/v1/admin/route-targets", sup, body(rows2, false, u2, reason)).status)
        assertEquals(4, env.count("SELECT count(*) FROM app.route_monthly_target WHERE month = date '$m-01'"))
        assertEquals(1200 + 500 + 250 + 80, env.count("SELECT sum(qty_base) FROM app.route_monthly_target WHERE month = date '$m-01'"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_target' AND entity_id = '$u2' AND (after ->> 'replaced_total_qty_base') = '1000' AND (after ->> 'replaced_rows') = '1'"))
        // Same rows twice in the sheet: the whole batch is refused, nothing is written.
        val u3 = UUID.randomUUID()
        val dup = sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(listOf(row("MIR-SR-D", "MaxB-10S", m, 5), row("MIR-SR-D", "MaxB-10S", m, 6)), false, u3, reason))
        assertEquals(HttpStatusCode.BadRequest, dup.status, dup.bodyAsText())
        assertTrue(dup.bodyAsText().contains("body.rows/1/route_code") && dup.bodyAsText().contains("duplicate_row"), dup.bodyAsText())
        assertEquals(0, env.count("SELECT count(*) FROM app.route_monthly_target WHERE batch_uuid = '$u3'"))
        assertEquals(0, env.count("SELECT count(*) FROM app.route_target_batch WHERE batch_uuid = '$u3'"))
    }

    @Test
    fun aPastMonthOrABadRowRefusesTheWholeBatch() = env.app {
        val admin = env.tok("admin1001")
        val u = UUID.randomUUID()
        val before = env.count("SELECT count(*) FROM app.route_monthly_target")
        val r = sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(listOf(row("MIR-SR-D", "MaxR-10S", next, 10), row("MIR-SR-D", "MaxR-20S", prev, 10)), false, u, reason))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("body.rows/1/month") && r.bodyAsText().contains("past_month"), r.bodyAsText())
        assertEquals(before, env.count("SELECT count(*) FROM app.route_monthly_target"), "all or none")
        assertEquals(0, env.count("SELECT count(*) FROM app.route_target_batch WHERE batch_uuid = '$u'"))
        // The current month is allowed (it is not before the current month).
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(listOf(row("MIR-SR-3F", "MaxR-20S", thisMonth, 10)), false, UUID.randomUUID(), reason)).status)
    }

    @Test
    fun bodyShapeAndLimitsAre400() = env.app {
        val admin = env.tok("admin1001")
        suspend fun status(b: String) = sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, b).status
        assertEquals(HttpStatusCode.BadRequest, status("""{"batch_uuid":"x","rows":[${row("MIR-SR-D", "MaxR-10S", next, 1)}],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, status("""{"batch_uuid":"${UUID.randomUUID()}","rows":[],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, status("""{"batch_uuid":"${UUID.randomUUID()}","rows":[${row("MIR-SR-D", "MaxR-10S", next, 1)}]}"""))
        assertEquals(HttpStatusCode.BadRequest, status("""{"batch_uuid":"${UUID.randomUUID()}","rows":[{"route_code":"MIR-SR-D","sku_code":"MaxR-10S","month":"$next","qty_base":1,"x":1}],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, status(body((1..20_001).map { row("MIR-SR-D", "MaxR-10S", next, 1) }, true)))
        assertEquals(HttpStatusCode.OK, status(body((1..20_000).map { row("MIR-SR-D", "MaxR-10S", next, 1) }, true)))
    }

    @Test
    fun listIsSortedPagedAndOfOneMonth() = env.app {
        val admin = env.tok("admin1001")
        val m = next.plusMonths(5)
        val rows = listOf(row("MIR-SR-3F", "MaxR-20S", m, 3), row("MIR-SR-D", "MaxR-10S", m, 1), row("MIR-SR-D", "MaxR-20S", m, 2), row("MIR-SR-2F", "MaxB-10S", m, 4))
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Put, "/v1/admin/route-targets", admin, body(rows, false, reason = reason)).status)
        val p1 = sendA3(HttpMethod.Get, "/v1/admin/route-targets?month=$m&limit=3", env.tok("support1001")).objA3()
        assertEquals(listOf("MIR-SR-2F|MaxB-10S", "MIR-SR-3F|MaxR-20S", "MIR-SR-D|MaxR-10S"), p1.itemsA3().map { it.strA3("route_code") + "|" + it.strA3("sku_code") })
        assertEquals("4", p1.itemsA3()[0].strA3("qty_base")); assertEquals(m.toString(), p1.itemsA3()[0].strA3("month")); assertEquals("Mirpur 10 Daily", p1.itemsA3()[2].strA3("route_name"))
        val p2 = sendA3(HttpMethod.Get, "/v1/admin/route-targets?month=$m&limit=3&cursor=${p1.strA3("next_cursor")}", admin).objA3()
        assertEquals(listOf("MIR-SR-D|MaxR-20S"), p2.itemsA3().map { it.strA3("route_code") + "|" + it.strA3("sku_code") })
        assertTrue(p2.getValue("next_cursor").toString() == "null")
        assertEquals(0, sendA3(HttpMethod.Get, "/v1/admin/route-targets?month=${m.plusMonths(1)}", admin).objA3().itemsA3().size)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/admin/route-targets?month=2026-1", admin).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/admin/route-targets?cursor=!!", admin).status)
    }

    @Test
    fun roleMatrix() = env.app {
        val rows = listOf(row("MIR-SR-D", "MaxR-10S", next.plusMonths(8), 1))
        for ((user, write, read) in listOf(
            Triple("admin1001", true, true), Triple("superadmin1001", true, true), Triple("support1001", false, true),
            Triple("tso1001", false, false), Triple("dmo1001", false, false), Triple("sr1001", false, false), Triple("amo1001", false, false),
        )) {
            val w = sendA3(HttpMethod.Put, "/v1/admin/route-targets", env.tok(user), body(rows, true)).status
            assertEquals(if (write) HttpStatusCode.OK else HttpStatusCode.Forbidden, w, "PUT as $user")
            val g = sendA3(HttpMethod.Get, "/v1/admin/route-targets", env.tok(user)).status
            assertEquals(if (read) HttpStatusCode.OK else HttpStatusCode.Forbidden, g, "GET as $user")
        }
        assertEquals(HttpStatusCode.Unauthorized, sendA3(HttpMethod.Get, "/v1/admin/route-targets", null).status)
    }
}

package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.rules.BusinessDate
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
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** N-5 (lane T-KPI1, D-TKPI-05): the KPI target upload: preview errors, commit, idempotency, audit, read, role matrix. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteKpiTargetsTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    // wall-clock-ok: the endpoint judges "past month" against the Dhaka month of the system clock
    private val thisMonth: YearMonth = YearMonth.from(BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate())
    private val next: YearMonth = thisMonth.plusMonths(1)
    private val prev: YearMonth = thisMonth.minusMonths(1)
    private val reason = "KPI sheet from the sales head"

    private fun b(route: String, brand: String, month: Any, memo: Any, alloc: Any = 0) = """{"route_code":"$route","brand_code":"$brand","month":"$month","memo_target":$memo,"kpi_alloc_mtk":$alloc}"""
    private fun m(route: String, month: Any, alloc: Any, cpr: String = "null", cprAlloc: String = "null") = """{"route_code":"$route","month":"$month","kpi_alloc_mtk":$alloc,"cpr_target_bp":$cpr,"cpr_alloc_mtk":$cprAlloc}"""
    private fun body(brand: List<String>, month: List<String>, dry: Boolean, u: UUID = UUID.randomUUID(), why: String? = null) =
        """{"batch_uuid":"$u","brand_rows":[${brand.joinToString(",")}],"month_rows":[${month.joinToString(",")}],"dry_run":$dry${if (why != null) ""","change_reason":"$why"""" else ""}}"""

    @Test
    fun previewReportsEveryRowErrorAndWritesNothing() = env.app {
        val admin = env.tok("admin1001")
        val brand = listOf(
            b("MIR-SR-D", "Maxim", next, 119, 2_000_000), b("NOPE", "Maxim", next, 1), b("MIR-AMO-1", "Maxim", next, 1), b("MIR-SR-D", "NoSuchBrand", next, 1),
            b("MIR-SR-D", "Maxim", prev, 1), b("MIR-SR-D", "Maxim", next, "12.5"), b("MIR-SR-D", "Maxim", next, 1, -1), b("MIR-SR-D", "maxim", next, 7),
        )
        val month = listOf(m("MIR-SR-D", next, 5_700_000, "5000", "600000"), m("MIR-SR-D", next, 1), m("MIR-SR-3F", next, 1, "10001"))
        val before = env.count("SELECT count(*) FROM app.route_brand_kpi_target") + env.count("SELECT count(*) FROM app.route_kpi_month") + env.count("SELECT count(*) FROM app.route_target_batch")
        val r = sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, body(brand, month, true))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val errs = r.objA3().getValue("errors").jsonArray.map { it.jsonObject }.map { Triple(it.strA3("section") + it.strA3("row"), it.strA3("column"), it.strA3("code")) }
        assertEquals(
            listOf(
                Triple("brand_rows2", "route_code", "unknown_route"), Triple("brand_rows3", "route_code", "not_selling_route"), Triple("brand_rows4", "brand_code", "unknown_brand"),
                Triple("brand_rows5", "month", "past_month"), Triple("brand_rows6", "memo_target", "invalid_quantity"), Triple("brand_rows7", "kpi_alloc_mtk", "invalid_amount"),
                Triple("brand_rows8", "route_code", "duplicate_row"), Triple("month_rows2", "route_code", "duplicate_row"), Triple("month_rows3", "cpr_target_bp", "invalid_quantity"),
            ),
            errs,
        )
        assertEquals(before, env.count("SELECT count(*) FROM app.route_brand_kpi_target") + env.count("SELECT count(*) FROM app.route_kpi_month") + env.count("SELECT count(*) FROM app.route_target_batch"), "a dry run writes nothing")
    }

    @Test
    fun commitUpsertsAuditsAndReplaysAndTheListReadsItBack() = env.app {
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        val u = UUID.randomUUID(); val mo = next.plusMonths(2)
        val brand = listOf(b("MIR-SR-D", "Maxim", mo, 119, 2_000_000))
        val month = listOf(m("MIR-SR-D", mo, 5_700_000, "5000", "600000"))
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, body(brand, month, false, u)).status, "a reason is required")
        val c = sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, body(brand, month, false, u, reason))
        assertEquals(HttpStatusCode.OK, c.status, c.bodyAsText()); assertEquals("false", c.objA3().strA3("replayed")); assertEquals("2", c.objA3().strA3("rows"))
        assertEquals(1, env.count("SELECT count(*) FROM app.route_brand_kpi_target WHERE batch_uuid = '$u' AND memo_target = 119 AND kpi_alloc_mtk = 2000000"))
        assertEquals(1, env.count("SELECT count(*) FROM app.route_kpi_month WHERE batch_uuid = '$u' AND kpi_alloc_mtk = 5700000 AND cpr_target_bp = 5000 AND cpr_alloc_mtk = 600000"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_kpi_target' AND entity_id = '$u' AND action = 'upload' AND reason = '$reason'"))
        // Replay (same content), then conflicts (other content, other user).
        val again = sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, body(brand, month, false, u, "Another reason, same batch content"))
        assertEquals("true", again.objA3().strA3("replayed"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_kpi_target' AND entity_id = '$u'"))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, body(brand, emptyList(), false, u, reason)).status)
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", sup, body(brand, month, false, u, reason)).status)
        // A second batch overwrites by key.
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", sup, body(listOf(b("MIR-SR-D", "Maxim", mo, 150, 1)), emptyList(), false, UUID.randomUUID(), reason)).status)
        val l = sendA3(HttpMethod.Get, "/v1/admin/route-kpi-targets?route_code=MIR-SR-D&month=$mo", env.tok("support1001")).objA3()
        val row = l.getValue("brand_rows").jsonArray.single().jsonObject
        assertEquals("150", row.strA3("memo_target")); assertEquals("1", row.strA3("kpi_alloc_mtk")); assertEquals("Maxim", row.strA3("brand_name"))
        assertEquals("5700000", l.getValue("month_row").jsonObject.strA3("kpi_alloc_mtk"))
        assertEquals("null", sendA3(HttpMethod.Get, "/v1/admin/route-kpi-targets?route_code=MIR-SR-3F&month=$mo", admin).objA3().getValue("month_row").toString())
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/admin/route-kpi-targets", admin).status, "route_code is required")
    }

    @Test
    fun programmeTargetsAreExactDecimalsForAnActiveSuperstarProgramme() = env.app {
        val admin = env.tok("admin1001"); val mo = next.plusMonths(3); val u = UUID.randomUUID()
        env.exec("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('superstar', 'MBLS-T', 'Match Box Lifting Star', date '$mo-01', date '$mo-28', 'active'), ('astha', 'AST-T', 'Astha', date '$mo-01', date '$mo-28', 'active')")
        fun p(route: String, code: String, month: Any, qty: String) = """{"route_code":"$route","programme_code":"$code","month":"$month","target_qty":$qty}"""
        fun pbody(rows: List<String>, dry: Boolean, id: UUID = UUID.randomUUID()) = """{"batch_uuid":"$id","programme_rows":[${rows.joinToString(",")}],"dry_run":$dry${if (!dry) ""","change_reason":"$reason"""" else ""}}"""
        val pv = sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, pbody(listOf(p("MIR-SR-D", "MBLS-T", mo, "\"20.000\""), p("MIR-SR-D", "NOPE", mo, "\"1\""), p("MIR-SR-D", "AST-T", mo, "\"1\""), p("MIR-SR-3F", "MBLS-T", mo, "20.5"), p("MIR-SR-3F", "MBLS-T", mo, "\"1.2345\"")), true))
        assertEquals(HttpStatusCode.OK, pv.status, pv.bodyAsText())
        val errs = pv.objA3().getValue("errors").jsonArray.map { it.jsonObject }.map { it.strA3("row") + it.strA3("column") + it.strA3("code") }
        assertEquals(listOf("2programme_codeunknown_programme", "3programme_codeunknown_programme", "4target_qtyinvalid_quantity", "5target_qtyinvalid_quantity"), errs, "a JSON number and a fourth decimal are not quantities; only an active superstar programme is a target")
        val c = sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, pbody(listOf(p("MIR-SR-D", "MBLS-T", mo, "\"20.000\""), p("MIR-SR-3F", "MBLS-T", mo, "\"7.5\"")), false, u))
        assertEquals(HttpStatusCode.OK, c.status, c.bodyAsText()); assertEquals("2", c.objA3().strA3("rows"))
        assertEquals(1, env.count("SELECT count(*) FROM app.route_programme_target t JOIN app.route r ON r.id = t.route_id WHERE r.code = 'MIR-SR-3F' AND t.target_qty = 7.500 AND t.batch_uuid = '$u'"))
        assertEquals("true", sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, pbody(listOf(p("MIR-SR-3F", "MBLS-T", mo, "\"7.5\""), p("MIR-SR-D", "MBLS-T", mo, "\"20.000\"")), false, u)).objA3().strA3("replayed"))
        val l = sendA3(HttpMethod.Get, "/v1/admin/route-kpi-targets?route_code=MIR-SR-D&month=$mo", admin).objA3().getValue("programme_rows").jsonArray.single().jsonObject
        assertEquals("20.000", l.strA3("target_qty")); assertEquals("MBLS-T", l.strA3("programme_code"))
    }

    @Test
    fun aBadRowRefusesTheWholeBatchAndTheRoleMatrixHolds() = env.app {
        val admin = env.tok("admin1001"); val u = UUID.randomUUID()
        val before = env.count("SELECT count(*) FROM app.route_brand_kpi_target")
        val r = sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", admin, body(listOf(b("MIR-SR-D", "Maxim", next.plusMonths(7), 5), b("MIR-SR-D", "Maxim", prev, 5)), emptyList(), false, u, reason))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("body.brand_rows/1/month") && r.bodyAsText().contains("past_month"))
        assertEquals(before, env.count("SELECT count(*) FROM app.route_brand_kpi_target")); assertEquals(0, env.count("SELECT count(*) FROM app.route_target_batch WHERE batch_uuid = '$u'"))
        for ((user, write, read) in listOf(Triple("admin1001", true, true), Triple("superadmin1001", true, true), Triple("support1001", false, true), Triple("tso1001", false, false), Triple("sr1001", false, false), Triple("amo1001", false, false))) {
            val w = sendA3(HttpMethod.Put, "/v1/admin/route-kpi-targets", env.tok(user), body(listOf(b("MIR-SR-D", "Maxim", next.plusMonths(9), 1)), emptyList(), true)).status
            assertEquals(if (write) HttpStatusCode.OK else HttpStatusCode.Forbidden, w, "PUT as $user")
            val g = sendA3(HttpMethod.Get, "/v1/admin/route-kpi-targets?route_code=MIR-SR-D", env.tok(user)).status
            assertEquals(if (read) HttpStatusCode.OK else HttpStatusCode.Forbidden, g, "GET as $user")
        }
        assertEquals(HttpStatusCode.Unauthorized, sendA3(HttpMethod.Get, "/v1/admin/route-kpi-targets?route_code=MIR-SR-D", null).status)
    }
}

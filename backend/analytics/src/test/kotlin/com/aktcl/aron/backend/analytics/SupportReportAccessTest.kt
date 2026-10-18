package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AZ-2: SUPPORT sees sync health only (docs/24 s8.5): every business report is 403, the report list is empty, other users' export files stay hidden. */
class SupportReportAccessTest : ReportFixture() {
    private val support = 15L

    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        r.opsRoutes(OpsDeps(OpsService(fresh.db, RegistryDefaults(), clock), DashboardService(fresh.db, clock), reach, guard, clock))
    }

    private fun addSupport() {
        fresh.db.jdbi.useHandle<Exception> { it.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($support, 'user15', 'User 15', 'SUPPORT', false)") }
        reaches[support] = Reach(support, Role.SUPPORT, day, true, emptySet(), emptySet(), false, emptyList())
    }

    @Test
    fun supportGetsForbiddenOnEveryBusinessReportAndKeepsSyncHealth() = app {
        addSupport()
        assertEquals(32, ReportHandlers.all.size)
        for (h in ReportHandlers.all) {
            val key = h.definition.report_key
            val r = run(support, Role.SUPPORT, key)
            assertEquals(HttpStatusCode.Forbidden, r.status, key)
            assertTrue(r.bodyAsText().contains("ERR_FORBIDDEN"), key)
        }
        val defs = Json.parseToJsonElement(client.get("/v1/reports") { bearerAuth(TestTokens.web(support, Role.SUPPORT)) }.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(0, defs.size)
        assertEquals(HttpStatusCode.OK, client.get("/v1/dashboards/sync-health?business_date=2026-10-04") { bearerAuth(TestTokens.web(support, Role.SUPPORT)) }.status)
        // The roles that keep reports still run them.
        assertEquals(HttpStatusCode.OK, run(10, Role.ANALYST, "retailer-list").status)
        assertEquals(HttpStatusCode.OK, run(13, Role.ADMIN, "settlement").status)
    }

    @Test
    fun supportCannotOpenSomeoneElsesExportFile() = app {
        addSupport()
        val r = run(10, Role.ANALYST, "route-memo", body(format = "pdf"))   // pdf always runs as a job
        assertEquals(HttpStatusCode.Accepted, r.status)
        val id = r.result()["export_id"]!!.jsonPrimitive.content
        exports.runUntilIdle()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/report-exports/$id") { bearerAuth(TestTokens.web(support, Role.SUPPORT)) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/report-exports/$id") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.status)
        // The export log stays readable to SUPPORT (who exported what; no rows, no link), as the audit log is.
        assertEquals(HttpStatusCode.OK, client.get("/v1/report-exports") { bearerAuth(TestTokens.web(support, Role.SUPPORT)) }.status)
    }
}

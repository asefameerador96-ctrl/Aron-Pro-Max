package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.util.Base64

// ---------------------------------------------------------------------------------------------------------------------
// The read-only online refreshes of the loyalty field side (contract 1.15.0; lane portal-loyalty-api, train 3, push B):
//   GET /v1/programmes/loyalty/balances   getLoyaltyBalances: the SAME balance function as the bundle (every ledger row posted so far less the redemptions not posted yet), read now
//   GET /v1/programmes/astha/targets      listAsthaTargets: the quarter's target rows with achievement as of the read
//   GET /v1/programmes/astha/gift-choices listGiftChoices: the quarter's gift choices
// The bundle stays the offline source: a failed refresh keeps the bundle figure with its age (phone). Scope comes from the token only (SR: assigned routes; AMO, TSO,
// DMO, WM: routes of their zones and routes; national roles: everything, which is why a national reader must narrow by route_id or zone_id for the two lists). The
// same SQL as the bundle section (BundleProgrammes), so the read and the bundle cannot disagree. Units are the bundle's: points integers, money integer milli-taka
// text, STD in base units. The tile flags (cfg.flag.*_ui) are screen switches and do not gate these reads.
// ---------------------------------------------------------------------------------------------------------------------

class ProgrammeReadDeps(val db: Database, val config: ServerConfig, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val READERS = setOf(Role.SR, Role.AMO, Role.TSO, Role.DMO, Role.WM, Role.NATIONAL, Role.TOP, Role.ADMIN, Role.SUPERADMIN)
private const val MAX_BALANCES = 2000

fun Route.programmeReadRoutes(d: ProgrammeReadDeps) {
    authenticated(d.guard) {
        get("/programmes/loyalty/balances") {
            val p = call.principal
            val q = call.request.queryParameters
            call.respond(withContext(Dispatchers.IO) { ProgrammeReads(d).balances(p, q.getAll("outlet_id"), q["programme_id"]) })
        }
        get("/programmes/astha/targets") {
            val p = call.principal
            call.respond(withContext(Dispatchers.IO) { ProgrammeReads(d).targets(p, call.request.queryParameters) })
        }
        get("/programmes/astha/gift-choices") {
            val p = call.principal
            call.respond(withContext(Dispatchers.IO) { ProgrammeReads(d).giftChoices(p, call.request.queryParameters) })
        }
    }
}

private fun bad(pointer: String, code: String = "invalid_value"): Nothing =
    throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError("query.$pointer", code)))

internal class ProgrammeReads(private val d: ProgrammeReadDeps) {
    private class Scope(val routes: List<Long>, val outlets: List<Long>, val reach: Reach)

    private fun today() = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()

    /**
     * The routes and active outlets the caller may read, from the token's reach. [routeId] and [zoneId] narrow and must be inside it (403 ERR_OUT_OF_SCOPE else).
     * A national reader with neither narrowing gets 400: the whole network is not a phone or panel read.
     */
    private fun scope(h: Handle, p: AronPrincipal, routeId: Long?, zoneId: Long?, needNarrowForNational: Boolean, outletIds: List<Long>? = null): Scope {
        if (p.role !in READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "loyalty and Astha reads are not available to this role")
        val reach: Reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today())
        if (zoneId != null && !reach.coversZone(zoneId)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone_id is outside your reach")
        if (outletIds != null) {   // named outlets: judged one by one by the rule ingest applies to an outlet (own routes for field reps, else zone or route)
            val visible = h.createQuery("SELECT id, route_id, zone_id FROM app.outlet WHERE id = ANY(CAST(:ids AS bigint[])) AND status = 'active' AND route_id IS NOT NULL ORDER BY id")
                .bindArray("ids", Long::class.javaObjectType, outletIds).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.list()
                .filter { (_, rid, zid) -> if (reach.ownRecordsOnly) rid in reach.routeIds else reach.coversZone(zid) || rid in reach.routeIds }
            return Scope(visible.map { it.second }.distinct(), visible.map { it.first }, reach)
        }
        if (needNarrowForNational && reach.national && !reach.ownRecordsOnly && routeId == null && zoneId == null) bad("route_id", "required_for_national_reach")
        val routes = h.createQuery(
            "SELECT r.id, r.zone_id FROM app.route r WHERE (CAST(:route AS bigint) IS NULL OR r.id = :route) AND (CAST(:zone AS bigint) IS NULL OR r.zone_id = :zone) ORDER BY r.id",
        ).bind("route", routeId).bind("zone", zoneId).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            .filter { (rid, zid) -> if (reach.ownRecordsOnly) rid in reach.routeIds else reach.coversZone(zid) || rid in reach.routeIds }
            .map { it.first }
        if (routeId != null && routeId !in routes) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "route_id is outside your reach")
        val outlets = if (routes.isEmpty()) emptyList() else h.createQuery("SELECT id FROM app.outlet WHERE route_id = ANY(CAST(:routes AS bigint[])) AND status = 'active' ORDER BY id")
            .bindArray("routes", Long::class.javaObjectType, routes).mapTo(Long::class.java).list()
        return Scope(routes, outlets, reach)
    }

    /** Outlets listed in `outlet_id` that are NOT in the caller's reach are left out of the answer (never an error that would tell which ids exist). */
    fun balances(p: AronPrincipal, outletParams: List<String>?, programmeParam: String?): JsonObject {
        val wanted = outletParams?.map { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("outlet_id") }
        if (wanted != null && wanted.size > 200) bad("outlet_id", "too_many")
        val programme = programmeParam?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("programme_id") }
        val date = today()
        val got = d.db.jdbi.withHandle<BundleProgrammes.Capped<JsonObject>, Exception> { h ->
            val sc = scope(h, p, null, null, needNarrowForNational = wanted == null, outletIds = wanted?.distinct())
            val outlets = sc.outlets
            if (outlets.isEmpty()) return@withHandle BundleProgrammes.Capped(emptyList(), false)
            // The programme filter is applied INSIDE the programme query (before its cap); the shared geography predicate keeps out-of-scope campaigns.
            val programmes = BundleProgrammes.programmes(h, date, listOf("diamond_league", "astha", "campaign", "superstar"), outlets, BundleProgrammes.scopeOf(h, sc.reach, outlets), programme).items.map { it.first }
            if (programmes.isEmpty()) BundleProgrammes.Capped(emptyList(), false) else BundleProgrammes.loyalty(h, outlets, programmes, date, MAX_BALANCES)
        }
        return buildJsonObject { put("as_of", d.clock.now().wire()); put("truncated", got.truncated); put("items", JsonArray(got.items)) }
    }

    fun targets(p: AronPrincipal, q: Parameters): JsonObject {
        val quarterText = q["quarter"] ?: bad("quarter", "required")
        val startMonth = runCatching { d.config.value("cfg.astha.quarter_start_month").jsonPrimitive.intOrNull }.getOrNull() ?: 1
        val quarter = AsthaQuarter.parse(quarterText, startMonth) ?: bad("quarter", "pattern")
        val routeId = q["route_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("route_id") }
        val zoneId = q["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("zone_id") }
        val (limit, offset) = page(q)
        val now = d.clock.now()
        val rows = d.db.readJdbi.withHandle<List<JsonObject>, Exception> { h ->
            val scope = scope(h, p, routeId, zoneId, needNarrowForNational = true)
            if (scope.routes.isEmpty()) emptyList() else BundleProgrammes.asthaTargets(h, scope.routes, scope.outlets, quarter, now, limit + 1, offset)
        }
        val shown = rows.take(limit)
        return buildJsonObject {
            put("quarter", quarter.label); put("as_of", now.wire()); put("items", JsonArray(shown))
            put("next_cursor", if (rows.size > limit) JsonPrimitive(cursor(offset + limit)) else JsonNull)
        }
    }

    fun giftChoices(p: AronPrincipal, q: Parameters): JsonObject {
        val quarterText = q["quarter"] ?: bad("quarter", "required")
        val startMonth = runCatching { d.config.value("cfg.astha.quarter_start_month").jsonPrimitive.intOrNull }.getOrNull() ?: 1
        val quarter = AsthaQuarter.parse(quarterText, startMonth) ?: bad("quarter", "pattern")
        val routeId = q["route_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("route_id") }
        val zoneId = q["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("zone_id") }
        val (limit, offset) = page(q)
        val rows = d.db.readJdbi.withHandle<List<JsonObject>, Exception> { h ->
            val scope = scope(h, p, routeId, zoneId, needNarrowForNational = true)
            if (scope.routes.isEmpty()) emptyList() else BundleProgrammes.giftAssignmentsOf(h, scope.reach, scope.routes, quarter, limit + 1, offset)
        }
        return buildJsonObject {
            put("items", JsonArray(rows.take(limit)))
            put("next_cursor", if (rows.size > limit) JsonPrimitive(cursor(offset + limit)) else JsonNull)
        }
    }

    private fun page(q: Parameters): Pair<Int, Int> {
        val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: bad("limit", "out_of_range") } ?: 100
        val offset = q["cursor"]?.let { c ->
            runCatching { String(Base64.getUrlDecoder().decode(c)).removePrefix("o1|").toInt().takeIf { v -> v >= 0 && String(Base64.getUrlDecoder().decode(c)).startsWith("o1|") } }.getOrNull() ?: bad("cursor")
        } ?: 0
        return limit to offset
    }

    private fun cursor(offset: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString("o1|$offset".toByteArray())
}

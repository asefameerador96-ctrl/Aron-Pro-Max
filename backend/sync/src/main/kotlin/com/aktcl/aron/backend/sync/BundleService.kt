package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DayPlan
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.RouteDayPlan
import com.aktcl.aron.backend.platform.RoutePlanner
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.CodeListKey
import com.aktcl.aron.contract.ConfigScopeType
import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * Builds the day bundle of a user for a business date (F-API-005, docs/24 s4.10) and records the two facts the first
 * bundle of a day carries: the route-day rows of the user's routes exist with `target_outlets` frozen (s12.4), and the
 * route-days move to `logged_in` (s4.9; a pre-fetch for a later date never does).
 *
 * `bundle_version` is `<date>:<seq>` where `seq` is a 9-digit digest of the bundle content without its meta, so equal
 * content always has the same version on every replica and any change of content changes it (ETag, 304). The
 * pre-generated Blob snapshots of s4.10 are the worker's (F-SYS-067); this builds on request.
 *
 * Deferred programmes (docs/27): `offers`, `targets`, `achievement_mtd` and `programme_flags` stay empty, `programmes`
 * is null.
 */
class BundleService(
    private val db: Database,
    private val config: ServerConfig,
    private val planner: RoutePlanner,
    private val clock: AronClock = AronClock.SYSTEM,
) {
    data class Result(val bundle: Bundle, val version: String)

    private data class UserRow(
        val id: Long, val username: String, val fullName: String, val role: String, val designation: String?, val locale: String,
        val homeZoneId: Long?,
    )

    private data class GeoChain(val zoneId: Long, val territoryId: Long, val divisionId: Long, val wingId: Long) {
        fun dayPlan() = DayPlan.ZoneChain(zoneId, territoryId, divisionId, wingId)
    }

    fun bundle(p: AronPrincipal, forDate: LocalDate?, appVersion: String?): Result {
        val now = clock.now()
        val today = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
        val user = db.jdbi.withHandle<UserRow, Exception> { h -> loadUser(h, p.userId) }
        val home = user.homeZoneId?.let { z -> db.jdbi.withHandle<Map<Long, GeoChain>, Exception> { h -> chains(h, listOf(z)) }[z] }
        val horizonDays = config.int("cfg.sys.schedule_horizon_days").coerceIn(0, 31)
        val until = now.plusSeconds(horizonDays * 86_400L)
        val cfg = db.jdbi.withHandle<ScopedConfig, Exception> { h -> ScopedConfig.load(h, now, until) }
        val roleOrdinal = db.jdbi.withHandle<Long?, Exception> { h ->
            h.createQuery("SELECT ordinal FROM app.role_def WHERE role = :r").bind("r", user.role).mapTo(Long::class.java).findOne().orElse(null)
        }
        val userChain = ScopedConfig.Chain.of(
            ConfigScopeType.ROLE to roleOrdinal, ConfigScopeType.WING to home?.wingId, ConfigScopeType.DIVISION to home?.divisionId,
            ConfigScopeType.TERRITORY to home?.territoryId, ConfigScopeType.ZONE to home?.zoneId, ConfigScopeType.USER to user.id,
            ConfigScopeType.DEVICE to p.deviceId,
        )
        val weekend = cfg.value("cfg.calendar.weekend_days", userChain)?.let { v -> runCatching { v.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }.filter { it in 1..7 } }.getOrNull() }
            ?.toSet() ?: DayPlan.DEFAULT_WEEKEND

        val date = forDate ?: today
        if (date.isBefore(today)) throw badFor("for must not be before today ($today)")
        if (date.isAfter(today)) {
            val next = nextWorkingDay(today, home, weekend)
            if (date.isAfter(next)) throw badFor("for must be at most the next working day ($next)")
        }
        val prefetch = date.isAfter(today)
        val plans = db.jdbi.withHandle<List<RouteDayPlan>, Exception> { h -> withScopedWeekend(h, planner.routesFor(user.id, date), date, cfg, roleOrdinal, user.id) }

        val dayStates = db.jdbi.inTransaction<Map<Long, DayRow>, Exception> { h ->
            versionGate(h, p, plans, date, appVersion)
            recordFirstBundle(h, user.id, plans, date, prefetch, now)
            loadDayStates(h, plans.map { it.routeId }, date)
        }

        val configVersion = config.configVersion() // one read: config.version and device_policy_version agree
        val bundle = db.jdbi.withHandle<Bundle, Exception> { h ->
            val routes = routeSnapshots(h, user.id, plans, date, dayStates, cfg, roleOrdinal)
            val zones = (plans.map { it.zoneId } + listOfNotNull(user.homeZoneId)).distinct()
            Bundle(
                meta = PLACEHOLDER_META,
                user = BundleUser(
                    user_id = user.id, username = user.username, full_name = user.fullName, role = user.role, designation = user.designation,
                    locale = if (user.locale == "en") "en" else "bn", bind_ordinal = bindOrdinal(h, p),
                    memo_seq_block_size = config.int("cfg.memo.seq_block_size").coerceIn(100, 999),
                    zone_id = user.homeZoneId, territory_id = home?.territoryId,
                    consents = ConsentRecords.accepted(h, user.id),
                ),
                config = ResolvedConfig(configVersion, cfg.deviceValues(userChain), cfg.deviceScheduled(userChain, until)),
                code_lists = codeLists(h, date),
                products = products(h),
                prices = prices(h, date, date.plusDays(horizonDays.toLong())),
                offers = emptyList(),
                calendar = CalendarSection(weekend.sorted(), holidays(h, date, date.plusDays(horizonDays.toLong()), zones)),
                templates = emptyList(),
                routes = routes,
                tasks = tasks(h, user.id),
                surveys = emptyList(),
                rubrics = emptyList(),
                supervisor = null,
                reason_texts = ReasonTexts.ALL,
                device_policy_version = configVersion,
                programmes = null,
                content = emptyList(),
                tutorials = emptyList(),
            )
        }
        val seq = snapshotSeq(user.id, date, bundle)
        val version = "$date:$seq"
        val meta = BundleMeta(
            bundle_version = version, valid_for_business_date = date.toString(), generated_at = now.wire(), server_time = clock.now().wire(),
            user_id = user.id, role = user.role, config_version = bundle.config.config_version, schema_version = ContractInfo.SCHEMA_VERSION,
            cursor = cursor(date, seq, now), is_prefetch = prefetch, paged_sections = emptyList(),
        )
        return Result(bundle.copy(meta = meta), version)
    }

    /**
     * `cfg.calendar.weekend_days` is scoped (global, wing, division): `planned_today` and the target follow the weekend
     * resolved for each route's zone, the same value the bundle's calendar carries.
     */
    private fun withScopedWeekend(h: Handle, plans: List<RouteDayPlan>, date: LocalDate, cfg: ScopedConfig, roleOrdinal: Long?, userId: Long): List<RouteDayPlan> {
        if (plans.isEmpty()) return plans
        val chains = chains(h, plans.map { it.zoneId }.distinct())
        val calendar = h.createQuery("SELECT date, scope_type, scope_id, kind, selling_day FROM app.calendar_holiday WHERE date = :d AND revoked_at IS NULL")
            .bind("d", date).map { rs, _ ->
                DayPlan.CalendarEntry(rs.getObject(1, LocalDate::class.java), DayPlan.CalendarScope.valueOf(rs.getString(2).uppercase()), rs.getLong(3), rs.getString(4), rs.getBoolean(5))
            }.list()
        val active = h.createQuery("SELECT id FROM app.route WHERE id = ANY(:r) AND status = 'active'").bindArray("r", Long::class.javaObjectType, plans.map { it.routeId })
            .mapTo(Long::class.java).set()
        return plans.map { r ->
            val g = chains[r.zoneId] ?: return@map r
            val chain = ScopedConfig.Chain.of(ConfigScopeType.ROLE to roleOrdinal, ConfigScopeType.WING to g.wingId, ConfigScopeType.DIVISION to g.divisionId,
                ConfigScopeType.TERRITORY to g.territoryId, ConfigScopeType.ZONE to g.zoneId, ConfigScopeType.USER to userId)
            val weekend = weekendOf(cfg, chain)
            val planned = DayPlan.isPlanned(DayPlan.PlanRoute(r.routeId, r.visitDaysMask, g.dayPlan(), r.routeId in active), date, calendar, weekend)
            r.copy(plannedToday = planned, targetOutlets = if (planned) r.activeOutlets else 0)
        }
    }

    private fun weekendOf(cfg: ScopedConfig, chain: ScopedConfig.Chain): Set<Int> =
        cfg.value("cfg.calendar.weekend_days", chain)?.let { v -> runCatching { v.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }.filter { it in 1..7 } }.getOrNull() }
            ?.toSet() ?: DayPlan.DEFAULT_WEEKEND

    /**
     * `snapshot_seq`: with `app.bundle_snapshot` (docs/requests/backend-bundle-snapshot-table.md) a number that grows with
     * every new content of the user's date (equal content keeps its number); until the table exists, the content
     * digest. Either way equal content gives an equal version (ETag) on every replica.
     */
    private fun snapshotSeq(userId: Long, date: LocalDate, b: Bundle): Long {
        val digest = contentDigest(b)
        return db.jdbi.inTransaction<Long, Exception> { h ->
            val hasTable = h.createQuery("SELECT to_regclass('app.bundle_snapshot') IS NOT NULL").mapTo(Boolean::class.java).one()
            if (!hasTable) return@inTransaction contentSeq(b)
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", "bundle:$userId:$date")
            val latest = h.createQuery("SELECT snapshot_seq, content_sha256 FROM app.bundle_snapshot WHERE user_id = :u AND business_date = :d ORDER BY snapshot_seq DESC LIMIT 1")
                .bind("u", userId).bind("d", date).map { rs, _ -> rs.getLong(1) to rs.getBytes(2) }.findOne().orElse(null)
            if (latest != null && latest.second.contentEquals(digest)) return@inTransaction latest.first
            val next = (latest?.first ?: 0L) + 1
            h.createUpdate("INSERT INTO app.bundle_snapshot (user_id, business_date, snapshot_seq, content_sha256) VALUES (:u, :d, :s, :h)")
                .bind("u", userId).bind("d", date).bind("s", next).bind("h", digest).execute()
            next
        }
    }

    private fun badFor(why: String) = ApiProblem(ProblemCode.ERR_VALIDATION, why, errors = listOf(FieldError("query.for", "out_of_range")))

    private fun loadUser(h: Handle, id: Long): UserRow = h.createQuery(
        "SELECT id, username, full_name, role, designation, locale, home_zone_id FROM app.app_user WHERE id = :id",
    ).bind("id", id).map { rs, _ ->
        UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"), rs.getString("role"), rs.getString("designation"),
            rs.getString("locale") ?: "bn", rs.getObject("home_zone_id") as Long?)
    }.findOne().orElseThrow { ApiProblem(ProblemCode.ERR_UNAUTHENTICATED, "unknown user") }

    private fun chains(h: Handle, zoneIds: Collection<Long>): Map<Long, GeoChain> = if (zoneIds.isEmpty()) emptyMap() else h.createQuery(
        """
        SELECT z.id, z.territory_id, t.division_id, d.wing_id FROM app.zone z
        JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id WHERE z.id = ANY(:z)
        """.trimIndent(),
    ).bindArray("z", Long::class.javaObjectType, zoneIds.toList()).map { rs, _ -> GeoChain(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }
        .list().associateBy { it.zoneId }

    /** The next selling day after [today] for the user's home zone (global calendar when the user has none), within 14 days. */
    private fun nextWorkingDay(today: LocalDate, home: GeoChain?, weekend: Set<Int>): LocalDate {
        val chain = home?.dayPlan() ?: DayPlan.ZoneChain(-1, -1, -1, -1)
        val calendar = db.jdbi.withHandle<List<DayPlan.CalendarEntry>, Exception> { h ->
            h.createQuery("SELECT date, scope_type, scope_id, kind, selling_day FROM app.calendar_holiday WHERE date > :a AND date <= :b AND revoked_at IS NULL")
                .bind("a", today).bind("b", today.plusDays(14)).map { rs, _ ->
                    DayPlan.CalendarEntry(rs.getObject(1, LocalDate::class.java), DayPlan.CalendarScope.valueOf(rs.getString(2).uppercase()), rs.getLong(3), rs.getString(4), rs.getBoolean(5))
                }.list()
        }
        return (1L..14L).map { today.plusDays(it) }.firstOrNull { DayPlan.isSellingDay(it, chain, calendar, weekend) } ?: today.plusDays(1)
    }

    /**
     * s3.7: a build below `cfg.release.min_version_code` gets 426 on a bundle for a NEW business date only; a phone
     * that already logged in on this date keeps its day (an open offline day finishes).
     */
    private fun versionGate(h: Handle, p: AronPrincipal, plans: List<RouteDayPlan>, date: LocalDate, appVersion: String?) {
        if (!p.isPhone) return
        val min = runCatching { config.value("cfg.release.min_version_code").jsonObject[p.flavour]?.jsonPrimitive?.intOrNull }.getOrNull() ?: return
        val code = appVersion?.substringAfterLast('+', "")?.toIntOrNull() ?: return
        if (code >= min) return
        val alreadyIn = plans.isNotEmpty() && h.createQuery(
            "SELECT count(*) FROM app.route_day WHERE route_id = ANY(:r) AND business_date = :d AND logged_in_at IS NOT NULL AND (assigned_user_id = :u OR acting_user_id = :u)",
        ).bindArray("r", Long::class.javaObjectType, plans.map { it.routeId }).bind("d", date).bind("u", p.userId).mapTo(Long::class.java).one() > 0
        if (!alreadyIn) throw ApiProblem(ProblemCode.ERR_APP_VERSION_UNSUPPORTED, "update the app", context = mapOf("min_version_code" to JsonPrimitive(min)))
    }

    /**
     * Creates the route-day of every route the user holds on [date] if the planning job has not (F-SYS-056), freezes
     * `target_outlets` once at the first bundle of the day (a pre-fetch shows the computed value but never freezes it)
     * and, unless this is a pre-fetch, stamps `logged_in_at` and moves `not_started` to `logged_in` (states never move
     * backwards, s4.9). The primary or cover holder is recorded whichever fetched first.
     */
    private fun recordFirstBundle(h: Handle, userId: Long, plans: List<RouteDayPlan>, date: LocalDate, prefetch: Boolean, now: Instant) {
        val at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        for (r in plans) {
            val cover = r.assignmentKind == "cover"
            h.createUpdate(
                """
                INSERT INTO app.route_day (route_id, business_date, planned, planned_source, assigned_user_id, acting_user_id)
                VALUES (:r, :d, :planned, :src, :assigned, :acting)
                ON CONFLICT (route_id, business_date) DO NOTHING
                """.trimIndent(),
            ).bind("r", r.routeId).bind("d", date).bind("planned", r.plannedToday).bind("src", if (cover) "cover" else "schedule")
                .bind("assigned", if (cover) null else userId).bind("acting", if (cover) userId else null).execute()
            // Who holds the route-day is recorded whoever fetched first (primary or cover).
            h.createUpdate(
                if (cover) "UPDATE app.route_day SET acting_user_id = :u WHERE route_id = :r AND business_date = :d AND acting_user_id IS NULL"
                else "UPDATE app.route_day SET assigned_user_id = :u WHERE route_id = :r AND business_date = :d AND assigned_user_id IS NULL",
            ).bind("u", userId).bind("r", r.routeId).bind("d", date).execute()
            // s12.4: the target is frozen by the first bundle OF THE DAY; a pre-fetch never counts (s4.9).
            if (!prefetch) {
                h.createUpdate("UPDATE app.route_day SET target_outlets = :t, target_frozen_at = :at, planned = :planned WHERE route_id = :r AND business_date = :d AND target_outlets IS NULL")
                    .bind("t", r.targetOutlets).bind("at", at).bind("planned", r.plannedToday).bind("r", r.routeId).bind("d", date).execute()
            }
        }
        if (!prefetch && plans.isNotEmpty()) {
            h.createQuery(
                """
                UPDATE app.route_day SET logged_in_at = :at, state = CASE WHEN state = 'not_started' THEN 'logged_in' ELSE state END
                WHERE route_id = ANY(:r) AND business_date = :d AND logged_in_at IS NULL
                RETURNING route_id
                """.trimIndent(),
            ).bindArray("r", Long::class.javaObjectType, plans.map { it.routeId }).bind("d", date).bind("at", at)
                .mapTo(Long::class.java).list()
                // F-SYS-086: the first login of the day moves the tile's state; once per route-day (logged_in_at was null).
                .forEach { h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'route_day_state')", it, date) }
        }
    }

    private data class DayRow(val state: RouteDayStateDto, val target: Int?, val snapshotVersion: Int)

    private fun loadDayStates(h: Handle, routeIds: List<Long>, date: LocalDate): Map<Long, DayRow> = if (routeIds.isEmpty()) emptyMap() else h.createQuery(
        """
        SELECT route_id, business_date, state, planned, submit_cycle, submit_voided, submit_count_mismatch, logged_in_at, sales_submitted_at,
               final_submitted_at, target_outlets, route_snapshot_version
        FROM app.route_day WHERE route_id = ANY(:r) AND business_date = :d
        """.trimIndent(),
    ).bindArray("r", Long::class.javaObjectType, routeIds).bind("d", date).map { rs, _ ->
        rs.getLong("route_id") to DayRow(RouteDayStateDto(
            route_id = rs.getLong("route_id"), business_date = rs.getObject("business_date", LocalDate::class.java).toString(),
            state = rs.getString("state"), planned = rs.getBoolean("planned"), submit_cycle = rs.getInt("submit_cycle"),
            submit_voided = rs.getBoolean("submit_voided"), submit_count_mismatch = rs.getObject("submit_count_mismatch") as Boolean?,
            logged_in_at = rs.ts("logged_in_at"), sales_submitted_at = rs.ts("sales_submitted_at"), final_submitted_at = rs.ts("final_submitted_at"),
        ), rs.getObject("target_outlets") as Int?, rs.getInt("route_snapshot_version"))
    }.list().toMap()

    private fun bindOrdinal(h: Handle, p: AronPrincipal): Int = p.deviceId?.let { d ->
        h.createQuery("SELECT bind_ordinal FROM app.device_binding WHERE device_id = :d AND user_id = :u AND status = 'active' ORDER BY bound_at DESC LIMIT 1")
            .bind("d", d).bind("u", p.userId).mapTo(Int::class.java).findOne().orElse(0)
    }?.coerceIn(0, 3) ?: 0

    private fun routeSnapshots(
        h: Handle, userId: Long, plans: List<RouteDayPlan>, date: LocalDate, states: Map<Long, DayRow>, cfg: ScopedConfig, roleOrdinal: Long?,
    ): List<RouteSnapshot> {
        if (plans.isEmpty()) return emptyList()
        val ids = plans.map { it.routeId }
        val routes = h.createQuery("SELECT * FROM app.route WHERE id = ANY(:r)").bindArray("r", Long::class.javaObjectType, ids).map { rs, _ ->
            RouteDto(
                id = rs.getLong("id"), code = rs.getString("code"), name = rs.getString("name"), display_label = rs.getString("display_label"),
                zone_id = rs.getLong("zone_id"), kind = rs.getString("kind"), visit_kind = rs.getString("visit_kind"),
                visit_days_mask = rs.getInt("visit_days_mask"), sequence_no = rs.getObject("sequence_no") as Int?, status = rs.getString("status"),
                created_at = rs.ts("created_at")!!, updated_at = rs.ts("updated_at")!!, version = rs.getInt("version"),
            )
        }.list().associateBy { it.id }
        // The primary holder of a covered route on the date (acting_for_user_id).
        val primaries = h.createQuery(
            "SELECT route_id, user_id FROM app.route_assignment WHERE route_id = ANY(:r) AND kind = 'primary' AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)",
        ).bindArray("r", Long::class.javaObjectType, ids).bind("d", date).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().toMap()
        val outlets = outlets(h, ids, cfg, roleOrdinal)
        val openMemos = openMemos(h, outlets.values.flatten().map { it.outlet_id })
        val plan = h.createQuery("SELECT zone_id, sku_id FROM app.sales_plan WHERE zone_id = ANY(:z) AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d) ORDER BY sku_id")
            .bindArray("z", Long::class.javaObjectType, plans.map { it.zoneId }.distinct()).bind("d", date).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            .groupBy({ it.first }, { it.second })
        return plans.sortedBy { it.routeId }.map { r ->
            val day = states[r.routeId]
            val target = day?.target ?: r.targetOutlets
            val routeOutlets = outlets[r.routeId].orEmpty()
            val outletIds = routeOutlets.map { it.outlet_id }.toSet()
            RouteSnapshot(
                route_id = r.routeId, route_snapshot_version = day?.snapshotVersion ?: 1, route = routes.getValue(r.routeId),
                assignment_kind = r.assignmentKind, acting_for_user_id = if (r.assignmentKind == "cover") primaries[r.routeId]?.takeIf { it != userId } else null,
                planned_today = r.plannedToday, target_outlets = target, outlets = routeOutlets,
                open_memos = openMemos.filter { it.outlet_id in outletIds }, sales_plan_sku_ids = plan[r.zoneId].orEmpty(),
                targets = emptyList(), achievement_mtd = emptyList(),
                day_state = day?.state ?: RouteDayStateDto(r.routeId, date.toString(), "not_started", r.plannedToday, 1, false, null, null, null, null),
            )
        }
    }

    /**
     * `outlet_radius_changes` of `GET /v1/config/delta` (android-core-backend-config-delta-radius.md, F-SYS-053): null
     * when no radius or accuracy row (any scope, outlet and route included) was written or superseded after [since];
     * otherwise every active outlet of the caller's routes today with its radius and accuracy resolved exactly as the
     * bundle resolves them (the same function). Unchanged outlets ride along: applying a value twice is harmless, and
     * "as of [since]" per outlet is not worth a second resolver. At most 5000 (contract maxItems).
     */
    fun outletRadiusChanges(userId: Long, since: Long): List<Triple<Long, Int, Int>>? {
        val now = clock.now()
        val touched = db.jdbi.withHandle<Boolean, Exception> { h ->
            h.createQuery(
                "SELECT EXISTS (SELECT 1 FROM app.cfg_value WHERE key = ANY(:k) AND (config_version > :s OR superseded_in_version > :s))",
            ).bindArray("k", String::class.java, RADIUS_KEYS).bind("s", since).mapTo(Boolean::class.java).one()
        }
        if (!touched) return null
        val today = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
        val routeIds = planner.routesFor(userId, today).map { it.routeId }.distinct()
        if (routeIds.isEmpty()) return emptyList()
        return db.jdbi.withHandle<List<Triple<Long, Int, Int>>, Exception> { h ->
            val cfg = ScopedConfig.load(h, now, now, RADIUS_KEYS.toSet())
            val role = h.createQuery("SELECT r.ordinal FROM app.app_user u JOIN app.role_def r ON r.role = u.role WHERE u.id = :u").bind("u", userId)
                .mapTo(Long::class.java).findOne().orElse(null)
            outlets(h, routeIds, cfg, role).values.flatten().map { Triple(it.outlet_id, it.radius_m, it.max_accuracy_m) }.take(5000)
        }
    }

    /** Active outlets of the routes with radius and accuracy resolved per outlet (outlet > route > zone > geo_class > territory > division > wing > role > global). */
    private fun outlets(h: Handle, routeIds: List<Long>, cfg: ScopedConfig, roleOrdinal: Long?): Map<Long, List<BundleOutlet>> {
        val global = ScopedConfig.Chain.of()
        val minR = cfg.int("cfg.geo.radius_min_m", global, 20).coerceIn(10, 5000)
        val maxR = cfg.int("cfg.geo.radius_max_m", global, 2000).coerceIn(minR, 5000)
        val dues = HashMap<Long, Pair<Long, String?>>()
        h.createQuery(
            """
            SELECT l.outlet_id, sum(l.amount_mtk) AS due, max(l.created_at) AS as_of FROM app.due_ledger l
            JOIN app.outlet o ON o.id = l.outlet_id WHERE o.route_id = ANY(:r) GROUP BY l.outlet_id
            """.trimIndent(),
        ).bindArray("r", Long::class.javaObjectType, routeIds).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.ts("as_of")) }.list()
            .forEach { (o, due, at) -> dues[o] = due to at }
        return h.createQuery(
            """
            SELECT o.*, c.name AS cluster_name, z.territory_id, t.division_id, d.wing_id, g.ordinal AS geo_ord,
                   EXISTS (SELECT 1 FROM app.outlet_change_request q WHERE q.outlet_id = o.id AND q.status IN ('pending','verified') AND q.voided_at IS NULL) AS pending
            FROM app.outlet o
            JOIN app.cluster c ON c.id = o.cluster_id
            JOIN app.zone z ON z.id = o.zone_id JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id
            LEFT JOIN app.geo_class_def g ON g.geo_class = o.geo_class
            WHERE o.route_id = ANY(:r) AND o.status = 'active'
            ORDER BY o.route_id, o.visit_sequence NULLS LAST, o.name, o.id
            """.trimIndent(),
        ).bindArray("r", Long::class.javaObjectType, routeIds).map { rs, _ ->
            val id = rs.getLong("id")
            val chain = ScopedConfig.Chain.of(
                ConfigScopeType.ROLE to roleOrdinal, ConfigScopeType.WING to rs.getLong("wing_id"), ConfigScopeType.DIVISION to rs.getLong("division_id"),
                ConfigScopeType.TERRITORY to rs.getLong("territory_id"), ConfigScopeType.GEO_CLASS to (rs.getObject("geo_ord") as Number?)?.toLong(),
                ConfigScopeType.ZONE to rs.getLong("zone_id"), ConfigScopeType.ROUTE to rs.getLong("route_id"), ConfigScopeType.OUTLET to id,
            )
            val due = dues[id]
            val name = rs.getString("name")
            BundleOutlet(
                outlet_id = id, route_id = rs.getLong("route_id"), code = rs.getString("code"), name = name, name_bn = rs.getString("name_bn"),
                name_sort_key = sortKey(name), owner_name = rs.getString("owner_name"),
                contact_number = rs.getString("contact_number")?.takeIf { PHONE_BD.matches(it) },
                lat = rs.getObject("lat") as Double?, lng = rs.getObject("lng") as Double?, location_confirmed = rs.getBoolean("location_confirmed"),
                provisional_lat = rs.getObject("provisional_lat") as Double?, provisional_lng = rs.getObject("provisional_lng") as Double?,
                cluster_id = rs.getLong("cluster_id"), cluster_name = rs.getString("cluster_name"), channel = rs.getString("channel"),
                sub_channel_id = rs.getObject("sub_channel_id") as Long?, geo_class = rs.getString("geo_class"), status = rs.getString("status"),
                price_type = rs.getString("price_type"), outlet_kind = rs.getString("outlet_kind"),
                radius_m = cfg.int("cfg.geo.radius_m", chain, 100).coerceIn(minR, maxR).coerceIn(10, 5000),
                max_accuracy_m = cfg.int("cfg.geo.max_accuracy_m", chain, 100).coerceIn(10, 1000),
                visit_sequence = rs.getObject("visit_sequence") as Int?,
                open_due_mtk = (due?.first ?: 0L).coerceAtLeast(0L), open_due_as_of = due?.second,
                programme_flags = emptyList(), pending_request = rs.getBoolean("pending"), suggested_qty = emptyList(),
            )
        }.list().groupBy { it.route_id }
    }

    /** Credit memos with an open balance in the due ledger (`memo_due` plus collections, voids and supersessions). */
    private fun openMemos(h: Handle, outletIds: List<Long>): List<OpenMemo> = if (outletIds.isEmpty()) emptyList() else h.createQuery(
        """
        SELECT l.memo_client_uuid, sum(l.amount_mtk) AS due, m.memo_no, m.business_date, m.outlet_id, m.net_mtk
        FROM app.due_ledger l JOIN app.memo m ON m.client_uuid = l.memo_client_uuid
        WHERE l.outlet_id = ANY(:o) AND l.memo_client_uuid IS NOT NULL
        GROUP BY l.memo_client_uuid, m.memo_no, m.business_date, m.outlet_id, m.net_mtk
        HAVING sum(l.amount_mtk) > 0
        ORDER BY m.business_date, m.memo_no, l.memo_client_uuid
        """.trimIndent(),
    ).bindArray("o", Long::class.javaObjectType, outletIds).map { rs, _ ->
        OpenMemo(rs.getString("memo_client_uuid"), rs.getString("memo_no"), rs.getObject("business_date", LocalDate::class.java).toString(),
            rs.getLong("outlet_id"), rs.getLong("net_mtk"), rs.getLong("due"))
    }.list()

    private fun codeLists(h: Handle, date: LocalDate): List<CodeList> {
        val known = CodeListKey.entries.map { it.wire }.toSet()
        return h.createQuery(
            "SELECT list_key, code, label_en, label_bn, sort, attrs::text AS attrs, valid_from, valid_to FROM app.code_list_item WHERE valid_from <= :d AND (valid_to IS NULL OR valid_to > :d) ORDER BY list_key, sort, code",
        ).bind("d", date).map { rs, _ ->
            rs.getString("list_key") to CodeItem(
                code = rs.getString("code"), label_bn = rs.getString("label_bn"), label_en = rs.getString("label_en"), sort = rs.getInt("sort"),
                attrs = rs.getString("attrs")?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap()),
                valid_from = rs.getObject("valid_from", LocalDate::class.java).toString(), valid_to = rs.getObject("valid_to", LocalDate::class.java)?.toString(),
            )
        }.list().filter { it.first in known }.groupBy({ it.first }, { it.second }).map { (k, v) -> CodeList(k, v) }
    }

    private fun products(h: Handle): BundleProducts {
        val nodes = h.createQuery("SELECT id, level, parent_id, code, name, name_bn, sort FROM app.product_node WHERE status = 'active' ORDER BY sort, id").map { rs, _ ->
            BundleProductNode(rs.getLong("id"), rs.getString("level"), rs.getObject("parent_id") as Long?, rs.getString("code"), rs.getString("name"), rs.getString("name_bn"), rs.getInt("sort"))
        }.list()
        val skus = h.createQuery("SELECT * FROM app.sku ORDER BY sort, id").map { rs, _ ->
            SkuDto(
                id = rs.getLong("id"), code = rs.getString("code"), variant_id = rs.getLong("variant_id"), category_code = rs.getString("category_code"),
                name = rs.getString("name"), short_name = rs.getString("short_name"), name_bn = rs.getString("name_bn"), base_unit = rs.getString("base_unit"),
                base_per_pack = rs.getInt("base_per_pack"), entry_unit_default = rs.getString("entry_unit_default"), report_unit = rs.getString("report_unit"),
                report_factor = rs.getBigDecimal("report_factor").setScale(3).toPlainString(), sort = rs.getInt("sort"), status = rs.getString("status"),
                thumbnail_media_uuid = rs.getString("thumbnail_media_uuid"), updated_at = rs.ts("updated_at")!!, version = rs.getInt("version"),
            )
        }.list()
        return BundleProducts(nodes, skus)
    }

    /** Selling prices (outlet, cc, distributor) valid on [date] or starting up to [horizon] (docs/24 s4.10). */
    private fun prices(h: Handle, date: LocalDate, horizon: LocalDate): List<SkuPrice> = h.createQuery(
        """
        SELECT id, sku_id, price_type, amount_mtk, per_base_qty, valid_from, valid_to FROM app.sku_price
        WHERE price_type IN ('outlet','cc','distributor') AND valid_from <= :h AND (valid_to IS NULL OR valid_to > :d)
        ORDER BY sku_id, price_type, valid_from
        """.trimIndent(),
    ).bind("d", date).bind("h", horizon).map { rs, _ ->
        SkuPrice(rs.getLong("id"), rs.getLong("sku_id"), rs.getString("price_type"), rs.getLong("amount_mtk"), rs.getInt("per_base_qty"),
            rs.getObject("valid_from", LocalDate::class.java).toString(), rs.getObject("valid_to", LocalDate::class.java)?.toString())
    }.list()

    /** Calendar entries from [from] to [to] that apply to the user's zones (global and the ancestors of each zone). */
    private fun holidays(h: Handle, from: LocalDate, to: LocalDate, zoneIds: List<Long>): List<Holiday> {
        val chains = chains(h, zoneIds).values.map { it.dayPlan() }
        return h.createQuery(
            "SELECT id, date, scope_type, scope_id, kind, selling_day, name_en, name_bn FROM app.calendar_holiday WHERE date >= :a AND date <= :b AND revoked_at IS NULL ORDER BY date, id",
        ).bind("a", from).bind("b", to).map { rs, _ ->
            Holiday(rs.getLong("id"), rs.getObject("date", LocalDate::class.java).toString(), rs.getString("scope_type"), rs.getLong("scope_id"),
                rs.getString("kind"), rs.getBoolean("selling_day"), rs.getString("name_en"), rs.getString("name_bn"))
        }.list().filter { e ->
            val scope = DayPlan.CalendarScope.entries.firstOrNull { it.wire == e.scope_type } ?: return@filter false
            scope == DayPlan.CalendarScope.GLOBAL || chains.any { it.matches(scope, e.scope_id) }
        }.take(60)
    }

    /** Open tasks assigned to the user (the task itself always travels in the bundle; a push only nudges, N-037). */
    private fun tasks(h: Handle, userId: Long): List<TaskDto> = h.createQuery(
        """
        SELECT client_uuid, task_type_code, title, description, assignee_user_id, user_id, outlet_id, due_date, status, created_at, status_changed_at, source
        FROM app.task WHERE assignee_user_id = :u AND status = 'ongoing' AND voided_at IS NULL ORDER BY created_at, client_uuid LIMIT 1000
        """.trimIndent(),
    ).bind("u", userId).map { rs, _ ->
        TaskDto(
            task_uuid = rs.getString("client_uuid"), task_type_code = rs.getString("task_type_code"), title = rs.getString("title"),
            description = rs.getString("description"), assignee_user_id = rs.getLong("assignee_user_id"), assigned_by_user_id = rs.getLong("user_id"),
            route_id = null, outlet_id = rs.getObject("outlet_id") as Long?, due_date = rs.getObject("due_date", LocalDate::class.java)?.toString(),
            status = rs.getString("status"), created_at = rs.ts("created_at")!!, resolved_at = null, resolution_note = null,
            source = if (rs.getString("source") == "online") "web" else "app",
        )
    }.list()

    companion object {
        private val RADIUS_KEYS = arrayOf("cfg.geo.radius_m", "cfg.geo.max_accuracy_m", "cfg.geo.radius_min_m", "cfg.geo.radius_max_m")

        private val PHONE_BD = Regex("^01[3-9]\\d{8}$")

        private val PLACEHOLDER_META = BundleMeta("", "", "", "", 0, "", 0, 1, "", false, emptyList())

        /** Case-folded name for sorting on the phone (Bangla text sorts by code point). */
        fun sortKey(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ").take(200)

        /** SHA-256 of the bundle without its meta. */
        fun contentDigest(b: Bundle): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(ResponseJson.encodeToString(Bundle.serializer(), b.copy(meta = PLACEHOLDER_META)).toByteArray(Charsets.UTF_8))

        /** The digest as 9 decimal digits, equal for equal content (the fallback snapshot_seq). */
        fun contentSeq(b: Bundle): Long = (ByteBuffer.wrap(contentDigest(b)).long and Long.MAX_VALUE) % 1_000_000_000L

        /** Opaque delta cursor: version, business date, snapshot digest and the generation instant (DeltaCursor pattern). */
        fun cursor(date: LocalDate, seq: Long, at: Instant): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString("c1|$date|$seq|${at.toEpochMilli()}".toByteArray())

        private fun ResultSet.ts(col: String): String? = getObject(col, OffsetDateTime::class.java)?.toInstant()?.wire()
    }
}

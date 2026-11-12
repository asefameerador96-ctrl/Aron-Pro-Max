package com.aktcl.aron.backend.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate

/**
 * The bundle's `programmes` section (contract ProgrammesSection; lane portal-loyalty-api, train 3, docs/requests/contract-1.15-loyalty-field.md).
 * Read-only SQL over `app.programme`, `app.programme_enrolment`, `app.loyalty_ledger`, `app.gift`, `app.gift_assignment`, `app.astha_target` and the
 * memos; scope is the outlets and routes of the caller's own bundle, never a list from the client. Units: points are integers, money is integer
 * milli-taka sent as a STRING, STD figures are base units (sticks, pieces) as Decimal3 text. `null` when nothing is active in the caller's reach.
 */
object BundleProgrammes {
    /** Which programme kinds the caller's flags show: the loyalty tiles (`cfg.flag.loyalty_ui`), Astha (`astha_ui`), Superstar (`superstar_ui`). */
    data class Flags(val loyalty: Boolean, val astha: Boolean, val superstar: Boolean) {
        fun kinds(): List<String> = buildList { if (loyalty) { add("diamond_league"); add("campaign") }; if (astha) add("astha"); if (superstar) add("superstar") }
    }

    /** The Astha quarter containing [date]: calendar quarters shifted by `cfg.astha.quarter_start_month` (default 1). Label is `YYYY-Qn` of the year the quarter starts in. */
    data class Quarter(val label: String, val months: List<LocalDate>) {
        companion object {
            fun of(date: LocalDate, startMonth: Int): Quarter {
                val sm = startMonth.coerceIn(1, 12)
                val monthsSinceStart = Math.floorMod(date.monthValue - sm, 12)
                val first = date.withDayOfMonth(1).minusMonths((monthsSinceStart % 3).toLong())
                val qIndex = (Math.floorMod(first.monthValue - sm, 12)) / 3 + 1
                return Quarter("${first.year}-Q$qIndex", (0L..2L).map { first.plusMonths(it) })
            }
        }
    }

    fun section(h: Handle, routeIds: List<Long>, outletIds: Set<Long>, date: LocalDate, now: Instant, flags: Flags, quarterStartMonth: Int, expiryWarningDays: Int = 7): JsonObject? {
        val kinds = flags.kinds()
        if (kinds.isEmpty() || outletIds.isEmpty()) return null
        val outlets = outletIds.toList()
        val programmes = programmes(h, date, kinds, outlets)
        if (programmes.isEmpty()) return null
        val ids = programmes.map { it.first }
        val q = Quarter.of(date, quarterStartMonth)
        val asthaIds = programmes.filter { it.second == "astha" }.map { it.first }
        return buildJsonObject {
            put("programmes", JsonArray(programmes.map { it.third }))
            put("loyalty", JsonArray(loyalty(h, outlets, ids, date, expiryWarningDays)))
            put("gifts", JsonArray(gifts(h, ids)))
            put("astha_targets", JsonArray(if (asthaIds.isEmpty()) emptyList() else asthaTargets(h, routeIds, outlets, q, now)))
            put("gift_assignments", JsonArray(if (asthaIds.isEmpty()) emptyList() else giftAssignments(h, asthaIds, outlets, q)))
            put("pending_gift_verifications", JsonArray(emptyList()))
        }
    }

    /** (id, kind, JSON) of the active programmes covering [date] whose kind is shown and that enrol one of [outlets] (a campaign needs no enrolment). */
    private fun programmes(h: Handle, date: LocalDate, kinds: List<String>, outlets: List<Long>): List<Triple<Long, String, JsonObject>> = h.createQuery(
        """
        SELECT p.id, p.kind, p.code, p.name_en, p.name_bn, p.period_label, p.active_from, p.active_to, p.points_expire_on, p.rules::text AS rules, p.version,
               v.id AS vid, v.version_no,
               COALESCE((SELECT jsonb_agg(jsonb_build_object('tier_code', t.tier_code, 'sort_order', t.sort_order, 'label_en', t.label_en, 'label_bn', t.label_bn,
                                                             'threshold', t.threshold::text, 'threshold_basis', t.threshold_basis) ORDER BY t.sort_order)
                         FROM app.programme_version_tier t WHERE t.programme_version_id = v.id), '[]'::jsonb)::text AS tiers
        FROM app.programme p LEFT JOIN app.programme_version v ON v.id = p.current_version_id
        WHERE p.status = 'active' AND p.kind = ANY(CAST(:kinds AS text[])) AND p.active_from <= :d AND p.active_to >= :d
          AND (p.kind = 'campaign' OR EXISTS (SELECT 1 FROM app.programme_enrolment e WHERE e.programme_id = p.id AND e.outlet_id = ANY(CAST(:outlets AS bigint[]))
                                               AND e.valid_from <= :d AND (e.valid_to IS NULL OR e.valid_to > :d)))
        ORDER BY p.id LIMIT 50
        """.trimIndent(),
    ).bind("d", date).bindArray("kinds", String::class.java, kinds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ ->
        val id = rs.getLong("id")
        Triple(id, rs.getString("kind"), buildJsonObject {
            put("programme_id", id); put("kind", rs.getString("kind")); put("code", rs.getString("code"))
            put("name_en", rs.getString("name_en")); put("name_bn", rs.getString("name_bn")); put("period_label", rs.getString("period_label"))
            put("active_from", rs.getObject("active_from", LocalDate::class.java).toString()); put("active_to", rs.getObject("active_to", LocalDate::class.java).toString())
            put("points_expire_on", rs.getObject("points_expire_on", LocalDate::class.java)?.toString())
            put("rules", Json.parseToJsonElement(rs.getString("rules"))); put("status", "active"); put("version", rs.getInt("version"))
            rs.getObject("vid")?.let { put("programme_version_id", (it as Number).toLong()); put("version_no", rs.getInt("version_no")) }
            put("tiers", Json.parseToJsonElement(rs.getString("tiers")))
        })
    }.list()

    /**
     * Ledger balance per enrolled outlet and programme as of the PREVIOUS business date (`as_of_date`). `expiring_points` is the credits that expire
     * within [warnDays] days of [date], capped by the balance (never negative); `expiry_date` is the earliest such date.
     */
    private fun loyalty(h: Handle, outlets: List<Long>, programmeIds: List<Long>, date: LocalDate, warnDays: Int): List<JsonObject> = h.createQuery(
        """
        SELECT e.outlet_id, e.programme_id, e.league_label,
               COALESCE(SUM(l.points) FILTER (WHERE l.business_date < :d), 0)::bigint AS balance,
               COALESCE(SUM(l.points) FILTER (WHERE l.business_date < :d AND l.points > 0 AND l.expires_on >= :d AND l.expires_on <= :d + CAST(:warn AS int)), 0)::bigint AS expiring,
               MIN(l.expires_on) FILTER (WHERE l.business_date < :d AND l.points > 0 AND l.expires_on >= :d AND l.expires_on <= :d + CAST(:warn AS int)) AS expiry_date
        FROM app.programme_enrolment e
        LEFT JOIN app.loyalty_ledger l ON l.outlet_id = e.outlet_id AND l.programme_id = e.programme_id
        WHERE e.programme_id = ANY(CAST(:pids AS bigint[])) AND e.outlet_id = ANY(CAST(:outlets AS bigint[])) AND e.valid_from <= :d AND (e.valid_to IS NULL OR e.valid_to > :d)
        GROUP BY e.outlet_id, e.programme_id, e.league_label
        ORDER BY e.programme_id, e.outlet_id
        LIMIT 11000
        """.trimIndent(),
    ).bind("d", date).bind("warn", warnDays).bindArray("pids", Long::class.javaObjectType, programmeIds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ ->
        val balance = rs.getLong("balance")
        buildJsonObject {
            put("outlet_id", rs.getLong("outlet_id")); put("programme_id", rs.getLong("programme_id")); put("league_label", rs.getString("league_label"))
            put("balance_points", balance); put("expiring_points", rs.getLong("expiring").coerceIn(0, maxOf(balance, 0)))
            put("expiry_date", rs.getObject("expiry_date", LocalDate::class.java)?.toString()); put("as_of_date", date.minusDays(1).toString())
        }
    }.list()

    /** Active gifts of the sent programmes. The picture URL is resolved from the asset by the portal; the bundle sends `image_url` null until the bundle builder gets a blob issuer. */
    private fun gifts(h: Handle, programmeIds: List<Long>): List<JsonObject> = h.createQuery(
        "SELECT id, programme_id, code, name_en, name_bn, points_cost, tier_codes, version FROM app.gift WHERE status = 'active' AND programme_id = ANY(CAST(:pids AS bigint[])) ORDER BY id LIMIT 200",
    ).bindArray("pids", Long::class.javaObjectType, programmeIds).map { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        val tiers = (rs.getArray("tier_codes").array as Array<String>).toList()
        buildJsonObject {
            put("gift_id", rs.getLong("id")); put("programme_id", rs.getLong("programme_id")); put("code", rs.getString("code"))
            put("name_en", rs.getString("name_en")); put("name_bn", rs.getString("name_bn"))
            put("points_cost", rs.getObject("points_cost")?.let { (it as Number).toInt() }); put("tier_codes", JsonArray(tiers.map { JsonPrimitive(it) }))
            put("image_url", JsonNull); put("status", "active"); put("version", rs.getInt("version"))
        }
    }.list()

    /**
     * Astha target rows of the quarter's months for the caller's routes, with server-side achievement up to `as_of` = [now]: value = sum of the
     * SALE lines' gross milli-taka per brand (memo-level deductions are not split by brand; the memo offer line is zero), qty in base units (STD basis) in total and per `pack_factor` (the SKU's base units per pack: `by_pack`, whose std_achieved sum to the row total),
     * memos `active` and not voided only (a superseded memo is replaced by its editing memo). The brand-NULL row is the memo target: achievement is the
     * count of active sale memos of the outlet in the month.
     */
    private fun asthaTargets(h: Handle, routeIds: List<Long>, outlets: List<Long>, q: Quarter, now: Instant): List<JsonObject> {
        val first = q.months.first(); val after = q.months.last().plusMonths(1)
        return h.createQuery(
            """
            WITH t AS (
              SELECT * FROM app.astha_target WHERE route_id = ANY(CAST(:routes AS bigint[])) AND month >= :first AND month < :after AND (outlet_id IS NULL OR outlet_id = ANY(CAST(:outlets AS bigint[])))
            ), sp AS (
              SELECT m.outlet_id, date_trunc('month', m.business_date)::date AS month, b.id AS brand_id, s.base_per_pack,
                     SUM(ml.qty_base)::numeric(16,3) AS std, SUM(ml.gross_mtk)::bigint AS value_mtk
              FROM app.memo m
              JOIN app.memo_line ml ON ml.memo_client_uuid = m.client_uuid AND ml.business_date = m.business_date AND ml.voided_at IS NULL AND ml.line_kind = 'sale'
              JOIN app.sku s ON s.id = ml.sku_id
              JOIN app.product_node v ON v.id = s.variant_id
              JOIN app.product_node b ON b.id = v.parent_id
              WHERE m.status = 'active' AND m.voided_at IS NULL AND m.memo_kind = 'sale' AND m.business_date >= :first AND m.business_date < :after
                AND m.outlet_id = ANY(CAST(:outlets AS bigint[]))
              GROUP BY 1, 2, 3, 4
            ), sales AS (
              SELECT outlet_id, month, brand_id, SUM(std)::numeric(16,3) AS std, SUM(value_mtk)::bigint AS value_mtk,
                     jsonb_agg(jsonb_build_object('pack_factor', base_per_pack, 'std_achieved', std::text) ORDER BY base_per_pack)::text AS packs
              FROM sp GROUP BY 1, 2, 3
            ), memos AS (
              SELECT m.outlet_id, date_trunc('month', m.business_date)::date AS month, count(*)::int AS n
              FROM app.memo m WHERE m.status = 'active' AND m.voided_at IS NULL AND m.memo_kind = 'sale' AND m.business_date >= :first AND m.business_date < :after
                AND m.outlet_id = ANY(CAST(:outlets AS bigint[])) GROUP BY 1, 2
            )
            SELECT t.route_id, t.outlet_id, t.brand_id, t.month, t.std_target::text AS std_target, t.memo_target, t.value_target_mtk,
                   COALESCE(s.std, 0)::text AS std_achieved, COALESCE(s.value_mtk, 0) AS value_achieved, COALESCE(s.packs, '[]') AS packs, COALESCE(mm.n, 0) AS memo_achieved
            FROM t
            LEFT JOIN sales s ON t.brand_id IS NOT NULL AND s.outlet_id = t.outlet_id AND s.month = t.month AND s.brand_id = t.brand_id
            LEFT JOIN memos mm ON t.brand_id IS NULL AND mm.outlet_id = t.outlet_id AND mm.month = t.month
            ORDER BY t.month, t.route_id, t.outlet_id NULLS FIRST, t.brand_id NULLS FIRST
            LIMIT 20000
            """.trimIndent(),
        ).bind("first", first).bind("after", after).bindArray("routes", Long::class.javaObjectType, routeIds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ ->
            buildJsonObject {
                put("route_id", rs.getLong("route_id")); put("outlet_id", rs.getObject("outlet_id")?.let { (it as Number).toLong() })
                put("brand_id", rs.getObject("brand_id")?.let { (it as Number).toLong() })
                put("month", rs.getObject("month", LocalDate::class.java).toString().substring(0, 7))
                put("std_target", rs.getString("std_target")); put("std_achieved", rs.getString("std_achieved"))
                put("memo_target", rs.getInt("memo_target")); put("memo_achieved", rs.getInt("memo_achieved"))
                put("value_target_mtk", rs.getLong("value_target_mtk").toString()); put("value_achieved_mtk", rs.getLong("value_achieved").toString())
                put("by_pack", if (rs.getObject("brand_id") == null) JsonArray(emptyList()) else Json.parseToJsonElement(rs.getString("packs")))
                put("as_of", now.toString())
            }
        }.list()
    }

    private fun giftAssignments(h: Handle, programmeIds: List<Long>, outlets: List<Long>, q: Quarter): List<JsonObject> = h.createQuery(
        """
        SELECT id, quarter, outlet_id, route_id, gift_id, tier_code, photo_media_uuid, locked_at, chosen_by_user_id, chosen_at
        FROM app.gift_assignment WHERE programme_id = ANY(CAST(:pids AS bigint[])) AND quarter = :q AND outlet_id = ANY(CAST(:outlets AS bigint[])) ORDER BY id LIMIT 2000
        """.trimIndent(),
    ).bind("q", q.label).bindArray("pids", Long::class.javaObjectType, programmeIds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ ->
        buildJsonObject {
            put("gift_assignment_id", rs.getLong("id")); put("quarter", rs.getString("quarter")); put("outlet_id", rs.getLong("outlet_id"))
            put("route_id", rs.getLong("route_id")); put("gift_id", rs.getLong("gift_id")); put("tier_code", rs.getString("tier_code"))
            put("photo_captured", rs.getObject("photo_media_uuid") != null); put("photo_media_uuid", rs.getString("photo_media_uuid"))
            put("locked", rs.getObject("locked_at") != null)
            put("chosen_by_user_id", rs.getObject("chosen_by_user_id")?.let { (it as Number).toLong() })
            put("chosen_at", rs.getObject("chosen_at", java.time.OffsetDateTime::class.java)?.toInstant()?.toString())
        }
    }.list()
}

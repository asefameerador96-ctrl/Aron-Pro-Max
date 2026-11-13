package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.platform.ProgrammeScopeParams
import com.aktcl.aron.backend.platform.ProgrammeScopeSql
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.wire
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
    private val log = org.slf4j.LoggerFactory.getLogger(BundleProgrammes::class.java)

    /** Hard bound on the Astha rows one user's bundle carries (about 250 bytes a row raw); past it the section is cut LOUDLY: a warning in the log and `astha_truncated` true for the phone to show. */
    const val MAX_ASTHA_ROWS = 1000
    const val MAX_PROGRAMMES = 50
    const val MAX_BALANCES = 11000
    const val MAX_GIFTS = 200
    const val MAX_GIFT_ASSIGNMENTS = 2000
    const val MAX_PENDING_VERIFICATIONS = 500
    /** How far back a redeemed campaign gift is still asked for its photo (business days). */
    const val PENDING_VERIFICATION_DAYS = 30L

    /**
     * TEST SEAM (production leaves it null): called with the section's own handle at the stages "start", "between" (after the balances, gifts and before the Astha rows)
     * and "end", so a test can assert the isolation level and read-only mode the section is really read in and commit a memo from another connection in between.
     */
    fun interface Probe { fun at(stage: String, h: Handle) }

    @Volatile var probe: Probe? = null

    /** A list cut at its cap: [truncated] is true when more rows existed (never a silent cut). */
    class Capped<T>(val items: List<T>, val truncated: Boolean)

    private fun <T> List<T>.capped(cap: Int) = Capped(take(cap), size > cap)

    /**
     * The programme scope parameters of the caller (ONE function for the bundle, the three reads and the portal: [ProgrammeScopeParams.of]) over the routes of [outlets].
     * [reach] is the caller's reach from the token (null fails closed: only programmes that name no place match).
     */
    internal fun scopeOf(h: Handle, reach: Reach?, outlets: List<Long>): ProgrammeScopeParams = ProgrammeScopeParams.of(
        h, reach,
        h.createQuery("SELECT DISTINCT route_id FROM app.outlet WHERE id = ANY(CAST(:o AS bigint[])) AND route_id IS NOT NULL").bindArray("o", Long::class.javaObjectType, outlets).mapTo(Long::class.java).list(),
    )

    internal fun enrolledOutlets(h: Handle, programmeIds: List<Long>, outlets: List<Long>, date: LocalDate): List<Long> = h.createQuery(
        "SELECT DISTINCT outlet_id FROM app.programme_enrolment WHERE programme_id = ANY(CAST(:pids AS bigint[])) AND outlet_id = ANY(CAST(:outlets AS bigint[])) AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d) ORDER BY 1",
    ).bind("d", date).bindArray("pids", Long::class.javaObjectType, programmeIds).bindArray("outlets", Long::class.javaObjectType, outlets).mapTo(Long::class.java).list()

    /** Which programme kinds the caller's flags show: the loyalty tiles (`cfg.flag.loyalty_ui`), Astha (`astha_ui`), Superstar (`superstar_ui`). */
    data class Flags(val loyalty: Boolean, val astha: Boolean, val superstar: Boolean) {
        fun kinds(): List<String> = buildList { if (loyalty) { add("diamond_league"); add("campaign") }; if (astha) add("astha"); if (superstar) add("superstar") }
    }

    fun section(h: Handle, reach: Reach?, routeIds: List<Long>, outletIds: Set<Long>, date: LocalDate, flags: Flags, quarterStartMonth: Int): JsonObject? {
        probe?.at("start", h)
        val kinds = flags.kinds()
        if (kinds.isEmpty() || outletIds.isEmpty()) return null
        val outlets = outletIds.toList()
        val scope = scopeOf(h, reach, outlets)
        val progs = programmes(h, date, kinds, outlets, scope)
        if (progs.items.isEmpty()) return null
        val programmes = progs.items
        val ids = programmes.map { it.first }
        val q = AsthaQuarter.of(date, quarterStartMonth)
        val asthaIds = programmes.filter { it.second == "astha" }.map { it.first }
        val cut = mutableListOf<String>()
        if (progs.truncated) cut += "programmes"
        val balances = loyalty(h, outlets, ids, date, date.minusDays(1), MAX_BALANCES); if (balances.truncated) cut += "loyalty"
        val gifts = gifts(h, ids); if (gifts.truncated) cut += "gifts"
        val assignments = if (asthaIds.isEmpty()) Capped(emptyList(), false) else giftAssignments(h, asthaIds, outlets, q).also { if (it.truncated) cut += "gift_assignments" }
        val pending = if ("campaign" in kinds) pendingGiftVerifications(h, outlets, date).also { if (it.truncated) cut += "pending_gift_verifications" } else Capped(emptyList(), false)
        probe?.at("between", h)
        val month = date.withDayOfMonth(1); val after = month.plusMonths(1)
        // The cut (lead ruling, push B2): only the CURRENT month (earlier months are the online read), only outlets enrolled in an active Astha programme, no all-zero row.
        val enrolled = if (asthaIds.isEmpty()) emptyList() else enrolledOutlets(h, asthaIds, outlets, date)
        val rows = if (enrolled.isEmpty()) emptyList() else asthaTargets(h, routeIds, enrolled, month, after, Instant.EPOCH, MAX_ASTHA_ROWS + 1, 0, dropEmpty = true, perRowAsOf = false)
        val truncated = rows.size > MAX_ASTHA_ROWS
        val sent = rows.take(MAX_ASTHA_ROWS)
        // `astha_as_of` is the newest memo ingest among the outlets of the rows ACTUALLY SENT (a memo at another outlet of a supervisor's zone moves nothing here), "data as of" for display only.
        val sentOutlets = sent.mapNotNull { (it["outlet_id"] as? JsonPrimitive)?.content?.toLongOrNull() }.distinct()
        val asOf = contentAsOf(h, sentOutlets, month, after)
        if (truncated) log.warn("bundle programmes section: more than {} Astha rows for the user's routes; sent the first {} and set astha_truncated", MAX_ASTHA_ROWS, MAX_ASTHA_ROWS)
        if (cut.isNotEmpty()) log.warn("bundle programmes section: cap reached for {}", cut)
        probe?.at("end", h)
        return buildJsonObject {
            put("programmes", JsonArray(programmes.map { it.third }))
            put("loyalty", JsonArray(balances.items))
            put("gifts", JsonArray(gifts.items))
            put("astha_targets", JsonArray(sent))
            put("astha_as_of", asOf.wire())
            put("astha_truncated", truncated)
            put("truncated_sections", JsonArray(cut.map { JsonPrimitive(it) }))
            put("gift_assignments", JsonArray(assignments.items))
            put("pending_gift_verifications", JsonArray(pending.items))
        }
    }

    /**
     * Redeemed campaign gifts of [outlets] still short of their Gift Verify photos (SRM-B-77 to 80): per redemption and gift, `qty` units against the photos stored for it (a flagged duplicate does not count,
     * a voided photo does not count). Redemptions of the last [PENDING_VERIFICATION_DAYS] business days; a partial submit is allowed, so a gift with some photos stays listed with `photos_captured` < `qty`.
     */
    internal fun pendingGiftVerifications(h: Handle, outlets: List<Long>, date: LocalDate): Capped<JsonObject> = h.createQuery(
        """
        SELECT * FROM (
          SELECT r.client_uuid::text AS ru, r.outlet_id, l.gift_id, sum(l.qty)::int AS qty,
                 (SELECT count(*) FROM app.gift_photo p WHERE p.redemption_client_uuid = r.client_uuid AND p.gift_id = l.gift_id AND p.voided_at IS NULL AND NOT p.is_duplicate)::int AS photos
            FROM app.redemption r
            JOIN app.programme pr ON pr.id = r.programme_id AND pr.kind = 'campaign'
            JOIN app.redemption_line l ON l.redemption_client_uuid = r.client_uuid AND l.voided_at IS NULL
           WHERE r.voided_at IS NULL AND r.outlet_id = ANY(CAST(:o AS bigint[])) AND r.business_date > :from AND r.business_date <= :d
           GROUP BY r.client_uuid, r.outlet_id, l.gift_id
        ) x WHERE photos < qty ORDER BY ru, gift_id LIMIT :lim
        """.trimIndent(),
    ).bindArray("o", Long::class.javaObjectType, outlets).bind("from", date.minusDays(PENDING_VERIFICATION_DAYS)).bind("d", date).bind("lim", MAX_PENDING_VERIFICATIONS + 1)
        .map { rs, _ ->
            buildJsonObject {
                put("redemption_client_uuid", rs.getString("ru")); put("outlet_id", rs.getLong("outlet_id")); put("gift_id", rs.getLong("gift_id"))
                put("qty", rs.getInt("qty")); put("photos_captured", rs.getInt("photos"))
            }
        }.list().capped(MAX_PENDING_VERIFICATIONS)

    /** (id, kind, JSON) of the active programmes covering [date] whose kind is shown and that enrol one of [outlets] (a campaign needs no enrolment). */
    internal fun programmes(h: Handle, date: LocalDate, kinds: List<String>, outlets: List<Long>, scope: ProgrammeScopeParams, only: Long? = null): Capped<Triple<Long, String, JsonObject>> {
        return h.createQuery(
        """
        SELECT p.id, p.kind, p.code, p.name_en, p.name_bn, p.period_label, p.active_from, p.active_to, p.points_expire_on, p.rules::text AS rules, p.version,
               v.id AS vid, v.version_no,
               COALESCE((SELECT jsonb_agg(jsonb_build_object('tier_code', t.tier_code, 'sort_order', t.sort_order, 'label_en', t.label_en, 'label_bn', t.label_bn,
                                                             'threshold', t.threshold::text, 'threshold_basis', t.threshold_basis) ORDER BY t.sort_order)
                         FROM app.programme_version_tier t WHERE t.programme_version_id = v.id), '[]'::jsonb)::text AS tiers
        FROM app.programme p LEFT JOIN app.programme_version v ON v.id = p.current_version_id
        WHERE p.status = 'active' AND p.kind = ANY(CAST(:kinds AS text[])) AND p.active_from <= :d AND p.active_to >= :d AND (CAST(:only AS bigint) IS NULL OR p.id = :only)
          AND ${ProgrammeScopeSql.geo("v")}
          AND (p.kind = 'campaign' OR EXISTS (SELECT 1 FROM app.programme_enrolment e WHERE e.programme_id = p.id AND e.outlet_id = ANY(CAST(:outlets AS bigint[]))
                                               AND e.valid_from <= :d AND (e.valid_to IS NULL OR e.valid_to > :d)))
        ORDER BY p.id LIMIT ${MAX_PROGRAMMES + 1}
        """.trimIndent(),
    ).bind("d", date).bind("only", only).also { scope.bindTo(it) }.bindArray("kinds", String::class.java, kinds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ ->
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
    }.list().capped(MAX_PROGRAMMES)
    }

    /**
     * Ledger balance per enrolled outlet and programme as of [cut] (`as_of_date`; the bundle sends the PREVIOUS business date, the live read today).
     * `balance_points` is the PLAIN SUM of ALL ledger rows up to [cut], `expiry` rows included (lead ruling, push B4): points lapse only when the nightly expiry job posts its
     * debit (computed FIFO, so a credit that was partly redeemed lapses only for what is left of it). Until that job has run, the balance may still include points past their
     * date. `expiry_date` is the earliest `expires_on` on or after [date] among the outlet's credits and `expiring_points` the credits on that date, capped by the balance and never
     * negative (debits are not matched to credits in this read); no window. `cfg.loyalty.expiry_days` is the lifetime AFTER THE PERIOD END (D24-68: April points expire on 7
     * May), so it sets `expires_on` when points are posted. A credit with no `expires_on` never expires.
     * The programme's scope is applied PER OUTLET ([ProgrammeScopeSql.outlet]): an AMO who covers two zones gets no balance for an outlet outside the programme's scope.
     */
    internal fun loyalty(h: Handle, outlets: List<Long>, programmeIds: List<Long>, date: LocalDate, cut: LocalDate, limit: Int = MAX_BALANCES): Capped<JsonObject> = h.createQuery(
        """
        WITH nx AS (
          SELECT outlet_id, programme_id, MIN(expires_on) AS exp FROM app.loyalty_ledger
          WHERE business_date <= :cut AND points > 0 AND expires_on >= :d AND outlet_id = ANY(CAST(:outlets AS bigint[])) AND programme_id = ANY(CAST(:pids AS bigint[]))
          GROUP BY 1, 2
        )
        SELECT e.outlet_id, e.programme_id, e.league_label,
               COALESCE(SUM(l.points) FILTER (WHERE l.business_date <= :cut), 0)::bigint AS balance,
               COALESCE(SUM(l.points) FILTER (WHERE l.business_date <= :cut AND l.points > 0 AND l.expires_on = nx.exp), 0)::bigint AS expiring,
               MAX(nx.exp) AS expiry_date
        FROM app.programme_enrolment e
        JOIN app.outlet o ON o.id = e.outlet_id
        JOIN app.programme pp ON pp.id = e.programme_id LEFT JOIN app.programme_version pv ON pv.id = pp.current_version_id
        LEFT JOIN app.loyalty_ledger l ON l.outlet_id = e.outlet_id AND l.programme_id = e.programme_id
        LEFT JOIN nx ON nx.outlet_id = e.outlet_id AND nx.programme_id = e.programme_id
        WHERE e.programme_id = ANY(CAST(:pids AS bigint[])) AND e.outlet_id = ANY(CAST(:outlets AS bigint[])) AND e.valid_from <= :d AND (e.valid_to IS NULL OR e.valid_to > :d)
          AND ${ProgrammeScopeSql.outlet("pv", "o")}
        GROUP BY e.outlet_id, e.programme_id, e.league_label
        ORDER BY e.programme_id, e.outlet_id
        LIMIT :limit
        """.trimIndent(),
    ).bind("d", date).bind("cut", cut).bind("limit", limit + 1).bindArray("pids", Long::class.javaObjectType, programmeIds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ ->
        val balance = rs.getLong("balance")
        buildJsonObject {
            put("outlet_id", rs.getLong("outlet_id")); put("programme_id", rs.getLong("programme_id")); put("league_label", rs.getString("league_label"))
            put("balance_points", balance); put("overdrawn", balance < 0); put("expiring_points", rs.getLong("expiring").coerceIn(0, maxOf(balance, 0)))
            put("expiry_date", rs.getObject("expiry_date", LocalDate::class.java)?.toString()); put("as_of_date", cut.toString())
        }
    }.list().capped(limit)

    /** Active gifts of the sent programmes. `image_url` stays null in the bundle (a signed URL would change the content digest on every build and be cached); the stable `image_asset_id` is sent and the phone fetches the bytes with GET /v1/sku-images/{asset_id}, which serves purpose gift_image assets too. */
    internal fun gifts(h: Handle, programmeIds: List<Long>): Capped<JsonObject> = h.createQuery(
        "SELECT id, programme_id, code, name_en, name_bn, points_cost, tier_codes, version, image_asset_id::text AS image_asset_id FROM app.gift WHERE status = 'active' AND programme_id = ANY(CAST(:pids AS bigint[])) ORDER BY id LIMIT ${MAX_GIFTS + 1}",
    ).bindArray("pids", Long::class.javaObjectType, programmeIds).map { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        val tiers = (rs.getArray("tier_codes").array as Array<String>).toList()
        buildJsonObject {
            put("gift_id", rs.getLong("id")); put("programme_id", rs.getLong("programme_id")); put("code", rs.getString("code"))
            put("name_en", rs.getString("name_en")); put("name_bn", rs.getString("name_bn"))
            put("points_cost", rs.getObject("points_cost")?.let { (it as Number).toInt() }); put("tier_codes", JsonArray(tiers.map { JsonPrimitive(it) }))
            put("image_url", JsonNull); put("image_asset_id", rs.getString("image_asset_id")); put("status", "active"); put("version", rs.getInt("version"))
        }
    }.list().capped(MAX_GIFTS)

    /**
     * The `as_of` of the bundle's Astha figures: the server's newest ingest (memo `received_at` or status change) among the memos of the MONTH at [outlets], so equal
     * content gives equal bytes and a rebuild moves the bundle version only when a memo arrived (a wall-clock `as_of` would mint a new snapshot on every build). The
     * Dhaka start of the MONTH when there is none. Display only; the achievement rule (what the phone adds) is the ACHIEVEMENT RULE of the contract's `ProgrammesSection`, stated once there.
     */
    internal fun contentAsOf(h: Handle, outlets: List<Long>, first: LocalDate, after: LocalDate): Instant {
        return h.createQuery(
            "SELECT max(GREATEST(received_at, status_changed_at)) FROM app.memo WHERE business_date >= :first AND business_date < :after AND outlet_id = ANY(CAST(:outlets AS bigint[]))",
        ).bind("first", first).bind("after", after).bindArray("outlets", Long::class.javaObjectType, outlets)
            .map { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java)?.toInstant() }.findOne().orElse(null) ?: first.atStartOfDay(java.time.ZoneId.of("Asia/Dhaka")).toInstant()
    }

    /**
     * Astha target rows of the quarter's months for the caller's routes, with server-side achievement up to `as_of` = [now]: value = sum of the
     * SALE lines' gross milli-taka per brand (memo-level deductions are not split by brand; the memo offer line is zero), qty in base units (STD basis) in total and per `pack_factor` (the SKU's base units per pack: `by_pack`, whose std_achieved sum to the row total),
     * memos `active` and not voided only (a superseded memo is replaced by its editing memo). The brand-NULL row is the memo target: achievement is the
     * count of active sale memos of the outlet in the month.
     */
    fun asthaTargets(h: Handle, routeIds: List<Long>, outlets: List<Long>, q: AsthaQuarter, asOf: Instant, limit: Int = 20000, offset: Int = 0): List<JsonObject> =
        asthaTargets(h, routeIds, outlets, q.months.first(), q.months.last().plusMonths(1), asOf, limit, offset, dropEmpty = false)

    /** The rows of the months in [first, after); [dropEmpty] leaves out a row whose targets and achievements are all zero (the bundle's cut). */
    internal fun asthaTargets(h: Handle, routeIds: List<Long>, outlets: List<Long>, first: LocalDate, after: LocalDate, asOf: Instant, limit: Int, offset: Int, dropEmpty: Boolean, perRowAsOf: Boolean = true): List<JsonObject> {
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
                AND NOT EXISTS (SELECT 1 FROM app.memo e WHERE e.supersedes_client_uuid = m.client_uuid AND e.status = 'active' AND e.voided_at IS NULL)
              GROUP BY 1, 2, 3, 4
            ), sales AS (
              SELECT outlet_id, month, brand_id, SUM(std)::numeric(16,3) AS std, SUM(value_mtk)::bigint AS value_mtk,
                     jsonb_agg(jsonb_build_object('pack_factor', base_per_pack, 'std_achieved', std::text) ORDER BY base_per_pack)::text AS packs
              FROM sp GROUP BY 1, 2, 3
            ), memos AS (
              SELECT m.outlet_id, date_trunc('month', m.business_date)::date AS month, count(*)::int AS n
              FROM app.memo m WHERE m.status = 'active' AND m.voided_at IS NULL AND m.memo_kind = 'sale' AND m.business_date >= :first AND m.business_date < :after
                AND m.outlet_id = ANY(CAST(:outlets AS bigint[])) AND NOT EXISTS (SELECT 1 FROM app.memo e WHERE e.supersedes_client_uuid = m.client_uuid AND e.status = 'active' AND e.voided_at IS NULL) GROUP BY 1, 2
            )
            SELECT t.route_id, t.outlet_id, t.brand_id, t.month, t.std_target::text AS std_target, t.memo_target, t.value_target_mtk,
                   COALESCE(s.std, 0)::text AS std_achieved, COALESCE(s.value_mtk, 0) AS value_achieved, COALESCE(s.packs, '[]') AS packs, COALESCE(mm.n, 0) AS memo_achieved
            FROM t
            LEFT JOIN sales s ON t.brand_id IS NOT NULL AND s.outlet_id = t.outlet_id AND s.month = t.month AND s.brand_id = t.brand_id
            LEFT JOIN memos mm ON t.brand_id IS NULL AND mm.outlet_id = t.outlet_id AND mm.month = t.month
            WHERE (NOT CAST(:dropEmpty AS boolean) OR t.std_target > 0 OR t.memo_target > 0 OR t.value_target_mtk > 0 OR COALESCE(s.std, 0) > 0 OR COALESCE(s.value_mtk, 0) > 0 OR COALESCE(mm.n, 0) > 0)
            ORDER BY t.month, t.route_id, t.outlet_id NULLS FIRST, t.brand_id NULLS FIRST
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        ).bind("limit", limit).bind("offset", offset).bind("dropEmpty", dropEmpty).bind("first", first).bind("after", after).bindArray("routes", Long::class.javaObjectType, routeIds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ ->
            buildJsonObject {
                put("route_id", rs.getLong("route_id")); put("outlet_id", rs.getObject("outlet_id")?.let { (it as Number).toLong() })
                put("brand_id", rs.getObject("brand_id")?.let { (it as Number).toLong() })
                put("month", rs.getObject("month", LocalDate::class.java).toString().substring(0, 7))
                put("std_target", rs.getString("std_target")); put("std_achieved", rs.getString("std_achieved"))
                put("memo_target", rs.getInt("memo_target")); put("memo_achieved", rs.getInt("memo_achieved"))
                put("value_target_mtk", rs.getLong("value_target_mtk").toString()); put("value_achieved_mtk", rs.getLong("value_achieved").toString())
                put("by_pack", if (rs.getObject("brand_id") == null) JsonArray(emptyList()) else Json.parseToJsonElement(rs.getString("packs")))
                if (perRowAsOf) put("as_of", asOf.wire())
            }
        }.list()
    }

    /** Gift assignments of the quarter on [routeIds] (the TSO panel and report read), paged by offset. */
    internal fun giftAssignmentsOf(h: Handle, reach: Reach?, routeIds: List<Long>, q: AsthaQuarter, limit: Int, offset: Int): List<JsonObject> {
        val scope = ProgrammeScopeParams.of(h, reach, routeIds)
        return h.createQuery(
            """
            SELECT a.id, a.quarter, a.outlet_id, a.route_id, a.gift_id, a.tier_code, a.photo_media_uuid, a.locked_at, a.chosen_by_user_id, a.chosen_at
            FROM app.gift_assignment a JOIN app.programme p ON p.id = a.programme_id LEFT JOIN app.programme_version v ON v.id = p.current_version_id
                 JOIN app.outlet o ON o.id = a.outlet_id
            WHERE a.quarter = :q AND a.route_id = ANY(CAST(:routes AS bigint[])) AND ${ProgrammeScopeSql.geo("v")} AND ${ProgrammeScopeSql.outlet("v", "o")}
            ORDER BY a.id LIMIT :limit OFFSET :offset
            """.trimIndent(),
        ).bind("q", q.label).bind("limit", limit).bind("offset", offset).bindArray("routes", Long::class.javaObjectType, routeIds)
            .also { scope.bindTo(it) }.map { rs, _ -> assignmentJson(rs) }.list()
    }

    private fun giftAssignments(h: Handle, programmeIds: List<Long>, outlets: List<Long>, q: AsthaQuarter): Capped<JsonObject> = h.createQuery(
        """
        SELECT a.id, a.quarter, a.outlet_id, a.route_id, a.gift_id, a.tier_code, a.photo_media_uuid, a.locked_at, a.chosen_by_user_id, a.chosen_at
        FROM app.gift_assignment a JOIN app.programme p ON p.id = a.programme_id LEFT JOIN app.programme_version v ON v.id = p.current_version_id JOIN app.outlet o ON o.id = a.outlet_id
        WHERE a.programme_id = ANY(CAST(:pids AS bigint[])) AND a.quarter = :q AND a.outlet_id = ANY(CAST(:outlets AS bigint[])) AND ${ProgrammeScopeSql.outlet("v", "o")} ORDER BY a.id LIMIT ${MAX_GIFT_ASSIGNMENTS + 1}
        """.trimIndent(),
    ).bind("q", q.label).bindArray("pids", Long::class.javaObjectType, programmeIds).bindArray("outlets", Long::class.javaObjectType, outlets).map { rs, _ -> assignmentJson(rs) }.list().capped(MAX_GIFT_ASSIGNMENTS)

    private fun assignmentJson(rs: java.sql.ResultSet): JsonObject = buildJsonObject {
        put("gift_assignment_id", rs.getLong("id")); put("quarter", rs.getString("quarter")); put("outlet_id", rs.getLong("outlet_id"))
        put("route_id", rs.getLong("route_id")); put("gift_id", rs.getLong("gift_id")); put("tier_code", rs.getString("tier_code"))
        put("photo_captured", rs.getObject("photo_media_uuid") != null); put("photo_media_uuid", rs.getString("photo_media_uuid"))
        put("locked", rs.getObject("locked_at") != null)
        put("chosen_by_user_id", rs.getObject("chosen_by_user_id")?.let { (it as Number).toLong() })
        put("chosen_at", rs.getObject("chosen_at", java.time.OffsetDateTime::class.java)?.toInstant()?.wire())
    }
}

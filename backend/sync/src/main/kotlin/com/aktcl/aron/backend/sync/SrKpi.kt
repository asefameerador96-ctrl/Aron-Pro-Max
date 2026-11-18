package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.rules.Percent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * The brand-wise KPI of the SR app (lane T-KPI1, N-5; screens 60 and 61; design docs/status/sr-targets-server.md s3, D-TKPI-05, D-TKPI-06).
 * Per assigned route and the Dhaka month of the bundle date:
 * - brand volume target = the sum of `app.route_monthly_target` over the brand's SKUs (one source with screen 10); brand memo target and
 *   allocated KPI from `app.route_brand_kpi_target`; month allocation and the CPR block from `app.route_kpi_month`;
 * - achievement = `dw.agg_daily_route_brand` (sold quantity and memos containing the brand, once per memo) of the business dates from the 1st
 *   up to and including `through_date` (the bundle date minus one day); today is never in a server figure (same rule as [SrTargets]);
 * - totals are SUMS of the brand rows (tested with the screenshot's rows 119 and 146, memo achievement 90 and 68: 75.63% and 46.58%; the screenshot's full
 *   totals 1,102 and 397 are NOT reproduced by a test, the claim of D-TKPI-06 is withdrawn in D-TKPI-10); the volume totals count the cigarette brands only, the
 *   match and lighter volume is the `categories` list (BLM rows), each brand and category also by pack size (`by_pack`, base units);
 * - percentages are integer basis points, rounded once, null when the target is 0 (the screenshot's dash);
 * - `achieved_mtk`, `achievement_bp` and each brand's `kpi_today_mtk` are null: the owner has not defined them (question Q-F), so nothing is invented.
 * Never accepts a client id; only the routes the bundle was built for are read.
 */
object SrKpi {
    private const val MAX_BRANDS = 200

    fun load(h: Handle, routeIds: Collection<Long>, date: LocalDate, asOf: Map<Long, Instant> = SrTargets.asOfByRoute(h, routeIds, date)): Map<Long, JsonObject> {
        if (routeIds.isEmpty()) return emptyMap()
        val month = YearMonth.from(date)
        val first = month.atDay(1)
        val through = date.minusDays(1)
        val ids = routeIds.toList()

        class Brand(val id: Long) {
            var volTarget = 0L; var volAchieved = 0L; var memoTarget = 0L; var memoAchieved = 0L; var alloc = 0L
            var cigarette = false
            fun any() = volTarget > 0 || volAchieved > 0 || memoTarget > 0 || memoAchieved > 0 || alloc > 0
        }
        val brands = HashMap<Long, MutableMap<Long, Brand>>()
        fun brand(route: Long, id: Long) = brands.getOrPut(route) { LinkedHashMap() }.getOrPut(id) { Brand(id) }

        h.createQuery(
            """
            SELECT t.route_id, b.id, s.category_code, sum(t.qty_base) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id
              JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
             WHERE t.route_id = ANY(:r) AND t.month = :m GROUP BY t.route_id, b.id, s.category_code
            """,
        ).bindArray("r", Long::class.javaObjectType, ids).bind("m", first).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)) }.list().forEach {
            val b = brand(it[0] as Long, it[1] as Long); b.volTarget += it[3] as Long; if (it[2] == "cigarette") b.cigarette = true
        }
        h.createQuery("SELECT route_id, brand_id, memo_target, kpi_alloc_mtk FROM app.route_brand_kpi_target WHERE route_id = ANY(:r) AND month = :m")
            .bindArray("r", Long::class.javaObjectType, ids).bind("m", first).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.list().forEach {
                val b = brand(it[0], it[1]); b.memoTarget = it[2]; b.alloc = it[3]
            }
        if (through >= first) {
            h.createQuery(
                """
                SELECT a.route_id, a.brand_id, sum(a.sold_qty_base), sum(a.memo_count), bool_or(c.is_cig) FROM dw.agg_daily_route_brand a
                  LEFT JOIN LATERAL (SELECT true AS is_cig FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id
                                      WHERE v.parent_id = a.brand_id AND s.category_code = 'cigarette' LIMIT 1) c ON true
                 WHERE a.route_id = ANY(:r) AND a.business_date BETWEEN :f AND :t GROUP BY a.route_id, a.brand_id
                """,
            ).bindArray("r", Long::class.javaObjectType, ids).bind("f", first).bind("t", through).map { rs, _ ->
                listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getObject(5) == true)
            }.list().forEach {
                val b = brand(it[0] as Long, it[1] as Long); b.volAchieved = it[2] as Long; b.memoAchieved = it[3] as Long; if (it[4] == true) b.cigarette = true
            }
        }
        // BLM rows: volume by category for match and lighter (screen 60); the target is the route's SKU targets of the category.
        val categories = HashMap<Long, MutableMap<String, LongArray>>()
        fun cat(route: Long, code: String) = categories.getOrPut(route) { LinkedHashMap() }.getOrPut(code) { LongArray(2) }
        h.createQuery(
            "SELECT t.route_id, s.category_code, sum(t.qty_base) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id WHERE t.route_id = ANY(:r) AND t.month = :m AND s.category_code IN ('match','lighter') GROUP BY 1, 2",
        ).bindArray("r", Long::class.javaObjectType, ids).bind("m", first).map { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getLong(3)) }.list().forEach { cat(it.first, it.second)[0] = it.third }
        if (through >= first) h.createQuery(
            "SELECT a.route_id, s.category_code, sum(a.sold_qty_base) FROM dw.agg_daily_route_sku a JOIN app.sku s ON s.id = a.sku_id WHERE a.route_id = ANY(:r) AND a.business_date BETWEEN :f AND :t AND s.category_code IN ('match','lighter') GROUP BY 1, 2",
        ).bindArray("r", Long::class.javaObjectType, ids).bind("f", first).bind("t", through).map { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getLong(3)) }.list().forEach { cat(it.first, it.second)[1] = it.third }

        // Per pack size (owner rule 5: the phone shows packets, and a stick sum of 20s and 10s cannot be turned into packets): target and achieved of every brand and
        // BLM category by `base_per_pack` of the SKUs, base units, from the same two sources as the sums above (review, D-TKPI-10).
        val brandPacks = HashMap<Pair<Long, Long>, MutableMap<Int, LongArray>>(); val catPacks = HashMap<Pair<Long, String>, MutableMap<Int, LongArray>>()
        fun packOf(m: MutableMap<Pair<Long, Long>, MutableMap<Int, LongArray>>, route: Long, brand: Long, pack: Int) = m.getOrPut(route to brand) { java.util.TreeMap() }.getOrPut(pack) { LongArray(2) }
        h.createQuery(
            """
            SELECT t.route_id, b.id, s.base_per_pack, sum(t.qty_base) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id
              JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
             WHERE t.route_id = ANY(:r) AND t.month = :m GROUP BY t.route_id, b.id, s.base_per_pack
            """,
        ).bindArray("r", Long::class.javaObjectType, ids).bind("m", first).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getInt(3).toLong(), rs.getLong(4)) }.list().forEach { packOf(brandPacks, it[0], it[1], it[2].toInt())[0] = it[3] }
        if (through >= first) h.createQuery(
            """
            SELECT a.route_id, b.id, s.base_per_pack, sum(a.sold_qty_base) FROM dw.agg_daily_route_sku a JOIN app.sku s ON s.id = a.sku_id
              JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
             WHERE a.route_id = ANY(:r) AND a.business_date BETWEEN :f AND :t GROUP BY a.route_id, b.id, s.base_per_pack
            """,
        ).bindArray("r", Long::class.javaObjectType, ids).bind("f", first).bind("t", through).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getInt(3).toLong(), rs.getLong(4)) }.list().forEach { packOf(brandPacks, it[0], it[1], it[2].toInt())[1] = it[3] }
        h.createQuery(
            "SELECT t.route_id, s.category_code, s.base_per_pack, sum(t.qty_base) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id WHERE t.route_id = ANY(:r) AND t.month = :m AND s.category_code IN ('match','lighter') GROUP BY 1, 2, 3",
        ).bindArray("r", Long::class.javaObjectType, ids).bind("m", first).map { rs, _ -> listOf(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)) }.list().forEach {
            catPacks.getOrPut((it[0] as Long) to (it[1] as String)) { java.util.TreeMap() }.getOrPut(it[2] as Int) { LongArray(2) }[0] = it[3] as Long
        }
        if (through >= first) h.createQuery(
            "SELECT a.route_id, s.category_code, s.base_per_pack, sum(a.sold_qty_base) FROM dw.agg_daily_route_sku a JOIN app.sku s ON s.id = a.sku_id WHERE a.route_id = ANY(:r) AND a.business_date BETWEEN :f AND :t AND s.category_code IN ('match','lighter') GROUP BY 1, 2, 3",
        ).bindArray("r", Long::class.javaObjectType, ids).bind("f", first).bind("t", through).map { rs, _ -> listOf(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)) }.list().forEach {
            catPacks.getOrPut((it[0] as Long) to (it[1] as String)) { java.util.TreeMap() }.getOrPut(it[2] as Int) { LongArray(2) }[1] = it[3] as Long
        }
        fun packsJson(m: Map<Int, LongArray>?) = buildJsonArray {
            m.orEmpty().forEach { (pack, v) -> if (v[0] > 0 || v[1] > 0) add(buildJsonObject { put("pack_factor", pack); put("target", v[0]); put("achieved", v[1]) }) }
        }

        val monthRows = h.createQuery("SELECT route_id, kpi_alloc_mtk, cpr_target_bp, cpr_alloc_mtk FROM app.route_kpi_month WHERE route_id = ANY(:r) AND month = :m")
            .bindArray("r", Long::class.javaObjectType, ids).bind("m", first)
            .map { rs, _ -> rs.getLong(1) to Triple(rs.getLong(2), rs.getObject(3) as Int?, rs.getObject(4) as Long?) }.list().toMap()

        fun bp(a: Long, t: Long): Long? = Percent.bp(a, t)
        // A route with no KPI data at all (no brand rows, no month row, no category target or sale) has no `kpi` member: nothing to show, nothing to resend.
        return ids.filter { r -> brands[r].orEmpty().values.any { it.any() } || monthRows[r] != null || categories[r].orEmpty().isNotEmpty() }.associateWith { route ->
            val rows = brands[route].orEmpty().values.filter { it.any() }.sortedBy { it.id }
            val shown = rows.take(MAX_BRANDS)
            val cig = rows.filter { it.cigarette }
            val volT = cig.sumOf { it.volTarget }; val volA = cig.sumOf { it.volAchieved }
            val memT = rows.sumOf { it.memoTarget }; val memA = rows.sumOf { it.memoAchieved }
            val m = monthRows[route]
            buildJsonObject {
                put("month", month.toString()); put("through_date", through.toString()); put("as_of", (asOf[route] ?: Instant.EPOCH).wire())
                if (m != null) put("allocated_mtk", m.first) else put("allocated_mtk", JsonNull)
                put("achieved_mtk", JsonNull); put("achievement_bp", JsonNull)
                put("volume", buildJsonObject { put("target", volT); put("achieved", volA); put("bp", bp(volA, volT)?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) } ?: JsonNull) })
                put("memo", buildJsonObject { put("target", memT); put("achieved", memA); put("bp", bp(memA, memT)?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) } ?: JsonNull) })
                put("brands", buildJsonArray {
                    shown.forEach { b ->
                        add(buildJsonObject {
                            put("brand_id", b.id)
                            put("volume_target", b.volTarget); put("volume_achieved", b.volAchieved)
                            put("volume_bp", bp(b.volAchieved, b.volTarget)?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) } ?: JsonNull)
                            put("memo_target", b.memoTarget); put("memo_achieved", b.memoAchieved)
                            put("memo_bp", bp(b.memoAchieved, b.memoTarget)?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) } ?: JsonNull)
                            put("kpi_alloc_mtk", b.alloc); put("kpi_today_mtk", JsonNull)
                            put("by_pack", packsJson(brandPacks[route to b.id]))
                        })
                    }
                })
                put("brands_truncated", rows.size > shown.size)
                put("categories", buildJsonArray {
                    categories[route].orEmpty().toSortedMap().forEach { (code, v) ->
                        add(buildJsonObject {
                            put("category_code", code); put("volume_target", v[0]); put("volume_achieved", v[1])
                            put("volume_bp", bp(v[1], v[0])?.let { x -> kotlinx.serialization.json.JsonPrimitive(x) } ?: JsonNull)
                            put("by_pack", packsJson(catPacks[route to code]))
                        })
                    }
                })
                if (m != null && (m.second != null || m.third != null)) put("cpr", buildJsonObject {
                    put("target_bp", m.second?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) } ?: JsonNull)
                    put("alloc_mtk", m.third?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) } ?: JsonNull)
                    put("achieved_bp", JsonNull)
                }) else put("cpr", JsonNull)
            }
        }
    }
}

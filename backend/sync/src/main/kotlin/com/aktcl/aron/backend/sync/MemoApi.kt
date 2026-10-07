package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64

@Serializable
data class MemoTotalsDto(
    val gross_mtk: Long, val offer_discount_mtk: Long, val drp_discount_mtk: Long, val qc_deduction_mtk: Long,
    val round_adj_mtk: Int, val net_mtk: Long, val paid_mtk: Long, val due_mtk: Long,
)

@Serializable
data class MemoLineDto(
    val memo_client_uuid: String, val line_no: Int, val sku_id: Long, val line_kind: String, val qty_entered: Int, val unit_entered: String,
    val pack_factor: Int, val qty_base: Int, val price_type: String, val price_valid_from: String, val base_price_mtk: Long,
    val price_per_qty: Int, val gross_mtk: Long, val offer_id: Long?,
)

@Serializable
data class MemoDiscountDto(
    val memo_client_uuid: String, val kind: String, val sku_id: Long?, val qty_base: Int?, val value_mtk: Long, val offer_id: Long?,
    val offer_version_id: Long?, val basis_qty_base: Int?, val line_no: Int?,
)

/** Contract MemoView. */
@Serializable
data class MemoViewDto(
    val memo_client_uuid: String, val memo_no: String, val business_date: String, val outlet_id: Long, val outlet_name: String,
    val route_id: Long, val user_id: Long, val status: String, val supersedes_memo_no: String?, val totals: MemoTotalsDto,
    val lines: List<MemoLineDto>, val discounts: List<MemoDiscountDto>, val printed_at: String?, val print_count: Int, val entry_source: String,
)

/** Contract MemoPage. */
@Serializable
data class MemoPageDto(val items: List<MemoViewDto>, val next_cursor: String?)

class MemoDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** backend:sync memo history (contract listMemos, F-API-025): the phone's online fallback beyond its local window, and the web. */
fun Route.memoRoutes(d: MemoDeps) {
    authenticated(d.guard) {
        get("/memos") {
            val q = MemoQuery.parse(call.request.queryParameters, BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate())
            call.respond(withContext(Dispatchers.IO) { MemoReader(d).page(call.principal, q) })
        }
    }
}

internal data class MemoQuery(
    val outletId: Long?, val routeId: Long?, val memoNo: String?, val from: LocalDate?, val to: LocalDate?, val limit: Int,
    val after: Pair<LocalDate, Long>?,
) {
    companion object {
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val MEMO_NO = Regex("^[a-z][a-z0-9]{3,31}-\\d{6}-\\d{3,4}$")
        private val CURSOR = Regex("^[A-Za-z0-9_-]{1,512}$")
        const val DEFAULT_LIMIT = 100 // the contract's Limit default
        const val DEFAULT_DAYS = 31L
        const val MAX_SPAN_DAYS = 92L

        private fun bad(field: String, code: String = "invalid_value", msg: String = field): Nothing =
            throw ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError("query.$field", code)))

        private fun id(q: Parameters, n: String): Long? = q[n]?.let { it.toLongOrNull()?.takeIf { v -> v > 0 } ?: bad(n) }

        private fun date(q: Parameters, n: String): LocalDate? =
            q[n]?.let { s -> s.takeIf { DATE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: bad(n) }

        /**
         * Without `memo_no`, the window is `from`..`to` (default: the 31 days up to today, Dhaka), at most 92 days. A
         * `memo_no` alone searches every date (the number names one memo, its date is in it).
         */
        fun parse(q: Parameters, today: LocalDate): MemoQuery {
            val memoNo = q["memo_no"]?.also { if (!MEMO_NO.matches(it)) bad("memo_no") }
            var from = date(q, "from")
            var to = date(q, "to")
            if (memoNo == null || from != null || to != null) {
                to = to ?: (from?.plusDays(DEFAULT_DAYS - 1)?.coerceAtMost(today) ?: today)
                from = from ?: to.minusDays(DEFAULT_DAYS - 1)
                if (from.isAfter(to)) bad("from", "out_of_range", "from is after to")
                if (from.plusDays(MAX_SPAN_DAYS).isBefore(to)) bad("to", "out_of_range", "at most 92 days after from")
            }
            val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: bad("limit", "out_of_range") } ?: DEFAULT_LIMIT
            val after = q["cursor"]?.let { c -> (if (CURSOR.matches(c)) decodeCursor(c) else null) ?: bad("cursor") }
            return MemoQuery(id(q, "outlet_id"), id(q, "route_id"), memoNo, from, to, limit, after)
        }

        fun encodeCursor(date: LocalDate, id: Long): String = Base64.getUrlEncoder().withoutPadding().encodeToString("m1|$date|$id".toByteArray())

        private fun decodeCursor(c: String): Pair<LocalDate, Long>? = runCatching {
            val parts = String(Base64.getUrlDecoder().decode(c)).split('|')
            if (parts.size != 3 || parts[0] != "m1") null else LocalDate.parse(parts[1]) to parts[2].toLong()
        }.getOrNull()
    }
}

/**
 * Memo history in the caller's reach (docs/24 s8.4, F-SR-054 "all memos for that outlet within the user's scope"):
 * reach is derived on the server from the token; a memo is visible when its route is one of the caller's routes, its
 * zone is in the caller's zones (non-field roles), or the caller wrote it. Out-of-reach rows are simply not returned,
 * whatever the filters name. Newest first, keyset-paginated on (business_date, id). Voided and superseded memos are
 * listed with their status (a reprint must show what was printed); a memo tombstoned by an admin data void is `void`
 * too, as the totals leave it out.
 */
internal class MemoReader(private val d: MemoDeps) {
    fun page(p: AronPrincipal, q: MemoQuery): MemoPageDto {
        val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
        val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
        return d.db.readJdbi.withHandle<MemoPageDto, Exception> { h ->
            val sql = StringBuilder(
                """
                SELECT m.id, m.client_uuid::text AS uuid, m.memo_no, m.business_date, m.outlet_id, o.name AS outlet_name, m.route_id, m.user_id,
                       CASE WHEN m.voided_at IS NOT NULL THEN 'void' WHEN m.status = 'voided' THEN 'void' ELSE m.status END AS status, s.memo_no AS supersedes_memo_no, m.gross_mtk, m.offer_discount_mtk, m.drp_discount_mtk, m.qc_deduction_mtk,
                       m.round_adj_mtk, m.net_mtk, m.paid_mtk, m.due_mtk
                FROM app.memo m
                JOIN app.outlet o ON o.id = m.outlet_id
                JOIN app.route r ON r.id = m.route_id
                LEFT JOIN app.memo s ON s.client_uuid = m.supersedes_client_uuid
                WHERE m.route_id IS NOT NULL
                """.trimIndent(),
            )
            if (!reach.national) {
                sql.append(" AND (m.user_id = :me OR m.route_id = ANY(:routes)")
                if (!reach.ownRecordsOnly) sql.append(" OR r.zone_id = ANY(:zones)")
                sql.append(")")
            }
            q.outletId?.let { sql.append(" AND m.outlet_id = :outlet") }
            q.routeId?.let { sql.append(" AND m.route_id = :route") }
            q.memoNo?.let { sql.append(" AND m.memo_no = :memo_no") }
            if (q.from != null) sql.append(" AND m.business_date BETWEEN :from AND :to")
            q.after?.let { sql.append(" AND (m.business_date, m.id) < (:after_date, :after_id)") }
            sql.append(" ORDER BY m.business_date DESC, m.id DESC LIMIT :limit")
            val query = h.createQuery(sql.toString()).bind("limit", q.limit + 1)
            if (!reach.national) {
                query.bind("me", p.userId).bindArray("routes", Long::class.javaObjectType, reach.routeIds.toList())
                if (!reach.ownRecordsOnly) query.bindArray("zones", Long::class.javaObjectType, reach.zoneIds.toList())
            }
            q.outletId?.let { query.bind("outlet", it) }
            q.routeId?.let { query.bind("route", it) }
            q.memoNo?.let { query.bind("memo_no", it) }
            if (q.from != null) query.bind("from", q.from).bind("to", q.to)
            q.after?.let { query.bind("after_date", it.first).bind("after_id", it.second) }
            data class Row(val id: Long, val date: LocalDate, val view: MemoViewDto)
            val rows = query.map { rs, _ ->
                val date = rs.getObject("business_date", LocalDate::class.java)
                Row(
                    rs.getLong("id"), date,
                    MemoViewDto(
                        rs.getString("uuid"), rs.getString("memo_no"), date.toString(), rs.getLong("outlet_id"), rs.getString("outlet_name"),
                        rs.getLong("route_id"), rs.getLong("user_id"), rs.getString("status"),
                        rs.getString("supersedes_memo_no"),
                        MemoTotalsDto(
                            rs.getLong("gross_mtk"), rs.getLong("offer_discount_mtk"), rs.getLong("drp_discount_mtk"), rs.getLong("qc_deduction_mtk"),
                            rs.getInt("round_adj_mtk"), rs.getLong("net_mtk"), rs.getLong("paid_mtk"), rs.getLong("due_mtk"),
                        ),
                        emptyList(), emptyList(), null, 0, "app",
                    ),
                )
            }.list()
            val page = rows.take(q.limit)
            if (page.isEmpty()) return@withHandle MemoPageDto(emptyList(), null)
            val uuids = page.map { java.util.UUID.fromString(it.view.memo_client_uuid) }
            val dates = page.map { it.date }.distinct()
            val lines = h.createQuery(
                """
                SELECT memo_client_uuid::text AS m, line_no, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type,
                       price_valid_from, base_price_mtk, price_per_qty, gross_mtk, offer_id
                FROM app.memo_line WHERE memo_client_uuid = ANY(:u) AND business_date = ANY(:d) ORDER BY line_no
                """.trimIndent(),
            ).bindArray("u", java.util.UUID::class.java, uuids).bindArray("d", LocalDate::class.java, dates).map { rs, _ ->
                MemoLineDto(
                    rs.getString("m"), rs.getInt("line_no"), rs.getLong("sku_id"), rs.getString("line_kind"), rs.getInt("qty_entered"),
                    rs.getString("unit_entered"), rs.getInt("pack_factor"), rs.getInt("qty_base"), rs.getString("price_type"),
                    rs.getObject("price_valid_from", LocalDate::class.java).toString(), rs.getLong("base_price_mtk"), rs.getInt("price_per_qty"),
                    rs.getLong("gross_mtk"), rs.getObject("offer_id")?.let { (it as Number).toLong() },
                )
            }.list().groupBy { it.memo_client_uuid }
            val discounts = h.createQuery(
                """
                SELECT memo_client_uuid::text AS m, kind, sku_id, qty_base, value_mtk, offer_id, offer_version_id, basis_qty_base, line_no
                FROM app.memo_discount WHERE memo_client_uuid = ANY(:u) AND business_date = ANY(:d) ORDER BY line_no NULLS LAST, id
                """.trimIndent(),
            ).bindArray("u", java.util.UUID::class.java, uuids).bindArray("d", LocalDate::class.java, dates).map { rs, _ ->
                fun l(c: String) = rs.getObject(c)?.let { (it as Number).toLong() }
                fun i(c: String) = rs.getObject(c)?.let { (it as Number).toInt() }
                MemoDiscountDto(rs.getString("m"), rs.getString("kind"), l("sku_id"), i("qty_base"), rs.getLong("value_mtk"), l("offer_id"), l("offer_version_id"), i("basis_qty_base"), i("line_no"))
            }.list().groupBy { it.memo_client_uuid }
            // Prints of the memo itself and its reprints; failed attempts are not prints.
            val prints = h.createQuery(
                """
                SELECT memo_client_uuid::text AS m, count(*) AS n, max(captured_at) AS last FROM app.print_event
                WHERE memo_client_uuid = ANY(:u) AND document_kind IN ('memo', 'memo_reprint') AND outcome = 'printed' AND voided_at IS NULL
                GROUP BY memo_client_uuid
                """.trimIndent(),
            ).bindArray("u", java.util.UUID::class.java, uuids).map { rs, _ ->
                rs.getString("m") to (rs.getInt("n") to rs.getObject("last", OffsetDateTime::class.java).toInstant().wire())
            }.list().toMap()
            val items = page.map { r ->
                val u = r.view.memo_client_uuid
                r.view.copy(
                    lines = lines[u].orEmpty(), discounts = discounts[u].orEmpty(),
                    print_count = prints[u]?.first ?: 0, printed_at = prints[u]?.second,
                )
            }
            val next = if (rows.size > q.limit) page.last().let { MemoQuery.encodeCursor(it.date, it.id) } else null
            MemoPageDto(items, next)
        }
    }
}

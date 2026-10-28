package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64

/** Contract FlaggedMemoView (1.8.0). */
@Serializable
data class FlaggedMemoView(
    val memo_client_uuid: String, val memo_no: String, val business_date: String, val status: String, val user_id: Long,
    val user_code: String, val user_name: String, val outlet_id: Long, val outlet_name: String, val route_id: Long?,
    val zone_id: Long, val territory_id: Long, val division_id: Long, val wing_id: Long, val gross_mtk: Long,
    val flags: List<String>, val stored_at: String, val voided_at: String?,
)

/** Contract FlaggedMemoPage. */
@Serializable
data class FlaggedMemoPage(val items: List<FlaggedMemoView>, val next_cursor: String?)

/**
 * getFlaggedMemos (contract 1.8.0, D-BC-FLAGGED-MEMOS-01): stored memos with a server flag, from the stable view
 * `dw.v_memo_flags` (V0111), under the caller's reach like the other dashboards (zones of the scoped node; a node outside
 * the reach is 403 `ERR_OUT_OF_SCOPE`). Names come from `app.app_user` (dw carries none). The view passes the raw
 * `app.memo.status`, so `voided` and an admin data void map to `void` exactly as MemoApi does. Newest stored first,
 * keyset-paginated on (stored_at, memo_id).
 */
internal class FlaggedMemos(private val db: Database) {
    fun page(reach: Reach, from: LocalDate, to: LocalDate, level: String?, nodeId: Long?, flag: String?, limit: Int, cursor: String?): FlaggedMemoPage {
        if (to.isBefore(from)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "to is before from", errors = listOf(FieldError("query.to", "invalid_value")))
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) throw ApiProblem(ProblemCode.ERR_VALIDATION, "to is more than $MAX_DAYS days after from", errors = listOf(FieldError("query.to", "out_of_range")))
        val after = cursor?.let { decode(it) }
        return db.readJdbi.withHandle<FlaggedMemoPage, Exception> { h ->
            val node = resolveScopedNode(h, reach, level, nodeId, ProblemCode.ERR_OUT_OF_SCOPE, from, to)
            val sql = """
                SELECT v.memo_id, v.memo_client_uuid::text AS uuid, v.memo_no, v.business_date,
                       CASE WHEN v.voided_at IS NOT NULL OR v.status = 'voided' THEN 'void' ELSE v.status END AS status,
                       v.user_id, u.username, u.full_name, v.outlet_id, v.outlet_name, v.route_id, v.zone_id, v.territory_id, v.division_id, v.wing_id,
                       v.gross_mtk, v.flags, v.stored_at, v.voided_at
                FROM dw.v_memo_flags v JOIN app.app_user u ON u.id = v.user_id
                WHERE v.business_date BETWEEN :from AND :to AND ${node.clause("v.zone_id", "v.route_id")}
                ${if (flag != null) "AND :flag = ANY(v.flags)" else ""}
                ${if (after != null) "AND (v.stored_at, v.memo_id) < (:after_at, :after_id)" else ""}
                ORDER BY v.stored_at DESC, v.memo_id DESC LIMIT :lim
            """.trimIndent()
            val q = node.bind(h.createQuery(sql)).bind("from", from).bind("to", to).bind("lim", limit + 1)
            flag?.let { q.bind("flag", it) }
            after?.let { q.bind("after_at", it.first).bind("after_id", it.second) }
            val rows = q.map { rs, _ ->
                val storedAt = rs.getObject("stored_at", OffsetDateTime::class.java)
                Row(rs.getLong("memo_id"), storedAt, FlaggedMemoView(
                    rs.getString("uuid"), rs.getString("memo_no"), rs.getObject("business_date", LocalDate::class.java).toString(), rs.getString("status"),
                    rs.getLong("user_id"), rs.getString("username"), rs.getString("full_name"), rs.getLong("outlet_id"), rs.getString("outlet_name"),
                    rs.getObject("route_id")?.let { (it as Number).toLong() }, rs.getLong("zone_id"), rs.getLong("territory_id"), rs.getLong("division_id"),
                    rs.getLong("wing_id"), rs.getLong("gross_mtk"), (rs.getArray("flags").array as Array<*>).map { it as String },
                    storedAt.toInstant().wire(), rs.getObject("voided_at", OffsetDateTime::class.java)?.toInstant()?.wire(),
                ))
            }.list()
            val page = rows.take(limit)
            val next = if (rows.size > limit) page.last().let { encode(it.storedAt, it.id) } else null
            FlaggedMemoPage(page.map { it.view }, next)
        }
    }

    private class Row(val id: Long, val storedAt: OffsetDateTime, val view: FlaggedMemoView)

    private fun encode(at: OffsetDateTime, id: Long) = Base64.getUrlEncoder().withoutPadding().encodeToString("${at.toInstant()}|$id".toByteArray())

    private fun decode(c: String): Pair<OffsetDateTime, Long> = runCatching {
        String(Base64.getUrlDecoder().decode(c)).split('|').let { OffsetDateTime.ofInstant(java.time.Instant.parse(it[0]), ZoneOffset.UTC) to it[1].toLong() }
    }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad cursor", errors = listOf(FieldError("query.cursor", "invalid_value")))

    companion object {
        const val MAX_DAYS = 92L
    }
}

package com.aktcl.aron.feature.programmes

/** One memo line of this phone: only `sale` lines count toward an Astha achievement (free and reward lines are out), in base units and integer milli-taka. */
data class LocalMemoLine(val skuId: Long, val lineKind: String, val qtyBase: Long, val packFactor: Int, val grossMtk: Long)

/** A memo this phone holds with its outbox state ([state] pending, in_flight, quarantined, acked, rejected; [ackedAt] the trusted-clock time of the ack). */
data class LocalMemo(
    val uuid: String, val outletId: Long, val businessDate: String, val supersedes: String?, val state: String, val ackedAt: String?, val lines: List<LocalMemoLine>,
    val routeId: Long? = null, val memoKind: String = "sale",
    /** Set on a local VOID record: the uuid of the memo it voids (the record itself has no lines and counts for nothing). This phone has no void write yet; the rule is built for it. */
    val voidOf: String? = null,
)

/**
 * The phone's own memos on top of the server's Astha figures, by the phone's own ORDER and no clock ([FigureRuleB] with no `through_date`: the Astha figure is cut at its snapshot, today included).
 * [ackedBeforeRequest] is the set of memo uuids the phone had an acknowledgement for when it SENT the request for the held bundle (recorded under the sync lock: no upload in flight). A local
 * memo is added when it is not in that set; a memo in the set that this phone has since superseded or voided (the edit not in the set) is taken back out; an `uncertain` memo (sent, answer lost)
 * is neither added nor subtracted. Without the recorded set (a promoted prefetch, a bundle stored before it was recorded) the figures are the server's alone. A memo is added only to rows of its own
 * route and only for outlets that are enrolled (have Astha rows); a zero-sale memo adds no memo count. Recomputed from scratch on every read, never accumulated.
 */
object AsthaLocal {
    /** Memos SENT but unanswered when the held snapshot was requested (state `uncertain`): the server figure may already hold them, so they are never added; the page says so. */
    fun awaiting(memos: List<LocalMemo>): Int = FigureRuleB.awaiting(memos)

    /** The memos to add and to take back out for the held bundle's [month] (`YYYY-MM`), or null when the order is unknown. */
    fun delta(memos: List<LocalMemo>, ackedBeforeRequest: Set<String>?, month: String?): Pair<List<LocalMemo>, List<LocalMemo>>? =
        FigureRuleB.delta(memos, ackedBeforeRequest, month, null)?.let { it.add to it.subtract }

    /** [data] with the memos' sale lines added to the matching brand rows (route row and the outlet's row), the memo counts to the All Brand rows, and by_pack kept per pack size. */
    fun apply(data: AsthaData, memos: List<LocalMemo>, ackedBeforeRequest: Set<String>?, month: String?): AsthaData {
        val (add, out) = delta(memos, ackedBeforeRequest, month) ?: return data
        val enrolled = data.outletIds.toSet()
        if (add.isEmpty() && out.isEmpty()) return data
        var rows = data.rows
        fun bump(m: LocalMemo, sign: Int) {
            val month = m.businessDate.take(7)
            val sales = m.lines.filter { it.lineKind == "sale" }
            val perBrand = sales.groupBy { data.skuBrand[it.skuId] }
            rows = rows.map { r ->
                if (r.month != month || m.outletId !in enrolled || (r.outletId != null && r.outletId != m.outletId) || (m.routeId != null && r.routeId != m.routeId)) return@map r
                if (r.brandId == null) return@map if (m.memoKind == "sale") r.copy(memoAchieved = (r.memoAchieved + sign).coerceAtLeast(0)) else r
                val ls = perBrand[r.brandId].orEmpty()
                if (ls.isEmpty()) return@map r
                val std = ls.sumOf { it.qtyBase * 1000L }; val mtk = ls.sumOf { it.grossMtk }
                var packs = r.byPack
                ls.groupBy { data.skuPack[it.skuId] ?: it.packFactor }.forEach { (f, l) ->
                    val d = l.sumOf { it.qtyBase * 1000L }
                    packs = if (packs.any { it.packFactor == f }) packs.map { if (it.packFactor == f) it.copy(stdAchievedMilli = (it.stdAchievedMilli + sign * d).coerceAtLeast(0)) else it }
                    else if (sign > 0) (packs + AsthaPack(f, d)).sortedBy { it.packFactor } else packs
                }
                r.copy(stdAchievedMilli = (r.stdAchievedMilli + sign * std).coerceAtLeast(0), valueAchievedMtk = (r.valueAchievedMtk + sign * mtk).coerceAtLeast(0), byPack = packs)
            }
        }
        add.forEach { bump(it, +1) }
        out.forEach { bump(it, -1) }
        return data.copy(rows = rows)
    }
}

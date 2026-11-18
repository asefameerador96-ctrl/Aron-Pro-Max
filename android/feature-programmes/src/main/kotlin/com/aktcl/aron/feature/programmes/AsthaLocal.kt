package com.aktcl.aron.feature.programmes

/** One memo line of this phone: only `sale` lines count toward an Astha achievement (free and reward lines are out), in base units and integer milli-taka. */
data class LocalMemoLine(val skuId: Long, val lineKind: String, val qtyBase: Long, val packFactor: Int, val grossMtk: Long)

/** A memo this phone holds with its outbox state ([state] pending, in_flight, quarantined, acked, rejected; [ackedAt] the trusted-clock time of the ack). */
data class LocalMemo(
    val uuid: String, val outletId: Long, val businessDate: String, val supersedes: String?, val state: String, val ackedAt: String?, val lines: List<LocalMemoLine>,
    val routeId: Long? = null, val memoKind: String = "sale",
)

/**
 * The phone's own memos on top of the server's Astha figures (contract: the server figure holds the memos it had acknowledged when this phone SENT the
 * bundle request; `astha_as_of` is display only and is never part of the rule). A memo counts when it is not refused and is either still on the phone or
 * was acknowledged AFTER the request instant ([requestedAt], the trusted clock when the held snapshot was requested); one that another local memo supersedes
 * does not count; and a counted memo that supersedes a memo the server ALREADY holds (acknowledged at or before the request) takes that memo's figures back
 * out. Without a request instant only the memos still on the phone count (never a double count). Recomputed from scratch on every read, never accumulated.
 * A memo is added only to rows of its own route and only for outlets that are enrolled (have Astha rows); a zero-sale memo adds no memo count (the server
 * counts sales only). An `uncertain` memo (sent, answer lost or not yet in) is neither added nor does it take its original out: the figure stays as the server gave it until the next full bundle. A void is not read here: this phone has no memo-void write, a server void arrives with the next bundle.
 */
object AsthaLocal {
    private val STILL_LOCAL = setOf("pending", "in_flight", "quarantined")

    /** Memos SENT but unanswered when the held snapshot was requested (state `uncertain`): the server figure may already hold them, so they are never added; the page says so. */
    fun awaiting(memos: List<LocalMemo>): Int = memos.count { it.state == "uncertain" && it.memoKind == "sale" }

    private fun insideServerFigure(m: LocalMemo, requestedMs: Long?): Boolean =
        m.state == "acked" && requestedMs != null && DhakaTime.ms(m.ackedAt)?.let { it <= requestedMs } == true

    /** The memos to add (positive) and to take back out (negative) for the snapshot requested at [requestedAt] (null = unknown). */
    fun delta(memos: List<LocalMemo>, requestedAt: String?): Pair<List<LocalMemo>, List<LocalMemo>> {
        val requestedMs = DhakaTime.ms(requestedAt)
        val live = memos.filter { it.state != "rejected" }
        val supersededUuids = live.mapNotNull { it.supersedes }.toSet()
        val add = live.filter { it.uuid !in supersededUuids && (it.state in STILL_LOCAL || (it.state == "acked" && requestedMs != null && DhakaTime.ms(it.ackedAt)?.let { t -> t > requestedMs } == true)) }
        // an edit may sit on a CHAIN (M <- E1 <- E2): walk each added memo's supersedes chain down to the first memo the server figure holds and take that one out
        val byUuid = memos.associateBy { it.uuid }
        val out = LinkedHashSet<LocalMemo>()
        for (m in add) {
            var cur = m.supersedes; var guard = 0
            while (cur != null && guard++ < 64) {
                val older = byUuid[cur] ?: break
                if (insideServerFigure(older, requestedMs)) { out += older; break }
                cur = older.supersedes
            }
        }
        return add to out.toList()
    }

    /** [data] with the memos' sale lines added to the matching brand rows (route row and the outlet's row), the memo counts to the All Brand rows, and by_pack kept per pack size. */
    fun apply(data: AsthaData, memos: List<LocalMemo>, requestedAt: String?): AsthaData {
        val (add, out) = delta(memos, requestedAt)
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

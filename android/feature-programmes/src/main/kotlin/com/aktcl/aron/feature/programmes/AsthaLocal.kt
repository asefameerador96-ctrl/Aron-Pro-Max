package com.aktcl.aron.feature.programmes

/** One memo line of this phone: only `sale` lines count toward an Astha achievement (free and reward lines are out), in base units and integer milli-taka. */
data class LocalMemoLine(val skuId: Long, val lineKind: String, val qtyBase: Long, val packFactor: Int, val grossMtk: Long)

/** A memo this phone holds with its outbox state ([state] pending, in_flight, quarantined, acked, rejected; [ackedAt] the trusted-clock time of the ack). */
data class LocalMemo(
    val uuid: String, val outletId: Long, val businessDate: String, val supersedes: String?, val state: String, val ackedAt: String?, val lines: List<LocalMemoLine>,
)

/**
 * The phone's own memos on top of the server's Astha figures (contract: the server achievement is through [AsthaData.asOf]; the phone adds what the
 * server cannot hold yet, by memo UUID, and never counts a memo twice). A memo counts when it is not refused and is either still on the phone or was
 * acknowledged after `astha_as_of`; one that another local memo supersedes does not count; and a counted memo that supersedes a memo the server
 * ALREADY holds (acknowledged at or before `astha_as_of`) takes that memo's figures back out. Recomputed from scratch on every read, never accumulated.
 * Limit: a memo voided on this phone is not subtracted until the next bundle (the void is not read here).
 */
object AsthaLocal {
    private val STILL_LOCAL = setOf("pending", "in_flight", "quarantined")

    private fun insideServerFigure(m: LocalMemo, asOfMs: Long?): Boolean =
        m.state == "acked" && asOfMs != null && DhakaTime.ms(m.ackedAt)?.let { it <= asOfMs } == true

    /** The memos to add (positive) and to take back out (negative) for [asOf] (`astha_as_of`, null = unknown: every non-refused memo counts, nothing is taken out). */
    fun delta(memos: List<LocalMemo>, asOf: String?): Pair<List<LocalMemo>, List<LocalMemo>> {
        val asOfMs = DhakaTime.ms(asOf)
        val live = memos.filter { it.state != "rejected" }
        val supersededUuids = live.mapNotNull { it.supersedes }.toSet()
        val add = live.filter { it.uuid !in supersededUuids && (it.state in STILL_LOCAL || asOfMs == null || DhakaTime.ms(it.ackedAt)?.let { t -> t > asOfMs } != false) }
        val out = live.filter { it.uuid in add.mapNotNull { a -> a.supersedes }.toSet() && insideServerFigure(it, asOfMs) }
        return add to out
    }

    /** [data] with the memos' sale lines added to the matching brand rows (route row and the outlet's row), the memo counts to the All Brand rows, and by_pack kept per pack size. */
    fun apply(data: AsthaData, memos: List<LocalMemo>): AsthaData {
        val (add, out) = delta(memos, data.asOf)
        if (add.isEmpty() && out.isEmpty()) return data
        var rows = data.rows
        fun bump(m: LocalMemo, sign: Int) {
            val month = m.businessDate.take(7)
            val sales = m.lines.filter { it.lineKind == "sale" }
            val perBrand = sales.groupBy { data.skuBrand[it.skuId] }
            rows = rows.map { r ->
                if (r.month != month || (r.outletId != null && r.outletId != m.outletId)) return@map r
                if (r.brandId == null) return@map r.copy(memoAchieved = (r.memoAchieved + sign).coerceAtLeast(0))
                val ls = perBrand[r.brandId].orEmpty()
                if (ls.isEmpty()) return@map r
                val std = ls.sumOf { it.qtyBase * 1000L }; val mtk = ls.sumOf { it.grossMtk }
                var packs = r.byPack
                ls.groupBy { it.packFactor }.forEach { (f, l) ->
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

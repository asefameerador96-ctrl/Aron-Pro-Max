package com.aktcl.aron.feature.programmes

/** What the phone itself sold on days the server figure does not cover, for one figure: sale lines only, base units. */
data class LocalToday(val qtyBase: Long, val memoCount: Long, val grossMtk: Long, val byPack: Map<Int, Long>) {
    val isEmpty: Boolean get() = memoCount == 0L && qtyBase == 0L
}

/**
 * THE figure frame of the target, KPI, Star and campaign screens (D-LEAD-FIGURES-SIMPLE-01; the rule is in the `RouteSnapshot` description of contract 1.24.0). The server figure is shown exactly as sent
 * with its [throughDate] and [asOf]; this frame adds only what is SEPARATE from it and labelled as such:
 *  1. [today]: "today on this phone": this phone's own live sale memos dated AFTER [throughDate] (the figure never holds them), computed only from local data;
 *  2. [unconfirmedEarlier]: a COUNT of this phone's memos and voids of days up to [throughDate] that the server has not confirmed (never sent, sent without an answer, held for review, or
 *     acknowledged after the bundle's request when the order is known);
 *  3. [forkLeftOut]: memos of a FORK (two live edits of one memo) left out of [today] and named.
 * It never adds a memo into the server figure and never subtracts from it; a local memo of an earlier day appears only in the count. Nothing here reads a clock.
 * [fromFirst] is true when the window is empty: [throughDate] is before the bundle month (the 1st of a month), so the figure is "from the 1st", not complete through the last day of last month.
 */
data class FigureFrame(
    val month: String?, val throughDate: String?, val asOf: String?, val fromFirst: Boolean,
    val today: LocalToday, val unconfirmedEarlier: Int, val forkLeftOut: Int,
) {
    companion object {
        /** The frame for the memos that belong to the figure ([memoIn]: its route, or outlet and programme) and whose sale lines count for it ([lineIn]: its brand, category or SKU). */
        fun of(
            memos: List<LocalMemo>, month: String?, throughDate: String?, asOf: String?, ackedBeforeRequest: Set<String>?,
            memoIn: (LocalMemo) -> Boolean = { true }, lineIn: (LocalMemoLine) -> Boolean = { true },
        ): FigureFrame {
            val fromFirst = month != null && throughDate != null && throughDate.take(7) < month
            if (month == null || throughDate == null) return FigureFrame(month, throughDate, asOf, fromFirst, LocalToday(0, 0, 0, emptyMap()), 0, 0)
            val mine = memos.filter { memoIn(it) && it.state != "rejected" }
            val byId = memos.associateBy { it.uuid }
            // who put each memo out: a live memo that supersedes it, or a live void record
            val killed = HashSet<String>()
            mine.forEach { k -> k.supersedes?.let { killed += it }; k.voidOf?.let { killed += it } }
            val tips = mine.filter { it.voidOf == null && it.uuid !in killed && it.businessDate.take(7) == month }
            fun rootOf(m: LocalMemo): String? = FigureRuleB.chainOfOrNull(m, byId)?.last()?.uuid
            val forkRoots = tips.filter { it.supersedes != null && it.businessDate > throughDate }.groupBy { rootOf(it) }.filter { (r, v) -> r != null && v.size > 1 }.keys
            var qty = 0L; var count = 0L; var mtk = 0L; val packs = HashMap<Int, Long>(); var unconfirmed = 0; var fork = 0
            fun hasLines(m: LocalMemo) = m.memoKind == "sale" && m.lines.any { it.lineKind == "sale" && lineIn(it) }
            for (m in tips) {
                if (!hasLines(m)) continue
                val after = m.businessDate > throughDate
                val chain = FigureRuleB.chainOfOrNull(m, byId)
                // a later-day memo counts as today only when EVERY memo of its edit chain is a later day too (the figure holds none of them); a chain reaching an earlier day is an unconfirmed change of that day
                val allAfter = after && (m.supersedes == null || chain?.all { it.businessDate > throughDate } == true)
                if (allAfter) {
                    if (m.supersedes != null && rootOf(m) in forkRoots) { fork++; continue }
                    val ls = m.lines.filter { it.lineKind == "sale" && lineIn(it) }
                    qty += ls.sumOf { it.qtyBase }; mtk += ls.sumOf { it.grossMtk }; count += 1
                    ls.groupBy { it.packFactor }.forEach { (f, l) -> packs[f] = (packs[f] ?: 0L) + l.sumOf { it.qtyBase } }
                } else if (!confirmed(m, ackedBeforeRequest)) unconfirmed++
            }
            // a void of an earlier-day memo that the server has not confirmed is an unconfirmed change of that day as well
            for (v in mine) {
                if (v.voidOf == null || v.businessDate.take(7) != month || v.businessDate > throughDate) continue
                val target = byId[v.voidOf]
                if (target != null && !hasLines(target)) continue
                if (!confirmed(v, ackedBeforeRequest)) unconfirmed++
            }
            return FigureFrame(month, throughDate, asOf, fromFirst, LocalToday(qty, count, mtk, packs.filterValues { it != 0L }), unconfirmed, fork)
        }

        /** The server holds it when it acknowledged it before the bundle's request left (the phone's own order); with no recorded order an acknowledged record is not counted (it may be in the figure). */
        private fun confirmed(m: LocalMemo, acked: Set<String>?): Boolean = m.state == "acked" && (acked == null || m.uuid in acked)
    }
}

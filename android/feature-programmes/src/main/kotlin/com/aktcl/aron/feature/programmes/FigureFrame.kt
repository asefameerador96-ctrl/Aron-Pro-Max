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
            fun hasLines(m: LocalMemo) = m.memoKind == "sale" && m.lines.any { it.lineKind == "sale" && lineIn(it) }
            fun known(m: LocalMemo): List<LocalMemo> = FigureRuleB.knownChain(m, byId)
            fun inWindow(m: LocalMemo) = m.businessDate.take(7) == month && m.businessDate <= throughDate // a day the server figure covers
            // a memo belongs to this figure when it, or any memo of its edit chain, has lines for it (an edit that drops the lines, or became a zero sale, still changes what the figure holds)
            fun relevant(m: LocalMemo) = known(m).any { hasLines(it) }
            val tips = mine.filter { it.voidOf == null && it.uuid !in killed && it.businessDate.take(7) == month }
            // a FORK: two live later-day edits of one memo have two tips for one sale; neither is put into "today" (named instead)
            val forkRoots = tips.filter { it.supersedes != null && it.businessDate > throughDate }.groupBy { FigureRuleB.rootKey(it, byId) }.filter { it.value.size > 1 }.keys
            var qty = 0L; var count = 0L; var mtk = 0L; val packs = HashMap<Int, Long>(); var unconfirmed = 0; var fork = 0
            for (m in tips) {
                if (!relevant(m)) continue
                if (m.businessDate > throughDate) {
                    // a live tip dated after through_date is today on this phone WHATEVER its chain holds: the figure never holds it
                    if (m.supersedes != null && FigureRuleB.rootKey(m, byId) in forkRoots) { if (hasLines(m)) fork++; continue }
                    if (hasLines(m)) {
                        val ls = m.lines.filter { it.lineKind == "sale" && lineIn(it) }
                        qty += ls.sumOf { it.qtyBase }; mtk += ls.sumOf { it.grossMtk }; count += 1
                        ls.groupBy { it.packFactor }.forEach { (f, l) -> packs[f] = (packs[f] ?: 0L) + l.sumOf { it.qtyBase } }
                    }
                    // when it edits a memo of a day the figure covers and the server has not confirmed the edit, that earlier-day memo may still be in the figure: an unconfirmed change, counted (never netted)
                    if (m.supersedes != null && !confirmed(m, ackedBeforeRequest) && known(m).drop(1).any { inWindow(it) && hasLines(it) }) unconfirmed++
                } else if (!confirmed(m, ackedBeforeRequest)) unconfirmed++
            }
            // a void the server has not confirmed, of a memo of a day the figure covers (whatever day the void itself carries); a void of a memo of another month is not this figure's
            for (v in mine) {
                if (v.voidOf == null || confirmed(v, ackedBeforeRequest)) continue
                val target = byId[v.voidOf] ?: continue
                if (known(target).any { inWindow(it) && hasLines(it) }) unconfirmed++ // the target, or any memo of its chain, is in the window with lines for this figure
            }
            return FigureFrame(month, throughDate, asOf, fromFirst, LocalToday(qty, count, mtk, packs.filterValues { it != 0L }), unconfirmed, fork)
        }

        /** The server holds it when it acknowledged it before the bundle's request left (the phone's own order); with no recorded order an acknowledged record is not counted (it may be in the figure). */
        private fun confirmed(m: LocalMemo, acked: Set<String>?): Boolean = m.state == "acked" && (acked == null || m.uuid in acked)
    }
}

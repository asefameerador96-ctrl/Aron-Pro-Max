package com.aktcl.aron.feature.programmes

/**
 * Phone rule B for the SR programme figures that the bundle carries per route (`achievement_mtd`, `kpi` volumes and memo counts, `campaigns`; contract text in the
 * `RouteSnapshot` description, request file `sr-programmes-phone-rule-b.md`). NO CLOCKS: the order is the phone's own. [ackedBeforeRequest] is the set of memo uuids the phone had
 * received `accepted` or `duplicate` for when it SENT the request for this bundle (recorded under the user's sync lock, so no upload batch is in flight at that moment: single flight).
 *
 * The server figure holds every memo acknowledged before that moment, dated from the 1st of the bundle [month] up to and including [throughDate]. So:
 * - ADD a local memo of the month that is not in the set, and every local memo dated after [throughDate] (the figure is cut there) whether acknowledged or not;
 * - SUBTRACT a memo that is in the set (dated up to [throughDate]) when it has since been voided or superseded locally and that void or edit record is NOT in the set;
 * - an edit is therefore: add the new memo, subtract the old one; a memo whose void or edit IS in the set is already out of the server figure and is left alone;
 * - a memo outside the bundle month is never touched; a refused memo counts for nothing; a memo SENT but unanswered when the request left (state `uncertain`) is neither added nor
 *   subtracted (the server figure may or may not hold it; the page says it awaits).
 * Recomputed from scratch on every read, never accumulated. With no month, no recorded order, or no server figure there is no local arithmetic (the figure is the server's, with its as-of label).
 */
object FigureRuleB {
    /** What to add (positive) and what to take back out of the server figure. */
    data class Delta(val add: List<LocalMemo>, val subtract: List<LocalMemo>)

    /** A figure to show: the numbers, whether the phone's own memos are in them, and the server's as-of label (informational, never used in arithmetic). */
    data class Shown(val qtyBase: Long, val memoCount: Long, val grossMtk: Long, val byPack: Map<Int, Long>, val localApplied: Boolean, val asOf: String?)

    /** One server figure (a row of `achievement_mtd`, a brand, a campaign): base-unit quantity, memo count, optional value in milli-taka, and `by_pack` as pack factor to base units. */
    data class ServerFigure(val qtyBase: Long, val memoCount: Long, val grossMtk: Long = 0L, val byPack: Map<Int, Long> = emptyMap(), val asOf: String? = null)

    /** The memos to add and to subtract, or null when the order is unknown (no month or no recorded set): then nothing local may be applied. */
    fun delta(memos: List<LocalMemo>, ackedBeforeRequest: Set<String>?, month: String?, throughDate: String?): Delta? {
        if (month == null || ackedBeforeRequest == null) return null
        fun inMonth(m: LocalMemo) = m.businessDate.take(7) == month
        fun cutAfter(m: LocalMemo) = throughDate != null && m.businessDate > throughDate
        val live = memos.filter { it.state != "rejected" && it.state != "uncertain" }
        // who put each memo out: a live local memo that supersedes it, or a live local void record
        val killer = HashMap<String, String>()
        live.forEach { k -> k.supersedes?.let { killer.putIfAbsent(it, k.uuid) }; k.voidOf?.let { killer.putIfAbsent(it, k.uuid) } }
        val dead = killer.keys
        val unsure = memos.filter { it.state == "uncertain" }.map { it.uuid }.toSet()
        val add = live.filter { it.voidOf == null && inMonth(it) && it.uuid !in dead && (it.uuid !in ackedBeforeRequest || cutAfter(it)) }
        val byUuid = memos.associateBy { it.uuid }
        val subtract = dead.mapNotNull { id ->
            val m = byUuid[id] ?: return@mapNotNull null
            val k = killer.getValue(id)
            // held by the server figure (acknowledged before the request, inside the month, up to through_date), and the record that voids or replaces it is not
            if (m.voidOf == null && inMonth(m) && !cutAfter(m) && id in ackedBeforeRequest && k !in ackedBeforeRequest && k !in unsure && m.state != "rejected" && m.state != "uncertain") m else null
        }
        return Delta(add, subtract)
    }

    /** True when some memo is sent but unanswered (the figure may already hold it): the page says it awaits confirmation. */
    fun awaiting(memos: List<LocalMemo>): Int = memos.count { it.state == "uncertain" && it.voidOf == null && it.memoKind == "sale" }

    /**
     * The figure to show. [memoIn] picks the memos that belong to the figure (its route; for a campaign its outlet and programme), [lineIn] the sale lines that belong to it (its brand,
     * category or SKU); a memo counts once when it has at least one such line and its kind is `sale` (a zero-sale memo adds no memo count and no quantity). Never below zero.
     * [server] null (the bundle has no such section): null, nothing to show. No order or month: the server figure as it is, `localApplied` false.
     */
    fun show(
        server: ServerFigure?, memos: List<LocalMemo>, ackedBeforeRequest: Set<String>?, month: String?, throughDate: String?,
        memoIn: (LocalMemo) -> Boolean = { true }, lineIn: (LocalMemoLine) -> Boolean = { true },
    ): Shown? {
        server ?: return null
        val d = delta(memos.filter(memoIn), ackedBeforeRequest, month, throughDate)
            ?: return Shown(server.qtyBase, server.memoCount, server.grossMtk, server.byPack, false, server.asOf)
        var qty = server.qtyBase; var count = server.memoCount; var mtk = server.grossMtk
        val packs = server.byPack.toMutableMap()
        fun put(m: LocalMemo, sign: Int) {
            if (m.memoKind != "sale") return
            val ls = m.lines.filter { it.lineKind == "sale" && lineIn(it) }
            if (ls.isEmpty()) return
            qty += sign * ls.sumOf { it.qtyBase }; mtk += sign * ls.sumOf { it.grossMtk }; count += sign
            ls.groupBy { it.packFactor }.forEach { (f, l) -> packs[f] = ((packs[f] ?: 0L) + sign * l.sumOf { it.qtyBase }).coerceAtLeast(0L) }
        }
        d.add.forEach { put(it, +1) }
        d.subtract.forEach { put(it, -1) }
        return Shown(qty.coerceAtLeast(0L), count.coerceAtLeast(0L), mtk.coerceAtLeast(0L), packs.filterValues { it != 0L }, true, server.asOf)
    }
}

/** Working-day numbers of the bundle month (`RouteSnapshot.calendar`) and the per-day figures made from them when today's local memos are in the achieved figure. */
data class RouteCalendar(val workingDays: Int, val elapsedBeforeToday: Int, val remainingAfterToday: Int, val todayIsWorking: Boolean, val lastMonthWorkingDays: Int = 0) {
    /** Days the achieved figure covers: those before today, plus today when it is a working day (the phone's memos of today are in the figure). */
    val daysCovered: Int get() = elapsedBeforeToday + if (todayIsWorking) 1 else 0

    /** Average per working day so far (ADS, PADS): the achieved figure over the days it covers, in thousandths of a unit, half up; null when the divisor is zero, never zero. */
    fun perDayMilli(achievedMilli: Long): Long? = daysCovered.takeIf { it > 0 }?.let { half(achievedMilli, it.toLong()) }

    /** Required per remaining working day (RADS): what is left to reach [targetMilli], over the days after today, in thousandths; null when the divisor is zero. */
    fun requiredPerDayMilli(targetMilli: Long, achievedMilli: Long): Long? = remainingAfterToday.takeIf { it > 0 }?.let { half((targetMilli - achievedMilli).coerceAtLeast(0L), it.toLong()) }

    private fun half(n: Long, d: Long): Long = (n * 2 + d) / (d * 2)
}

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

    /**
     * The uuids of live memos that depend on a memo SENT but unanswered (`uncertain`): an edit or void of it, and an edit of such an edit. The server may hold the predecessor, the edit, both or
     * neither, so nothing of the chain is added or subtracted and the page says it awaits confirmation (never the newest memo on top of a figure that may already hold an older version).
     */
    fun tainted(memos: List<LocalMemo>): Set<String> {
        val byUuid = memos.associateBy { it.uuid }
        fun target(m: LocalMemo) = m.supersedes ?: m.voidOf
        fun hasUncertainAncestor(m: LocalMemo): Boolean {
            var cur = target(m); var guard = 0
            while (cur != null && guard++ < 64) { val p = byUuid[cur] ?: return false; if (p.state == "uncertain" || p.state == "quarantined") return true; cur = target(p) }
            return false
        }
        return memos.filter { it.state != "rejected" && it.state != "uncertain" && it.state != "quarantined" && hasUncertainAncestor(it) }.map { it.uuid }.toSet()
    }

    /** Memo states that can only mean "never left this phone" (the outbox row is pending with no attempt): certainly NOT inside any server figure, whatever the order is. */
    private val NEVER_SENT = setOf("pending")

    /**
     * The memos to add and to subtract, or null when there is no month. With the recorded order ([ackedBeforeRequest]) the full rule applies. WITHOUT it (a promoted prefetch, a bundle stored before
     * the order was recorded) only what is certain is done: a memo that never left the phone (and is not an edit or a void of anything) is certainly not in the server figure and is ADDED;
     * an acknowledged memo may or may not be in it and is NOT added, and nothing is subtracted; [unknownOrder] counts them for the page's note.
     */
    fun delta(memos: List<LocalMemo>, ackedBeforeRequest: Set<String>?, month: String?, throughDate: String?): Delta? {
        if (month == null) return null
        fun inMonth(m: LocalMemo) = m.businessDate.take(7) == month
        fun cutAfter(m: LocalMemo) = throughDate != null && m.businessDate > throughDate
        val taint = tainted(memos)
        // a memo the server QUARANTINED (held for review, or waiting for a parent) is never added: the server can commit it later with no upload ack (a reviewer accepts, a sweep), so adding it would count it twice
        val live = memos.filter { it.state != "rejected" && it.state != "uncertain" && it.state != "quarantined" && it.uuid !in taint }
        // who put each memo out: a live local memo that supersedes it, or a live local void record
        val killer = HashMap<String, String>()
        live.forEach { k -> k.supersedes?.let { killer.putIfAbsent(it, k.uuid) }; k.voidOf?.let { killer.putIfAbsent(it, k.uuid) } }
        val dead = killer.keys
        if (ackedBeforeRequest == null) {
            // the old-phone fallback of the identity rule: never-sent pending memos, and memos dated after through_date (the figure is cut there)
            return Delta(live.filter { it.voidOf == null && it.supersedes == null && inMonth(it) && it.uuid !in dead && (it.state in NEVER_SENT || cutAfter(it)) }, emptyList())
        }
        // a memo acknowledged through a RESOLUTION that reached the phone after the request is awaiting until the next bundle: the server may already have held it (never added)
        val add = live.filter { it.voidOf == null && inMonth(it) && it.uuid !in dead && !(it.viaResolution && it.state == "acked" && it.uuid !in ackedBeforeRequest) && (it.uuid !in ackedBeforeRequest || cutAfter(it)) }
        val byUuid = memos.associateBy { it.uuid }
        val subtract = dead.mapNotNull { id ->
            val m = byUuid[id] ?: return@mapNotNull null
            val k = killer.getValue(id)
            // held by the server figure (acknowledged before the request, inside the month, up to through_date), and the record that voids or replaces it is not
            if (m.voidOf == null && inMonth(m) && !cutAfter(m) && id in ackedBeforeRequest && k !in ackedBeforeRequest && m.state != "rejected" && m.state != "uncertain") m else null
        }
        return Delta(add, subtract)
    }

    /** Sale memos the page must say await confirmation: sent and unanswered, and the edits and voids that depend on one. */
    fun awaiting(memos: List<LocalMemo>, ackedBeforeRequest: Set<String>? = null): Int {
        val taint = tainted(memos)
        return memos.count {
            it.voidOf == null && it.memoKind == "sale" &&
                (it.state == "uncertain" || it.uuid in taint || (ackedBeforeRequest != null && it.viaResolution && it.state == "acked" && it.uuid !in ackedBeforeRequest))
        }
    }

    /**
     * Sale memos the server holds for review (state `quarantined`): shown in their own line, never added. One becomes part of the server figure only when a resolution accepted it (or an accepted or
     * duplicate ack) reached the phone BEFORE a bundle request, and then it is in the acknowledged set of that bundle; a rejection removes it.
     */
    fun heldForReview(memos: List<LocalMemo>): Int = memos.count { it.voidOf == null && it.memoKind == "sale" && it.state == "quarantined" }

    /** With NO recorded order: the sale memos left out of the figures because they may already be in them (acknowledged ones, and edits of anything), for the page's own note. 0 when the order is known. */
    fun unknownOrder(memos: List<LocalMemo>, orderKnown: Boolean): Int =
        if (orderKnown) 0 else memos.count { it.voidOf == null && it.memoKind == "sale" && it.state != "rejected" && it.state != "uncertain" && it.state != "quarantined" && (it.state !in NEVER_SENT || it.supersedes != null) }

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

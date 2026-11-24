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
 * Recomputed from scratch on every read, never accumulated. With no month or no server figure there is no local arithmetic (the figure is the server's, with its as-of label). With NO recorded order the
 * old-phone fallback applies (see [delta]): only memos that are certainly not in the server figure are added, nothing is subtracted, and the rest is counted by [unknownOrder] for the page's note.
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

    /** [m] and the memos it replaced, newest first, down to the root of its edit chain; null when an ancestor is missing on this phone or the chain loops. */
    fun chainOfOrNull(m: LocalMemo, byId: Map<String, LocalMemo>): List<LocalMemo>? = chainOf(m, byId)

    private fun chainOf(m: LocalMemo, byId: Map<String, LocalMemo>): List<LocalMemo>? {
        val out = ArrayList<LocalMemo>(); var cur: LocalMemo? = m; var guard = 0
        while (cur != null) {
            if (guard++ > 64) return null
            out += cur
            val up = cur.supersedes ?: return out
            cur = byId[up] ?: return null
        }
        return out
    }

    /** Memo states that can only mean "never left this phone" (the outbox row is pending with no attempt): certainly NOT inside any server figure, whatever the order is. */
    private val NEVER_SENT = setOf("pending")

    /**
     * The memos to add and to subtract, or null when there is no month. With the recorded order ([ackedBeforeRequest]) the full rule applies. WITHOUT it (a promoted prefetch, a bundle stored before
     * the order was recorded) only what is certain is done, and nothing is ever subtracted. ADDED are the live memos of the month that are certainly not in the server figure: a memo that never left the
     * phone (not an edit, not a void), and a memo dated AFTER [throughDate] (the figure is cut there, so it never holds it), including an edit of such a memo when EVERY memo of its edit chain, down to the root, is
     * dated after [throughDate] (the figure holds none of the versions, so the newest edit counts in full and the older ones are dead). NOT added: an acknowledged memo dated up to [throughDate] (it may be in the
     * figure), an edit whose chain reaches a memo dated up to [throughDate] (that memo may be in the figure, so nothing can be netted); [unknownOrder] counts what was left out for the page's note.
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
            val byId = memos.associateBy { it.uuid }
            // an edit counts only when EVERY memo of its chain, down to the root, is dated after through_date (the figure holds none of them); a chain that reaches back to a memo the figure may hold, or to one this phone does not have, is left out
            fun editOfLaterDay(m: LocalMemo) = m.supersedes != null && chainOf(m, byId)?.all { cutAfter(it) } == true
            val cand = live.filter { it.voidOf == null && inMonth(it) && it.uuid !in dead && ((it.supersedes == null && (it.state in NEVER_SENT || cutAfter(it))) || editOfLaterDay(it)) }
            // a FORK (two live edits of one memo, even on different days) has two tips for one sale: neither is added (the page names them), never both
            val rootOf = cand.filter { it.supersedes != null }.associate { it.uuid to chainOf(it, byId)!!.last().uuid }
            val forked = rootOf.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }.values.flatten().toSet()
            return Delta(cand.filter { it.uuid !in forked }, emptyList())
        }
        // a memo acknowledged through a RESOLUTION that reached the phone after the request is awaiting until the next bundle: the server may already have held it (never added)
        val byUuid = memos.associateBy { it.uuid }
        // a FORK (two live edits of one memo) has two tips for one sale: neither is added and the memo they edit is not subtracted (the figure may read low, never high); on one phone an edit always supersedes the
        // newest live version, so a fork needs a record the phone did not make itself, but it is handled the same way here as without the order
        val forkedTips = live.filter { it.voidOf == null && it.supersedes != null && it.uuid !in dead && inMonth(it) }
            .groupBy { chainOf(it, byUuid)?.last()?.uuid }.filter { (root, tips) -> root != null && tips.size > 1 }
        val forkedIds = forkedTips.values.flatten().map { it.uuid }.toSet()
        val add = live.filter { it.voidOf == null && inMonth(it) && it.uuid !in dead && it.uuid !in forkedIds && !(it.viaResolution && it.state == "acked" && it.uuid !in ackedBeforeRequest) && (it.uuid !in ackedBeforeRequest || cutAfter(it)) }
        val subtract = dead.mapNotNull { id ->
            val m = byUuid[id] ?: return@mapNotNull null
            if (forkedTips.isNotEmpty() && chainOf(m, byUuid)?.last()?.uuid in forkedTips.keys) return@mapNotNull null
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

    /**
     * With NO recorded order: the sale memos the fallback of [delta] really LEFT OUT because they may already be in the figure (acknowledged ones dated up to [throughDate], and edits of anything the figure may
     * hold), for the page's own note. A memo the fallback adds (never sent, or dated after [throughDate]) is not counted. 0 when the order is known. [month] null: nothing is added, so all candidates count.
     */
    fun unknownOrder(memos: List<LocalMemo>, orderKnown: Boolean, month: String? = null, throughDate: String? = null): Int {
        if (orderKnown) return 0
        val added = delta(memos, null, month, throughDate)?.add?.map { it.uuid }?.toSet().orEmpty()
        val byId = memos.associateBy { it.uuid }
        val replaced = memos.filter { it.uuid in added }.flatMap { chainOf(it, byId).orEmpty().drop(1) }.map { it.uuid }.toSet() // every older version an ADDED edit replaces is accounted for
        val edited = memos.filter { it.state != "rejected" }.mapNotNull { it.supersedes }.toSet()
        val voided = memos.filter { it.state != "rejected" }.mapNotNull { it.voidOf }.toSet()
        return memos.count { (month == null || it.businessDate.take(7) == month) && it.voidOf == null && it.memoKind == "sale" && it.state != "rejected" && it.state != "uncertain" && it.state != "quarantined" && it.uuid !in added && it.uuid !in replaced &&
            !(throughDate != null && it.businessDate > throughDate && it.uuid in voided) /* a later-day memo that was voided is simply gone */ && !(throughDate != null && it.businessDate > throughDate && it.uuid in edited) /* a later-day memo with a live edit is counted by its edit */ && (it.state !in NEVER_SENT || it.supersedes != null) }
    }

    /**
     * The figure to show. [memoIn] picks the memos that belong to the figure (its route; for a campaign its outlet and programme), [lineIn] the sale lines that belong to it (its brand,
     * category or SKU); a memo counts once when it has at least one such line and its kind is `sale` (a zero-sale memo adds no memo count and no quantity). Never below zero.
     * [server] null (the bundle has no such section): null, nothing to show. No order or month: the server figure as it is, `localApplied` false.
     */
    fun show(
        server: ServerFigure?, memos: List<LocalMemo>, ackedBeforeRequest: Set<String>?, month: String?, throughDate: String?,
        memoIn: (LocalMemo) -> Boolean = { true }, lineIn: (LocalMemoLine) -> Boolean = { true },
    ): Shown? = show(server, plan(memos, ackedBeforeRequest, month, throughDate), memoIn, lineIn)

    /** What to add and subtract for ALL the phone's memos, computed ONCE (the maps of the chains and the taint are built here, not per figure); null when there is no month. */
    class Plan(val delta: Delta?)

    fun plan(memos: List<LocalMemo>, ackedBeforeRequest: Set<String>?, month: String?, throughDate: String?): Plan = Plan(delta(memos, ackedBeforeRequest, month, throughDate))

    /** One figure from a [plan] built once: the plan's memos filtered by [memoIn] (an edit stays in the route and outlet of the memo it edits), then [lineIn]. */
    fun show(server: ServerFigure?, plan: Plan, memoIn: (LocalMemo) -> Boolean = { true }, lineIn: (LocalMemoLine) -> Boolean = { true }): Shown? {
        server ?: return null
        val d = plan.delta?.let { Delta(it.add.filter(memoIn), it.subtract.filter(memoIn)) }
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

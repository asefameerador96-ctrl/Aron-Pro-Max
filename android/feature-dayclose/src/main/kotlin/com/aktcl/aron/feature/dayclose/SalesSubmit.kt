package com.aktcl.aron.feature.dayclose

/** Outbox state counts of the route-day (device side): rows by acknowledgement state. */
data class OutboxStates(val pending: Int, val inFlight: Int, val acked: Int, val rejected: Int, val quarantined: Int) {
    val total: Int get() = pending + inFlight + acked + rejected + quarantined
}

/**
 * The sync screen's reconciliation row (F-SR-034): device versus server count per record type. [server] is null before the
 * first successful sync (the Server column then shows blank with a timestamp, not zero).
 */
data class CountRow(val recordType: String, val device: Int, val server: Int?) {
    val matches: Boolean get() = server != null && server == device
}

/** One line of the reconciliation table as shown: a record type, or several grouped by [SubmitDisplay.views]. */
data class CountView(val key: String, val device: Int, val server: Int?, val matches: Boolean)

/**
 * DISPLAY ONLY grouping of the reconciliation rows (the AMO manual lists nine rows: Outlet, Sale, Stock, QC, Promotion,
 * Distribution and OOS, Price compliance, Joint call, Survey, while the phone counts one row per record type). The submit gate and
 * the day_submit payload read the per-type [CountRow]s and never see this: nothing here changes, sorts or drops them.
 */
object SubmitDisplay {
    /**
     * The rows to show. Without [groups] one view per row, in order. With [groups] (each group lists record types, the first one names
     * the group), one view per group that has any member, in the groups' order: device summed, server summed (unknown when any member's
     * server count is unknown), and `matches` only when EVERY member matches (a +1 and a -1 never cancel). Types in no group follow
     * as their own rows, in their original order, so no count is hidden.
     */
    /** The five rows of the legacy screen (sr/sales-submit.png): outlet, sale, stock, QC, promotion, in that order, those the phone counts. Display only; the gate still reads every per-type row. */
    val LEGACY_FIVE = listOf("visit", "memo", "stock_movement", "qc_line", "memo_discount")

    fun legacyFive(counts: List<CountRow>): List<CountView> =
        LEGACY_FIVE.mapNotNull { key -> counts.firstOrNull { it.recordType == key }?.let { CountView(it.recordType, it.device, it.server, it.matches) } }

    fun views(counts: List<CountRow>, groups: List<List<String>>?): List<CountView> {
        if (groups == null) return counts.map { CountView(it.recordType, it.device, it.server, it.matches) }
        val grouped = groups.flatten().toSet()
        val out = ArrayList<CountView>()
        for (g in groups) {
            val members = counts.filter { it.recordType in g }
            if (members.isEmpty()) continue
            out += CountView(
                g.first(), members.sumOf { it.device }, if (members.any { it.server == null }) null else members.sumOf { it.server!! },
                members.all { it.matches },
            )
        }
        counts.filter { it.recordType !in grouped }.forEach { out += CountView(it.recordType, it.device, it.server, it.matches) }
        return out
    }
}

/** Dues owed by retailers at submit: a warning, never a block (assumed rule, Q-UI-08). */
data class DuesAtSubmit(val retailersWithDues: Int, val outstandingMtk: Long)

sealed interface SubmitBlock {
    data class Unsynced(val pending: Int, val inFlight: Int) : SubmitBlock
    data class Rejected(val count: Int) : SubmitBlock
    data class Quarantined(val count: Int) : SubmitBlock
    data class CountMismatch(val recordType: String, val device: Int, val server: Int?) : SubmitBlock
    data object AlreadySubmitted : SubmitBlock
}

/** [notes] are answered-by-server rows (rejected, quarantined): shown and recorded in the submit, never blocking. */
data class SubmitGate(val enabled: Boolean, val blocks: List<SubmitBlock>, val duesWarning: DuesAtSubmit?, val notes: List<SubmitBlock> = emptyList())

object SalesSubmitRules {
    /**
     * Sales Submit is enabled when every record of the day is acknowledged and the per-type counts match the server's
     * (assumed rule, Q-UI-08). Outstanding dues add a warning and never disable the button. The submit itself is queued
     * offline as the last event of the day; the success text shows only once the server settles it.
     */
    fun gate(states: OutboxStates, counts: List<CountRow>, dues: DuesAtSubmit, alreadySubmitted: Boolean): SubmitGate {
        val blocks = ArrayList<SubmitBlock>()
        if (alreadySubmitted) blocks += SubmitBlock.AlreadySubmitted
        if (states.pending + states.inFlight > 0) blocks += SubmitBlock.Unsynced(states.pending, states.inFlight)
        // The server has already answered a rejected or quarantined row (docs/24 s4.12: reconciled = accepted + rejected + quarantined);
        // they can never be retried, so they must not lock the day. They are carried into the day_submit counts.
        val notes = ArrayList<SubmitBlock>()
        if (states.rejected > 0) notes += SubmitBlock.Rejected(states.rejected)
        if (states.quarantined > 0) notes += SubmitBlock.Quarantined(states.quarantined)
        counts.filterNot { it.matches }.forEach { blocks += SubmitBlock.CountMismatch(it.recordType, it.device, it.server) }
        return SubmitGate(blocks.isEmpty(), blocks, dues.takeIf { it.retailersWithDues > 0 || it.outstandingMtk > 0 }, notes)
    }

    /** The Sync data button retries until the counts agree; it is offered whenever the device and server disagree. */
    fun syncButtonEnabled(online: Boolean, syncing: Boolean): Boolean = online && !syncing
}

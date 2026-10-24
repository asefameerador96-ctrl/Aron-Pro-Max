package com.aktcl.aron.feature.dayclose

/** Outbox state counts of the route-day (device side): rows by acknowledgement state. */
data class OutboxStates(val pending: Int, val inFlight: Int, val acked: Int, val rejected: Int, val quarantined: Int) {
    val total: Int get() = pending + inFlight + acked + rejected + quarantined
}

/**
 * The sync screen's reconciliation row (F-SR-034): device versus server count per record type. [server] is null before the
 * first successful sync (the Server column then shows blank with a timestamp, not zero); it counts every upload of this login
 * for the date, so other phones and earlier installs of the same login add to it.
 *
 * D-NIGHT-DAYCLOSE-01 compare rule (device ⊆ server): the row is in sync when the server holds at least what this phone
 * committed; a server count above the device count is information ([serverExtra]), never a mismatch; a device count above
 * the server count is the mismatch (an unacknowledged row is the separate Unsynced block). [serverThisDevice] is the ADAPTER
 * for the per-device digest of contract 1.9 queue row 4: once the server reports this phone's own count, the rule compares
 * with that instead of the login total.
 */
data class CountRow(val recordType: String, val device: Int, val server: Int?, val serverThisDevice: Int? = null) {
    private val compareWith: Int? get() = serverThisDevice ?: server
    val matches: Boolean get() = compareWith?.let { it >= device } == true
    /** Rows the server has beyond this phone's (other phones or earlier installs of this login today); 0 when none or unknown. */
    val serverExtra: Int get() = server?.let { (it - device).coerceAtLeast(0) } ?: 0
}

/** What happened to a record the server did not take as sent (docs/24 s4.5), as the Sales Submit list says it. */
enum class RejectedOutcome {
    /** Quarantined: the server stored it and flagged it for review. Final: never blocks Sales Submit. */
    STORED_FLAGGED,
    /** Rejected: the server did not store it. Final: never blocks Sales Submit. */
    DROPPED,
    /** Answered with a retryable code and still queued on the phone: blocks Sales Submit until it is re-synced. */
    RETRY,
}

/**
 * One record of the phone's own ack registry (its outbox) that the server refused, holds or asked to retry, listed on the
 * Sales Submit screen instead of a bare count (D-NIGHT-DAYCLOSE-01 (b)). [memoNo] or [outletName] say which one it is;
 * [reasonBn]/[reasonEn] come from the bundle's `reason_texts` (the server's `ReasonTexts`), null when the code has none.
 */
data class RejectedRow(
    val clientUuid: String,
    val recordType: String,
    val businessDate: String,
    val code: String?,
    val outcome: RejectedOutcome,
    val memoNo: String? = null,
    val outletName: String? = null,
    val reasonBn: String? = null,
    val reasonEn: String? = null,
)

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

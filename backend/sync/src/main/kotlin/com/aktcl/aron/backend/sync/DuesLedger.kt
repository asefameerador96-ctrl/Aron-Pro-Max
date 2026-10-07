package com.aktcl.aron.backend.sync

import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.JsonObject
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Outstanding dues per outlet (F-SYS-060): `app.due_ledger` is append-only and one row per source record and kind, so a
 * replayed upload never posts twice. Written in the same transaction as the record that causes it:
 *
 * - a credit memo (`due_mtk > 0`) adds `memo_due` (+due);
 * - a `due_collection` adds `collection` (-amount) against its memo;
 * - a `memo_void` marks the memo voided (a tombstone, s4.9 rule 6) and adds `memo_void` (-the memo's open balance);
 * - an edited memo (`supersedes_client_uuid`) marks the original superseded and adds `memo_superseded` (-its open balance).
 *
 * A collection always pays its memo's outlet; one captured before its memo's void or edit but uploaded after it gives back
 * part of the closure, so every upload order ends at the in-order balance. An edit of a memo that is no longer active is
 * refused ([check]). [ageing] cancels voids and edits against their own memo and allocates collections FIFO.
 */
object DuesLedger {
    /** A memo's rows are read and closed under one lock, so a void, an edit and a collection never race on its balance. */
    private fun lockMemo(h: Handle, memo: String) { h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "memo:$memo") }

    private fun memoRow(h: Handle, memo: String): Pair<String, Long>? =
        h.createQuery("SELECT status, outlet_id FROM app.memo WHERE client_uuid = CAST(:m AS uuid) LIMIT 1").bind("m", memo)
            .map { rs, _ -> rs.getString(1) to rs.getLong(2) }.findOne().orElse(null)

    /**
     * Before store: an edit names an active memo. A second edit of one original, or an edit of a voided memo, would
     * make two live memos or bring a cancelled due back; it is refused `edit_not_allowed` and kept in sync_rejected.
     */
    fun check(h: Handle, type: String, p: JsonObject): Pair<RecordOutcomeCode, String>? {
        if (type != "memo") return null
        val old = p.str("supersedes_client_uuid") ?: return null
        lockMemo(h, old)
        val status = memoRow(h, old)?.first ?: return null // a missing original was parked by the parent check
        return if (status == "active") null else RecordOutcomeCode.EDIT_NOT_ALLOWED to "memo $old is $status"
    }

    fun afterStored(h: Handle, type: String, env: JsonObject, p: JsonObject) {
        val bd = env.str("business_date") ?: return
        val cu = env.str("client_uuid") ?: return
        when (type) {
            "memo" -> {
                p.str("supersedes_client_uuid")?.let { old ->
                    lockMemo(h, old)
                    h.createUpdate(
                        "UPDATE app.memo SET status = 'superseded', status_changed_at = now(), superseded_by_client_uuid = CAST(:n AS uuid) WHERE client_uuid = CAST(:o AS uuid) AND status = 'active'",
                    ).bind("n", cu).bind("o", old).execute()
                    closeMemoBalance(h, old, "memo_superseded", cu, bd)
                }
                val due = p.long("due_mtk") ?: 0
                if (due > 0) post(h, p.long("outlet_id")!!, bd, "memo_due", due, cu, p.str("memo_no"), cu)
            }
            "due_collection" -> {
                val amount = p.long("amount_mtk") ?: 0
                if (amount <= 0) return
                val memo = p.str("against_memo_client_uuid")
                if (memo == null) { post(h, p.long("outlet_id")!!, bd, "collection", -amount, null, p.str("against_memo_no"), cu); return }
                lockMemo(h, memo)
                // The memo's outlet, never the payload's: a collection pays its own memo's outlet.
                val (status, outlet) = memoRow(h, memo) ?: return
                post(h, outlet, bd, "collection", -amount, memo, p.str("against_memo_no"), cu)
                if (status != "active") reopenClosure(h, memo, outlet, env.str("captured_at"), cu)
            }
            "memo_void" -> {
                val memo = p.str("memo_client_uuid") ?: return
                lockMemo(h, memo)
                h.createUpdate(
                    "UPDATE app.memo SET status = 'voided', status_changed_at = now(), voided_by_client_uuid = CAST(:v AS uuid) WHERE client_uuid = CAST(:m AS uuid) AND status = 'active'",
                ).bind("v", cu).bind("m", memo).execute()
                closeMemoBalance(h, memo, "memo_void", cu, bd)
            }
        }
    }

    /** Takes the memo's open balance off the ledger (nothing when it has none). */
    private fun closeMemoBalance(h: Handle, memo: String, kind: String, source: String, bd: String) {
        val row = h.createQuery(
            """
            SELECT l.outlet_id, sum(l.amount_mtk), max(l.memo_no) FROM app.due_ledger l WHERE l.memo_client_uuid = CAST(:m AS uuid) GROUP BY l.outlet_id
            """.trimIndent(),
        ).bind("m", memo).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getString(3)) }.list()
        row.filter { it.second > 0 }.forEach { (outlet, open, memoNo) -> post(h, outlet, bd, kind, -open, memo, memoNo, source) }
    }

    /**
     * A collection captured before its memo's void or edit but uploaded after it: the closure took off the whole open
     * balance, so the memo now sits below zero by what was collected. In capture order the closure would have taken
     * off only what was left; an `adjustment` (note [LATE_COLLECTION]) gives back that much of the closure, at most
     * the closure itself, dated on the closure's business date, so every upload order ends at the in-order ledger on
     * every date. A collection captured after the void or edit (a stale list on another phone) is real cash against
     * the outlet and gives nothing back.
     */
    private fun reopenClosure(h: Handle, memo: String, outlet: Long, collectionCapturedAt: String?, source: String) {
        val closure = h.createQuery(
            """
            SELECT l.business_date::text, COALESCE(v.captured_at, m.captured_at)
            FROM app.due_ledger l
            LEFT JOIN app.memo_void v ON v.client_uuid = l.source_client_uuid AND l.entry_kind = 'memo_void'
            LEFT JOIN app.memo m ON m.client_uuid = l.source_client_uuid AND l.entry_kind = 'memo_superseded'
            WHERE l.memo_client_uuid = CAST(:m AS uuid) AND l.entry_kind IN ('memo_void', 'memo_superseded')
            ORDER BY l.id LIMIT 1
            """.trimIndent(),
        ).bind("m", memo).map { rs, _ -> rs.getString(1) to rs.getObject(2, java.time.OffsetDateTime::class.java)?.toInstant() }.findOne().orElse(null) ?: return
        val (closureDate, closureCapturedAt) = closure
        val collected = collectionCapturedAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        if (collected != null && closureCapturedAt != null && collected.isAfter(closureCapturedAt)) return
        val (balance, closed, reopened) = h.createQuery(
            """
            SELECT COALESCE(sum(amount_mtk), 0),
                   COALESCE(-sum(amount_mtk) FILTER (WHERE entry_kind IN ('memo_void', 'memo_superseded')), 0),
                   COALESCE(sum(amount_mtk) FILTER (WHERE entry_kind = 'adjustment' AND note = :n), 0)
            FROM app.due_ledger WHERE memo_client_uuid = CAST(:m AS uuid)
            """.trimIndent(),
        ).bind("m", memo).bind("n", LATE_COLLECTION).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.one()
        val give = minOf(-balance, closed - reopened)
        if (give > 0) post(h, outlet, closureDate, "adjustment", give, memo, null, source, LATE_COLLECTION)
    }

    const val LATE_COLLECTION = "late_collection_reopens_closure"

    private fun post(h: Handle, outlet: Long, bd: String, kind: String, amount: Long, memo: String?, memoNo: String?, source: String, note: String? = null) {
        h.createUpdate(
            """
            INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, memo_no, source_client_uuid, note)
            VALUES (:o, CAST(:d AS date), :k, :a, CAST(:m AS uuid), :no, CAST(:s AS uuid), :note)
            ON CONFLICT (source_client_uuid, entry_kind) DO NOTHING
            """.trimIndent(),
        ).bind("o", outlet).bind("d", bd).bind("k", kind).bind("a", amount).bind("m", memo).bind("no", memoNo).bind("s", source).bind("note", note).execute()
    }

    /** Ageing of one outlet on [asOf]: the four buckets of s12.3 `dues-ageing`, and the balance they sum to. */
    data class Ageing(val d0to7: Long, val d8to30: Long, val d31to60: Long, val d61plus: Long, val balance: Long, val credit: Long)

    /** One ledger row as the ageing reads it. */
    data class Entry(val date: LocalDate, val kind: String, val amount: Long, val memo: String? = null, val note: String? = null)

    /**
     * A void or an edit cancels its own memo (with the late-collection adjustments that give part of that back); only
     * collections and the other adjustments pay FIFO, oldest open debit first. What is left of each debit is aged by
     * `asOf - business_date`. The buckets sum to the balance; an overpaid outlet has empty buckets and the surplus in
     * [Ageing.credit] (so buckets + credit = balance).
     */
    fun ageing(h: Handle, outletId: Long, asOf: LocalDate): Ageing {
        val rows = h.createQuery("SELECT business_date, entry_kind, amount_mtk, memo_client_uuid::text, note FROM app.due_ledger WHERE outlet_id = :o AND business_date <= :a ORDER BY business_date, id")
            .bind("o", outletId).bind("a", asOf)
            .map { rs, _ -> Entry(rs.getObject(1, LocalDate::class.java), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)) }.list()
        return fifo(rows, asOf)
    }

    private fun cancelsOwnMemo(e: Entry) = e.memo != null && (e.kind == "memo_void" || e.kind == "memo_superseded" || (e.kind == "adjustment" && e.note == LATE_COLLECTION))

    /** Pure allocation over ledger entries, exposed for tests. */
    fun fifo(rows: List<Entry>, asOf: LocalDate): Ageing {
        val cancel = rows.filter(::cancelsOwnMemo).groupBy { it.memo!! }.mapValues { (_, es) -> es.sumOf { it.amount } }
        val usedCancel = HashSet<String>()
        var credits = 0L
        val debits = ArrayList<Pair<LocalDate, Long>>()
        for (e in rows) {
            if (cancelsOwnMemo(e)) continue
            if (e.kind == "memo_due" && e.memo != null && e.memo in cancel && usedCancel.add(e.memo)) {
                val net = e.amount + cancel.getValue(e.memo)
                if (net > 0) debits += e.date to net else credits += -net
            } else if (e.amount > 0) debits += e.date to e.amount
            else credits += -e.amount
        }
        // A cancellation whose memo_due is not in these rows (dated after asOf) counts as a plain credit.
        cancel.filterKeys { it !in usedCancel }.values.forEach { v -> if (v < 0) credits += -v else debits += asOf to v }
        val left = debits.sortedBy { it.first }.map { (d, amt) -> val paid = minOf(amt, credits); credits -= paid; d to (amt - paid) }
        val b = LongArray(4)
        left.forEach { (d, amt) ->
            val age = ChronoUnit.DAYS.between(d, asOf)
            b[when { age <= 7 -> 0; age <= 30 -> 1; age <= 60 -> 2; else -> 3 }] += amt
        }
        val balance = rows.sumOf { it.amount }
        return Ageing(b[0], b[1], b[2], b[3], balance, -credits)
    }
}

package com.aktcl.aron.backend.sync

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
 * [ageing] allocates the outlet's credits FIFO to its oldest debits and buckets what is left by age.
 */
object DuesLedger {
    fun afterStored(h: Handle, type: String, env: JsonObject, p: JsonObject) {
        val bd = env.str("business_date") ?: return
        val cu = env.str("client_uuid") ?: return
        when (type) {
            "memo" -> {
                p.str("supersedes_client_uuid")?.let { old ->
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
                if (amount > 0) post(h, p.long("outlet_id")!!, bd, "collection", -amount, p.str("against_memo_client_uuid"), p.str("against_memo_no"), cu)
            }
            "memo_void" -> {
                val memo = p.str("memo_client_uuid") ?: return
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

    private fun post(h: Handle, outlet: Long, bd: String, kind: String, amount: Long, memo: String?, memoNo: String?, source: String) {
        h.createUpdate(
            """
            INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, memo_no, source_client_uuid)
            VALUES (:o, CAST(:d AS date), :k, :a, CAST(:m AS uuid), :no, CAST(:s AS uuid))
            ON CONFLICT (source_client_uuid, entry_kind) DO NOTHING
            """.trimIndent(),
        ).bind("o", outlet).bind("d", bd).bind("k", kind).bind("a", amount).bind("m", memo).bind("no", memoNo).bind("s", source).execute()
    }

    /** Ageing of one outlet on [asOf]: the four buckets of s12.3 `dues-ageing`, and the balance they sum to. */
    data class Ageing(val d0to7: Long, val d8to30: Long, val d31to60: Long, val d61plus: Long, val balance: Long, val credit: Long)

    /**
     * FIFO: every credit (collection, void, supersession, negative adjustment) pays the oldest open debit first; what is
     * left of each debit is aged by `asOf - business_date`. The buckets sum to the balance; an overpaid outlet has
     * empty buckets and the surplus in [Ageing.credit] (so buckets + credit = balance).
     */
    fun ageing(h: Handle, outletId: Long, asOf: LocalDate): Ageing {
        val rows = h.createQuery("SELECT business_date, amount_mtk FROM app.due_ledger WHERE outlet_id = :o AND business_date <= :a ORDER BY business_date, id")
            .bind("o", outletId).bind("a", asOf).map { rs, _ -> rs.getObject(1, LocalDate::class.java) to rs.getLong(2) }.list()
        return fifo(rows, asOf)
    }

    /** Pure FIFO over (business_date, amount) rows, exposed for tests. */
    fun fifo(rows: List<Pair<LocalDate, Long>>, asOf: LocalDate): Ageing {
        var credits = rows.filter { it.second < 0 }.sumOf { -it.second }
        val left = rows.filter { it.second > 0 }.sortedBy { it.first }.map { (d, amt) ->
            val paid = minOf(amt, credits); credits -= paid; d to (amt - paid)
        }
        val b = LongArray(4)
        left.forEach { (d, amt) ->
            val age = ChronoUnit.DAYS.between(d, asOf)
            b[when { age <= 7 -> 0; age <= 30 -> 1; age <= 60 -> 2; else -> 3 }] += amt
        }
        val balance = rows.sumOf { it.second }
        return Ageing(b[0], b[1], b[2], b[3], balance, -credits)
    }
}

package com.aktcl.aron.backend.sync

import com.aktcl.aron.rules.DiscountKind
import com.aktcl.aron.rules.DiscountLine
import com.aktcl.aron.rules.MemoLine
import com.aktcl.aron.rules.MemoMath
import com.aktcl.aron.rules.Money
import com.aktcl.aron.rules.QcLine
import com.aktcl.aron.rules.StatedMemo
import kotlinx.serialization.json.JsonObject
import org.jdbi.v3.core.Handle

/**
 * Server recompute of memo arithmetic (F-SYS-062, docs/24 s7.3, s7.4) with the shared rules (`MemoMath`), so phone and
 * server compute the same numbers:
 *
 * - [familyMismatches]: a memo and its children in one family segment (the normal case: the phone holds a family until
 *   it is complete) are verified before anything is stored; on any failed equation the memo and every child are
 *   quarantined `arithmetic_mismatch` together, so a half-applied memo never exists.
 * - [recordMismatch]: equations one record shows on its own (memo header net/round/paid/due/credit, line gross).
 * - [afterChildStored]: a family split across batches is verified when its last child arrives; the memo already
 *   stored (it was printed) stays and gets `server_flags` `arithmetic_mismatch` for review.
 * - A line priced differently from the price list valid on `price_list_date` flags the memo `price_mismatch` and is
 *   never rejected: the printed memo is the fact.
 */
object MemoChecks {
    internal val CHILD_TYPES = setOf("memo_line", "memo_discount", "qc_line")

    /** client_uuid to mismatch detail for every record of a memo whose segment holds all its children and fails. */
    fun familyMismatches(records: List<JsonObject>): Map<String, String> {
        val out = HashMap<String, String>()
        for (memo in records.filter { it.str("type") == "memo" }) {
            val mu = memo.str("client_uuid") ?: continue
            val p = memo["payload"] as? JsonObject ?: continue
            val children = records.filter { it.str("type") in CHILD_TYPES && (it["payload"] as? JsonObject)?.str("memo_client_uuid") == mu }
            val counts = Triple(children.count { it.str("type") == "memo_line" }, children.count { it.str("type") == "memo_discount" }, children.count { it.str("type") == "qc_line" })
            val stated = Triple(p.int("line_count") ?: 0, p.int("discount_line_count") ?: 0, p.int("qc_line_count") ?: 0)
            if (counts.first > stated.first || counts.second > stated.second || counts.third > stated.third) {
                // More children than the header counts (s7.4 counts = the child records): never a split family.
                val detail = "memo $mu: children $counts exceed the stated counts $stated"
                out[mu] = detail
                children.forEach { c -> c.str("client_uuid")?.let { out[it] = detail } }
                continue
            }
            if (counts != stated) continue
            val problems = runCatching { verify(p, children.map { it.str("type")!! to (it["payload"] as JsonObject) }) }.getOrElse { listOf("unreadable: ${it.message}") }
            if (problems.isNotEmpty()) {
                val detail = "memo $mu: " + problems.joinToString("; ")
                out[mu] = detail
                children.forEach { c -> c.str("client_uuid")?.let { out[it] = detail } }
            }
        }
        return out
    }

    /**
     * A memo line naming an SKU the server does not have is rejected `unknown_sku` (s4.5); its memo and the other
     * children are then quarantined with it, so a memo is never stored with a line missing behind its gross.
     */
    fun unknownSkuSiblings(h: Handle, records: List<JsonObject>): Map<String, String> {
        val lines = records.filter { it.str("type") == "memo_line" }
        val skus = lines.mapNotNull { (it["payload"] as? JsonObject)?.long("sku_id") }.distinct()
        if (skus.isEmpty()) return emptyMap()
        val known = h.createQuery("SELECT id FROM app.sku WHERE id = ANY(:s)").bindArray("s", Long::class.javaObjectType, skus).mapTo(Long::class.java).set()
        return unknownSkuSiblings(known, records)
    }

    /** [unknownSkuSiblings] against SKU ids already read (the batch prefetch, SCALE-02). */
    fun unknownSkuSiblings(known: Set<Long>, records: List<JsonObject>): Map<String, String> {
        val lines = records.filter { it.str("type") == "memo_line" }
        val out = HashMap<String, String>()
        lines.filter { (it["payload"] as? JsonObject)?.long("sku_id")?.let { s -> s !in known } == true }.forEach { bad ->
            val memo = (bad["payload"] as JsonObject).str("memo_client_uuid") ?: return@forEach
            val detail = "memo $memo: line ${bad.str("client_uuid")} names an unknown SKU"
            out[memo] = detail
            records.filter { it.str("type") in CHILD_TYPES && (it["payload"] as? JsonObject)?.str("memo_client_uuid") == memo && it !== bad }
                .forEach { c -> c.str("client_uuid")?.let { out[it] = detail } }
        }
        return out
    }

    /**
     * Content fingerprint of a memo (s4.5 `content_duplicate`: same outlet, lines and minute): outlet, the minute of
     * `committed_at` and the sorted (sku, qty) of its lines in the segment. Only for a memo whose lines are all there.
     */
    fun memoFingerprints(records: List<JsonObject>): Map<String, ByteArray> = records.filter { it.str("type") == "memo" }.mapNotNull { memo ->
        val mu = memo.str("client_uuid") ?: return@mapNotNull null
        val p = memo["payload"] as? JsonObject ?: return@mapNotNull null
        val lines = records.filter { it.str("type") == "memo_line" && (it["payload"] as? JsonObject)?.str("memo_client_uuid") == mu }.map { it["payload"] as JsonObject }
        if (lines.size != p.int("line_count") || lines.isEmpty()) return@mapNotNull null
        val minute = p.str("committed_at")?.take(16) ?: return@mapNotNull null
        val body = "mfp1|${p.long("outlet_id")}|$minute|" + lines.map { "${it.long("sku_id")}:${it.long("qty_base")}:${it.str("line_kind")}" }.sorted().joinToString(",")
        mu to java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
    }.toMap()

    /** Equations a single record shows on its own; null when they hold (or the record is not money). */
    fun recordMismatch(type: String, p: JsonObject): String? = runCatching {
        when (type) {
            "memo" -> {
                val gross = p.long("gross_mtk")!!; val offer = p.long("offer_discount_mtk")!!; val drp = p.long("drp_discount_mtk")!!
                val qc = p.long("qc_deduction_mtk")!!; val round = p.long("round_adj_mtk")!!; val net = p.long("net_mtk")!!
                val paid = p.long("paid_mtk")!!; val due = p.long("due_mtk")!!
                val raw = gross - offer - drp - qc
                when {
                    net != Money.roundToPaisaHalfUp(raw) -> "net_mtk $net, recomputed ${Money.roundToPaisaHalfUp(raw)}"
                    round != net - raw -> "round_adj_mtk $round, recomputed ${net - raw}"
                    paid + due != net -> "paid_mtk + due_mtk ${paid + due} != net_mtk $net"
                    p.bool("is_credit") != (due > 0) -> "is_credit ${p.bool("is_credit")} with due_mtk $due"
                    else -> null
                }
            }
            "memo_line" -> {
                val want = Money.lineGrossMtk(p.long("qty_base")!!, p.long("base_price_mtk")!!, p.long("price_per_qty")!!)
                if (p.long("gross_mtk") != want) "line gross_mtk ${p.long("gross_mtk")}, recomputed $want" else null
            }
            else -> null
        }
    }.getOrElse { "unreadable money member" }

    private fun verify(memo: JsonObject, children: List<Pair<String, JsonObject>>): List<String> {
        val lines = children.filter { it.first == "memo_line" }.map { (_, c) ->
            MemoLine.priced(c.long("sku_id")!!, c.long("qty_base")!!, c.long("base_price_mtk")!!, c.long("price_per_qty")!!).copy(grossMtk = c.long("gross_mtk")!!)
        }
        val discounts = children.filter { it.first == "memo_discount" }.map { (_, c) ->
            DiscountLine(c.long("sku_id"), c.long("qty_base") ?: 0, c.long("value_mtk")!!, DiscountKind.entries.first { it.wire == c.str("kind") })
        }
        val qc = children.filter { it.first == "qc_line" }.map { (_, c) -> QcLine(c.long("settlement_mtk")!!, c.bool("applied_to_memo")!!) }
        val stated = StatedMemo(
            grossMtk = memo.long("gross_mtk")!!, offerDiscountMtk = memo.long("offer_discount_mtk")!!, drpDiscountMtk = memo.long("drp_discount_mtk")!!,
            qcDeductionMtk = memo.long("qc_deduction_mtk")!!, roundAdjMtk = memo.long("round_adj_mtk")!!, netMtk = memo.long("net_mtk")!!,
            paidMtk = memo.long("paid_mtk")!!, dueMtk = memo.long("due_mtk")!!, lineCount = memo.int("line_count")!!,
            discountLineCount = memo.int("discount_line_count")!!, qcLineCount = memo.int("qc_line_count")!!,
        )
        return MemoMath.verify(stated, lines, discounts, qc).map { "${it.field} stated ${it.stated}, recomputed ${it.computed}" }
    }

    /**
     * After a memo child is stored: price check of a line, and when the memo's children are now complete, the full
     * verification of a family that arrived split across batches (flag only; the memo is already stored).
     */
    fun afterChildStored(h: Handle, type: String, p: JsonObject) {
        val memo = p.str("memo_client_uuid") ?: return
        if (type == "memo_line") {
            val price = h.createQuery(
                """
                SELECT sp.amount_mtk, sp.per_base_qty FROM app.memo m
                JOIN app.sku_price sp ON sp.sku_id = :sku AND sp.price_type = m.price_type AND sp.valid_from <= m.price_list_date
                     AND (sp.valid_to IS NULL OR sp.valid_to > m.price_list_date)
                WHERE m.client_uuid = CAST(:m AS uuid) LIMIT 1
                """.trimIndent(),
            ).bind("sku", p.long("sku_id")).bind("m", memo).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.findOne().orElse(null)
            val stated = p.long("base_price_mtk") to p.long("price_per_qty")
            if (price == null || price != stated) flag(h, memo, "price_mismatch")
        }
        if (type !in CHILD_TYPES) return
        val split = h.createQuery(
            """
            SELECT m.line_count = (SELECT count(*) FROM app.memo_line l WHERE l.memo_client_uuid = m.client_uuid)
               AND m.discount_line_count = (SELECT count(*) FROM app.memo_discount d WHERE d.memo_client_uuid = m.client_uuid)
               AND m.qc_line_count = (SELECT count(*) FROM app.qc_entry_line q WHERE q.memo_client_uuid = m.client_uuid AND q.applied_to_memo) AS complete,
               m.gross_mtk = COALESCE((SELECT sum(l.gross_mtk) FROM app.memo_line l WHERE l.memo_client_uuid = m.client_uuid), 0)
               AND m.offer_discount_mtk = COALESCE((SELECT sum(d.value_mtk) FROM app.memo_discount d WHERE d.memo_client_uuid = m.client_uuid AND d.kind <> 'drp'), 0)
               AND m.drp_discount_mtk = COALESCE((SELECT sum(d.value_mtk) FROM app.memo_discount d WHERE d.memo_client_uuid = m.client_uuid AND d.kind = 'drp'), 0)
               AND m.qc_deduction_mtk = COALESCE((SELECT sum(q.settlement_mtk) FROM app.qc_entry_line q WHERE q.memo_client_uuid = m.client_uuid AND q.applied_to_memo), 0) AS sums_ok
            FROM app.memo m WHERE m.client_uuid = CAST(:m AS uuid) LIMIT 1
            """.trimIndent(),
        ).bind("m", memo).map { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }.findOne().orElse(null) ?: return
        if (split.first && !split.second) flag(h, memo, "arithmetic_mismatch")
        // More stored children than the header counts: a split family that went wrong.
        val over = h.createQuery(
            """
            SELECT m.line_count < (SELECT count(*) FROM app.memo_line l WHERE l.memo_client_uuid = m.client_uuid)
                OR m.discount_line_count < (SELECT count(*) FROM app.memo_discount d WHERE d.memo_client_uuid = m.client_uuid)
                OR m.qc_line_count < (SELECT count(*) FROM app.qc_entry_line q WHERE q.memo_client_uuid = m.client_uuid AND q.applied_to_memo)
            FROM app.memo m WHERE m.client_uuid = CAST(:m AS uuid) LIMIT 1
            """.trimIndent(),
        ).bind("m", memo).mapTo(Boolean::class.java).findOne().orElse(false)
        if (over) flag(h, memo, "arithmetic_mismatch")
    }

    /**
     * [afterChildStored] for every child stored by one batch unit in four statements, not three per line (SCALE-02):
     * the price check of all memo lines in one query, then the completeness, sums and over-count checks of every memo
     * touched in one query, then one flag update per flag. Same flags, same guards.
     */
    fun afterChildrenStored(h: Handle, children: List<Pair<String, JsonObject>>) {
        val lines = children.filter { it.first == "memo_line" && it.second.str("memo_client_uuid") != null }
        val priceBad = LinkedHashSet<String>()
        if (lines.isNotEmpty()) {
            priceBad += h.createQuery(
                """
                SELECT DISTINCT x.memo FROM unnest(CAST(:memo AS text[]), CAST(:sku AS bigint[]), CAST(:price AS bigint[]), CAST(:per AS bigint[])) AS x(memo, sku, price, per)
                LEFT JOIN app.memo m ON m.client_uuid = CAST(x.memo AS uuid)
                LEFT JOIN LATERAL (SELECT sp.amount_mtk, sp.per_base_qty FROM app.sku_price sp WHERE sp.sku_id = x.sku AND sp.price_type = m.price_type
                                   AND sp.valid_from <= m.price_list_date AND (sp.valid_to IS NULL OR sp.valid_to > m.price_list_date) LIMIT 1) sp ON true
                WHERE sp.amount_mtk IS NULL OR sp.amount_mtk IS DISTINCT FROM x.price OR sp.per_base_qty IS DISTINCT FROM x.per
                """.trimIndent(),
            ).bindArray("memo", String::class.java, lines.map { it.second.str("memo_client_uuid")!! })
                .bindArray("sku", Long::class.javaObjectType, lines.map { it.second.long("sku_id") })
                .bindArray("price", Long::class.javaObjectType, lines.map { it.second.long("base_price_mtk") })
                .bindArray("per", Long::class.javaObjectType, lines.map { it.second.long("price_per_qty") })
                .mapTo(String::class.java).list()
        }
        val memos = children.filter { it.first in CHILD_TYPES }.mapNotNull { it.second.str("memo_client_uuid") }.distinct()
        val arithBad = LinkedHashSet<String>()
        if (memos.isNotEmpty()) {
            arithBad += h.createQuery(
                """
                SELECT m.client_uuid::text FROM app.memo m
                CROSS JOIN LATERAL (
                  SELECT (SELECT count(*) FROM app.memo_line l WHERE l.memo_client_uuid = m.client_uuid) AS n_lines,
                         (SELECT count(*) FROM app.memo_discount d WHERE d.memo_client_uuid = m.client_uuid) AS n_disc,
                         (SELECT count(*) FROM app.qc_entry_line q WHERE q.memo_client_uuid = m.client_uuid AND q.applied_to_memo) AS n_qc,
                         COALESCE((SELECT sum(l.gross_mtk) FROM app.memo_line l WHERE l.memo_client_uuid = m.client_uuid), 0) AS s_gross,
                         COALESCE((SELECT sum(d.value_mtk) FROM app.memo_discount d WHERE d.memo_client_uuid = m.client_uuid AND d.kind <> 'drp'), 0) AS s_offer,
                         COALESCE((SELECT sum(d.value_mtk) FROM app.memo_discount d WHERE d.memo_client_uuid = m.client_uuid AND d.kind = 'drp'), 0) AS s_drp,
                         COALESCE((SELECT sum(q.settlement_mtk) FROM app.qc_entry_line q WHERE q.memo_client_uuid = m.client_uuid AND q.applied_to_memo), 0) AS s_qc
                ) c
                WHERE m.client_uuid = ANY(CAST(:m AS uuid[]))
                  AND ((m.line_count = c.n_lines AND m.discount_line_count = c.n_disc AND m.qc_line_count = c.n_qc
                        AND NOT (m.gross_mtk = c.s_gross AND m.offer_discount_mtk = c.s_offer AND m.drp_discount_mtk = c.s_drp AND m.qc_deduction_mtk = c.s_qc))
                       OR m.line_count < c.n_lines OR m.discount_line_count < c.n_disc OR m.qc_line_count < c.n_qc)
                """.trimIndent(),
            ).bindArray("m", String::class.java, memos).mapTo(String::class.java).list()
        }
        if (priceBad.isNotEmpty()) flagAll(h, priceBad, "price_mismatch")
        if (arithBad.isNotEmpty()) flagAll(h, arithBad, "arithmetic_mismatch")
    }

    private fun flagAll(h: Handle, memos: Collection<String>, flag: String) {
        h.createUpdate(
            "UPDATE app.memo SET server_flags = array_append(COALESCE(server_flags, '{}'), :f) WHERE client_uuid = ANY(CAST(:m AS uuid[])) AND NOT (:f = ANY(COALESCE(server_flags, '{}')))",
        ).bind("f", flag).bindArray("m", String::class.java, memos.toList()).execute()
    }

    private fun flag(h: Handle, memo: String, flag: String) {
        h.createUpdate(
            "UPDATE app.memo SET server_flags = array_append(COALESCE(server_flags, '{}'), :f) WHERE client_uuid = CAST(:m AS uuid) AND NOT (:f = ANY(COALESCE(server_flags, '{}')))",
        ).bind("f", flag).bind("m", memo).execute()
    }
}

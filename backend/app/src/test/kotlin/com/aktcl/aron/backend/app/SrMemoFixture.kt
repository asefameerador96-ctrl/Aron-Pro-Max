package com.aktcl.aron.backend.app

/**
 * Real accepted memos for the SR programme tests (D-TKPI-12): the server figures are read straight from `app.memo` / `app.memo_line`, so the tests write memos, not
 * `dw.agg_*` rows. Quantities are entered in sticks (`pack_factor` 1); price 100 mtk per base unit so the header equations hold. `emit` also writes the outbox event
 * the aggregation worker projects (the dashboards' aggregate), so a test can drive the projector and the rebuild at chosen moments.
 */
class SrMemoFixture(private val exec: (String) -> Unit, private val userId: Long, seqStart: Int = 0) {
    class L(val sku: Long, val qty: Int, val kind: String = "sale")
    private var seq = seqStart

    fun memo(
        uuid: String, route: Long, outlet: Long, date: String, lines: List<L>, receivedAt: String = "now()", supersedes: String? = null, emit: Boolean = false,
        memoNo: String? = null, manualBy: Long? = null,
    ) {
        val no = memoNo ?: "sr1001-${date.substring(2).replace("-", "")}-${"%03d".format(++seq)}"
        val gross = lines.filter { it.kind == "sale" }.sumOf { it.qty.toLong() * 100 }
        exec(
            """
            INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at,
                                  price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit,
                                  line_count, discount_line_count, qc_line_count, status, supersedes_client_uuid, edit_reason_code, received_at, entry_source, entered_by_user_id)
            VALUES (CAST('$uuid' AS uuid), CAST('$uuid' AS uuid), DATE '$date', $userId, $route, TIMESTAMPTZ '$date 10:05:00+06', 0, gen_random_uuid(), $outlet, '$no', 'sale',
                    TIMESTAMPTZ '$date 10:05:00+06', DATE '$date', 'outlet', $gross, 0, 0, 0, 0, $gross, $gross, 0, false, ${lines.size}, 0, 0, 'active',
                    ${supersedes?.let { "CAST('$it' AS uuid)" } ?: "NULL"}, ${if (supersedes != null) "'wrong_quantity'" else "NULL"}, $receivedAt, ${if (manualBy != null) "'manual'" else "'device'"}, ${manualBy ?: "NULL"})
            """.trimIndent(),
        )
        lines.forEachIndexed { i, l ->
            exec(
                """
                INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id, line_kind, qty_entered, unit_entered,
                                           pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk)
                VALUES (gen_random_uuid(), gen_random_uuid(), DATE '$date', $userId, $route, TIMESTAMPTZ '$date 10:05:00+06', 0, CAST('$uuid' AS uuid), ${i + 1}, ${l.sku}, '${l.kind}', ${l.qty}, 'stick',
                        1, ${l.qty}, 'outlet', DATE '$date', ${if (l.kind == "sale") 100 else 0}, 1, ${if (l.kind == "sale") l.qty * 100 else 0})
                """.trimIndent(),
            )
        }
        if (supersedes != null) exec("UPDATE app.memo SET status = 'superseded', status_changed_at = now(), superseded_by_client_uuid = CAST('$uuid' AS uuid) WHERE client_uuid = CAST('$supersedes' AS uuid)")
        if (emit) event("memo.created", uuid, date)
    }

    /** [n] one-line sale memos of [sku] on [date] adding up to [total] sticks (each at least 1); uuids `00000000-0000-4000-8000-<tag padded to 8 hex digits><4-digit index>`. */
    fun bulk(tag: String, route: Long, outlet: Long, date: String, sku: Long, n: Int, total: Int, receivedAt: String = "now()") {
        require(total >= n)
        for (i in 0 until n) memo("00000000-0000-4000-8000-%s%04d".format(tag.padEnd(8, '0'), i), route, outlet, date, listOf(L(sku, if (i < n - 1) total / n else total - (total / n) * (n - 1))), receivedAt)
    }

    /** The admin/phone void of a whole memo (`status = 'voided'`, the same columns the ingest sets). */
    fun void(uuid: String, date: String, emit: Boolean = false) {
        exec("UPDATE app.memo SET status = 'voided', status_changed_at = now(), voided_by_client_uuid = gen_random_uuid() WHERE client_uuid = CAST('$uuid' AS uuid)")
        if (emit) event("memo.voided", uuid, date)
    }

    fun event(type: String, uuid: String, date: String) =
        exec("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid, payload) VALUES ('$type', split_part('$type', '.', 1), '$uuid', DATE '$date', CAST('$uuid' AS uuid), '{}'::jsonb)")
}

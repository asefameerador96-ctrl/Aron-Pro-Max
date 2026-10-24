package com.aktcl.aron.backend.app

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * The trace's sync step under concurrency and batch_uuid reuse (docs/24 s2: a retried, duplicated or concurrent upload never creates a
 * second sale or doubles a number). One GUL-SR-2 day of sr1001, three memos, then:
 *  1. eight twins send the whole day at once under eight batch_uuids: each record is accepted exactly once across all answers;
 *  2. a second day's worth of twins send under ONE batch_uuid: no 5xx, the day is stored once;
 *  3. a batch_uuid reused for another record set answers 409 ERR_SYNC_BATCH_UUID_REUSED and stores nothing of the second set;
 *  4. a changed copy of a stored record is quarantined payload_conflict and changes no figure.
 * After each step the rows per table, server_totals and the warehouse equal the device.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DataFlowTraceConcurrencyTest : DataFlowTraceSupport() {
    override val traceName get() = "data-flow-trace-concurrency"
    private lateinit var recs: List<ObjectNode>
    private val twins = 8

    private fun parallel(n: Int, call: (Int) -> Answer): List<Answer> {
        val pool = Executors.newFixedThreadPool(n)
        val gate = CyclicBarrier(n)
        try { return pool.invokeAll((0 until n).map { i -> Callable { gate.await(); call(i) } }).map { it.get() } } finally { pool.shutdown() }
    }

    private fun count(table: String) = sqlLong("SELECT count(*) FROM $table t JOIN app.app_user u ON u.id = t.user_id WHERE u.username = 'sr1001' AND t.business_date = DATE '$day'")
    private fun deviceMatchesServer(label: String) {
        for ((t, tbl) in listOf("visit" to "app.visit", "memo" to "app.memo", "memo_line" to "app.memo_line", "due_collection" to "app.due_collection"))
            hop("$label: $t rows, device vs $tbl", "device" to sr.counts[t], "server" to count(tbl))
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val money = send(sr, recs.take(1), uuid()).body!!.path("server_totals").single { it.path("business_date").asText() == day }.path("money")
        hop("$label: server_totals gross / net / paid / due", "server" to listOf("gross_mtk", "net_mtk", "paid_mtk", "due_mtk").map { money.path(it).asLong() },
            "device" to listOf(active.sumOf { it.gross }, active.sumOf { it.net }, active.sumOf { it.paid }, active.sumOf { it.due }))
    }

    @Test
    @Order(1)
    fun eightTwinsUnderEightBatchUuids() {
        h("1 Eight concurrent twins, eight batch_uuids, the same day")
        prepareSrDay()
        val cig20 = pick("cigarette", 20); val cig10 = pick("cigarette", 10); val bidi = pick("bidi"); val lighter = pick("lighter"); val match = pick("match")
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted().take(3)
        sr.memos += Memo(outletByCode(codes[0]), codes[0], listOf(Line(cig20, 3), Line(bidi, 1), Line(match, 2)), paidShare = 100, minute = 0)
        sr.memos += Memo(outletByCode(codes[1]), codes[1], listOf(Line(cig10, 4), Line(lighter, 1)), paidShare = 40, minute = 40)
        sr.memos += Memo(outletByCode(codes[2]), codes[2], listOf(Line(cig20, 1)), paidShare = 100, minute = 80)
        val list = mutableListOf<ObjectNode>()
        sr.memos.forEachIndexed { i, m -> val (r, a) = radiusOf(m.outlet); list += recordsOf(sr, m, gulPins.getValue(m.outlet), r, a, i + 1) }
        val credit = sr.memos[1]; val cu = uuid()
        list += envelope(sr, "due_collection", cu, cu, 0, 100, json.readTree(
            """{"outlet_id":${credit.outlet},"against_memo_client_uuid":"${credit.memo}","against_memo_no":"${list.first { it.path("client_uuid").asText() == credit.memo }.path("payload").path("memo_no").asText()}",
                "against_memo_business_date":"$day","amount_mtk":1000,"is_full_settlement":false,"outstanding_before_mtk":${credit.due},"payment_mode":"cash"}"""))
        recs = list
        list.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts[t] = n }
        val answers = parallel(twins) { send(sr, recs, uuid()) }
        hop("every twin answers 200", "statuses" to answers.map { it.status }.distinct(), "expected" to listOf(200))
        val accepted = recs.associate { it.path("client_uuid").asText() to 0 }.toMutableMap()
        val others = mutableSetOf<String>()
        for (a in answers) for (k in a.body!!.path("acks")) {
            val st = k.path("status").asText()
            if (st == "accepted") accepted.merge(k.path("client_uuid").asText(), 1, Int::plus) else others += st
        }
        hop("each record is accepted exactly once across the eight answers", "accepted counts" to accepted.values.distinct(), "expected" to listOf(1))
        hop("every other answer for a record is a duplicate", "other statuses" to others.sorted(), "expected" to listOf("duplicate"))
        deviceMatchesServer("after the twins")
        hop("the ingest registry holds each client_uuid once", "rows" to sqlLong("SELECT count(*) FROM app.ingest_registry r WHERE r.client_uuid = ANY(ARRAY[${recs.joinToString(",") { "'" + it.path("client_uuid").asText() + "'::uuid" }}])"), "uuids" to recs.size.toLong())
    }

    @Test
    @Order(2)
    fun twinsUnderOneBatchUuid() {
        h("2 Eight concurrent sends under ONE batch_uuid, the same set")
        val bu = uuid()
        val answers = parallel(twins) { send(sr, recs, bu) }
        note("statuses", answers.map { it.status })
        hop("no answer is a 5xx", "server errors" to answers.count { it.status >= 500 }, "expected" to 0)
        hop("every answer is 200 and replayed (the day was already stored by step 1; this batch_uuid is new, so one answer processes it)", "answers" to answers.map { it.status }.distinct(), "expected" to listOf(200), hard = false)
        note("replayed flags", answers.map { it.body?.path("replayed")?.asText() })
        hop("the processing answer reports every record duplicate", "statuses" to answers.flatMap { a -> a.body!!.path("acks").map { it.path("status").asText() } }.distinct().sorted(), "expected" to listOf("duplicate"))
        deviceMatchesServer("after the one-batch_uuid twins")
    }

    @Test
    @Order(3)
    fun aReusedBatchUuidForAnotherSet() {
        h("3 A batch_uuid reused for another record set")
        val bu = uuid()
        val first = send(sr, recs.take(2), bu)
        hop("the first set under the batch_uuid is answered 200", "status" to first.status, "expected" to 200)
        val extra = Memo(outletByCode(route.path("outlets").map { it.path("code").asText() }.sorted()[3]), route.path("outlets").map { it.path("code").asText() }.sorted()[3], listOf(Line(pick("cigarette", 20), 2)), paidShare = 100, minute = 120)
        val (r, a) = radiusOf(extra.outlet)
        val extraRecs = recordsOf(sr, extra, gulPins.getValue(extra.outlet), r, a, 4)
        val reused = send(sr, recs.take(2) + extraRecs, bu)
        hop("another set under the same batch_uuid answers 409", "status/code" to "${reused.status} ${runCatching { json.readTree(reused.text).path("code").asText("") }.getOrDefault("")}", "expected" to "409 ERR_SYNC_BATCH_UUID_REUSED")
        hop("nothing of the second set is stored", "memo rows for the extra outlet" to sqlLong("SELECT count(*) FROM app.memo WHERE client_uuid = '${extra.memo}'::uuid"), "expected" to 0L)
        val again = send(sr, extraRecs, uuid())
        hop("the extra memo under a new batch_uuid is accepted", "statuses" to acks(again).distinct(), "expected" to listOf("accepted"))
        sr.memos += extra
        recs = recs + extraRecs
        extraRecs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts.merge(t, n, Int::plus) }
        deviceMatchesServer("after the reuse and the late memo")
    }

    @Test
    @Order(4)
    fun aChangedCopyOfAStoredRecord() {
        h("4 A changed copy of a stored record (same client_uuid, other content)")
        val line = recs.first { it.path("type").asText() == "memo_line" }
        val changed = line.deepCopy().also { (it.path("payload") as ObjectNode).put("qty_entered", line.path("payload").path("qty_entered").asInt() + 1) }
        val a = send(sr, listOf(changed), uuid())
        hop("the changed copy is quarantined as payload_conflict", "ack" to ackOf(a, "memo_line"), "expected" to "quarantined payload_conflict")
        deviceMatchesServer("after the changed copy")
        runWorker()
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val ar = rows("SELECT gross_mtk, net_mtk, paid_mtk, due_mtk FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").single()
        hop("dw.agg_daily_route gross / net / paid / due", "dw" to listOf("gross_mtk", "net_mtk", "paid_mtk", "due_mtk").map { (ar[it] as Number).toLong() },
            "device" to listOf(active.sumOf { it.gross }, active.sumOf { it.net }, active.sumOf { it.paid }, active.sumOf { it.due }))
    }
}

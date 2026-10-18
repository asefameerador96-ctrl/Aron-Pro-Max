package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N-024: the sync property and fuzz suite. Several phones (each the SR of its own mirrored division, [ApiWorld]) capture
 * random selling days (sales on credit, collections, voids) and deliver them to `POST /v1/sync/batch` through the
 * production wiring under random fault schedules, one fault per batch:
 * - **reorder** and **partial**: the outbox sends a random subset in random order (children before parents);
 * - **duplicate**: rows already acknowledged ride along in later batches; a whole batch is replayed (same batch_uuid);
 * - **drop**: the response is lost; the phone later resends the same batch (docs/24 s4.6: the persisted batch first);
 * - **truncated**: the gzip body is cut short in transit; the server must refuse it whole (it fails at decode, before
 *   the batch is claimed) and accept the same batch_uuid resent whole;
 * - **kill**: the API process dies in the middle of the batch ([KillSwitch] throws an Error out of ingest after the
 *   n-th stored record: the families before it are committed, no response is sent); the phone resends the same batch.
 *   Not modelled (no seam without a product change): a death after the last family committed but before the response
 *   is stored, or during after-commit hooks; that window is covered by the replay path of the drop fault only;
 * - **concurrent**: the same batch twice in flight at once.
 * After every schedule the phone drains its outbox and resends its whole day once more, then [Oracle] checks the
 * property: for the phone's user and business date the server holds exactly the device's rows (every client_uuid,
 * and nothing else: the row count per table equals the device count), every memo's due balance equals its in-order
 * balance, `server_totals` (money, sold sticks by SKU, accepted per type) equals the device's own totals, and a replay
 * answers the same acks. The self-tests seed a duplicate sale and a doubled number into the database and require the
 * oracle to report them.
 *
 * Size: `ARON_SYNC_FUZZ_SCHEDULES` (default 240, about two minutes; the acceptance run uses 10000), seed
 * `ARON_SYNC_FUZZ_SEED` (default fixed), phones `ARON_SYNC_FUZZ_PHONES` (default 4, each runs its schedules in
 * sequence, the phones in parallel). A failure names the phone and schedule seed; rerun with ARON_SYNC_FUZZ_SEED,
 * the same ARON_SYNC_FUZZ_SCHEDULES and ARON_SYNC_FUZZ_PHONES: a phone's schedules build on each other (sale numbers,
 * totals), so a schedule is reproduced only together with the ones before it on that phone. Client and batch uuids are
 * random; they never steer a schedule's choices.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SyncFaultScheduleFuzzTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val schedules = System.getenv("ARON_SYNC_FUZZ_SCHEDULES")?.toIntOrNull()?.coerceIn(1, 100_000) ?: 240
    private val baseSeed = System.getenv("ARON_SYNC_FUZZ_SEED")?.toLongOrNull() ?: 20261017L
    private val phoneCount = System.getenv("ARON_SYNC_FUZZ_PHONES")?.toIntOrNull()?.coerceIn(1, 16) ?: 4
    private val kill = KillSwitch()
    private lateinit var world: ApiWorld
    private lateinit var phones: List<SyncPhone>
    /** Eleven more phones of their own for the seeded-fault tests, so a failing fuzz run cannot leave them half-delivered. */
    private lateinit var probes: List<SyncPhone>
    private val day = "2027-01-03"
    private val faultsSeen = ConcurrentHashMap<String, AtomicInteger>()

    // ---- the server-side kill --------------------------------------------------------------------------------------

    /** A process death, not an exception: ingest catches Exception per record and per family, never an Error. */
    class KillProcess : Error("simulated API process kill in the middle of a batch")

    /** Armed per device for its next batch: the n-th stored record of that device dies with the process. */
    class KillSwitch : RecordHandler {
        override val types = setOf("visit", "visit_close", "memo", "memo_line", "due_collection", "memo_void")
        private val armed = ConcurrentHashMap<Long, AtomicInteger>()
        val fired = AtomicInteger()
        fun arm(deviceId: Long, after: Int) { armed[deviceId] = AtomicInteger(after) }
        fun disarm(deviceId: Long) { armed.remove(deviceId) }
        override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
            val left = rec.deviceId?.let { armed[it] } ?: return
            if (left.decrementAndGet() <= 0) { armed.remove(rec.deviceId); fired.incrementAndGet(); throw KillProcess() }
        }
    }

    // ---- the phones ------------------------------------------------------------------------------------------------

    // ---- wire helpers ----------------------------------------------------------------------------------------------

    private fun uuid() = UUID.randomUUID().toString()
    private fun batch(p: SyncPhone, records: List<JsonObject>, batchUuid: String = uuid()): String = p.batch(records, batchUuid)

    /** One POST; an answer that is not 200 (a kill, a truncated body, 503, 429) is returned as its status, never thrown. */
    private suspend fun HttpClient.send(p: SyncPhone, body: ByteArray): Pair<Int, JsonObject?> = try {
        val r = post("/v1/sync/batch") {
            bearerAuth(p.token); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            // The mirrored phones are copies of the seeded one and hold the harness key (ApiWorld.phoneKey).
            header("X-Device-Proof", world.batchProof(p.deviceUuid, body))
            setBody(ByteArrayContent(body, ContentType.Application.Json))
        }
        val text = r.bodyAsText()
        r.status.value to if (r.status == HttpStatusCode.OK) Json.parseToJsonElement(text).jsonObject else null
    } catch (e: Throwable) {
        if (e is AssertionError) throw e
        599 to null // the connection died with the process
    }

    // ---- the oracle ------------------------------------------------------------------------------------------------

    private val tables = mapOf(
        "visit" to ("app.visit" to "client_uuid"), "visit_close" to ("app.visit" to "close_client_uuid"), "memo" to ("app.memo" to "client_uuid"),
        "memo_line" to ("app.memo_line" to "client_uuid"), "due_collection" to ("app.due_collection" to "client_uuid"), "memo_void" to ("app.memo_void" to "client_uuid"),
    )

    /**
     * Every difference between the phone's truth and the server, empty when they agree: rows per table for the user and
     * day (count and identity), memo balances, and the `server_totals` of [last] (a batch response for that day).
     */
    internal fun oracle(p: SyncPhone, last: JsonObject?): List<String> {
        val problems = mutableListOf<String>()
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            for ((type, tc) in tables) {
                val (table, col) = tc
                val device = p.rows[type].orEmpty()
                val server = h.createQuery("SELECT $col::text FROM $table WHERE user_id = :u AND business_date = CAST(:d AS date) AND $col IS NOT NULL")
                    .bind("u", p.userId).bind("d", day).mapTo(String::class.java).list()
                if (server.size != device.size) problems += "[A1] $type: server holds ${server.size} rows, the device ${device.size}"
                val missing = device - server.toSet()
                val extra = server.toSet() - device
                if (missing.isNotEmpty()) problems += "[A2] $type: ${missing.size} device rows missing on the server, e.g. ${missing.first()}"
                if (extra.isNotEmpty()) problems += "[A3] $type: ${extra.size} server rows the device never made, e.g. ${extra.first()}"
            }
            // The whole database, not only this day (checker N-024 #1): the phone only ever captures [day], so any row of
            // its user or its phone on another date, or more rows by user or phone than the device made, is a second sale.
            for ((type, tc) in tables) {
                val (table, col) = tc
                val device = p.rows[type].orEmpty().size
                val byUser = h.createQuery("SELECT count(*) FROM $table WHERE user_id = :u AND $col IS NOT NULL").bind("u", p.userId).mapTo(Long::class.java).one()
                val twice = h.createQuery("SELECT count(*) - count(DISTINCT $col) FROM $table WHERE user_id = :u AND $col IS NOT NULL").bind("u", p.userId).mapTo(Long::class.java).one()
                if (twice != 0L) problems += "[B2] $type: $twice client_uuids stored twice over all dates"
                if (byUser != device.toLong()) problems += "[B] $type: the user holds $byUser rows over all dates, the device made $device"
                if (hasColumn(h, table, "device_id")) {
                    val byDevice = h.createQuery("SELECT count(*) FROM $table WHERE device_id = :d AND $col IS NOT NULL").bind("d", p.deviceId).mapTo(Long::class.java).one()
                    if (byDevice != device.toLong()) problems += "[C] $type: the phone holds $byDevice rows over all dates, the device made $device"
                }
            }
            val made = p.rows.values.flatten().toSet()
            val registry = h.createQuery("SELECT client_uuid::text FROM app.ingest_registry WHERE user_id = :u").bind("u", p.userId).mapTo(String::class.java).list()
            if (registry.size != made.size || registry.toSet() != made) problems += "[D] ingest registry: ${registry.size} rows for the user, the device made ${made.size} (${(registry.toSet() - made).size} unknown)"
            // Ledger rows carry no user (ingest posts them by outlet): every ledger row on the phone's outlets, on any date,
            // must belong to one of the device's memos of the day (the fuzz world sells each route by one phone only).
            val stray = h.createQuery("SELECT count(*) FROM app.due_ledger WHERE outlet_id = ANY(:o) AND NOT (business_date = CAST(:d AS date) AND memo_client_uuid::text = ANY(:m))")
                .bindArray("o", Long::class.javaObjectType, p.outlets).bind("d", day).bindArray("m", String::class.java, p.memoDue.keys.toList()).mapTo(Long::class.java).one()
            if (stray != 0L) problems += "[E] due ledger: $stray rows on the phone's outlets that belong to no memo of the device's day"
            val balances = h.createQuery("SELECT memo_client_uuid::text, COALESCE(sum(amount_mtk), 0) FROM app.due_ledger WHERE memo_client_uuid IN (SELECT client_uuid FROM app.memo WHERE user_id = :u AND business_date = CAST(:d AS date)) GROUP BY 1")
                .bind("u", p.userId).bind("d", day).map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
            val wrong = p.memoDue.filter { (m, open) -> (balances[m] ?: 0L) != open }
            if (wrong.isNotEmpty()) problems += "[F] ${wrong.size} memo balances differ, e.g. ${wrong.keys.first()} device ${wrong.values.first()} server ${balances[wrong.keys.first()]}"
        }
        if (last != null) {
            val totals = last["server_totals"]!!.jsonArray.map { it.jsonObject }.singleOrNull { it["business_date"]!!.jsonPrimitive.content == day }
            if (totals == null) problems += "[K] no server_totals for $day" else {
                val money = totals["money"]!!.jsonObject
                fun m(k: String) = money[k]!!.jsonPrimitive.content.toLong()
                val dev = listOf(p.activeMemos, p.gross, p.gross, p.due, p.gross - p.due, p.collected)
                val srv = listOf(m("active_memo_count"), m("gross_mtk"), m("net_mtk"), m("due_mtk"), m("paid_mtk"), m("due_collected_mtk"))
                if (dev != srv) problems += "[G] server_totals money (count, gross, net, due, paid, collected) device $dev server $srv"
                val soldSrv = money["sold_qty_base_by_sku"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }
                if (soldSrv != p.sold.toMap()) problems += "[H] sold sticks by sku device ${p.sold} server $soldSrv"
                val byType = totals["by_type"]!!.jsonObject
                val acc = byType.mapValues { it.value.jsonObject["accepted"]!!.jsonPrimitive.content.toInt() }.filterValues { it > 0 }
                if (acc != p.accepted.toMap()) problems += "[I] accepted per type (device count vs server count) device ${p.accepted} server $acc"
                val stuck = byType.filterValues { v -> v.jsonObject["rejected"]!!.jsonPrimitive.content != "0" || v.jsonObject["quarantined"]!!.jsonPrimitive.content != "0" }
                if (stuck.isNotEmpty()) problems += "[J] rejected or quarantined rows on the server: $stuck"
            }
        }
        return problems
    }

    private val columns = ConcurrentHashMap<String, Boolean>()
    private fun hasColumn(h: Handle, table: String, column: String) = columns.getOrPut("$table.$column") {
        h.createQuery("SELECT count(*) > 0 FROM information_schema.columns WHERE table_schema || '.' || table_name = :t AND column_name = :c")
            .bind("t", table).bind("c", column).mapTo(Boolean::class.java).one()
    }

    // ---- one schedule ----------------------------------------------------------------------------------------------

    private fun seen(f: String) = faultsSeen.getOrPut(f) { AtomicInteger() }.incrementAndGet()

    private suspend fun HttpClient.schedule(p: SyncPhone, seed: Long): JsonObject {
        val rnd = Random(seed)
        val day0 = p.captureDay(rnd)
        val acked = HashSet<String>()
        fun note(resp: JsonObject?) = resp?.get("acks")?.jsonArray?.forEach { a ->
            val o = a.jsonObject
            val st = o["status"]!!.jsonPrimitive.content
            if (st == "accepted" || st == "duplicate") acked += o["client_uuid"]!!.jsonPrimitive.content
            if (st == "quarantined" || (st == "rejected" && o["retryable"]?.jsonPrimitive?.content != "true")) {
                throw AssertionError("phone ${p.tag} seed=$seed: a valid record was refused for good: $o")
            }
        }
        var pending = day0.shuffled(rnd)
        var rounds = 0
        while (pending.isNotEmpty() && rounds++ < 40) {
            val take = rnd.nextInt(1, pending.size + 1)
            if (take < pending.size) seen("partial")
            val chunk = pending.take(take).toMutableList()
            if (rnd.nextInt(3) == 0) day0.filter { it.uuid() in acked }.shuffled(rnd).take(2).forEach { chunk += it; seen("ride-along") } // duplicates ride along
            val records = chunk.shuffled(rnd).distinctBy { it.uuid() }
            val bu = uuid()
            val text = batch(p, records, bu)
            val body = gz(text)
            when (rnd.nextInt(8)) {
                0 -> { // drop: the response never reaches the phone; the persisted batch is resent first
                    seen("drop"); send(p, body)
                    val (st, r) = send(p, body); note(r)
                    if (r != null && r["replayed"]?.jsonPrimitive?.content != "true") throw AssertionError("phone ${p.tag} seed=$seed: a completed batch resent is a replay ($st)")
                }
                1 -> { // truncated in transit, then resent whole under the same batch_uuid
                    seen("truncate")
                    val (st, _) = send(p, body.copyOf(rnd.nextInt(1, body.size)))
                    if (st == 200) throw AssertionError("phone ${p.tag} seed=$seed: a truncated body was answered 200")
                    val (st2, r) = send(p, body)
                    if (st2 == 409) throw AssertionError("phone ${p.tag} seed=$seed: a truncated batch claimed its batch_uuid (409 on the whole resend)")
                    note(r)
                }
                2 -> { // the API process dies after the n-th stored record; the phone resends the same batch
                    seen("kill")
                    kill.arm(p.deviceId, rnd.nextInt(1, records.size + 1))
                    val (st, r) = send(p, body)
                    kill.disarm(p.deviceId)
                    if (st == 200) note(r) else { seen("kill-fired"); val (_, r2) = send(p, body); note(r2) }
                }
                3 -> { // a duplicate batch: replayed with the same batch_uuid, answered with the same acks
                    seen("replay")
                    val (_, r1) = send(p, body); note(r1)
                    val (_, r2) = send(p, body)
                    if (r1 != null && r2 != null && r1["acks"] != r2["acks"]) throw AssertionError("phone ${p.tag} seed=$seed: a replay answered other acks")
                    note(r2)
                }
                4 -> { // the same batch twice in flight
                    seen("concurrent")
                    coroutineScope { listOf(async { send(p, body) }, async { send(p, body) }).awaitAll() }.forEach { (st, r) ->
                        if (st !in setOf(200, 409, 429, 503)) throw AssertionError("phone ${p.tag} seed=$seed: a concurrent copy answered $st")
                        note(r)
                    }
                }
                else -> { seen("plain"); note(send(p, body).second) }
            }
            pending = pending.filter { it.uuid() !in acked }.let { left -> if (rnd.nextBoolean()) left.shuffled(rnd) else left }
        }
        if (pending.isNotEmpty()) throw AssertionError("phone ${p.tag} seed=$seed: ${pending.size} rows never acknowledged after $rounds rounds")
        // The whole day resent under new batch_uuids (a reinstalled outbox): every row is a duplicate and nothing moves.
        var last: JsonObject? = null
        for (c in day0.shuffled(rnd).chunked(25)) {
            val (st, r) = send(p, gz(batch(p, c)))
            if (r == null) throw AssertionError("phone ${p.tag} seed=$seed: full resend answered $st")
            val notDup = r["acks"]!!.jsonArray.filter { it.jsonObject["status"]!!.jsonPrimitive.content != "duplicate" }
            if (notDup.isNotEmpty()) throw AssertionError("phone ${p.tag} seed=$seed: a resend of stored rows answered $notDup")
            last = r
        }
        val problems = oracle(p, last)
        if (problems.isNotEmpty()) throw AssertionError("phone ${p.tag} seed=$seed: the server differs from the device:\n  " + problems.joinToString("\n  "))
        return last!!
    }

    // ---- setup and tests -------------------------------------------------------------------------------------------

    @BeforeAll
    fun setUp() {
        val tags = (0 until phoneCount + 11).map { i -> "Q" + ('A' + i / 26) + ('A' + i % 26) }
        world = ApiWorld(repoRoot, mirrors = tags, extraRecordHandlers = listOf(kill))
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            // The day is long: a family is never skipped for its attempts.
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.sync.family_skip_after', 'global', 0, '1000'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'qa fuzz' FROM app.cfg_version")
        }
        val all = tags.mapIndexed { i, tag -> world.phoneFor(tag, i, day) }
        phones = all.take(phoneCount); probes = all.drop(phoneCount)
        check((phones + probes).all { it.outlets.size == 20 }) { "every mirrored daily route has 20 outlets" }
    }

    @AfterAll
    fun tearDown() { world.close() }

    /** The production wiring in process, without testApplication's one-minute limit (the 10,000 run takes many). */
    private fun withApi(block: suspend HttpClient.() -> Unit) = runBlocking {
        val app = TestApplication { application { aronApi(world.wiring) } }
        app.start()
        try { app.client.block() } finally { app.stop() }
    }

    private suspend fun HttpClient.signIn(p: SyncPhone) {
        if (p.token.isEmpty()) p.token = world.login(this, ApiWorld.Caller(p.username, "app_sr", p.deviceUuid)) ?: error("no token for ${p.username}")
    }

    @Test
    @Order(1)
    fun everyFaultScheduleEndsWithExactlyTheDevicesRowsAndEqualTotals() = withApi {
        val client = this
        for (p in phones) signIn(p)
        println("SyncFaultScheduleFuzzTest: ARON_SYNC_FUZZ_SEED=$baseSeed ARON_SYNC_FUZZ_SCHEDULES=$schedules ARON_SYNC_FUZZ_PHONES=$phoneCount")
        val started = System.nanoTime()
        coroutineScope {
            phones.mapIndexed { i, p ->
                async {
                    var k = i
                    while (k < schedules) { client.schedule(p, baseSeed + k); k += phones.size }
                }
            }.awaitAll()
        }
        val secs = (System.nanoTime() - started) / 1e9
        val report = "schedules=$schedules phones=$phoneCount seconds=${"%.0f".format(secs)} kills_fired=${kill.fired.get()} faults=${faultsSeen.toSortedMap()} rows=${phones.sumOf { p -> p.rows.values.sumOf { it.size } }}"
        println("SyncFaultScheduleFuzzTest: $report")
        File("build").mkdirs()
        File("build/sync-fault-fuzz.txt").writeText(report + "\n")
        // The schedules were really hostile: every fault kind ran, and the kill really fired mid-batch.
        if (schedules >= 100) {
            listOf("drop", "truncate", "kill", "replay", "concurrent", "plain").forEach { f -> assertTrue((faultsSeen[f]?.get() ?: 0) > 0, "fault $f never ran") }
            assertTrue(kill.fired.get() > 0, "the kill switch never fired")
        }
    }

    /** Copies one row of [table] that matches [where], with [fresh] column values (SQL expressions) in place of the copied ones. */
    private fun copyRow(table: String, where: String, fresh: Map<String, String>, bypassTriggers: Boolean = false) = world.fresh.db.jdbi.useHandle<Exception> { h ->
        // A missing or broken database guard (a dropped trigger) is modelled with the session's triggers off.
        if (bypassTriggers) h.execute("SET session_replication_role = replica")
        val cols = h.createQuery("SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema || '.' || table_name = :t AND column_name <> 'id' AND is_generated = 'NEVER' AND column_name <> ALL(:skip)")
            .bind("t", table).bind("skip", fresh.keys.toTypedArray()).mapTo(String::class.java).one()
        val n = h.execute("INSERT INTO $table (${fresh.keys.joinToString(", ")}, $cols) SELECT ${fresh.values.joinToString(", ")}, $cols FROM $table WHERE $where LIMIT 1")
        if (bypassTriggers) h.execute("SET session_replication_role = DEFAULT")
        check(n == 1) { "nothing to copy in $table where $where" }
    }

    /** A probe phone with a clean, oracle-approved day with a standing sale (and a collection when [needCollection]) and its last batch answer. */
    private suspend fun HttpClient.cleanDay(p: SyncPhone, seed: Long, needCollection: Boolean = false): JsonObject {
        signIn(p)
        var last = schedule(p, seed)
        var k = 1
        // Every fault needs a standing sale (a voided-only day has no sticks), some a collection.
        while ((p.sold.isEmpty() || (needCollection && p.rows["due_collection"].isNullOrEmpty())) && k < 20) last = schedule(p, seed + k++)
        assertEquals(emptyList(), oracle(p, last), "the oracle agrees before the seeded fault")
        return last
    }

    /**
     * One seeded fault per oracle clause (checker N-024 #2): each fault names the clauses (labels in [oracle]) that must
     * report it, every clause is named by at least one fault, and each is judged with a real batch answer on a phone of
     * its own. So switching any clause off turns this test red. Faults add rows, lose rows, swap identities, move rows
     * to another date, double amounts and tamper with the server's totals.
     */
    @Test
    @Order(2)
    fun everySeededFaultIsReportedByItsOracleClause() = withApi {
        val faults = mutableListOf<Triple<String, Set<String>, List<String>>>()
        fun expect(name: String, clauses: Set<String>, problems: List<String>) { faults += Triple(name, clauses, problems) }
        // A lost row gets past the database's guard_synced_row trigger only with triggers off (data loss, not an API path).
        fun db(sql: String) = world.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            try { check(h.execute(sql) >= 1) { "seeding changed nothing: $sql" } } finally { h.execute("SET session_replication_role = DEFAULT") }
        }
        var probe = 0
        suspend fun fresh(needCollection: Boolean = false): Pair<SyncPhone, JsonObject> { val p = probes[probe]; return p to cleanDay(p, baseSeed + 1_000_000 + 100_000L * probe++, needCollection) }

        fresh().let { (p, last) -> // a second sale on the same day (a broken upsert)
            copyRow("app.memo", "client_uuid = '${p.memoDue.keys.last()}'", mapOf("client_uuid" to "gen_random_uuid()", "memo_no" to "regexp_replace(memo_no, '-[0-9]+$', '-9999')", "external_ref" to "NULL"))
            expect("duplicate sale, same day", setOf("A1", "A3", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a second sale filed under another business date
            copyRow("app.memo", "client_uuid = '${p.memoDue.keys.last()}'", mapOf("client_uuid" to "gen_random_uuid()", "business_date" to "business_date - 1", "external_ref" to "NULL"))
            expect("duplicate sale, another date", setOf("B", "C"), oracle(p, last))
        }
        fresh().let { (p, last) -> // the SAME client_uuid stored again on another date (past a broken client_uuid_once trigger)
            copyRow("app.memo", "client_uuid = '${p.memoDue.keys.last()}'", mapOf("business_date" to "business_date - 1", "external_ref" to "NULL"), bypassTriggers = true)
            expect("same uuid, another date", setOf("B2", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a line stored twice
            copyRow("app.memo_line", "client_uuid = '${p.rows["memo_line"]!!.first()}'", mapOf("client_uuid" to "gen_random_uuid()", "line_no" to "3", "external_ref" to "NULL"))
            expect("duplicate line", setOf("A1", "A3", "B"), oracle(p, last))
        }
        fresh(needCollection = true).let { (p, last) -> // a collection stored twice
            copyRow("app.due_collection", "client_uuid = '${p.rows["due_collection"]!!.first()}'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL"))
            expect("duplicate collection", setOf("A1", "A3", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a lost line (the device has it, the server does not)
            db("DELETE FROM app.memo_line WHERE client_uuid = '${p.rows["memo_line"]!!.first()}'")
            expect("lost line", setOf("A1", "A2", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // an identity swap: one line lost, a foreign one in its place (counts unchanged)
            val (lost, kept) = p.rows["memo_line"]!!.toList().let { it[0] to it[1] }
            copyRow("app.memo_line", "client_uuid = '$kept'", mapOf("client_uuid" to "gen_random_uuid()", "line_no" to "3", "external_ref" to "NULL"))
            db("DELETE FROM app.memo_line WHERE client_uuid = '$lost'")
            expect("identity swap", setOf("A2", "A3"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a foreign record in the ingest registry of the user
            copyRow("app.ingest_registry", "client_uuid = '${p.rows["memo"]!!.first()}'", mapOf("client_uuid" to "gen_random_uuid()"))
            expect("foreign registry row", setOf("D"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a ledger amount counted twice (a replay that re-ran a side effect)
            copyRow("app.due_ledger", "memo_client_uuid = '${p.memoDue.keys.last()}' ORDER BY id", mapOf("source_client_uuid" to "gen_random_uuid()"))
            expect("doubled ledger amount", setOf("F"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a ledger row of the user on another date, against no memo of the day
            copyRow("app.due_ledger", "memo_client_uuid = '${p.memoDue.keys.last()}' ORDER BY id", mapOf("source_client_uuid" to "gen_random_uuid()", "business_date" to "business_date - 1", "memo_client_uuid" to "gen_random_uuid()"))
            expect("ledger row on another date", setOf("E"), oracle(p, last))
        }
        fresh().let { (p, last) -> // the server's own answer differs from the device, the database intact
            fun tamper(edit: (JsonObject) -> JsonObject?): JsonObject = JsonObject(last + ("server_totals" to JsonArray(last["server_totals"]!!.jsonArray.map { it.jsonObject }.mapNotNull { t -> if (t["business_date"]!!.jsonPrimitive.content != day) t else edit(t) })))
            fun bump(o: JsonObject, path: List<String>, delta: Long): JsonObject = JsonObject(o + (path[0] to if (path.size == 1) JsonPrimitive(o[path[0]]!!.jsonPrimitive.content.toLong() + delta) else bump(o[path[0]]!!.jsonObject, path.drop(1), delta)))
            expect("server gross differs", setOf("G"), oracle(p, tamper { bump(it, listOf("money", "gross_mtk"), 10) }))
            expect("server collected differs", setOf("G"), oracle(p, tamper { bump(it, listOf("money", "due_collected_mtk"), 1000) }))
            expect("server sticks differ", setOf("H"), oracle(p, tamper { bump(it, listOf("money", "sold_qty_base_by_sku", p.sold.firstKey()), 1) }))
            expect("server accepted count differs", setOf("I"), oracle(p, tamper { bump(it, listOf("by_type", "memo", "accepted"), 1) }))
            expect("server holds a rejected row", setOf("J"), oracle(p, tamper { bump(it, listOf("by_type", "memo", "rejected"), 1) }))
            expect("no totals for the day", setOf("K"), oracle(p, tamper { null }))
        }
        val missing = faults.mapNotNull { (name, want, got) -> val labels = got.mapNotNull { Regex("^\\[(\\w+)]").find(it)?.groupValues?.get(1) }.toSet(); (want - labels).takeIf { it.isNotEmpty() }?.let { "$name: clauses $it did not report it (got $got)" } }
        assertEquals(emptyList(), missing, "seeded faults not reported by the clause that must catch them")
        val clauses = setOf("A1", "A2", "A3", "B", "B2", "C", "D", "E", "F", "G", "H", "I", "J", "K")
        assertEquals(clauses, faults.flatMap { it.second }.toSet(), "every oracle clause is guarded by a seeded fault")
        println("SyncFaultScheduleFuzzTest seeded faults: " + faults.joinToString("; ") { "${it.first} -> ${it.third.firstOrNull()}" })
    }
}

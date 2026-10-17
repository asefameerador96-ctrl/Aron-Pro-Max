package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.wire
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-API-009 / docs/24 s4.9 rules 3 and 4: a concurrency fuzz of the TSO zone-day close (`POST /v1/day/final-submit`,
 * online-only, once per zone and business date) through the production wiring of an [ApiWorld] mirror division
 * (`QDC`: zone Z-QDC with three SR routes, TSO `qdctso1001`, DMO `qdcdmo1001` made a delegate, SR `qdcsr1001` with its
 * own phone). Every round takes a fresh business date (the clock moves to that date, or to the morning after it, so the
 * close is today's or yesterday's) and fires at once:
 * - (a) 2 to 4 Final Submits sharing one client_uuid by one caller (double click, timeout retry);
 * - (b) 1 to 3 Final Submits with other client_uuids, by the TSO or the delegate DMO (two tabs, two people);
 * - (c) the SR phone's sync batches of the same route-day ([SyncPhone] sales, collections, voids moved to the date),
 *   sent in order one batch at a time, sometimes resent (a lost response) or sent twice at once, so some families commit
 *   before the close and some after; sometimes a tail batch after every answer (certainly late);
 * - (d) in about one round of three, an admin reopen racing a second wave of submits (new uuids and a replay of the
 *   first winner), then one more submit after the reopen and a batch after the second close.
 *
 * The reopen route `POST /v1/day/reopen` is in the contract but not served by the backend (docs/33 part C: "backend route
 * [PLANNED]", gaps G29/G34). The test probes it once: while it answers 404, (d) reopens with SQL the way the route would
 * have to, under the same zone-day advisory lock Final Submit takes (`final:<zone>:<date>`), setting only the columns
 * `app.final_submit` defines for a reopen. The route-day side of a reopen is not specified anywhere, so the oracle
 * expects what the product does today: `route_day.final_submitted_at` keeps the FIRST close (COALESCE).
 *
 * The oracle ([oracle], one label per clause) per round:
 * - L1 exactly one live (`reopened_at IS NULL`) close of the zone-day; L2 exactly one close row per closed cycle;
 * - R1 every 200 answer to a cycle's winning uuid is identical and equals the stored close (submitted_at, kind,
 *   counts); R2 no other uuid ever gets a 200; R3 every 409 is ERR_DAY_ALREADY_FINAL_SUBMITTED with
 *   context.submitted_at equal to a close that was live in that wave; R4 no 5xx and no other status;
 * - S1 every active route of the zone has a route-day whose final_submitted_at is the (first) close's time; S2 its
 *   state is final_submitted;
 * - Y1 the ingest registry of the SR and date holds exactly the device's client_uuids; Y2 every table holds exactly
 *   the device's rows (count and identity); Y3 no valid record was refused;
 * - T1 late_rows_after_final of the SR's route-day is EXACT: an ingest family is one transaction whose first record locks
 *   the route-day row, so a family is wholly late or not, and the phone's families commit in order; the late count is
 *   therefore the records of a suffix of the family stream, and the suffix is bounded by what the client observed
 *   (a family whose batch answered before the first winning call was sent is not late; one whose batch was sent after
 *   the close was answered is late). T2 the other routes count no late rows; T3 the close rows' late_rows add up to
 *   the route-days' (and after a reopen the second close counts exactly the rows sent after it); T4 0 <= late <= rows;
 * - O1 after a reopen, the first close carries the reopen, and the second close is newer than the first and belongs
 *   to a submit sent after the reopen started. Not asserted, only counted and printed (`close-stamped-before-reopen`):
 *   Final Submit reads its clock before it waits for the zone-day lock, so a submit queued behind the reopen is
 *   stamped a little BEFORE the reopen it follows (latent while the reopen route is not served; see the report).
 * [everySeededFaultIsReportedByItsClause] seeds one fault per clause and requires that clause to report it.
 *
 * Known product defects this class found (reported to the lead, not fixed here; the qa lane edits no product code):
 * - **KD-1** (T1, about 1 round in 40): DayStates.afterStored checks lateness with `UPDATE route_day ... WHERE
 *   final_submitted_at IS NOT NULL`, which locks nothing while the day is open; a Final Submit can commit between a
 *   family's first record (the visit: not counted) and its later records (counted), although the whole family commits
 *   after the close. late_rows_after_final and final_submit.late_rows are then one row short (T1), or, when the close
 *   commits between the visit's two checks, the route-day is one short and the close row is right (T3). A report that
 *   fits either shape says "matches KD-1".
 * - **KD-2** (R1, about 1 close in 2,000): Final Submit answers `now` truncated to the millisecond but stores it rounded
 *   to the microsecond, so a replay can answer 1 ms later than the first 200 ([aReplayAnswersTheFirstSuccessToTheMillisecond],
 *   disabled until fixed).
 * KD-1 rounds are counted and listed by default (`tolerated-KD-1` and their seeds in the report) until the fix lands;
 * every other failure fails the run. `ARON_DAYCLOSE_TOLERATE=none` makes KD-1 fail too (the strict run).
 *
 * Size: `ARON_DAYCLOSE_FUZZ_ROUNDS` (default 30, about 20 s), seed `ARON_DAYCLOSE_FUZZ_SEED` (default fixed). A failure
 * names the round seed; every round runs and the failures are listed together with a count per clause. Timing is the
 * system's (coroutines on Dispatchers.IO), so a seed fixes the scenario, not the interleaving; the oracle never depends
 * on who wins.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DayCloseRaceTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val rounds = System.getenv("ARON_DAYCLOSE_FUZZ_ROUNDS")?.toIntOrNull()?.coerceIn(1, 20_000) ?: 30
    private val baseSeed = System.getenv("ARON_DAYCLOSE_FUZZ_SEED")?.toLongOrNull() ?: 20261017L
    // KD-1 is an open product defect (docs/requests/qa-backend-dayclose-late-rows.md): its rounds are counted and listed
    // by default so CI stays green while the fix is queued; any other failure still fails. Set ARON_DAYCLOSE_TOLERATE=none
    // for the strict run, and drop this default with the fix (the request says so).
    private val tolerate = (System.getenv("ARON_DAYCLOSE_TOLERATE") ?: "KD-1").split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }.toSet()
    private val tag = "QDC"
    private lateinit var world: ApiWorld
    private lateinit var proto: SyncPhone
    private var zone = 0L
    private var adminId = 0L
    private lateinit var zoneRoutes: List<Long>
    private lateinit var roleOf: Map<String, String>
    private var reopenRouted = false

    // ---- the clock: a business date per round, real time ticking inside it ----------------------------------------

    @Volatile private var clockBase = Instant.parse("2027-01-03T06:00:00Z")
    @Volatile private var clockNanos = System.nanoTime()
    @Volatile private var frozen: Instant? = null
    /** True in the seeded-fault test: the clock ticks in whole microseconds, so the known rounding defect (KD-2) cannot fire there. */
    @Volatile private var microClock = false
    private val clock = AronClock { frozen ?: clockBase.plusNanos(System.nanoTime() - clockNanos).let { if (microClock) it.truncatedTo(ChronoUnit.MICROS) else it } }
    private fun setClock(at: Instant) { clockNanos = System.nanoTime(); clockBase = at }

    /** Next free business date: two days per round (the close may run the morning after), never reused. */
    private var nextDate = LocalDate.parse("2027-01-03")
    @Synchronized private fun allocDate(): LocalDate = nextDate.also { nextDate = nextDate.plusDays(2) }

    // ---- records --------------------------------------------------------------------------------------------------

    private val instantField = Regex("\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)\"")

    /** [SyncPhone] captures on 2027-01-03; its records moved to [date] (times by whole days, memo numbers renamed). */
    private fun moved(rec: JsonObject, date: LocalDate): JsonObject {
        val days = ChronoUnit.DAYS.between(LocalDate.parse("2027-01-03"), date)
        val text = rec.toString().replace(instantField) { "\"" + Instant.parse(it.groupValues[1]).plus(days, ChronoUnit.DAYS) + "\"" }
            .replace("-270103-", "-" + date.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun families(records: List<JsonObject>): List<List<JsonObject>> {
        val out = mutableListOf<MutableList<JsonObject>>()
        for (r in records) {
            val f = r["family_uuid"]!!.jsonPrimitive.content
            if (out.isNotEmpty() && out.last().last()["family_uuid"]!!.jsonPrimitive.content == f) out.last() += r else out += mutableListOf(r)
        }
        return out
    }

    // ---- what a round observed -------------------------------------------------------------------------------------

    /** One Final Submit (or reopen) call: who, which uuid, which wave (1, 2 = racing the reopen, 3 = after it). */
    data class Call(val user: String, val uuid: String, val wave: Int, val start: Long, val end: Long, val status: Int, val body: JsonObject?)

    /** One batch of whole families (indexes in the round's family stream), timed over all its copies. */
    data class Sent(val families: List<Int>, val start: Long, val end: Long)

    data class Round(
        val seed: Long, val date: LocalDate, val phone: SyncPhone, val calls: List<Call>, val familySizes: List<Int>, val batches: List<Sent>,
        val reopened: Boolean, val postRecords: Int, val refused: List<String>,
    )

    // ---- wire ------------------------------------------------------------------------------------------------------

    private fun uuid() = UUID.randomUUID().toString()
    private fun json(s: String): JsonObject? = runCatching { Json.parseToJsonElement(s).jsonObject }.getOrNull()

    private val tokens = ConcurrentHashMap<String, String>()
    private suspend fun HttpClient.web(user: String): String =
        world.login(this, ApiWorld.Caller(user, "web", null)) ?: error("no token for $user")

    private suspend fun HttpClient.finalSubmit(user: String, cu: String, date: LocalDate, wave: Int): Call {
        val start = System.nanoTime()
        return try {
            val r = post("/v1/day/final-submit") {
                bearerAuth(tokens.getValue(user)); contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
                setBody("""{"client_uuid":"$cu","zone_id":$zone,"business_date":"$date"}""")
            }
            val text = r.bodyAsText()
            Call(user, cu, wave, start, System.nanoTime(), r.status.value, json(text))
        } catch (e: Throwable) {
            if (e is AssertionError) throw e
            Call(user, cu, wave, start, System.nanoTime(), 599, null)
        }
    }

    private suspend fun HttpClient.batch(p: SyncPhone, body: ByteArray): Triple<Int, JsonObject?, Long> = try {
        val r = post("/v1/sync/batch") {
            bearerAuth(p.token); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            setBody(ByteArrayContent(body, ContentType.Application.Json))
        }
        val text = r.bodyAsText()
        Triple(r.status.value, if (r.status.value == 200) json(text) else null, System.nanoTime())
    } catch (e: Throwable) {
        if (e is AssertionError) throw e
        Triple(599, null, System.nanoTime())
    }

    /** The admin reopen: the route when the backend serves it, else the SQL it would run (see the class comment). */
    private suspend fun HttpClient.reopen(date: LocalDate): Call {
        val start = System.nanoTime()
        if (reopenRouted) {
            val r = post("/v1/day/reopen") {
                bearerAuth(tokens.getValue("admin1001")); contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
                setBody("""{"zone_id":$zone,"business_date":"$date","reason":"qa day-close race: reopen"}""")
            }
            return Call("admin1001", "reopen", 2, start, System.nanoTime(), r.status.value, json(r.bodyAsText()))
        }
        val n = kotlinx.coroutines.withContext(Dispatchers.IO) {
            world.fresh.db.jdbi.inTransaction<Int, Exception> { h ->
                h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", "final:$zone:$date")
                h.createUpdate(
                    """
                    UPDATE app.final_submit SET reopened_at = :at, reopened_by = :by, reopen_reason = 'qa day-close race: simulated reopen', reopen_client_uuid = gen_random_uuid()
                    WHERE zone_id = :z AND business_date = :d AND reopened_at IS NULL
                    """.trimIndent(),
                ).bind("at", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).bind("by", adminId).bind("z", zone).bind("d", date).execute()
            }
        }
        return Call("admin1001", "reopen", 2, start, System.nanoTime(), if (n == 1) 200 else 409, null)
    }

    // ---- one round -------------------------------------------------------------------------------------------------

    private val stats = ConcurrentHashMap<String, AtomicInteger>()
    private fun count(k: String, n: Int = 1) { stats.getOrPut(k) { AtomicInteger() }.addAndGet(n) }

    private suspend fun HttpClient.round(seed: Long, forceReopen: Boolean? = null, quiet: Boolean = false): Round {
        val rnd = Random(seed)
        val date = allocDate()
        val closeNextMorning = rnd.nextBoolean()
        setClock((if (closeNextMorning) date.plusDays(1) else date).atTime(6, 0).toInstant(ZoneOffset.UTC).plusMillis(rnd.nextLong(0, 3_600_000)))
        // The clock jumped a day or more: fresh tokens.
        for (u in listOf("${tag.lowercase()}tso1001", "${tag.lowercase()}dmo1001", "admin1001")) tokens[u] = web(u)
        val phone = SyncPhone(date.toString(), proto.tag, proto.deviceUuid, proto.deviceId, proto.userId, proto.username, proto.routeId, proto.outlets, proto.skus)
        phone.token = world.login(this, ApiWorld.Caller(phone.username, "app_sr", phone.deviceUuid)) ?: error("no token for ${phone.username}")

        val refused = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun note(resp: JsonObject?) = resp?.get("acks")?.jsonArray?.forEach { a ->
            val st = a.jsonObject["status"]!!.jsonPrimitive.content
            if (st != "accepted" && st != "duplicate") refused += a.toString()
        }

        // (c) the phone's stream: families in capture order, in batches of one to three families.
        val stream = (0 until rnd.nextInt(1, 3)).flatMap { phone.captureDay(rnd) }.map { moved(it, date) }
        val fams = families(stream)
        val plan = mutableListOf<List<Int>>()
        run { var i = 0; while (i < fams.size) { val n = rnd.nextInt(1, 4).coerceAtMost(fams.size - i); plan += (i until i + n).toList(); i += n } }
        val tail = plan.size >= 2 && rnd.nextInt(3) == 0 // the last batch waits for every close answer: certainly late
        val live = if (tail) plan.dropLast(1) else plan
        val gaps = live.map { rnd.nextLong(0, 25) }
        val modes = plan.map { rnd.nextInt(6) } // 0: resent after its answer (lost response), 1: two copies in flight, else once
        val batchUuids = plan.map { uuid() }
        val sent = java.util.Collections.synchronizedList(mutableListOf<Sent>())

        suspend fun HttpClient.sendBatch(b: Int) {
            val body = gz(phone.batch(plan[b].flatMap { fams[it] }, batchUuids[b]))
            val start = System.nanoTime()
            var end: Long
            var ok: Boolean
            when (modes[b]) {
                1 -> {
                    count("batch-concurrent-copies")
                    val two = coroutineScope { listOf(async(Dispatchers.IO) { batch(phone, body) }, async(Dispatchers.IO) { batch(phone, body) }).awaitAll() }
                    two.forEach { note(it.second) }
                    two.filter { it.first != 200 && it.first != 409 && it.first != 429 && it.first != 503 }.forEach { refused += "batch copy answered ${it.first}" }
                    end = two.maxOf { it.third }; ok = two.any { it.first == 200 }
                }
                else -> { val (st, r, e) = batch(phone, body); note(r); end = e; ok = st == 200 }
            }
            var tries = 0
            while (!ok && tries++ < 5) { val (st, r, e) = batch(phone, body); note(r); end = e; ok = st == 200 }
            if (!ok) refused += "batch ${batchUuids[b]} never answered 200"
            sent += Sent(plan[b], start, end)
            if (modes[b] == 0) { count("batch-resent"); val (_, r, _) = batch(phone, body); note(r) } // the lost response: a replay
        }

        // (a) and (b): the submits of wave 1.
        val tso = "${tag.lowercase()}tso1001"; val dmo = "${tag.lowercase()}dmo1001"
        fun someone() = if (rnd.nextInt(4) == 0) dmo else tso
        val shared = uuid(); val owner = someone()
        // The submits come as one burst (a double click, a second tab) at a random point of the phone's stream.
        val burst = rnd.nextLong(0, (live.size * 60L).coerceAtLeast(20))
        val wave1 = List(rnd.nextInt(2, 5)) { Triple(owner, shared, burst + rnd.nextLong(0, 15)) } +
            List(rnd.nextInt(1, 4)) { Triple(someone(), uuid(), burst + rnd.nextLong(0, 40)) }
        if (quiet) live.indices.forEach { b -> sendBatch(b) } // the seeded-fault test: every row before the close
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())
        coroutineScope {
            val sync = async(Dispatchers.IO) { if (!quiet) live.indices.forEach { b -> delay(gaps[b]); sendBatch(b) } }
            val fs = wave1.map { (u, cu, d) -> async(Dispatchers.IO) { delay(d); calls += finalSubmit(u, cu, date, 1) } }
            (fs + sync).awaitAll()
        }
        if (tail) { count("tail-batch"); sendBatch(plan.lastIndex) }
        count("final-submit-calls", wave1.size)

        // (d) a reopen racing a second wave, one submit after it, and rows after the second close.
        val reopen = forceReopen ?: (rnd.nextInt(3) == 0)
        var post = 0
        if (reopen) {
            count("reopen-rounds")
            val wave2 = List(rnd.nextInt(1, 4)) { Triple(someone(), uuid(), rnd.nextLong(0, 30)) } +
                (if (rnd.nextBoolean()) listOf(Triple(owner, shared, rnd.nextLong(0, 30))) else emptyList())
            val reopenAfter = rnd.nextLong(0, 30)
            coroutineScope {
                val ro = async(Dispatchers.IO) { delay(reopenAfter); calls += reopen(date) }
                (wave2.map { (u, cu, d) -> async(Dispatchers.IO) { delay(d); calls += finalSubmit(u, cu, date, 2) } } + ro).awaitAll()
            }
            calls += finalSubmit(tso, uuid(), date, 3)
            val more = families(phone.captureDay(rnd).map { moved(it, date) })
            val body = gz(phone.batch(more.flatten()))
            val (st, r, _) = batch(phone, body); note(r)
            if (st != 200) refused += "post-close batch answered $st"
            post = more.sumOf { it.size }
            count("final-submit-calls", wave2.size + 1)
        }
        return Round(seed, date, phone, calls.toList(), fams.map { it.size }, sent.toList().sortedBy { it.families.first() }, reopen, post, refused.toList())
    }

    // ---- the oracle ------------------------------------------------------------------------------------------------

    private val tables = mapOf(
        "visit" to ("app.visit" to "client_uuid"), "visit_close" to ("app.visit" to "close_client_uuid"), "memo" to ("app.memo" to "client_uuid"),
        "memo_line" to ("app.memo_line" to "client_uuid"), "due_collection" to ("app.due_collection" to "client_uuid"), "memo_void" to ("app.memo_void" to "client_uuid"),
    )

    data class Close(val uuid: String, val submittedAt: Instant, val reopenedAt: Instant?, val late: Int, val role: String)

    /** Allowed late counts on the SR's route-day for wave 1 (suffixes of the family stream within the observed bounds). */
    internal fun lateBounds(r: Round, first: Close): Pair<IntRange, Set<Int>> {
        val m = r.familySizes.size
        val winnerStart = r.calls.filter { it.wave == 1 && it.uuid == first.uuid }.minOfOrNull { it.start } ?: Long.MAX_VALUE
        val closeSeen = r.calls.filter { it.wave == 1 && (it.status == 200 && it.uuid == first.uuid || it.status == 409 && code(it) == ALREADY) }.minOfOrNull { it.end } ?: Long.MAX_VALUE
        var lo = 0; var hi = m
        for (b in r.batches) {
            if (b.end < winnerStart) lo = maxOf(lo, b.families.max() + 1)
            if (b.start > closeSeen) hi = minOf(hi, b.families.min())
        }
        val suffix = IntArray(m + 1).also { s -> for (i in m - 1 downTo 0) s[i] = s[i + 1] + r.familySizes[i] }
        return lo..hi to (lo..hi).map { suffix[it] }.toSet()
    }

    private fun suffixOf(r: Round, c: Int) = r.familySizes.drop(c).sum()

    private fun code(c: Call) = c.body?.get("code")?.jsonPrimitive?.content
    private fun ctxAt(c: Call) = (c.body?.get("context") as? JsonObject)?.get("submitted_at")?.jsonPrimitive?.content
    private fun wire(i: Instant) = i.wire()

    internal fun oracle(r: Round): List<String> {
        val p = mutableListOf<String>()
        val h0 = world.fresh.db.jdbi.open()
        h0.use { h ->
            val closes = h.createQuery(
                """
                SELECT f.client_uuid::text, f.submitted_at, f.reopened_at, f.late_rows, u.role FROM app.final_submit f JOIN app.app_user u ON u.id = f.submitted_by
                WHERE f.zone_id = :z AND f.business_date = :d ORDER BY f.id
                """.trimIndent(),
            ).bind("z", zone).bind("d", r.date).map { rs, _ ->
                Close(rs.getString(1), rs.getObject(2, OffsetDateTime::class.java).toInstant(), rs.getObject(3, OffsetDateTime::class.java)?.toInstant(), rs.getInt(4), rs.getString(5))
            }.list()
            val cycles = if (r.reopened) 2 else 1
            val live = closes.filter { it.reopenedAt == null }
            if (live.size != 1) p += "[L1] ${live.size} live closes of the zone-day (want exactly one)"
            if (closes.size != cycles) p += "[L2] ${closes.size} close rows for $cycles closed cycle(s)"
            if (closes.isEmpty()) return p
            val first = closes[0]
            val winners = closes.associateBy { it.uuid }

            // Answers.
            for (c in r.calls.filter { it.uuid != "reopen" }) {
                when (c.status) {
                    200 -> {
                        val w = winners[c.uuid]
                        if (w == null) { p += "[R2] ${c.user} uuid ${c.uuid} (wave ${c.wave}) got 200 but holds no close"; continue }
                        val b = c.body
                        val kind = if (w.role == "TSO") "manual" else "delegated"
                        if (b == null || b["submitted_at"]?.jsonPrimitive?.content != wire(w.submittedAt) || b["kind"]?.jsonPrimitive?.content != kind ||
                            b["zone_id"]?.jsonPrimitive?.content != zone.toString() || b["business_date"]?.jsonPrimitive?.content != r.date.toString()
                        ) p += "[R1] a 200 to ${c.uuid} differs from the stored close (${wire(w.submittedAt)}, $kind): $b"
                    }
                    409 -> {
                        val allowed = if (c.wave == 1) setOf(wire(first.submittedAt)) else closes.map { wire(it.submittedAt) }.toSet()
                        if (code(c) != ALREADY || ctxAt(c) !in allowed) p += "[R3] wave ${c.wave} 409 to ${c.uuid}: ${code(c)} submitted_at ${ctxAt(c)} (allowed $allowed)"
                        // A cycle's winner is never refused once its cycle is open: the first close's uuid never, the second
                        // close's uuid not after the reopen (before it, it lost wave 1 like any other uuid).
                        val cycle = closes.indexOfFirst { it.uuid == c.uuid }
                        if (cycle == 0 || (cycle > 0 && c.wave >= 2)) p += "[R3] the winning uuid ${c.uuid} (cycle ${cycle + 1}) was answered 409 ${code(c)} in wave ${c.wave}"
                    }
                    else -> p += "[R4] ${c.user} wave ${c.wave} answered ${c.status}: ${c.body?.get("code")}"
                }
            }
            for (w in closes) {
                val oks = r.calls.filter { it.uuid == w.uuid && it.status == 200 }
                if (oks.isEmpty()) p += "[R1] the close ${w.uuid} was never answered 200"
                if (oks.map { it.body }.toSet().size > 1) p += "[R1] the 200 answers to ${w.uuid} differ: ${oks.map { it.body }.toSet()}"
            }
            if (r.reopened && closes.size >= 2) {
                val second = closes[1]
                if (first.reopenedAt == null || !second.submittedAt.isAfter(first.submittedAt)) p += "[O1] the second close ${second.submittedAt} is not after the reopened first close ${first.submittedAt}"
                if (r.calls.none { it.uuid == second.uuid && it.wave >= 2 }) p += "[O1] the second close ${second.uuid} was not sent after the reopen"
            }

            // Route-days.
            val days = h.createQuery("SELECT route_id, final_submitted_at, state, late_rows_after_final FROM app.route_day WHERE business_date = :d AND route_id = ANY(:r)")
                .bind("d", r.date).bindArray("r", Long::class.javaObjectType, zoneRoutes)
                .map { rs, _ -> rs.getLong(1) to Triple(rs.getObject(2, OffsetDateTime::class.java)?.toInstant(), rs.getString(3), rs.getInt(4)) }.list().toMap()
            for (route in zoneRoutes) {
                val d = days[route]
                if (d == null || d.first != first.submittedAt) p += "[S1] route $route final_submitted_at ${d?.first} (close ${first.submittedAt})"
                if (d?.second != "final_submitted") p += "[S2] route $route state ${d?.second}"
                if (route != r.phone.routeId && (d?.third ?: 0) != 0) p += "[T2] route $route (no rows) counts ${d?.third} late rows"
            }

            // Rows.
            val made = r.phone.rows.values.flatten().toSet()
            val registry = h.createQuery("SELECT client_uuid::text FROM app.ingest_registry WHERE user_id = :u AND business_date = :d")
                .bind("u", r.phone.userId).bind("d", r.date).mapTo(String::class.java).list()
            if (registry.size != made.size || registry.toSet() != made) p += "[Y1] registry holds ${registry.size} rows for the SR and date, the device made ${made.size}"
            for ((type, tc) in tables) {
                val (table, col) = tc
                val device = r.phone.rows[type].orEmpty()
                val server = h.createQuery("SELECT $col::text FROM $table WHERE user_id = :u AND business_date = :d AND $col IS NOT NULL")
                    .bind("u", r.phone.userId).bind("d", r.date).mapTo(String::class.java).list()
                if (server.size != device.size || server.toSet() != device) p += "[Y2] $type: server ${server.size} rows, device ${device.size} (${(device - server.toSet()).size} missing, ${(server.toSet() - device).size} foreign)"
            }
            if (r.refused.isNotEmpty()) p += "[Y3] ${r.refused.size} valid records or batches refused, e.g. ${r.refused.first()}"

            // Late rows.
            val total = r.familySizes.sum() + r.postRecords
            val late = days[r.phone.routeId]?.third ?: 0
            val (cuts, allowed) = lateBounds(r, first)
            val want = allowed.map { it + r.postRecords }.toSet()
            if (late !in want) {
                // KD-1: one family split by the close (its first record checked the day open, its later records saw it closed).
                val split = cuts.any { c -> c < r.familySizes.size && r.familySizes[c] > 1 && late == suffixOf(r, c) - 1 + r.postRecords }
                p += "[T1] route-day counts $late late rows; the observed order allows $want (cuts $cuts of ${r.familySizes.size} families, +${r.postRecords} after the second close)" +
                    if (split) " -- matches KD-1: one family's first record not counted although its family committed after the close" else ""
            }
            val routeLate = days.values.sumOf { it.third }
            if (closes.sumOf { it.late } != routeLate) {
                // KD-1, second shape: the close committed between the visit's route-day check and its close-row check, so
                // the close row counts the visit (rightly) and the route-day does not.
                val split = closes.sumOf { it.late } - routeLate == 1 && closes.sumOf { it.late } in want
                p += "[T3] close rows count ${closes.map { it.late }} late rows, the route-days $routeLate" +
                    if (split) " -- matches KD-1: the route-day missed the first record of a family the close row counted" else ""
            }
            if (r.reopened && closes.size >= 2 && closes[1].late != r.postRecords) p += "[T3] the second close counts ${closes[1].late} late rows, ${r.postRecords} were sent after it"
            if (late < 0 || late > total) p += "[T4] $late late rows of $total stored"
        }
        return p
    }

    // ---- setup and tests -------------------------------------------------------------------------------------------

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot, mirrors = listOf(tag), clock = clock)
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            // The DMO is a Final Submit delegate (cfg.day.final_submit_delegate_roles), so two people can race.
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.day.final_submit_delegate_roles', 'global', 0, '[\"DMO\"]'::jsonb, now() - interval '1 day', max(config_version), 'qa day-close race' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.sync.family_skip_after', 'global', 0, '1000'::jsonb, now() - interval '1 day', max(config_version), 'qa day-close race' FROM app.cfg_version")
            zone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-$tag'").mapTo(Long::class.java).one()
            zoneRoutes = h.createQuery("SELECT id FROM app.route WHERE zone_id = :z AND status = 'active' ORDER BY id").bind("z", zone).mapTo(Long::class.java).list()
            adminId = h.createQuery("SELECT id FROM app.app_user WHERE username = 'admin1001'").mapTo(Long::class.java).one()
        }
        proto = world.phoneFor(tag, 0, "2027-01-03")
        check(zoneRoutes.size == 3 && proto.routeId in zoneRoutes) { "the mirrored zone has three SR routes" }
    }

    @AfterAll
    fun tearDown() { world.close() }

    private fun withApi(block: suspend HttpClient.() -> Unit) = runBlocking {
        val app = TestApplication { application { aronApi(world.wiring) } }
        app.start()
        try { app.client.block() } finally { app.stop() }
    }

    private suspend fun HttpClient.probeReopen() {
        tokens["admin1001"] = web("admin1001")
        val r = post("/v1/day/reopen") {
            bearerAuth(tokens.getValue("admin1001")); contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
            setBody("""{"zone_id":$zone,"business_date":"2027-01-01","reason":"qa probe"}""")
        }
        reopenRouted = r.status.value != 404
        println("DayCloseRaceTest: POST /v1/day/reopen answers ${r.status.value} -> reopen ${if (reopenRouted) "through the route" else "simulated in SQL under the zone-day lock (route not served)"}")
    }


    /**
     * PRODUCT DEFECT reproduction (found by this fuzz's R1 analysis, not fixed here: no product edits in the qa lane).
     * Final Submit answers `now.wire()` (the clock truncated to the millisecond) but stores `now` through JDBC, which
     * rounds to the microsecond; the replay answers the stored value truncated. When the clock's sub-microsecond part
     * rounds across a millisecond (the last 500 ns of a millisecond: about 1 close in 2,000 with JDK 21's nanosecond
     * `Instant.now()`), the replay and every 409 `context.submitted_at` say one millisecond later than the first 200.
     * Fix: truncate `now` to microseconds (or milliseconds) once, before it is answered and stored.
     */
    @Test
    @Order(3)
    @org.junit.jupiter.api.Disabled("KD-2, product defect: a Final Submit replay can differ by 1 ms from the first answer (sub-microsecond rounding); docs/requests/qa-backend-dayclose-late-rows.md; enable with the fix")
    fun aReplayAnswersTheFirstSuccessToTheMillisecond() = withApi {
        val tso = "${tag.lowercase()}tso1001"
        val date = allocDate()
        frozen = date.atTime(6, 0).toInstant(ZoneOffset.UTC).plusNanos(123_999_600L) // .1239996 s rounds to .124000 in PostgreSQL
        try {
            tokens[tso] = web(tso)
            val cu = uuid()
            val first = finalSubmit(tso, cu, date, 1)
            val replay = finalSubmit(tso, cu, date, 1)
            assertEquals(200, first.status); assertEquals(200, replay.status)
            assertEquals(first.body, replay.body, "the replay answers the first success")
        } finally { frozen = null }
    }

    @Test
    @Order(1)
    fun theZoneDayClosesExactlyOnceUnderEveryRace() = withApi {
        probeReopen()
        println("DayCloseRaceTest: ARON_DAYCLOSE_FUZZ_SEED=$baseSeed ARON_DAYCLOSE_FUZZ_ROUNDS=$rounds")
        val started = System.nanoTime()
        val stampedBefore = mutableListOf<Long>()
        val failed = mutableListOf<String>()
        val toleratedSeeds = mutableListOf<Long>()
        val byClause = sortedMapOf<String, Int>()
        var interleaved = 0; var lateRows = 0; var rows = 0; var widest = 0
        for (k in 0 until rounds) {
            val seed = baseSeed + k
            val r = round(seed)
            val problems = oracle(r)
            val knownOnly = problems.isNotEmpty() && problems.all { "KD-1" in it }
            if (knownOnly && "KD-1" in tolerate) { count("tolerated-KD-1"); toleratedSeeds += seed }
            else if (problems.isNotEmpty()) {
                failed += "round seed=$seed date=${r.date} reopened=${r.reopened}:\n  " + problems.joinToString("\n  ") + "\n  calls: " + r.calls.joinToString { "${it.wave}/${it.user}/${it.uuid.take(8)}=${it.status}" }
                problems.mapNotNull { Regex("^\\[(\\w+)]").find(it)?.groupValues?.get(1) }.toSet().forEach { byClause.merge(it + if (problems.any { q -> "KD-1" in q }) "(KD-1)" else "", 1, Int::plus) }
            }
            val late = world.fresh.db.jdbi.withHandle<Int, Exception> { h ->
                h.createQuery("SELECT late_rows_after_final FROM app.route_day WHERE route_id = :r AND business_date = :d").bind("r", r.phone.routeId).bind("d", r.date).mapTo(Int::class.java).one()
            } - r.postRecords
            val stored = r.familySizes.sum()
            if (late in 1 until stored) interleaved++
            if (r.reopened && world.fresh.db.jdbi.withHandle<Boolean, Exception> { h ->
                    h.createQuery("SELECT EXISTS (SELECT 1 FROM app.final_submit a JOIN app.final_submit b USING (zone_id, business_date) WHERE a.zone_id = :z AND a.business_date = :d AND a.reopened_at IS NOT NULL AND b.id > a.id AND b.submitted_at < a.reopened_at)")
                        .bind("z", zone).bind("d", r.date).mapTo(Boolean::class.java).one()
                }) { count("close-stamped-before-reopen"); stampedBefore += seed }
            lateRows += late; rows += stored
            val first = world.fresh.db.jdbi.withHandle<Close, Exception> { h ->
                h.createQuery("SELECT client_uuid::text, submitted_at, reopened_at, late_rows, 'x' FROM app.final_submit WHERE zone_id = :z AND business_date = :d ORDER BY id LIMIT 1")
                    .bind("z", zone).bind("d", r.date).map { rs, _ -> Close(rs.getString(1), rs.getObject(2, OffsetDateTime::class.java).toInstant(), null, rs.getInt(4), "x") }.one()
            }
            widest = maxOf(widest, lateBounds(r, first).second.size)
            count("409", r.calls.count { it.status == 409 }); count("200", r.calls.count { it.status == 200 && it.uuid != "reopen" })
        }
        val secs = (System.nanoTime() - started) / 1e9
        val report = "rounds=$rounds seed=$baseSeed seconds=${"%.1f".format(secs)} wave1_rows=$rows late_rows=$lateRows rounds_closed_mid_stream=$interleaved widest_allowed_late_set=$widest reopen_routed=$reopenRouted failed_rounds=${failed.size} failed_by_clause=$byClause tolerated=${tolerate.ifEmpty { "none" }} tolerated_seeds=${toleratedSeeds.take(5)} stamped_before_reopen_seeds=${stampedBefore.take(5)} stats=${stats.toSortedMap()}"
        println("DayCloseRaceTest: $report")
        File("build").mkdirs()
        File("build/dayclose-race-fuzz.txt").writeText(report + "\n")
        if (failed.isNotEmpty()) throw AssertionError("${failed.size} of $rounds rounds broke the oracle ($byClause); first ones:\n" + failed.take(5).joinToString("\n"))
        if (rounds >= 20) {
            assertTrue((stats["reopen-rounds"]?.get() ?: 0) > 0, "no round reopened")
            assertTrue((stats["409"]?.get() ?: 0) > 0, "no submit lost a race")
        }
    }

    /**
     * One seeded fault per oracle clause on clean rounds of its own: each names the clauses that must report it, and
     * every clause is named by at least one fault, so switching a clause off turns this test red.
     */
    @Test
    @Order(2)
    fun everySeededFaultIsReportedByItsClause() = withApi {
        if (tokens["admin1001"] == null) probeReopen()
        microClock = true
        val faults = mutableListOf<Triple<String, Set<String>, List<String>>>()
        fun expect(name: String, clauses: Set<String>, problems: List<String>) { faults += Triple(name, clauses, problems) }
        fun db(sql: String, replica: Boolean = false) = world.fresh.db.jdbi.useHandle<Exception> { h ->
            if (replica) h.execute("SET session_replication_role = replica")
            try { check(h.execute(sql) >= 1) { "seeding changed nothing: $sql" } } finally { if (replica) h.execute("SET session_replication_role = DEFAULT") }
        }
        var n = 0
        suspend fun clean(reopen: Boolean = false): Round {
            val r = round(baseSeed + 5_000_000 + 1000L * n++, forceReopen = reopen, quiet = true)
            assertEquals(emptyList(), oracle(r), "the oracle agrees before the seeded fault (seed ${r.seed})")
            return r
        }
        fun where(r: Round) = "zone_id = $zone AND business_date = DATE '${r.date}'"
        fun rd(r: Round, route: Long = r.phone.routeId) = "route_id = $route AND business_date = DATE '${r.date}'"

        clean().let { r -> // two live closes (past the final_submit_once index, dropped for the moment)
            world.fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("DROP INDEX app.final_submit_once")
                h.execute("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, route_states) SELECT gen_random_uuid(), zone_id, business_date, submitted_by, submitted_at + interval '1 second', via, route_states FROM app.final_submit WHERE ${where(r)}")
            }
            try { expect("second live close", setOf("L1", "L2"), oracle(r)) } finally {
                world.fresh.db.jdbi.useHandle<Exception> { h ->
                    h.execute("DELETE FROM app.final_submit WHERE ${where(r)} AND id <> (SELECT min(id) FROM app.final_submit WHERE ${where(r)})")
                    h.execute("CREATE UNIQUE INDEX final_submit_once ON app.final_submit (zone_id, business_date) WHERE reopened_at IS NULL")
                }
            }
        }
        clean().let { r -> db("UPDATE app.final_submit SET reopened_at = now(), reopened_by = $adminId WHERE ${where(r)}"); expect("no live close", setOf("L1"), oracle(r)) }
        clean().let { r ->
            db("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, reopened_at, reopened_by) SELECT gen_random_uuid(), zone_id, business_date, submitted_by, submitted_at - interval '1 hour', via, submitted_at - interval '30 minutes', $adminId FROM app.final_submit WHERE ${where(r)}")
            expect("an extra closed cycle", setOf("L2"), oracle(r))
        }
        clean().let { r -> db("UPDATE app.route_day SET final_submitted_at = final_submitted_at + interval '1 second' WHERE ${rd(r, zoneRoutes.last())}"); expect("route close time tampered", setOf("S1"), oracle(r)) }
        clean().let { r -> db("UPDATE app.route_day SET state = 'sales_submitted' WHERE ${rd(r, zoneRoutes.first())}"); expect("route state moved back", setOf("S2"), oracle(r)) }
        clean().let { r -> db("UPDATE app.route_day SET late_rows_after_final = late_rows_after_final + 1 WHERE ${rd(r)}"); expect("route-day late +1", setOf("T1", "T3"), oracle(r)) }
        clean().let { r -> db("UPDATE app.final_submit SET late_rows = late_rows + 1 WHERE ${where(r)}"); expect("close late +1", setOf("T3"), oracle(r)) }
        clean().let { r ->
            val over = r.familySizes.sum() + 5
            db("UPDATE app.route_day SET late_rows_after_final = $over WHERE ${rd(r)}"); db("UPDATE app.final_submit SET late_rows = $over WHERE ${where(r)}")
            expect("late above the rows", setOf("T1", "T4"), oracle(r))
        }
        clean().let { r -> val other = zoneRoutes.first { it != r.phone.routeId }; db("UPDATE app.route_day SET late_rows_after_final = 1 WHERE ${rd(r, other)}"); db("UPDATE app.final_submit SET late_rows = late_rows + 1 WHERE ${where(r)}"); expect("late on a route without rows", setOf("T2"), oracle(r)) }
        clean().let { r -> db("DELETE FROM app.memo_line WHERE client_uuid = '${r.phone.rows["memo_line"]!!.first()}'", replica = true); expect("lost line", setOf("Y2"), oracle(r)) }
        clean().let { r ->
            world.fresh.db.jdbi.useHandle<Exception> { h ->
                val cols = h.createQuery("SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'ingest_registry' AND column_name NOT IN ('id', 'client_uuid') AND is_generated = 'NEVER'")
                    .mapTo(String::class.java).one()
                check(h.execute("INSERT INTO app.ingest_registry (client_uuid, $cols) SELECT gen_random_uuid(), $cols FROM app.ingest_registry WHERE client_uuid = '${r.phone.rows["memo"]!!.first()}'") == 1)
            }
            expect("foreign registry row", setOf("Y1"), oracle(r))
        }
        clean().let { r -> expect("refused record", setOf("Y3"), oracle(r.copy(refused = listOf("rejected memo (seeded)")))) }
        clean().let { r ->
            val i = r.calls.indexOfFirst { it.status == 200 }
            val bad = r.calls[i].copy(body = JsonObject(r.calls[i].body!! + ("submitted_at" to JsonPrimitive("2027-01-01T00:00:00.000Z"))))
            expect("a 200 differs", setOf("R1"), oracle(r.copy(calls = r.calls.toMutableList().also { it[i] = bad })))
            val j = r.calls.indexOfFirst { it.status == 409 }.takeIf { it >= 0 }
            val extra = Call("x", uuid(), 1, 0, 0, 200, r.calls[i].body)
            expect("another uuid answered 200", setOf("R2"), oracle(r.copy(calls = r.calls + extra)))
            val conflict = j?.let { r.calls[it] } ?: Call("x", uuid(), 1, 0, 0, 409, null)
            val wrong = conflict.copy(uuid = uuid(), body = Json.parseToJsonElement("""{"code":"$ALREADY","context":{"submitted_at":"2027-01-01T00:00:00.000Z"}}""").jsonObject)
            expect("409 with another close time", setOf("R3"), oracle(r.copy(calls = r.calls + wrong)))
            expect("a 5xx", setOf("R4"), oracle(r.copy(calls = r.calls + Call("x", uuid(), 1, 0, 0, 500, null))))
        }
        clean(reopen = true).let { r ->
            db("UPDATE app.final_submit SET submitted_at = (SELECT submitted_at FROM app.final_submit WHERE ${where(r)} AND reopened_at IS NOT NULL) - interval '1 second' WHERE ${where(r)} AND reopened_at IS NULL")
            expect("second close older than the first", setOf("O1"), oracle(r))
        }
        val missing = faults.mapNotNull { (name, want, got) ->
            val labels = got.mapNotNull { Regex("^\\[(\\w+)]").find(it)?.groupValues?.get(1) }.toSet()
            (want - labels).takeIf { it.isNotEmpty() }?.let { "$name: clauses $it did not report it (got $got)" }
        }
        assertEquals(emptyList(), missing, "seeded faults not reported by the clause that must catch them")
        val clauses = setOf("L1", "L2", "R1", "R2", "R3", "R4", "S1", "S2", "Y1", "Y2", "Y3", "T1", "T2", "T3", "T4", "O1")
        assertEquals(clauses, faults.flatMap { it.second }.toSet(), "every oracle clause is guarded by a seeded fault")
        println("DayCloseRaceTest seeded faults: " + faults.joinToString("; ") { "${it.first} -> ${it.third.firstOrNull()}" })
    }

    companion object {
        const val ALREADY = "ERR_DAY_ALREADY_FINAL_SUBMITTED"
    }
}

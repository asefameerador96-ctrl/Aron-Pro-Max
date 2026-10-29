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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tso-manual B3 (build contract s5 B3 tests "fuzz over all TSO record types (duplicates, out-of-order, partial
 * batches; device count == server count)"), the TSO counterpart of [SupervisorSyncFaultFuzzTest]: duplicate,
 * reordered, partial, truncated and killed-mid-batch uploads of every record type the TSO app queues never create a
 * second record or double a number, through the production wiring with the lane's handlers (LeaveApplicationHandler,
 * FeedbackChecks, VisitPlanRecords, TaskRecords).
 *
 * TSO phones (flavour `tso`, client `app_tso`), each the TSO of a mirrored division ([TsoWorld], `<tag>tso1001` on
 * T-<TAG>), capture random My Call days on 2027-01-03:
 * - Set Plan: a `visit_plan` (envelope route, plan date today or later) and one to three `visit_plan_outlet` rows of
 *   outlets of that route (the plan's family, rank 1);
 * - Visit Query on some planned outlets: a `call_assessment` (`retailer_questionnaire`, the V0139 rubric, parent the
 *   planned outlet) and one `call_assessment_answer` per question (its family, rank 1);
 * - Assign Task: a `task` for the route's primary SR on the visited outlet (envelope route);
 * - sometimes a `leave_application` and a `feedback` (either category, sometimes with an image).
 * Every TSO type is covered (support tickets are lane B1's, in its own fuzz test).
 *
 * Fault schedules, one per batch, as the supervisor test: partial, reorder (children before their parents park and
 * resolve), ride-along duplicates, replay of the same batch_uuid, a dropped response, a truncated body, the same batch
 * twice in flight and a process kill after the n-th stored record. After every schedule the whole day is resent under
 * new batch uuids (every row a duplicate) and [oracle] judges: [A1] rows per table for the user and day equal the
 * device's, [A2] none missing, [A3] none the device never made, [B] no extra rows over all dates, [B2] no client_uuid
 * twice, [C] rows by the phone equal the device's, [D] one registry row per device uuid and no other, [F] the numbers
 * (leave days, planned outlets, answers, tasks) equal the device's, [R] every plan holds the device's route and every
 * planned outlet the device's plan, [I] server_totals accepted per type equal the device's counts, [J] nothing
 * rejected, parked or quarantined. A second test seeds one fault per clause and requires that clause to report it.
 *
 * Size: `ARON_TSO_FUZZ_SCHEDULES` (default 150), `ARON_TSO_FUZZ_SEED` (default fixed), `ARON_TSO_FUZZ_PHONES` (default
 * 3, in parallel). A failure names the phone and the schedule seed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TsoSyncFaultFuzzTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val baseSeed = System.getenv("ARON_TSO_FUZZ_SEED")?.toLongOrNull() ?: 20261029L
    private val phoneCount = System.getenv("ARON_TSO_FUZZ_PHONES")?.toIntOrNull()?.coerceIn(1, 6) ?: 3
    private val schedules = (System.getenv("ARON_TSO_FUZZ_SCHEDULES")?.toIntOrNull() ?: 150).coerceIn(1, 3_000 * phoneCount)
    private val kill = KillSwitch()
    private lateinit var world: TsoWorld
    private lateinit var phones: List<Phone>
    private lateinit var cleanPhone: Phone
    private lateinit var probes: List<Phone>
    private val probeCount = 8
    private val day = "2027-01-03"
    private val faultsSeen = ConcurrentHashMap<String, AtomicInteger>()

    /** A process death, not an exception: ingest catches Exception per record and per family, never an Error. */
    class KillProcess : Error("simulated API process kill in the middle of a TSO batch")

    /** Armed per device for its next batch: the n-th stored record of that device dies with the process. */
    class KillSwitch : RecordHandler {
        override val types = TYPES.toSet()
        private val armed = ConcurrentHashMap<Long, AtomicInteger>()
        val fired = AtomicInteger()
        fun arm(deviceId: Long, after: Int) { armed[deviceId] = AtomicInteger(after) }
        fun disarm(deviceId: Long) { armed.remove(deviceId) }
        override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
            val left = rec.deviceId?.let { armed[it] } ?: return
            if (left.decrementAndGet() <= 0) { armed.remove(rec.deviceId); fired.incrementAndGet(); throw KillProcess() }
        }
    }

    /** An outlet of a route of the TSO's zone and the route's primary SR. */
    data class Stop(val outletId: Long, val routeId: Long, val srUserId: Long)

    /** One TSO phone and its cumulative device-side truth. */
    class Phone(val tag: String, val tsoPhone: TsoPhone, val routes: Map<Long, List<Stop>>, val rubricId: Long) {
        val userId get() = tsoPhone.userId
        val deviceId get() = tsoPhone.deviceId
        var token = ""
        private var tick = 0L
        val rows = HashMap<String, MutableSet<String>>()
        val accepted = sortedMapOf<String, Int>()
        var leaveDays = 0L
        val planRoute = HashMap<String, Long>() // plan uuid -> route
        val outletPlan = HashMap<String, String>() // plan outlet uuid -> plan uuid
        var answers = 0L

        private fun at(): String {
            val t = Instant.parse("2027-01-02T18:30:00Z").plusMillis(10L * tick++)
            check(t.isBefore(Instant.parse("2027-01-03T03:55:00Z"))) { "capture time $t passed the fixed clock" }
            return TS.format(t)
        }

        private fun env(type: String, cu: String, family: String, rank: Int, route: Long?, payload: String) = buildJsonObject {
            val at = at()
            put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
            put("business_date", "2027-01-03"); put("captured_at", at); put("captured_elapsed_ms", 18_330_000); put("boot_count", 21)
            put("clock_offset_ms", -900); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "2027-01-03:2")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", Json.parseToJsonElement(payload))
        }

        /** One My Call day of the TSO; every uuid is drawn from [rnd]. [everything] makes it carry every type. */
        fun captureDay(rnd: Random, everything: Boolean = false): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            fun uuid() = rndUuid(rnd)
            val route = routes.keys.toList()[rnd.nextInt(routes.size)]
            val stops = routes.getValue(route).shuffled(rnd).take(rnd.nextInt(1, 4))
            val plan = uuid()
            val date = LocalDate.parse("2027-01-03").plusDays(rnd.nextLong(0, 5))
            out += env("visit_plan", plan, plan, 0, route, """{"plan_date":"$date","note":${if (rnd.nextBoolean()) "\"Market day\"" else "null"}}""")
            planRoute[plan] = route
            for ((i, s) in stops.withIndex()) {
                val po = uuid()
                out += env("visit_plan_outlet", po, plan, 1, route, """{"plan_client_uuid":"$plan","outlet_id":${s.outletId}}""")
                outletPlan[po] = plan
                if (!(everything && i == 0) && rnd.nextInt(2) == 0) continue
                // Visit Query on this planned outlet, then perhaps Assign Task.
                val a = uuid()
                val delegate = everything || rnd.nextBoolean()
                out += env("call_assessment", a, a, 0, route, """{"kind":"retailer_questionnaire","visit_client_uuid":null,"visit_plan_outlet_client_uuid":"$po","rubric_id":$rubricId,
                    "rubric_version":1,"total_score":0,"max_score":0,"delegate_task":$delegate}""")
                for (c in 1..2) if (everything || rnd.nextInt(4) != 0) {
                    out += env("call_assessment_answer", uuid(), a, 1, route, """{"assessment_client_uuid":"$a","criterion_id":$c,"answer_text":"${if (c == 1) "হ্যাঁ" else "Sometimes"} $tick"}""")
                    answers++
                }
                if (delegate) {
                    val t = uuid()
                    out += env("task", t, t, 0, route, """{"task_type_code":"oos","assignee_user_id":${s.srUserId},"outlet_id":${s.outletId},"title":"OOS",
                        "description":"Refill ${s.outletId}","due_date":"$date","source_visit_client_uuid":null}""")
                }
            }
            if (everything || rnd.nextInt(3) == 0) {
                val l = uuid(); val days = rnd.nextInt(1, 4)
                out += env("leave_application", l, l, 0, null, """{"leave_type_code":"${listOf("casual", "sick", "earn")[rnd.nextInt(3)]}","from_date":"${LocalDate.parse("2027-01-03").plusDays(rnd.nextLong(1, 40))}","days":$days,"reason":"Family $tick"}""")
                leaveDays += days
            }
            if (everything || rnd.nextInt(3) == 0) {
                val f = uuid()
                val photo = if (rnd.nextBoolean()) "\"${uuid()}\"" else "null"
                out += env("feedback", f, f, 0, null, """{"category_code":"${if (rnd.nextBoolean()) "suggestion" else "feedback"}","title":"Shelf $tick","description":"The retailer asks for a rack.","photo_uuid":$photo}""")
            }
            out.forEach { r -> val t = r.type(); rows.getOrPut(t) { mutableSetOf() } += r.uuid(); accepted.merge(t, 1, Int::plus) }
            return out
        }

        fun batch(records: List<JsonObject>, batchUuid: String): String = buildJsonObject {
            put("batch_uuid", batchUuid); put("device_uuid", tsoPhone.deviceUuid); put("schema_version", 1); put("app_version", ApiWorld.APP_VERSION)
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList()))
            put("device_counts", buildJsonObject { put("2027-01-03", buildJsonObject { accepted.forEach { (t, n) -> put(t, n) } }) })
            put("records", JsonArray(records))
        }.toString()
    }

    private fun phoneFor(tag: String, rubricId: Long): Phone {
        val tp = world.phone("${tag.lowercase()}tso1001", "tso")
        val stops = world.rows(
            """
            SELECT o.id AS o, o.route_id AS r, (SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = o.route_id AND a.kind = 'primary' AND a.valid_to IS NULL ORDER BY a.id LIMIT 1) AS sr
              FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id WHERE z.code = 'Z-$tag' AND o.route_id IS NOT NULL AND o.status = 'active' ORDER BY o.id
            """.trimIndent(),
        ).map { Stop((it["o"] as Number).toLong(), (it["r"] as Number).toLong(), (it["sr"] as Number).toLong()) }
        return Phone(tag, tp, stops.groupBy { it.routeId }, rubricId)
    }

    // ---- the oracle ------------------------------------------------------------------------------------------------

    private val tables = linkedMapOf(
        "visit_plan" to "app.visit_plan", "visit_plan_outlet" to "app.visit_plan_outlet", "call_assessment" to "app.call_assessment",
        "call_assessment_answer" to "app.call_assessment_answer", "task" to "app.task", "leave_application" to "app.leave_application", "feedback" to "app.feedback",
    )

    internal fun oracle(p: Phone, last: JsonObject?): List<String> {
        val problems = mutableListOf<String>()
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            for ((type, table) in tables) {
                val device = p.rows[type].orEmpty()
                val server = h.createQuery("SELECT client_uuid::text FROM $table WHERE user_id = :u AND business_date = CAST(:d AS date)").bind("u", p.userId).bind("d", day).mapTo(String::class.java).list()
                if (server.size != device.size) problems += "[A1] $type: server holds ${server.size} rows, the device ${device.size}"
                val missing = device - server.toSet(); val extra = server.toSet() - device
                if (missing.isNotEmpty()) problems += "[A2] $type: ${missing.size} device rows missing on the server, e.g. ${missing.first()}"
                if (extra.isNotEmpty()) problems += "[A3] $type: ${extra.size} server rows the device never made, e.g. ${extra.first()}"
                val byUser = h.createQuery("SELECT count(*) FROM $table WHERE user_id = :u").bind("u", p.userId).mapTo(Long::class.java).one()
                if (byUser != device.size.toLong()) problems += "[B] $type: the user holds $byUser rows over all dates, the device made ${device.size}"
                val twice = h.createQuery("SELECT count(*) - count(DISTINCT client_uuid) FROM $table WHERE user_id = :u").bind("u", p.userId).mapTo(Long::class.java).one()
                if (twice != 0L) problems += "[B2] $type: $twice client_uuids stored twice"
                val byDevice = h.createQuery("SELECT count(*) FROM $table WHERE device_id = :d").bind("d", p.deviceId).mapTo(Long::class.java).one()
                if (byDevice != device.size.toLong()) problems += "[C] $type: the phone holds $byDevice rows, the device made ${device.size}"
            }
            val made = p.rows.values.flatten().toSet()
            val registry = h.createQuery("SELECT client_uuid::text FROM app.ingest_registry WHERE user_id = :u").bind("u", p.userId).mapTo(String::class.java).list()
            if (registry.size != made.size || registry.toSet() != made) problems += "[D] ingest registry: ${registry.size} rows for the user, the device made ${made.size}"
            val days = h.createQuery("SELECT coalesce(sum(days), 0) FROM app.leave_application WHERE user_id = :u").bind("u", p.userId).mapTo(Long::class.java).one()
            val planned = h.createQuery("SELECT count(*) FROM app.visit_plan_outlet po JOIN app.visit_plan v ON v.client_uuid = po.plan_client_uuid WHERE v.user_id = :u").bind("u", p.userId).mapTo(Long::class.java).one()
            val answered = h.createQuery("SELECT count(*) FROM app.call_assessment_answer a JOIN app.call_assessment q ON q.client_uuid = a.assessment_client_uuid WHERE q.user_id = :u").bind("u", p.userId).mapTo(Long::class.java).one()
            val tasks = h.createQuery("SELECT count(*) FROM app.task WHERE user_id = :u AND route_id IS NOT NULL").bind("u", p.userId).mapTo(Long::class.java).one()
            val dev = listOf(p.leaveDays, p.outletPlan.size.toLong(), p.answers, p.rows["task"].orEmpty().size.toLong())
            if (dev != listOf(days, planned, answered, tasks)) problems += "[F] numbers (leave days, planned outlets, answers, routed tasks) device $dev server ${listOf(days, planned, answered, tasks)}"
            val routes = h.createQuery("SELECT client_uuid::text, route_id FROM app.visit_plan WHERE user_id = :u").bind("u", p.userId).map { rs, _ -> rs.getString(1) to (rs.getObject(2) as Long?) }.list().toMap()
            val wrongRoute = p.planRoute.filter { (pl, r) -> routes[pl] != r }
            val parents = h.createQuery("SELECT client_uuid::text, plan_client_uuid::text FROM app.visit_plan_outlet WHERE user_id = :u").bind("u", p.userId).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
            val wrongPlan = p.outletPlan.filter { (po, pl) -> parents[po] != pl }
            if (wrongRoute.isNotEmpty() || wrongPlan.isNotEmpty()) problems += "[R] ${wrongRoute.size} plans hold another route, ${wrongPlan.size} planned outlets another plan"
        }
        if (last != null) {
            val totals = last["server_totals"]!!.jsonArray.map { it.jsonObject }.singleOrNull { it["business_date"]!!.jsonPrimitive.content == day }
            if (totals == null) problems += "[I] no server_totals for $day" else {
                val byType = totals["by_type"]!!.jsonObject
                val acc = byType.mapValues { it.value.jsonObject["accepted"]!!.jsonPrimitive.int }.filterValues { it > 0 }
                if (acc != p.accepted.toMap()) problems += "[I] accepted per type (device count vs server count) device ${p.accepted} server $acc"
                val stuck = byType.filterValues { v -> v.jsonObject["rejected"]!!.jsonPrimitive.content != "0" || v.jsonObject["quarantined"]!!.jsonPrimitive.content != "0" }
                if (stuck.isNotEmpty()) problems += "[J] rejected, parked or quarantined rows on the server: $stuck"
            }
        }
        return problems
    }

    // ---- one schedule ----------------------------------------------------------------------------------------------

    private fun seen(f: String) = faultsSeen.getOrPut(f) { AtomicInteger() }.incrementAndGet()

    private fun acceptedRows(p: Phone): Long = world.one("SELECT count(*) FROM app.ingest_registry WHERE user_id = ${p.userId} AND status = 'accepted'")

    private fun parentOf(r: JsonObject): String? = r["payload"]!!.jsonObject.let { pl ->
        PARENT_FIELDS.firstNotNullOfOrNull { f -> pl[f]?.let { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content } }
    }

    private suspend fun HttpClient.send(p: Phone, body: ByteArray): Pair<Int, JsonObject?> = try {
        val r = post("/v1/sync/batch") {
            bearerAuth(p.token); header("X-Device-Id", p.tsoPhone.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            setBody(ByteArrayContent(body, ContentType.Application.Json))
        }
        val text = r.bodyAsText()
        r.status.value to if (r.status == HttpStatusCode.OK) Json.parseToJsonElement(text).jsonObject else null
    } catch (e: Throwable) {
        if (e is AssertionError) throw e
        599 to null
    }

    private suspend fun HttpClient.schedule(p: Phone, seed: Long, everything: Boolean = false): JsonObject {
        val rnd = Random(seed)
        val day0 = p.captureDay(rnd, everything)
        val acked = HashSet<String>()
        fun note(resp: JsonObject?) = resp?.get("acks")?.jsonArray?.forEach { a ->
            val o = a.jsonObject
            val st = o["status"]!!.jsonPrimitive.content
            if (st == "accepted" || st == "duplicate") acked += o["client_uuid"]!!.jsonPrimitive.content
            if (st == "rejected" && o["code"]?.jsonPrimitive?.content == "parent_missing") seen("parked")
            if (st == "quarantined" || (st == "rejected" && o["retryable"]?.jsonPrimitive?.content != "true")) {
                throw AssertionError("phone ${p.tag} seed=$seed: a valid TSO record was refused for good: $o")
            }
        }
        var pending = day0.shuffled(rnd)
        var rounds = 0
        while (pending.isNotEmpty() && rounds++ < 40) {
            val take = rnd.nextInt(1, pending.size + 1)
            if (take < pending.size) seen("partial")
            val chunk = pending.take(take).toMutableList()
            if (rnd.nextInt(3) == 0) day0.filter { it.uuid() in acked }.shuffled(rnd).take(2).forEach { chunk += it; seen("ride-along") }
            val records = chunk.shuffled(rnd).distinctBy { it.uuid() }
            val body = gz(p.batch(records, rndUuid(rnd)))
            when (rnd.nextInt(9)) {
                0 -> {
                    seen("drop"); send(p, body)
                    val (st, r) = send(p, body); note(r)
                    if (r != null && r["replayed"]?.jsonPrimitive?.content != "true") throw AssertionError("phone ${p.tag} seed=$seed: a completed batch resent is a replay ($st)")
                }
                1 -> {
                    seen("truncate")
                    val (st, _) = send(p, body.copyOf(rnd.nextInt(1, body.size)))
                    if (st !in 400..499) throw AssertionError("phone ${p.tag} seed=$seed: a truncated body was answered $st, not a 4xx")
                    val (st2, r) = send(p, body)
                    if (st2 == 409) throw AssertionError("phone ${p.tag} seed=$seed: a truncated batch claimed its batch_uuid")
                    note(r)
                }
                2 -> {
                    seen("kill")
                    val before = acceptedRows(p)
                    kill.arm(p.deviceId, rnd.nextInt(1, records.size + 1))
                    val (st, r) = send(p, body)
                    kill.disarm(p.deviceId)
                    if (st == 200) note(r) else {
                        seen("kill-fired")
                        if (acceptedRows(p) > before) seen("kill-partial")
                        val (_, r2) = send(p, body); note(r2)
                    }
                }
                3 -> {
                    seen("replay")
                    val (_, r1) = send(p, body); note(r1)
                    val (_, r2) = send(p, body)
                    if (r1 != null && r2 != null && r1["acks"] != r2["acks"]) throw AssertionError("phone ${p.tag} seed=$seed: a replay answered other acks")
                    note(r2)
                }
                4 -> {
                    seen("concurrent")
                    coroutineScope { listOf(async { send(p, body) }, async { send(p, body) }).awaitAll() }.forEach { (st, r) ->
                        if (st !in setOf(200, 409, 429, 503)) throw AssertionError("phone ${p.tag} seed=$seed: a concurrent copy answered $st")
                        note(r)
                    }
                }
                5 -> {
                    seen("reorder")
                    val orphans = records.filter { r -> parentOf(r).let { it != null && it !in acked } }
                    if (orphans.isNotEmpty()) {
                        val r = send(p, gz(p.batch(orphans, rndUuid(rnd)))).second
                        r?.get("acks")?.jsonArray?.count { it.jsonObject["code"]?.jsonPrimitive?.content == "parent_missing" }?.let { n -> repeat(n) { seen("reorder-parked") } }
                        note(r)
                    }
                    note(send(p, body).second)
                }
                else -> { seen("plain"); note(send(p, body).second) }
            }
            pending = pending.filter { it.uuid() !in acked }.let { left -> if (rnd.nextBoolean()) left.shuffled(rnd) else left }
        }
        if (pending.isNotEmpty()) throw AssertionError("phone ${p.tag} seed=$seed: ${pending.size} rows never acknowledged after $rounds rounds")
        var last: JsonObject? = null
        for (c in day0.shuffled(rnd).chunked(25)) {
            val (st, r) = send(p, gz(p.batch(c, rndUuid(rnd))))
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
        val tags = (0 until phoneCount + 1 + probeCount).map { i -> "T" + ('A' + i / 26) + ('A' + i % 26) }
        world = TsoWorld(repoRoot, mirrors = tags, extraRecordHandlers = listOf(kill))
        val rubric = world.one("SELECT id FROM app.rubric WHERE kind = 'retailer_questionnaire' AND status = 'active' ORDER BY id LIMIT 1")
        val all = tags.map { phoneFor(it, rubric) }
        phones = all.take(phoneCount)
        cleanPhone = all[phoneCount]
        probes = all.drop(phoneCount + 1)
        check(all.all { p -> p.routes.size == 3 && p.routes.values.all { it.size >= 3 } }) { "every mirrored zone has its three routes with outlets and a primary SR" }
    }

    @AfterAll
    fun tearDown() { world.close() }

    private suspend fun HttpClient.signIn(p: Phone) { if (p.token.isEmpty()) p.token = with(world) { login(p.tsoPhone) } }

    @Test
    @Order(1)
    fun aCleanTsoDayOfEveryTypeIsAccepted() = world.withApi {
        val p = cleanPhone
        signIn(p)
        val records = p.captureDay(Random(baseSeed - 1), everything = true)
        assertEquals(TYPES.toSet(), records.map { it.type() }.toSet(), "the clean day carries every TSO type")
        val (st, r) = send(p, gz(p.batch(records, rndUuid(Random(baseSeed - 2)))))
        assertEquals(200, st)
        val notAccepted = r!!["acks"]!!.jsonArray.map { it.jsonObject }.filter { it["status"]!!.jsonPrimitive.content != "accepted" }
        assertEquals(emptyList(), notAccepted, "every TSO record of a clean day is accepted")
        assertEquals(emptyList(), oracle(p, r))
    }

    @Test
    @Order(2)
    fun everyTsoFaultScheduleEndsWithExactlyTheDevicesRowsAndNumbers() = world.withApi {
        val client = this
        for (p in phones) signIn(p)
        println("TsoSyncFaultFuzzTest: ARON_TSO_FUZZ_SEED=$baseSeed ARON_TSO_FUZZ_SCHEDULES=$schedules ARON_TSO_FUZZ_PHONES=$phoneCount")
        val started = System.nanoTime()
        coroutineScope {
            phones.mapIndexed { i, p -> async { var k = i; while (k < schedules) { client.schedule(p, baseSeed + k); k += phones.size } } }.awaitAll()
        }
        val secs = (System.nanoTime() - started) / 1e9
        val byType = TYPES.associateWith { t -> phones.sumOf { it.rows[t]?.size ?: 0 } }
        val report = "schedules=$schedules phones=$phoneCount seconds=${"%.0f".format(secs)} kills_fired=${kill.fired.get()} faults=${faultsSeen.toSortedMap()} rows=${byType.values.sum()} by_type=$byType"
        println("TsoSyncFaultFuzzTest: $report")
        File("build").mkdirs()
        File("build/tso-sync-fault-fuzz.txt").writeText(report + "\n")
        assertTrue(TYPES.all { (byType[it] ?: 0) > 0 }, "every TSO type was fuzzed: $byType")
        if (schedules >= 30) {
            listOf("drop", "truncate", "kill", "replay", "concurrent", "reorder", "reorder-parked", "partial", "plain", "parked").forEach { f -> assertTrue((faultsSeen[f]?.get() ?: 0) > 0, "fault $f never ran") }
            assertTrue(kill.fired.get() > 0, "the kill switch never fired")
        }
    }

    /** One seeded fault per oracle clause on a probe phone with a clean, oracle-approved day: the clause must report it. */
    @Test
    @Order(3)
    fun everySeededTsoFaultIsReportedByItsOracleClause() = world.withApi {
        val faults = mutableListOf<Triple<String, Set<String>, List<String>>>()
        fun expect(name: String, clauses: Set<String>, problems: List<String>) { faults += Triple(name, clauses, problems) }
        fun db(sql: String) = world.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            try { check(h.execute(sql) >= 1) { "seeding changed nothing: $sql" } } finally { h.execute("SET session_replication_role = DEFAULT") }
        }
        fun copyRow(table: String, where: String, fresh: Map<String, String>) = world.fresh.db.jdbi.useHandle<Exception> { h ->
            val cols = h.createQuery("SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema || '.' || table_name = :t AND column_name <> 'id' AND is_generated = 'NEVER' AND column_name <> ALL(:skip)")
                .bind("t", table).bind("skip", fresh.keys.toTypedArray()).mapTo(String::class.java).one()
            check(h.execute("INSERT INTO $table (${fresh.keys.joinToString(", ")}, $cols) SELECT ${fresh.values.joinToString(", ")}, $cols FROM $table WHERE $where LIMIT 1") == 1)
        }
        var probe = 0
        suspend fun fresh(): Pair<Phone, JsonObject> {
            val p = probes[probe]; signIn(p)
            val last = schedule(p, baseSeed + 1_000_000 + 100_000L * probe++, everything = true)
            assertEquals(emptyList(), oracle(p, last), "the oracle agrees before the seeded fault")
            return p to last
        }
        fun first(p: Phone, type: String) = p.rows[type]!!.first()

        fresh().let { (p, last) -> // a planned outlet stored twice (a broken upsert)
            copyRow("app.visit_plan_outlet", "client_uuid = '${first(p, "visit_plan_outlet")}'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL"))
            expect("duplicate planned outlet", setOf("A1", "A3", "B", "C", "F"), oracle(p, last))
        }
        fresh().let { (p, last) -> // the same leave uuid stored again on another date (past a dropped unique constraint)
            val unique = world.scalar("SELECT conname FROM pg_constraint WHERE conrelid = 'app.leave_application'::regclass AND contype = 'u' AND pg_get_constraintdef(oid) = 'UNIQUE (client_uuid)'")!!
            world.exec("ALTER TABLE app.leave_application DROP CONSTRAINT $unique")
            try {
                copyRow("app.leave_application", "client_uuid = '${first(p, "leave_application")}'", mapOf("external_ref" to "NULL", "business_date" to "business_date - 1"))
                expect("same leave uuid twice", setOf("B", "B2", "C", "F"), oracle(p, last))
                db("DELETE FROM app.leave_application WHERE client_uuid = '${first(p, "leave_application")}' AND business_date <> DATE '$day'")
            } finally { world.exec("ALTER TABLE app.leave_application ADD CONSTRAINT $unique UNIQUE (client_uuid)") }
        }
        fresh().let { (p, last) -> // a lost answer
            db("DELETE FROM app.call_assessment_answer WHERE client_uuid = '${first(p, "call_assessment_answer")}'")
            expect("lost answer", setOf("A1", "A2", "B", "C", "F"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a plan moved to another route
            db("UPDATE app.visit_plan SET route_id = route_id + 100000 WHERE client_uuid = '${first(p, "visit_plan")}'")
            expect("plan route changed", setOf("R"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a foreign registry row of the user
            copyRow("app.ingest_registry", "client_uuid = '${first(p, "feedback")}'", mapOf("client_uuid" to "gen_random_uuid()"))
            expect("foreign registry row", setOf("D"), oracle(p, last))
        }
        fresh().let { (p, last) -> // an identity swap: the feedback lost, a foreign one in its place
            val lost = first(p, "feedback")
            copyRow("app.feedback", "client_uuid = '$lost'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL"))
            db("DELETE FROM app.feedback WHERE client_uuid = '$lost'")
            expect("identity swap", setOf("A2", "A3"), oracle(p, last))
        }
        fresh().let { (p, last) -> // the server's own answer differs from the device
            fun tamper(edit: (JsonObject) -> JsonObject): JsonObject = JsonObject(last + ("server_totals" to JsonArray(last["server_totals"]!!.jsonArray.map { it.jsonObject }.map { t -> if (t["business_date"]!!.jsonPrimitive.content != day) t else edit(t) })))
            fun bump(o: JsonObject, path: List<String>): JsonObject = JsonObject(o + (path[0] to if (path.size == 1) JsonPrimitive(o[path[0]]!!.jsonPrimitive.content.toLong() + 1) else bump(o[path[0]]!!.jsonObject, path.drop(1))))
            expect("server accepted count differs", setOf("I"), oracle(p, tamper { bump(it, listOf("by_type", "visit_plan", "accepted")) }))
            expect("server holds a quarantined row", setOf("J"), oracle(p, tamper { bump(it, listOf("by_type", "leave_application", "quarantined")) }))
        }
        fresh().let { (p, last) -> // a doubled number: a leave's days doubled
            db("UPDATE app.leave_application SET days = days * 2 WHERE client_uuid = '${first(p, "leave_application")}'")
            expect("doubled leave days", setOf("F"), oracle(p, last))
        }
        check(probe == probes.size) { "probes used $probe of ${probes.size}" }
        val missing = faults.mapNotNull { (name, want, got) -> val labels = got.mapNotNull { Regex("^\\[(\\w+)]").find(it)?.groupValues?.get(1) }.toSet(); (want - labels).takeIf { it.isNotEmpty() }?.let { "$name: clauses $it did not report it (got $got)" } }
        assertEquals(emptyList(), missing, "seeded faults not reported by the clause that must catch them")
        assertEquals(setOf("A1", "A2", "A3", "B", "B2", "C", "D", "F", "R", "I", "J"), faults.flatMap { it.second }.toSet(), "every oracle clause is guarded by a seeded fault")
    }

    companion object {
        /** The record types the TSO app queues (A3's outbox), parents first. */
        val TYPES = listOf("visit_plan", "visit_plan_outlet", "call_assessment", "call_assessment_answer", "task", "leave_application", "feedback")

        /** Payload members that name a record's parent. */
        private val PARENT_FIELDS = listOf("assessment_client_uuid", "visit_plan_outlet_client_uuid", "plan_client_uuid")
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun rndUuid(rnd: Random): String {
            val msb = (rnd.nextLong() and -0xf001L) or 0x4000L
            val lsb = (rnd.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE
            return UUID(msb, lsb).toString()
        }
    }
}

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
 * N-024, the AMO counterpart of [SyncFaultScheduleFuzzTest]: duplicate, reordered, partial, truncated and killed-mid-batch
 * uploads of the supervisor record types never create a second record or double a number.
 *
 * Several AMO phones (flavour `amo`, package `com.aktcl.aron.amo`, signed in with client `app_amo`): `amo1001` on Z-MIR
 * and the AMOs of mirrored zones ([ApiWorld] mirrors, `<tag>amo1001` on Z-<tag>, zone reach) capture random supervisor
 * days through `POST /v1/sync/batch`. Each call on an outlet of the AMO's zone is one family:
 * - `visit` (the day's first call `amo_joint_call`, the others `amo_control_call`; `assessed_user_id` = the route's
 *   primary SR) and its `visit_close`;
 * - a `call_assessment` (`joint_call`, the seeded rubric, assessed user = the route's SR) and one
 *   `call_assessment_answer` per criterion (scored 1 to 5, yes/no and text);
 * - a `distribution_check` and one to four `distribution_check_line` rows (brand-level `product_node` ids of the seed);
 * - sometimes a `task` assigned to the route's SR (`<tag>sr1001`) with one or two `task_event`s (resolved, then maybe
 *   reopened), so the task's server status follows the newest event by capture time.
 * Every type of the list is covered; the rubric and its criteria are seeded with plain SQL (no foreign key ties an
 * assessment to them, but the reference data is real). No type had to be left out.
 *
 * Fault schedules, one fault per batch: **partial** (a random subset, so children often go before their parents and
 * park), **reorder** (the batch's children whose parents are not acknowledged yet are sent first on their own: they
 * park, counted as `reorder-parked`, then resolve with the whole batch), **ride-along** duplicates,
 * **replay** (the same batch_uuid twice), **drop** (the response is lost, the batch is resent), **truncate** (the gzip
 * body is cut, a 4xx is required, then the whole batch is resent), **concurrent** (the same batch twice in flight) and
 * **kill** ([KillSwitch] throws an Error out of ingest after the n-th stored record; the phone resends; `kill-partial`
 * counts the kills that left earlier families committed, accepted registry rows before vs after). After every
 * schedule the whole day is resent under new batch uuids (all must be duplicates), then [oracle] judges.
 *
 * Oracle clauses: [A1] row count per table for the user and day equals the device's, [A2] no device row missing,
 * [A3] no row the device never made, [B] no extra rows of the AMO user over all dates, [B2] no client_uuid stored twice,
 * [C] rows by the phone equal the device's, [D] one ingest_registry row per device uuid and no other, [F] the numbers
 * (assessment total and max scores, answer scores, present and out-of-stock brand lines) equal the device's sums,
 * [T] every task's status equals the device's (newest event wins), [L] over ALL users, the rows linked to the device's
 * parents (visit, assessment, check, task) number exactly the device's children, [V] every answer (score, yes/no,
 * text), brand line (brand, present, oos) and assessment (total, max, assessed user) holds the device's own values,
 * [I] server_totals accepted per type equals the
 * device's, [J] no rejected, parked or quarantined row in server_totals, [K] server_totals carries the day. A second
 * test seeds one fault per clause and requires that clause to report it.
 *
 * Size: `ARON_SUPERVISOR_FUZZ_SCHEDULES` (default 60, about 30 s), `ARON_SUPERVISOR_FUZZ_SEED` (default fixed),
 * `ARON_SUPERVISOR_FUZZ_PHONES` (default 3, run in parallel, each its schedules in sequence; at most 10,000 schedules
 * per phone, so capture times stay before the fixed clock). A failure names the phone and the schedule seed; a phone's
 * schedules build on each other, so rerun with the same three values. Record and batch uuids are drawn from the
 * schedule's Random, so a rerun sends the same records; what the server answers to the concurrent twins (which copy
 * wins) and so the order of later draws may still differ, so a rerun is very likely, not certain, to repeat a failure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SupervisorSyncFaultFuzzTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val baseSeed = System.getenv("ARON_SUPERVISOR_FUZZ_SEED")?.toLongOrNull() ?: 20261017L
    private val phoneCount = System.getenv("ARON_SUPERVISOR_FUZZ_PHONES")?.toIntOrNull()?.coerceIn(1, 8) ?: 3
    /** At most 10,000 schedules per phone, so capture times never pass the fixed clock ([Phone] capture tick). */
    private val schedules = (System.getenv("ARON_SUPERVISOR_FUZZ_SCHEDULES")?.toIntOrNull() ?: 60).coerceIn(1, 10_000 * phoneCount)
    private val kill = KillSwitch()
    private lateinit var world: ApiWorld
    private lateinit var phones: List<Phone>
    private lateinit var cleanPhone: Phone
    /** Phones of their own for the seeded-fault test, so a failing fuzz run cannot leave them half-delivered. */
    private lateinit var probes: List<Phone>
    private val probeCount = 14
    private val day = "2027-01-03"
    private val faultsSeen = ConcurrentHashMap<String, AtomicInteger>()

    // ---- the server-side kill --------------------------------------------------------------------------------------

    /** A process death, not an exception: ingest catches Exception per record and per family, never an Error. */
    class KillProcess : Error("simulated API process kill in the middle of a supervisor batch")

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

    // ---- the AMO phone ---------------------------------------------------------------------------------------------

    /** An outlet of the AMO's zone, its route and the route's primary SR. */
    data class Stop(val outletId: Long, val routeId: Long, val srUserId: Long?)

    /** One AMO phone and its cumulative device-side truth. */
    class Phone(
        val day: String, val tag: String, val deviceUuid: String, val deviceId: Long, val userId: Long, val username: String,
        val stops: List<Stop>, val brands: List<Long>, val rubricId: Long, val criteria: List<Pair<Long, String>>, val zoneSr: Long,
    ) {
        var token = ""
        private var tick = 0L
        private var callSeq = 0
        val rows = HashMap<String, MutableSet<String>>() // record type -> client_uuids the device committed
        val accepted = sortedMapOf<String, Int>()
        var totalScore = 0L; var maxScore = 0L; var answerScore = 0L; var linesPresent = 0L; var linesOos = 0L
        val taskStatus = LinkedHashMap<String, String>() // task uuid -> the status its newest event gives
        /** The device's copy of each row's values: answers (score, answer_bool, answer_text), lines (brand_id, present, oos), assessments (total, max, assessed user). */
        val answerValues = HashMap<String, List<Any?>>()
        val lineValues = HashMap<String, List<Any?>>()
        val assessmentValues = HashMap<String, List<Any?>>()

        /**
         * Capture times 10 ms apart from Dhaka midnight. A schedule captures at most 3 calls of 16 records, so the
         * 10,000 schedules a phone may run (the cap) use at most 4,800 s of the 9.5 h before the fixed clock (10:00 Dhaka).
         */
        private fun at(): String {
            val t = Instant.parse("2027-01-02T18:30:00Z").plusMillis(10L * tick++)
            check(t.isBefore(Instant.parse("2027-01-03T03:50:00Z"))) { "capture time $t passed the fixed clock" }
            return TS.format(t)
        }

        private fun env(type: String, cu: String, family: String, rank: Int, route: Long, payload: String, at: String) = buildJsonObject {
            put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
            put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18_330_000); put("boot_count", 77)
            put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", route); put("bundle_version", "$day:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", Json.parseToJsonElement(payload))
        }

        private val fix = """{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
            "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

        /**
         * A supervisor day of one to three calls (the first an `amo_joint_call`, the others `amo_control_call`);
         * [forceTask] makes every call delegate a task. Every uuid is drawn from [rnd], so a schedule's records are
         * the same on a rerun.
         */
        fun captureDay(rnd: Random, forceTask: Boolean = false): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            fun uuid() = rndUuid(rnd)
            repeat(rnd.nextInt(1, 4)) { call ->
                val stop = stops[callSeq % stops.size]
                val seq = callSeq++ % 1000 + 1
                val route = stop.routeId
                val sr = stop.srUserId
                val visit = uuid()
                val t0 = at()
                val kind = if (call == 0) "amo_joint_call" else "amo_control_call"
                out += env("visit", visit, visit, 0, route, """{"visit_kind":"$kind","outlet_id":${stop.outletId},"opened_at":"$t0","sequence_no":$seq,"planned":false,
                    "assessed_user_id":${sr ?: "null"},"fix":$fix,
                    "geo":{"verdict":"in_range","distance_m":21.5,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""", t0)
                val tc = at()
                out += env("visit_close", uuid(), visit, 1, route, """{"visit_client_uuid":"$visit","outcome_code":"zero_sale_stock_ok","call_started_at":"$t0","call_declined":false,"ended_at":"$tc","is_zero_sale":true}""", tc)
                // The joint-call assessment and its answers.
                val makeTask = forceTask || rnd.nextInt(2) == 0
                val a = uuid()
                val answers = criteria.map { (id, kind) -> Triple(id, kind, if (kind == "score_1_5") rnd.nextInt(1, 6) else rnd.nextInt(2)) }
                val total = answers.filter { it.second == "score_1_5" }.sumOf { it.third }
                val max = 5 * criteria.count { it.second == "score_1_5" }
                out += env("call_assessment", a, visit, 1, route, """{"kind":"joint_call","visit_client_uuid":"$visit","visit_plan_outlet_client_uuid":null,"rubric_id":$rubricId,
                    "rubric_version":1,"assessed_user_id":${sr ?: "null"},"total_score":$total,"max_score":$max,"delegate_task":$makeTask}""", at())
                totalScore += total; maxScore += max; answerScore += total
                assessmentValues[a] = listOf(total.toLong(), max.toLong(), sr)
                for ((id, kind, v) in answers) {
                    val text = if (v == 1) "দোকানে স্টক ঠিক আছে" else "Shelf needs a facing"
                    val (body, values) = when (kind) {
                        "score_1_5" -> """"score":$v,"answer_text":null,"answer_bool":null""" to listOf(v.toLong(), null, null)
                        "bool" -> """"score":null,"answer_text":null,"answer_bool":${v == 1}""" to listOf(null, v == 1, null)
                        else -> """"score":null,"answer_text":"$text","answer_bool":null""" to listOf(null, null, text)
                    }
                    val cu = uuid()
                    answerValues[cu] = values
                    out += env("call_assessment_answer", cu, visit, 2, route, """{"assessment_client_uuid":"$a","criterion_id":$id,$body}""", at())
                }
                // The distribution check and its brand lines.
                val check = uuid()
                out += env("distribution_check", check, visit, 1, route, """{"visit_client_uuid":"$visit","posm_present":${rnd.nextBoolean()},"note":null}""", at())
                for (brand in brands.shuffled(rnd).take(rnd.nextInt(1, minOf(4, brands.size) + 1))) {
                    val present = rnd.nextInt(4) != 0
                    val oos = present && rnd.nextInt(3) == 0
                    if (present) linesPresent++
                    if (oos) linesOos++
                    val cu = uuid()
                    lineValues[cu] = listOf(brand, present, oos)
                    out += env("distribution_check_line", cu, visit, 2, route, """{"check_client_uuid":"$check","brand_id":$brand,"present":$present,"oos":$oos}""", at())
                }
                // Sometimes a task for the route's SR, resolved and maybe reopened later.
                if (makeTask) {
                    val task = uuid()
                    out += env("task", task, visit, 1, route, """{"task_type_code":"amo_follow_up","assignee_user_id":${sr ?: zoneSr},"outlet_id":${stop.outletId},
                        "title":"Fix the shelf display","description":"Joint call follow-up","due_date":"$day","source_visit_client_uuid":"$visit"}""", at())
                    val events = listOf("resolved", "reopened").take(rnd.nextInt(1, 3))
                    for (e in events) out += env("task_event", uuid(), visit, 2, route, """{"task_uuid":"$task","event":"$e","note":null}""", at())
                    taskStatus[task] = if (events.last() == "resolved") "completed" else "ongoing"
                }
            }
            out.forEach { r -> val t = r.type(); rows.getOrPut(t) { mutableSetOf() } += r.uuid(); accepted.merge(t, 1, Int::plus) }
            return out
        }

        /** A batch body of [records] (device_counts carry the phone's own counts). */
        fun batch(records: List<JsonObject>, batchUuid: String): String = buildJsonObject {
            put("batch_uuid", batchUuid); put("device_uuid", deviceUuid); put("schema_version", 1); put("app_version", ApiWorld.APP_VERSION)
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList()))
            put("device_counts", buildJsonObject { put(day, buildJsonObject { accepted.forEach { (t, n) -> put(t, n) } }) })
            put("records", JsonArray(records))
        }.toString()
    }

    /** A bound, active AMO phone for zone [tag] (null: the seed's own Z-MIR and amo1001), a copy of the dev phone's key. */
    private fun phoneFor(tag: String?, index: Int, rubricId: Long, criteria: List<Pair<Long, String>>): Phone = world.fresh.db.jdbi.withHandle<Phone, Exception> { h ->
        val device = "00000000-0000-4000-8000-0000000003%02d".format(index)
        val prefix = tag?.lowercase() ?: ""
        val zone = "Z-" + (tag ?: "MIR")
        val user = "${prefix}amo1001"
        h.execute(
            "INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) " +
                "SELECT '$device', 'amo', 'com.aktcl.aron.amo', d.status, d.device_owner, d.lockdown_level, d.trust_level, d.public_key_jwk, 'qa-amo-$index', d.attestation_summary, d.app_signing_cert_sha256, z.id, d.device_info, d.app_version FROM app.device d, app.zone z WHERE d.device_uuid = '${ApiWorld.SR_PHONE}' AND z.code = '$zone'",
        )
        h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '$device' AND u.username = '$user'")
        fun id(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
        val stops = h.createQuery(
            """
            SELECT o.id, o.route_id, (SELECT ra.user_id FROM app.route_assignment ra WHERE ra.route_id = o.route_id AND ra.kind = 'primary' AND ra.valid_to IS NULL ORDER BY ra.id LIMIT 1)
            FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id WHERE z.code = :z AND o.route_id IS NOT NULL AND o.status = 'active' ORDER BY o.id LIMIT 60
            """.trimIndent(),
        ).bind("z", zone).map { rs, _ -> Stop(rs.getLong(1), rs.getLong(2), rs.getObject(3) as Long?) }.list()
        val brands = h.createQuery("SELECT id FROM app.product_node WHERE level = 'brand' AND status = 'active' ORDER BY id").mapTo(Long::class.java).list()
        Phone(
            day, tag ?: "MIR", device, id("SELECT id FROM app.device WHERE device_uuid = '$device'"), id("SELECT id FROM app.app_user WHERE username = '$user'"), user,
            stops, brands, rubricId, criteria, id("SELECT id FROM app.app_user WHERE username = '${prefix}sr1001'"),
        )
    }

    // ---- wire helpers ----------------------------------------------------------------------------------------------

    /** One POST; an answer that is not 200 (a kill, a truncated body, 503, 429) is returned as its status, never thrown. */
    private suspend fun HttpClient.send(p: Phone, body: ByteArray): Pair<Int, JsonObject?> = try {
        val r = post("/v1/sync/batch") {
            bearerAuth(p.token); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            setBody(ByteArrayContent(body, ContentType.Application.Json))
        }
        val text = r.bodyAsText()
        r.status.value to if (r.status == HttpStatusCode.OK) Json.parseToJsonElement(text).jsonObject else null
    } catch (e: Throwable) {
        if (e is AssertionError) throw e
        599 to null // the connection died with the process
    }

    // ---- the oracle ------------------------------------------------------------------------------------------------

    private val tables = linkedMapOf(
        "visit" to ("app.visit" to "client_uuid"), "visit_close" to ("app.visit" to "close_client_uuid"),
        "call_assessment" to ("app.call_assessment" to "client_uuid"), "call_assessment_answer" to ("app.call_assessment_answer" to "client_uuid"),
        "distribution_check" to ("app.distribution_check" to "client_uuid"), "distribution_check_line" to ("app.distribution_check_line" to "client_uuid"),
        "task" to ("app.task" to "client_uuid"), "task_event" to ("app.task_event" to "client_uuid"),
    )

    /** Every difference between the phone's truth and the server, empty when they agree ([last]: a batch answer for the day). */
    internal fun oracle(p: Phone, last: JsonObject?): List<String> {
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
            // Over the whole database: the phone only ever captures [day], so any row of its user or phone on another date,
            // or more rows than the device made, is a second record.
            for ((type, tc) in tables) {
                val (table, col) = tc
                val device = p.rows[type].orEmpty().size.toLong()
                val byUser = h.createQuery("SELECT count(*) FROM $table WHERE user_id = :u AND $col IS NOT NULL").bind("u", p.userId).mapTo(Long::class.java).one()
                val twice = h.createQuery("SELECT count(*) - count(DISTINCT $col) FROM $table WHERE user_id = :u AND $col IS NOT NULL").bind("u", p.userId).mapTo(Long::class.java).one()
                if (twice != 0L) problems += "[B2] $type: $twice client_uuids stored twice over all dates"
                if (byUser != device) problems += "[B] $type: the user holds $byUser rows over all dates, the device made $device"
                val byDevice = h.createQuery("SELECT count(*) FROM $table WHERE device_id = :d AND $col IS NOT NULL").bind("d", p.deviceId).mapTo(Long::class.java).one()
                if (byDevice != device) problems += "[C] $type: the phone holds $byDevice rows over all dates, the device made $device"
            }
            val made = p.rows.values.flatten().toSet()
            val registry = h.createQuery("SELECT client_uuid::text FROM app.ingest_registry WHERE user_id = :u").bind("u", p.userId).mapTo(String::class.java).list()
            if (registry.size != made.size || registry.toSet() != made) problems += "[D] ingest registry: ${registry.size} rows for the user, the device made ${made.size} (${(registry.toSet() - made).size} unknown, ${(made - registry.toSet()).size} unregistered)"
            // The numbers a supervisor day carries: never doubled, never lost.
            val (tot, max) = h.createQuery("SELECT COALESCE(sum(total_score), 0), COALESCE(sum(max_score), 0) FROM app.call_assessment WHERE user_id = :u")
                .bind("u", p.userId).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one()
            val ans = h.createQuery("SELECT COALESCE(sum(score), 0) FROM app.call_assessment_answer WHERE user_id = :u").bind("u", p.userId).mapTo(Long::class.java).one()
            val (present, oos) = h.createQuery("SELECT count(*) FILTER (WHERE present), count(*) FILTER (WHERE oos) FROM app.distribution_check_line WHERE user_id = :u")
                .bind("u", p.userId).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one()
            val dev = listOf(p.totalScore, p.maxScore, p.answerScore, p.linesPresent, p.linesOos)
            val srv = listOf(tot, max, ans, present, oos)
            if (dev != srv) problems += "[F] numbers (total score, max score, answer scores, brands present, brands oos) device $dev server $srv"
            val status = h.createQuery("SELECT client_uuid::text, status FROM app.task WHERE user_id = :u").bind("u", p.userId)
                .map { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
            val wrong = p.taskStatus.filter { (t, s) -> status[t] != s }
            if (wrong.isNotEmpty()) problems += "[T] ${wrong.size} task statuses differ, e.g. ${wrong.keys.first()} device ${wrong.values.first()} server ${status[wrong.keys.first()]}"
            // Family links with no user filter: a child row filed under another user (or no phone) on one of the device's
            // parents is a second record the per-user clauses cannot see.
            for ((child, link) in LINKS) {
                val (table, col, parentType) = link
                val parents = p.rows[parentType].orEmpty().toList()
                val linked = h.createQuery("SELECT count(*) FROM $table WHERE $col::text = ANY(:p)").bindArray("p", String::class.java, parents).mapTo(Long::class.java).one()
                val device = p.rows[child].orEmpty().size.toLong()
                if (linked != device) problems += "[L] $child: $linked rows on the device's ${parentType}s over all users, the device made $device"
            }
            // Row by row, the stored values equal the device's copy (a swap that keeps every sum equal is still a change).
            fun n(v: Any?): Any? = if (v is Number) v.toLong() else v
            fun compare(type: String, sql: String, device: Map<String, List<Any?>>) {
                val server = h.createQuery(sql).bind("u", p.userId).map { rs, _ -> rs.getString(1) to (2..4).map { n(rs.getObject(it)) } }.list().toMap()
                val diff = device.filter { (cu, v) -> server[cu]?.let { it != v.map(::n) } == true }
                if (diff.isNotEmpty()) problems += "[V] $type: ${diff.size} rows hold other values than the device's, e.g. ${diff.keys.first()} device ${diff.values.first()} server ${server[diff.keys.first()]}"
            }
            compare("call_assessment_answer", "SELECT client_uuid::text, score, answer_bool, answer_text FROM app.call_assessment_answer WHERE user_id = :u", p.answerValues)
            compare("distribution_check_line", "SELECT client_uuid::text, brand_id, present, oos FROM app.distribution_check_line WHERE user_id = :u", p.lineValues)
            compare("call_assessment", "SELECT client_uuid::text, total_score, max_score, assessed_user_id FROM app.call_assessment WHERE user_id = :u", p.assessmentValues)
        }
        if (last != null) {
            val totals = last["server_totals"]!!.jsonArray.map { it.jsonObject }.singleOrNull { it["business_date"]!!.jsonPrimitive.content == day }
            if (totals == null) problems += "[K] no server_totals for $day" else {
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

    /** Accepted registry rows of the phone's user (before and after a fired kill: the kill-partial counter). */
    private fun acceptedRows(p: Phone): Long = world.fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT count(*) FROM app.ingest_registry WHERE user_id = :u AND status = 'accepted'").bind("u", p.userId).mapTo(Long::class.java).one()
    }

    /** The client_uuid of a child record's parent (its family link), or null for a family root. */
    private fun parentOf(r: JsonObject): String? = r["payload"]!!.jsonObject.let { pl ->
        PARENT_FIELDS.firstNotNullOfOrNull { f -> pl[f]?.let { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content } }
    }

    private suspend fun HttpClient.schedule(p: Phone, seed: Long, forceTask: Boolean = false): JsonObject {
        val rnd = Random(seed)
        val day0 = p.captureDay(rnd, forceTask)
        val acked = HashSet<String>()
        fun note(resp: JsonObject?) = resp?.get("acks")?.jsonArray?.forEach { a ->
            val o = a.jsonObject
            val st = o["status"]!!.jsonPrimitive.content
            if (st == "accepted" || st == "duplicate") acked += o["client_uuid"]!!.jsonPrimitive.content
            if (st == "rejected" && o["code"]?.jsonPrimitive?.content == "parent_missing") seen("parked")
            if (st == "quarantined" || (st == "rejected" && o["retryable"]?.jsonPrimitive?.content != "true")) {
                throw AssertionError("phone ${p.tag} seed=$seed: a valid supervisor record was refused for good: $o")
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
                0 -> { // drop: the response never reaches the phone; the persisted batch is resent first
                    seen("drop"); send(p, body)
                    val (st, r) = send(p, body); note(r)
                    if (r != null && r["replayed"]?.jsonPrimitive?.content != "true") throw AssertionError("phone ${p.tag} seed=$seed: a completed batch resent is a replay ($st)")
                }
                1 -> { // truncated in transit: refused with a 4xx, then resent whole under the same batch_uuid
                    seen("truncate")
                    val (st, _) = send(p, body.copyOf(rnd.nextInt(1, body.size)))
                    if (st !in 400..499) throw AssertionError("phone ${p.tag} seed=$seed: a truncated body was answered $st, not a 4xx")
                    val (st2, r) = send(p, body)
                    if (st2 == 409) throw AssertionError("phone ${p.tag} seed=$seed: a truncated batch claimed its batch_uuid (409 on the whole resend)")
                    note(r)
                }
                2 -> { // the API process dies after the n-th stored record; the phone resends the same batch
                    seen("kill")
                    val before = acceptedRows(p)
                    kill.arm(p.deviceId, rnd.nextInt(1, records.size + 1))
                    val (st, r) = send(p, body)
                    kill.disarm(p.deviceId)
                    if (st == 200) note(r) else {
                        seen("kill-fired")
                        // The families before the kill stayed committed: the resend must find them stored, not store them again.
                        if (acceptedRows(p) > before) seen("kill-partial")
                        val (_, r2) = send(p, body); note(r2)
                    }
                }
                3 -> { // a whole-batch replay with the same batch_uuid, answered with the same acks
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
                5 -> { // reorder: the children whose parents are not acknowledged yet go first on their own (they park), then the whole batch
                    seen("reorder")
                    val orphans = records.filter { r -> r["rank"]!!.jsonPrimitive.int > 0 && parentOf(r).let { it != null && it !in acked } }
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
        // The whole day resent under new batch_uuids (a reinstalled outbox): every row is a duplicate and nothing moves.
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
        // Phone 0 is the seed's own amo1001 on Z-MIR; every other phone is the AMO of a mirrored zone.
        val tags = (1 until phoneCount + 1 + probeCount).map { i -> "U" + ('A' + i / 26) + ('A' + i % 26) }
        world = ApiWorld(repoRoot, mirrors = tags, extraRecordHandlers = listOf(kill))
        var rubric = 0L
        val criteria = listOf(1L to "score_1_5", 2L to "score_1_5", 3L to "score_1_5", 4L to "bool", 5L to "text")
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            // A joint-call rubric (head and published version 1) the assessments name.
            rubric = h.createQuery("INSERT INTO app.rubric (kind, version, status) VALUES ('joint_call', 1, 'active') RETURNING id").mapTo(Long::class.java).one()
            val json = criteria.joinToString(",", "[", "]") { (id, kind) -> """{"criterion_id":$id,"key":"c$id","label_en":"Criterion $id","label_bn":null,"answer_type":"$kind"}""" }
            h.createUpdate("INSERT INTO app.rubric_version (rubric_id, version, criteria) VALUES (:r, 1, CAST(:c AS jsonb))").bind("r", rubric).bind("c", json).execute()
        }
        val all = (listOf<String?>(null) + tags).mapIndexed { i, tag -> phoneFor(tag, i, rubric, criteria) }
        phones = all.take(phoneCount)
        cleanPhone = all[phoneCount]
        probes = all.drop(phoneCount + 1)
        check(all.all { it.stops.size >= 20 && it.stops.all { s -> s.srUserId != null } }) { "every AMO zone has routed outlets with a primary SR" }
        check(all.all { it.brands.isNotEmpty() }) { "the seed has brand-level product nodes" }
    }

    @AfterAll
    fun tearDown() { world.close() }

    /** The production wiring in process, without testApplication's one-minute limit (the long runs take many). */
    private fun withApi(block: suspend HttpClient.() -> Unit) = runBlocking {
        val app = TestApplication { application { aronApi(world.wiring) } }
        app.start()
        try { app.client.block() } finally { app.stop() }
    }

    private suspend fun HttpClient.signIn(p: Phone) {
        if (p.token.isEmpty()) p.token = world.login(this, ApiWorld.Caller(p.username, "app_amo", p.deviceUuid)) ?: error("no token for ${p.username}")
    }

    /** One clean supervisor day carrying every record type, sent in one plain batch: every record is accepted. */
    @Test
    @Order(1)
    fun aCleanSupervisorDayOfEveryTypeIsAccepted() = withApi {
        val p = cleanPhone
        signIn(p)
        val records = p.captureDay(Random(baseSeed - 1), forceTask = true)
        assertEquals(TYPES.toSet(), records.map { it.type() }.toSet(), "the clean day carries every supervisor type")
        val (st, r) = send(p, gz(p.batch(records, rndUuid(Random(baseSeed - 2)))))
        assertEquals(200, st)
        val notAccepted = r!!["acks"]!!.jsonArray.map { it.jsonObject }.filter { it["status"]!!.jsonPrimitive.content != "accepted" }
        assertEquals(emptyList(), notAccepted, "every supervisor record of a clean day is accepted")
        assertEquals(emptyList(), oracle(p, r), "the oracle agrees on the clean day")
    }

    @Test
    @Order(2)
    fun everySupervisorFaultScheduleEndsWithExactlyTheDevicesRowsAndNumbers() = withApi {
        val client = this
        for (p in phones) signIn(p)
        println("SupervisorSyncFaultFuzzTest: ARON_SUPERVISOR_FUZZ_SEED=$baseSeed ARON_SUPERVISOR_FUZZ_SCHEDULES=$schedules ARON_SUPERVISOR_FUZZ_PHONES=$phoneCount")
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
        val byType = TYPES.associateWith { t -> phones.sumOf { it.rows[t]?.size ?: 0 } }
        val report = "schedules=$schedules phones=$phoneCount seconds=${"%.0f".format(secs)} kills_fired=${kill.fired.get()} faults=${faultsSeen.toSortedMap()} " +
            "rows=${byType.values.sum()} by_type=$byType tasks=${phones.sumOf { it.taskStatus.size }}"
        println("SupervisorSyncFaultFuzzTest: $report")
        File("build").mkdirs()
        File("build/supervisor-sync-fault-fuzz.txt").writeText(report + "\n")
        // The schedules were really hostile: every fault kind ran, children really parked and the kill really fired.
        if (schedules >= 40) {
            listOf("drop", "truncate", "kill", "replay", "concurrent", "reorder", "reorder-parked", "partial", "plain", "parked", "kill-partial").forEach { f -> assertTrue((faultsSeen[f]?.get() ?: 0) > 0, "fault $f never ran") }
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

    /** A probe phone with a clean, oracle-approved supervisor day that holds a task, and its last batch answer. */
    private suspend fun HttpClient.cleanDay(p: Phone, seed: Long): JsonObject {
        signIn(p)
        val last = schedule(p, seed, forceTask = true)
        assertEquals(emptyList(), oracle(p, last), "the oracle agrees before the seeded fault")
        return last
    }

    /**
     * One seeded fault per oracle clause: each fault names the clauses that must report it, every clause is named by at
     * least one fault, and each is judged with a real batch answer on a probe phone of its own. Switching any clause off
     * turns this test red.
     */
    @Test
    @Order(3)
    fun everySeededSupervisorFaultIsReportedByItsOracleClause() = withApi {
        val faults = mutableListOf<Triple<String, Set<String>, List<String>>>()
        fun expect(name: String, clauses: Set<String>, problems: List<String>) { faults += Triple(name, clauses, problems) }
        // Lost or changed rows get past the guard_synced_row triggers only with triggers off (data loss, not an API path).
        fun db(sql: String) = world.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            try { check(h.execute(sql) >= 1) { "seeding changed nothing: $sql" } } finally { h.execute("SET session_replication_role = DEFAULT") }
        }
        var probe = 0
        suspend fun fresh(): Pair<Phone, JsonObject> { val p = probes[probe]; return p to cleanDay(p, baseSeed + 1_000_000 + 100_000L * probe++) }
        fun first(p: Phone, type: String) = p.rows[type]!!.first()

        fresh().let { (p, last) -> // a second assessment on the same day (a broken upsert): a doubled score too
            copyRow("app.call_assessment", "client_uuid = '${first(p, "call_assessment")}'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL"))
            expect("duplicate assessment, same day", setOf("A1", "A3", "B", "C", "F"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a second visit filed under another business date
            copyRow("app.visit", "client_uuid = '${first(p, "visit")}'", mapOf("client_uuid" to "gen_random_uuid()", "business_date" to "business_date - 1", "external_ref" to "NULL", "close_client_uuid" to "NULL", "outcome_code" to "NULL"))
            expect("duplicate visit, another date", setOf("B", "C"), oracle(p, last))
        }
        fresh().let { (p, last) -> // the SAME visit client_uuid stored again on another date (past a broken client_uuid_once trigger)
            copyRow("app.visit", "client_uuid = '${first(p, "visit")}'", mapOf("business_date" to "business_date - 1", "external_ref" to "NULL", "close_client_uuid" to "NULL", "outcome_code" to "NULL"), bypassTriggers = true)
            expect("same visit uuid, another date", setOf("B2", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // an assessment answer stored twice
            copyRow("app.call_assessment_answer", "client_uuid = '${first(p, "call_assessment_answer")}'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL"))
            expect("duplicate answer", setOf("A1", "A3", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a brand line stored twice
            copyRow("app.distribution_check_line", "client_uuid = '${first(p, "distribution_check_line")}'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL"))
            expect("duplicate brand line", setOf("A1", "A3", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a lost answer (the device has it, the server does not)
            db("DELETE FROM app.call_assessment_answer WHERE client_uuid = '${first(p, "call_assessment_answer")}'")
            expect("lost answer", setOf("A1", "A2", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a lost visit close (the visit stays, its close columns are wiped)
            db("UPDATE app.visit SET close_client_uuid = NULL, outcome_code = NULL WHERE close_client_uuid = '${first(p, "visit_close")}'")
            expect("lost visit close", setOf("A1", "A2", "B"), oracle(p, last))
        }
        fresh().let { (p, last) -> // an identity swap: one task event lost, a foreign one in its place (counts unchanged)
            val lost = first(p, "task_event")
            copyRow("app.task_event", "client_uuid = '$lost'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL"))
            db("DELETE FROM app.task_event WHERE client_uuid = '$lost'")
            expect("identity swap", setOf("A2", "A3"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a foreign record in the ingest registry of the user
            copyRow("app.ingest_registry", "client_uuid = '${first(p, "call_assessment")}'", mapOf("client_uuid" to "gen_random_uuid()"))
            expect("foreign registry row", setOf("D"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a doubled number: the assessment's max score counted twice
            db("UPDATE app.call_assessment SET max_score = max_score * 2 WHERE client_uuid = '${first(p, "call_assessment")}'")
            expect("doubled max score", setOf("F"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a task whose status no longer follows its newest event
            val (task, want) = p.taskStatus.entries.first().toPair()
            db("UPDATE app.task SET status = '${if (want == "completed") "ongoing" else "completed"}' WHERE client_uuid = '$task'")
            expect("task status flipped", setOf("T"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a second answer on the device's assessment, filed under another user and no phone
            copyRow("app.call_assessment_answer", "client_uuid = '${first(p, "call_assessment_answer")}'", mapOf("client_uuid" to "gen_random_uuid()", "external_ref" to "NULL", "user_id" to "${p.zoneSr}", "device_id" to "NULL"))
            expect("foreign-user child on a device parent", setOf("L"), oracle(p, last))
        }
        fresh().let { (p, last) -> // a value swap: two answers trade scores, every count and sum stays equal
            val scored = p.answerValues.filterValues { it[0] != null }.entries.toList()
            val (a, b) = scored.firstNotNullOfOrNull { x -> scored.firstOrNull { y -> y.value[0] != x.value[0] }?.let { x to it } } ?: error("the probe day has no two answers with different scores")
            db("UPDATE app.call_assessment_answer SET score = CASE client_uuid WHEN '${a.key}' THEN ${b.value[0]} ELSE ${a.value[0]} END WHERE client_uuid IN ('${a.key}', '${b.key}')")
            expect("value swap, sums equal", setOf("V"), oracle(p, last))
        }
        fresh().let { (p, last) -> // the server's own answer differs from the device, the database intact
            fun tamper(edit: (JsonObject) -> JsonObject?): JsonObject = JsonObject(last + ("server_totals" to JsonArray(last["server_totals"]!!.jsonArray.map { it.jsonObject }.mapNotNull { t -> if (t["business_date"]!!.jsonPrimitive.content != day) t else edit(t) })))
            fun bump(o: JsonObject, path: List<String>, delta: Long): JsonObject = JsonObject(o + (path[0] to if (path.size == 1) JsonPrimitive(o[path[0]]!!.jsonPrimitive.content.toLong() + delta) else bump(o[path[0]]!!.jsonObject, path.drop(1), delta)))
            expect("server accepted count differs", setOf("I"), oracle(p, tamper { bump(it, listOf("by_type", "call_assessment", "accepted"), 1) }))
            expect("server holds a parked row", setOf("J"), oracle(p, tamper { bump(it, listOf("by_type", "task_event", "rejected"), 1) }))
            expect("server holds a quarantined row", setOf("J"), oracle(p, tamper { bump(it, listOf("by_type", "visit", "quarantined"), 1) }))
            expect("no totals for the day", setOf("K"), oracle(p, tamper { null }))
        }
        check(probe == probes.size) { "probes used $probe of ${probes.size}" }
        val missing = faults.mapNotNull { (name, want, got) -> val labels = got.mapNotNull { Regex("^\\[(\\w+)]").find(it)?.groupValues?.get(1) }.toSet(); (want - labels).takeIf { it.isNotEmpty() }?.let { "$name: clauses $it did not report it (got $got)" } }
        assertEquals(emptyList(), missing, "seeded faults not reported by the clause that must catch them")
        val clauses = setOf("A1", "A2", "A3", "B", "B2", "C", "D", "F", "T", "L", "V", "I", "J", "K")
        assertEquals(clauses, faults.flatMap { it.second }.toSet(), "every oracle clause is guarded by a seeded fault")
        println("SupervisorSyncFaultFuzzTest seeded faults: " + faults.joinToString("; ") { "${it.first} -> ${it.third.firstOrNull()}" })
    }

    companion object {
        /** The supervisor record types the AMO phone uploads, parents first. */
        val TYPES = listOf("visit", "visit_close", "call_assessment", "call_assessment_answer", "distribution_check", "distribution_check_line", "task", "task_event")

        /** Payload members that name a record's parent (the task_event's task before the visit). */
        private val PARENT_FIELDS = listOf("task_uuid", "assessment_client_uuid", "check_client_uuid", "visit_client_uuid", "source_visit_client_uuid")

        /** Child type -> (table, family link column, parent type) for the [L] clause. */
        private val LINKS = linkedMapOf(
            "call_assessment" to Triple("app.call_assessment", "visit_client_uuid", "visit"),
            "call_assessment_answer" to Triple("app.call_assessment_answer", "assessment_client_uuid", "call_assessment"),
            "distribution_check" to Triple("app.distribution_check", "visit_client_uuid", "visit"),
            "distribution_check_line" to Triple("app.distribution_check_line", "check_client_uuid", "distribution_check"),
            "task" to Triple("app.task", "source_visit_client_uuid", "visit"),
            "task_event" to Triple("app.task_event", "task_uuid", "task"),
        )
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** A UUID v4 drawn from [rnd] (version and variant bits set), so a schedule's uuids repeat on a rerun with its seed. */
        fun rndUuid(rnd: Random): String {
            val msb = (rnd.nextLong() and -0xf001L) or 0x4000L // version 4
            val lsb = (rnd.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE // variant 10
            return UUID(msb, lsb).toString()
        }
    }
}

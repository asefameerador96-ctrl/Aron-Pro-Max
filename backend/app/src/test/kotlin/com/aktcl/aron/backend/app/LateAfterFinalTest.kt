package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tso-manual B3, D-TSO-MAN-31 (contract 1.10.0; build contract s5 B3 tests "late cases"): the day lock after Final
 * Submit, through the production wiring of an [ApiWorld] mirror division (`LAF`: zone Z-LAF, SR `lafsr1001` with its own
 * phone, TSO `laftso1001`), on 2027-01-03. The TSO closes the zone-day at 06:00Z; the [SyncPhone] captures sale families
 * (visit, memo, lines, close, collections, voids) at 18:30Z of the day before onwards, so they are captured before the lock
 * unless [shifted] past it. In order:
 * 1. A family captured before the lock that arrives after it is quarantined `after_final_submit` whole (the visit by the
 *    lock, its children with their held parent), stays out of the tables and the late counters, stays held on resend,
 *    and is stored and counted late once an ADMIN accepts it on the quarantine page; a resend is then a duplicate.
 * 2. A family captured after the lock is refused `day_final_submitted` (final, not retryable) whole; a memo_void after the
 *    lock of a memo stored before it is refused and the memo stays active; a `day_submit` after the lock is accepted
 *    (state only) and counts no late row.
 * 3. cfg.day.late_sync_after_final_policy: `reject` refuses even a family captured before the lock; `accept_and_flag`
 *    stores it and counts it late at once (the pre-1.10 rule, D-55).
 * 4. After an admin reopen the day is open: a family captured before the old lock is an ordinary accepted row, not late.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LateAfterFinalTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val tag = "LAF"
    private val day = "2027-01-03"
    private val lockAt = Instant.parse("2027-01-03T06:00:00Z")
    private val now = AtomicReference(lockAt)
    private val clock = AronClock { now.get() }
    private lateinit var world: ApiWorld
    private lateinit var phone: SyncPhone
    private var zone = 0L
    private var srId = 0L
    /** The families stored before the close (test 1), whose memos the after-lock void names (test 2). */
    private val before = mutableListOf<JsonObject>()

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot, mirrors = listOf(tag), clock = clock)
        zone = one("SELECT id FROM app.zone WHERE code = 'Z-$tag'")
        phone = world.phoneFor(tag, 0, day)
        srId = phone.userId
    }

    @AfterAll
    fun tearDown() { world.close() }

    private fun one(sql: String): Long = world.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()

    private fun withApi(block: suspend HttpClient.() -> Unit) = runBlocking {
        val app = TestApplication { application { aronApi(world.wiring) } }
        app.start()
        try { app.client.block() } finally { app.stop() }
    }

    private suspend fun HttpClient.web(user: String): String = world.login(this, ApiWorld.Caller(user, "web", null)) ?: error("no token for $user")

    private suspend fun HttpClient.sync(records: List<JsonObject>): List<JsonObject> {
        phone.token = world.login(this, ApiWorld.Caller(phone.username, "app_sr", phone.deviceUuid)) ?: error("no token for ${phone.username}")
        val r = post("/v1/sync/batch") {
            bearerAuth(phone.token); header("X-Device-Id", phone.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            setBody(ByteArrayContent(gz(phone.batch(records)), ContentType.Application.Json))
        }
        val text = r.bodyAsText()
        assertEquals(200, r.status.value, text)
        return json(text)["acks"]!!.jsonArray.map { it.jsonObject }
    }

    private suspend fun HttpClient.closeZoneDay(cu: String = uuid()): HttpResponse = post("/v1/day/final-submit") {
        bearerAuth(web("${tag.lowercase()}tso1001")); contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
        setBody("""{"client_uuid":"$cu","zone_id":$zone,"business_date":"$day"}""")
    }

    private val instant = Regex("\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)\"")

    /** The records with every instant moved [hours] later (the business date and the memo numbers stay). */
    private fun shifted(records: List<JsonObject>, hours: Long): List<JsonObject> = records.map { r ->
        json(r.toString().replace(instant) { "\"" + Instant.parse(it.groupValues[1]).plus(hours, ChronoUnit.HOURS) + "\"" })
    }

    private fun ackSummary(acks: List<JsonObject>): Set<Triple<String, String?, String?>> =
        acks.map { Triple(it["status"]!!.jsonPrimitive.content, it["code"]?.jsonPrimitive?.contentOrNull, it["retryable"]?.jsonPrimitive?.contentOrNull) }.toSet()


    /** (late_rows_after_final of the SR's route-day, late_rows of the live close). */
    private fun late(): Pair<Int, Int> = world.fresh.db.jdbi.withHandle<Pair<Int, Int>, Exception> { h ->
        val rd = h.createQuery("SELECT late_rows_after_final FROM app.route_day WHERE route_id = :r AND business_date = DATE '$day'").bind("r", phone.routeId).mapTo(Int::class.java).one()
        val fs = h.createQuery("SELECT coalesce(sum(late_rows), 0)::int FROM app.final_submit WHERE zone_id = :z AND business_date = DATE '$day'").bind("z", zone).mapTo(Int::class.java).one()
        rd to fs
    }

    private fun openHeld(): List<Pair<Long, String>> = world.fresh.db.jdbi.withHandle<List<Pair<Long, String>>, Exception> { h ->
        h.createQuery("SELECT id, client_uuid::text FROM app.sync_quarantine WHERE user_id = :u AND business_date = DATE '$day' AND code = 'after_final_submit' AND status = 'open' ORDER BY id")
            .bind("u", srId).map { rs, _ -> rs.getLong(1) to rs.getString(2) }.list()
    }

    private fun storedMemos(uuids: Collection<String>): Long = world.fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT count(*) FROM app.memo WHERE client_uuid::text = ANY(:u)").bindArray("u", String::class.java, uuids.toList()).mapTo(Long::class.java).one()
    }

    private fun uuidsOf(records: List<JsonObject>, type: String) = records.filter { it.type() == type }.map { it.uuid() }

    private val switches = AtomicInteger(0)

    /** A global config switch the way the portal sets it (DeviceGateTest pattern); the clock moves past the 30 s config cache. */
    private fun policy(value: String) {
        val n = switches.incrementAndGet()
        val key = "cfg.day.late_sync_after_final_policy"
        world.fresh.db.jdbi.useTransaction<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test late policy' FROM app.cfg_version")
            h.execute("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n WHERE key = '$key' AND scope_type = 'global' AND effective_to IS NULL")
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                    "SELECT '$key', 'global', 0, '\"$value\"'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n, max(config_version), 'test' FROM app.cfg_version",
            )
        }
        now.updateAndGet { it.plusSeconds(31) }
    }

    @Test
    @Order(1)
    fun aRowCapturedBeforeTheLockIsHeldThenAcceptedAndCountedLate() = withApi {
        // Before the close: an ordinary day.
        val day0 = phone.captureDay(Random(1))
        assertEquals(setOf("accepted"), sync(day0).map { it["status"]!!.jsonPrimitive.content }.toSet())
        before += day0
        val c = closeZoneDay()
        assertEquals(200, c.status.value, c.bodyAsText())
        assertEquals(lockAt, Instant.parse(json(c.bodyAsText())["submitted_at"]!!.jsonPrimitive.content))
        assertEquals(0 to 0, late())

        // A family captured before the lock arrives after it: held whole, out of the tables and the counters.
        val held = phone.captureDay(Random(2))
        val acks = sync(held)
        assertEquals(setOf(Triple("quarantined", "after_final_submit", null)), ackSummary(acks), acks.toString())
        assertEquals(held.map { it.uuid() }.toSet(), openHeld().map { it.second }.toSet())
        assertEquals(0L, storedMemos(uuidsOf(held, "memo")))
        assertEquals(0 to 0, late(), "held rows are counted only when an admin accepts them")
        // The phone resends them (a lost response): still held, nothing new.
        assertEquals(setOf(Triple("quarantined", "after_final_submit", null)), ackSummary(sync(held)))
        assertEquals(held.size, openHeld().size)

        // ADMIN accepts on the quarantine page: the visit's acceptance releases its tree; each record is counted late once.
        val admin = web("admin1001")
        while (true) {
            val (id, _) = openHeld().firstOrNull() ?: break
            val r = post("/v1/admin/quarantine/$id/resolve") {
                bearerAuth(admin); contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
                setBody("""{"action":"accept","reason":"late sale checked with the SR"}""")
            }
            assertEquals(200, r.status.value, r.bodyAsText())
        }
        assertEquals(uuidsOf(held, "memo").size.toLong(), storedMemos(uuidsOf(held, "memo")))
        assertEquals(held.size to held.size, late(), "the route-day and the live close count every accepted late row")
        assertEquals(setOf("duplicate"), sync(held).map { it["status"]!!.jsonPrimitive.content }.toSet())
        assertEquals(held.size to held.size, late(), "a resend after the accept counts nothing")
        before += held
    }

    @Test
    @Order(2)
    fun aRowCapturedAtOrAfterTheLockIsRefusedButStateRecordsAreAccepted() = withApi {
        now.set(Instant.parse("2027-01-03T17:30:00Z")) // 23:30 in Dhaka, still the business date
        val counted = late()
        // A family captured after the lock (16 h later: 10:30Z onwards): refused whole, final.
        val after = shifted(phone.captureDay(Random(3)), 16)
        val acks = sync(after)
        assertEquals(setOf(Triple("rejected", "day_final_submitted", "false")), ackSummary(acks), acks.toString())
        assertEquals(0L, storedMemos(uuidsOf(after, "memo")))
        assertEquals(setOf(Triple("rejected", "day_final_submitted", "false")), ackSummary(sync(after)), "a resend keeps the refusal")

        // A void after the lock of a memo stored before it: refused, the memo stays active.
        val voided = before.filter { it.type() == "memo_void" }.map { it["payload"]!!.jsonObject["memo_client_uuid"]!!.jsonPrimitive.content }.toSet()
        val memo = before.first { it.type() == "memo" && it.uuid() !in voided }
        val cu = uuid()
        val void = buildJsonObject {
            put("type", "memo_void"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
            put("business_date", day); put("captured_at", "2027-01-03T17:00:00Z"); put("captured_elapsed_ms", 18_330_000); put("boot_count", 412)
            put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", phone.routeId); put("bundle_version", "$day:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", json(
                """{"memo_client_uuid":"${memo.uuid()}","memo_no":"${memo["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content}","reason_code":"retailer_cancelled","retailer_ack":true,
                   "fix":{"purpose":"memo_void","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                          "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}""",
            ))
        }
        assertEquals(setOf(Triple("rejected", "day_final_submitted", "false")), ackSummary(sync(listOf(void))))
        assertEquals("active", world.fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT status FROM app.memo WHERE client_uuid = CAST(:u AS uuid)").bind("u", memo.uuid()).mapTo(String::class.java).one() })

        // A Sales Submit after the lock: accepted (state only), the route-day stays closed, no late row.
        val sub = uuid()
        val submit = buildJsonObject {
            put("type", "day_submit"); put("client_uuid", sub); put("family_uuid", sub); put("rank", 0); put("schema_version", 1)
            put("business_date", day); put("captured_at", "2027-01-03T17:10:00Z"); put("captured_elapsed_ms", 0); put("boot_count", 0)
            put("clock_offset_ms", 0); put("captured_offline", false); put("route_id", phone.routeId); put("config_version", 1)
            put("payload", json(
                """{"scope":"route_day","submit_cycle":1,"device_counts":{},"device_money":{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,
                   "qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}},
                   "rejected_count":0,"quarantined_count":0,"pending_count":0,"submitted_with_dues":false,"dues_outstanding_mtk":0,"retailers_with_dues":0,"stock_slip_printed":false}""",
            ))
        }
        assertEquals(setOf(Triple("accepted", null, null)), ackSummary(sync(listOf(submit))))
        assertEquals("final_submitted", world.fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT state FROM app.route_day WHERE route_id = :r AND business_date = DATE '$day'").bind("r", phone.routeId).mapTo(String::class.java).one() })
        assertEquals(counted, late(), "refused rows and state records count no late row")
        assertEquals(0, openHeld().size)
    }

    @Test
    @Order(3)
    fun theRejectAndAcceptAndFlagPoliciesActOnRowsCapturedBeforeTheLock() = withApi {
        val counted = late()
        policy("reject")
        try {
            val refused = phone.captureDay(Random(4))
            assertEquals(setOf(Triple("rejected", "day_final_submitted", "false")), ackSummary(sync(refused)))
            assertEquals(counted, late())
            policy("accept_and_flag")
            val flagged = phone.captureDay(Random(5))
            assertEquals(setOf("accepted"), sync(flagged).map { it["status"]!!.jsonPrimitive.content }.toSet())
            assertEquals(uuidsOf(flagged, "memo").size.toLong(), storedMemos(uuidsOf(flagged, "memo")))
            assertEquals((counted.first + flagged.size) to (counted.second + flagged.size), late(), "accept_and_flag counts each stored late row at once")
        } finally {
            policy("quarantine")
        }
        assertEquals(0, openHeld().size)
    }

    @Test
    @Order(4)
    fun afterAReopenTheDayIsOpenAndRowsAreOrdinary() = withApi {
        val admin = web("admin1001")
        val r = post("/v1/day/reopen") {
            bearerAuth(admin); contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
            setBody("""{"zone_id":$zone,"business_date":"$day","reason":"late uploads expected from the SR"}""")
        }
        assertEquals(200, r.status.value, r.bodyAsText())
        val counted = late()
        val rows = phone.captureDay(Random(6))
        assertEquals(setOf("accepted"), sync(rows).map { it["status"]!!.jsonPrimitive.content }.toSet())
        val rd = world.fresh.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT late_rows_after_final FROM app.route_day WHERE route_id = :r AND business_date = DATE '$day'").bind("r", phone.routeId).mapTo(Int::class.java).one() }
        assertEquals(counted.first, rd, "rows of an open day are not late")
        assertTrue(world.fresh.db.jdbi.withHandle<Boolean, Exception> { h -> h.createQuery("SELECT final_submitted_at IS NULL FROM app.route_day WHERE route_id = :r AND business_date = DATE '$day'").bind("r", phone.routeId).mapTo(Boolean::class.java).one() })
        // Closing again (a new uuid) locks the day again: a row captured before the new close is held again.
        val again = closeZoneDay()
        assertEquals(200, again.status.value, again.bodyAsText())
        val held = phone.captureDay(Random(7))
        assertEquals(setOf(Triple("quarantined", "after_final_submit", null)), ackSummary(sync(held)))
        assertEquals(held.size, openHeld().size)
    }
}

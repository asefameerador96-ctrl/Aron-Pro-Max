package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N-059, the local half of the restore drill: a point-in-time restore loses the writes acknowledged after the restore
 * point; the phones learn it from the new server generation (docs/24 s4.8), re-send what was acknowledged after
 * `lost_after_utc`, and a row-by-row reconciliation of device and server rows shows zero missing and zero duplicated.
 *
 * The restore is a copy of the live database taken at the restore point (`CREATE DATABASE ... TEMPLATE`), served by a
 * second production wiring with the same signing key, as the API is re-pointed after an Azure restore. Three phones in
 * three divisions sell before the restore point and after it.
 * - [aRestoreWithANewGenerationReconcilesToZeroMissingAndZeroDuplicated]: the generation is minted as the restore
 *   runbook must ([mintAfterRestore]); after the re-send every device row is on the server exactly once, money and
 *   memo balances equal, and a second re-send changes nothing.
 * - [aRestoreWithoutANewGenerationLosesTheTail]: the negative control. Without the mint the phones see no change, send
 *   nothing, and the reconciliation reports every row acknowledged after the restore point as missing.
 *
 * Where it runs: local PostgreSQL and CI. Not covered here: a zone failover or HA promotion (kind `failover`), the
 * Azure restore itself (`.github/workflows/drill.yml`, owner approval), the re-send jitter and the digest step of
 * s4.8, and the real Android SyncEngine (device lab). The row's failover half is not evidenced by this test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreDrillReconciliationTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val day = ApiWorld.BUSINESS_DATE
    @Volatile private var now: Instant = Instant.parse("${day}T04:00:00Z")
    private lateinit var world: ApiWorld
    /** Each drill sells in its own three divisions, so the two drills never share a user, route or outlet. */
    private val tags = mapOf(true to listOf("RSA", "RSB", "RSC"), false to listOf("RSD", "RSE", "RSF"))
    private val restored = mutableListOf<Pair<String, Wiring>>()
    private val restorePoint = Instant.parse("${day}T04:30:00Z")

    @BeforeAll
    fun setUp() { world = ApiWorld(repoRoot, mirrors = tags.values.flatten(), clock = AronClock { now }) }

    @AfterAll
    fun tearDown() {
        restored.forEach { (name, w) -> w.database?.close(); admin { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
        world.close()
    }

    /** One phone's record and the server time its acknowledgement arrived (the phone keeps both in its outbox). */
    private class Sent(val record: JsonObject, val ackedAt: Instant)

    private class Phone(val p: SyncPhone) {
        val sent = mutableListOf<Sent>()
        var generationSeen: String? = null
        var lastTotals: JsonObject? = null
        /** Records the phone re-sent after a generation change (each pass), and the first statement it acted on. */
        val resent = mutableListOf<Int>()
        var statement: JsonObject? = null
    }

    private val tables = mapOf(
        "visit" to ("app.visit" to "client_uuid"), "visit_close" to ("app.visit" to "close_client_uuid"), "memo" to ("app.memo" to "client_uuid"),
        "memo_line" to ("app.memo_line" to "client_uuid"), "due_collection" to ("app.due_collection" to "client_uuid"), "memo_void" to ("app.memo_void" to "client_uuid"),
    )

    @Test
    fun aRestoreWithANewGenerationReconcilesToZeroMissingAndZeroDuplicated() = drill(mint = true) { phones, server, lostTail ->
        assertTrue(lostTail > 0, "the drill must lose a tail to prove anything")
        // The phones acted on the server's statement, not on a fallback: the first pass re-sent exactly the lost tail.
        for (ph in phones) {
            val st = ph.statement ?: error("${ph.p.username} never read a generation statement")
            assertEquals("pitr", st["kind"]!!.jsonPrimitive.content, st.toString())
            assertEquals(restorePoint, Instant.parse(st["lost_after_utc"]!!.jsonPrimitive.content), st.toString())
            assertEquals(restorePoint, Instant.parse(st["restore_point_utc"]!!.jsonPrimitive.content), st.toString())
        }
        assertEquals(lostTail, phones.sumOf { it.resent.first() }, "the first re-send is exactly the rows acknowledged after the restore point")
        val problems = phones.flatMap { reconcile(server, it) }
        assertEquals(emptyList(), problems, "after the restore and the re-send")
        for (ph in phones) {
            val p = ph.p
            val money = ph.lastTotals?.get("money")?.jsonObject ?: error("no server_totals for ${p.username}")
            fun m(k: String) = money[k]!!.jsonPrimitive.content.toLong()
            assertEquals(listOf(p.activeMemos, p.gross, p.due, p.collected), listOf(m("active_memo_count"), m("gross_mtk"), m("due_mtk"), m("due_collected_mtk")), "money of ${p.username}")
            val byType = ph.lastTotals!!["by_type"]!!.jsonObject
            assertEquals(p.accepted.toMap(), byType.mapValues { it.value.jsonObject["accepted"]!!.jsonPrimitive.content.toInt() }.filterValues { it > 0 }, "accepted per type of ${p.username}")
        }
    }

    @Test
    fun aRestoreWithoutANewGenerationLosesTheTail() = drill(mint = false) { phones, server, lostTail ->
        val missing = phones.sumOf { ph -> reconcile(server, ph).filter { it.startsWith("missing") }.sumOf { it.substringAfter("missing ").substringBefore(" ").toInt() } }
        assertEquals(lostTail, missing, "every row acknowledged after the restore point is reported missing")
        assertTrue(phones.all { it.resent.isEmpty() }, "no generation change, so no phone re-sent anything")
    }

    /**
     * Sells before and after the restore point, restores a copy taken at it, optionally mints the generation, lets each
     * phone run the s4.8 protocol on the restored server, then hands the phones, the restored database and the number of
     * rows acknowledged after the restore point to [check].
     */
    private fun drill(mint: Boolean, check: (List<Phone>, Wiring, Int) -> Unit) {
        val phones = tags.getValue(mint).mapIndexed { i, t -> Phone(world.phoneFor(t, (if (mint) 1 else 11) + i, day)) }
        val rnd = Random(if (mint) 59 else 590)
        now = Instant.parse("${day}T04:00:00Z")
        withApi(world.wiring) { phones.forEach { signIn(it); deliver(it, it.p.captureDay(rnd)) } }
        now = restorePoint
        val copy = snapshot()
        now = Instant.parse("${day}T05:00:00Z")
        withApi(world.wiring) { phones.forEach { deliver(it, it.p.captureDay(rnd) + it.p.captureDay(rnd)) } }
        val lostTail = phones.sumOf { ph -> ph.sent.count { it.ackedAt > restorePoint } }
        now = Instant.parse("${day}T05:30:00Z")
        if (mint) admin(copy.first) { mintAfterRestore(it, restorePoint) }
        val server = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to copy.second, "ARON_JWT_SIGNING_KEY_FILE" to world.jwtKeyFile.absolutePath)), AronClock { now },
        )
        restored += copy.first to server
        withApi(server) {
            // An hour and a half later the access tokens have expired: each phone signs in again, as it would.
            phones.forEach { signIn(it); resync(it) }
            if (mint) {
                // A second re-send (a phone that ran the protocol twice) changes nothing.
                val before = phones.flatMap { reconcile(server, it) }
                phones.forEach { ph -> ph.generationSeen = null; resync(ph) }
                assertEquals(before, phones.flatMap { reconcile(server, it) }, "a repeated re-send is idempotent")
            }
        }
        check(phones, server, lostTail)
    }

    // ---- the restore ----------------------------------------------------------------------------------------------

    /**
     * The restore runbook's step after an Azure point-in-time restore: the restored database still names the old
     * generation, so a new one is minted with the restore point as `lost_after_utc`. It goes through
     * `app.mint_server_generation` (V0087, on the hardening branch, promoted with it) when the function exists, else
     * through the same two statements. Without it no phone re-sends (the negative control).
     */
    private fun mintAfterRestore(c: java.sql.Connection, restorePoint: Instant) {
        val hasFunction = c.createStatement().use { st -> st.executeQuery("SELECT to_regprocedure('app.mint_server_generation(text,timestamptz,timestamptz,text)') IS NOT NULL").use { it.next(); it.getBoolean(1) } }
        if (hasFunction) {
            c.prepareStatement("SELECT app.mint_server_generation('pitr_restore', ?::timestamptz, ?::timestamptz, 'N-059 drill')").use {
                it.setString(1, restorePoint.toString()); it.setString(2, restorePoint.toString()); it.executeQuery().close()
            }
            return
        }
        c.createStatement().use { it.execute("UPDATE app.server_generation SET is_current = false WHERE is_current") }
        c.prepareStatement("INSERT INTO app.server_generation (generation, kind, started_at, restore_point_utc, lost_after_utc) VALUES (gen_random_uuid(), 'pitr_restore', ?::timestamptz, ?::timestamptz, ?::timestamptz)").use {
            it.setString(1, now.toString()); it.setString(2, restorePoint.toString()); it.setString(3, restorePoint.toString()); it.executeUpdate()
        }
    }

    /** A copy of the live database as it is now (name, JDBC URL): the state an Azure restore to this instant returns. */
    private fun snapshot(): Pair<String, String> {
        val src = world.fresh.name
        val name = "aron_rs_" + UUID.randomUUID().toString().replace("-", "").take(16)
        admin { c ->
            // TEMPLATE needs the source idle: refuse new connections, end the pools' ones, copy, reopen.
            c.execute("ALTER DATABASE $src WITH ALLOW_CONNECTIONS false")
            try {
                // A pool may still be reconnecting: end its sessions again and retry while the source is "being accessed".
                var attempt = 0
                while (true) {
                    c.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$src' AND pid <> pg_backend_pid()")
                    try { c.execute("CREATE DATABASE $name TEMPLATE $src"); break } catch (e: java.sql.SQLException) {
                        if (e.sqlState != "55006" || ++attempt >= 10) throw e
                        Thread.sleep(200)
                    }
                }
            } finally {
                c.execute("ALTER DATABASE $src WITH ALLOW_CONNECTIONS true")
            }
        }
        // The pools still hold the ended connections; drop them so the next request opens fresh ones.
        listOfNotNull(world.wiring.database?.write, world.fresh.dataSource).filterIsInstance<com.zaxxer.hikari.HikariDataSource>()
            .forEach { it.hikariPoolMXBean?.softEvictConnections() }
        return name to world.fresh.url.replace("/$src", "/$name")
    }

    private fun admin(block: (java.sql.Statement) -> Unit) =
        DriverManager.getConnection(System.getenv("ARON_TEST_PG_URL")).use { c -> c.createStatement().use(block) }

    private fun admin(db: String, block: (java.sql.Connection) -> Unit) =
        DriverManager.getConnection(System.getenv("ARON_TEST_PG_URL").replace(Regex("(jdbc:postgresql://[^/]+/)[^?]*"), "$1$db")).use(block)

    // ---- the phone -------------------------------------------------------------------------------------------------

    private fun withApi(w: Wiring, block: suspend HttpClient.() -> Unit) = runBlocking {
        val app = TestApplication { application { aronApi(w) } }
        app.start()
        try { app.client.block() } finally { app.stop() }
    }

    private suspend fun HttpClient.signIn(ph: Phone) {
        ph.p.token = world.login(this, ApiWorld.Caller(ph.p.username, "app_sr", ph.p.deviceUuid)) ?: error("no token for ${ph.p.username}")
    }

    /** Sends [records] until each is acknowledged (a [resend] keeps the original acknowledgement times); returns the generation. */
    private suspend fun HttpClient.deliver(ph: Phone, records: List<JsonObject>, resend: Boolean = false): String? {
        var pending = records
        var generation: String? = null
        repeat(5) {
            if (pending.isEmpty()) return generation
            val r = post("/v1/sync/batch") {
                bearerAuth(ph.p.token); header("X-Device-Id", ph.p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
                setBody(ByteArrayContent(gz(ph.p.batch(pending, trigger = if (resend) "resync" else "manual")), ContentType.Application.Json))
            }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            generation = r.headers["X-Server-Generation"]
            if (ph.generationSeen == null && !resend) ph.generationSeen = generation
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            ph.lastTotals = body["server_totals"]!!.jsonArray.map { it.jsonObject }.singleOrNull { it["business_date"]!!.jsonPrimitive.content == day }
            val acked = body["acks"]!!.jsonArray.map { it.jsonObject }
                .filter { it["status"]!!.jsonPrimitive.content in setOf("accepted", "duplicate") }.map { it["client_uuid"]!!.jsonPrimitive.content }.toSet()
            if (!resend) pending.filter { it.uuid() in acked }.forEach { ph.sent += Sent(it, now) }
            pending = pending.filter { it.uuid() !in acked }
        }
        check(pending.isEmpty()) { "${ph.p.username}: ${pending.size} records never acknowledged" }
        return generation
    }

    /**
     * docs/24 s4.8 as the phone runs it: any response names the generation; a new one means GET /v1/sync/generation
     * (since the last seen) and a re-send of every record acknowledged after the earliest loss it names.
     */
    private suspend fun HttpClient.resync(ph: Phone) {
        val r = get("/v1/sync/totals") { bearerAuth(ph.p.token); header("X-Device-Id", ph.p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION) }
        val current = r.headers["X-Server-Generation"] ?: error("no X-Server-Generation on ${r.status}")
        if (current == ph.generationSeen) return
        val since = ph.generationSeen?.let { "?since=$it" } ?: ""
        val g = get("/v1/sync/generation$since") { bearerAuth(ph.p.token); header("X-Device-Id", ph.p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION) }
        assertEquals(HttpStatusCode.OK, g.status, g.bodyAsText())
        val statement = Json.parseToJsonElement(g.bodyAsText()).jsonObject
        assertEquals(current, statement["generation"]!!.jsonPrimitive.content, "the statement names the generation the header named")
        if (ph.generationSeen != null) assertEquals(ph.generationSeen, statement["previous_generation"]?.jsonPrimitive?.content, "previous generation since $since")
        // A new generation without a loss instant would leave the phone guessing: the drill treats it as a failure.
        val lostAfter = listOf("earliest_lost_after_utc", "lost_after_utc").firstNotNullOfOrNull { k -> statement[k]?.takeIf { it != JsonNull }?.jsonPrimitive?.content }
            ?: error("generation $current names no lost_after_utc: $statement")
        if (ph.statement == null) ph.statement = statement // the first pass's statement is the one asserted
        val again = ph.sent.filter { it.ackedAt > Instant.parse(lostAfter) }.map { it.record }
        ph.resent += again.size
        if (again.isNotEmpty()) deliver(ph, again, resend = true)
        ph.generationSeen = current
    }

    // ---- the reconciliation ----------------------------------------------------------------------------------------

    /** Every difference between the phone's rows and the restored server's, row by row; empty when they agree. */
    private fun reconcile(server: Wiring, ph: Phone): List<String> = server.database!!.jdbi.withHandle<List<String>, Exception> { h ->
        val p = ph.p
        val out = mutableListOf<String>()
        for ((type, uuids) in p.rows.toSortedMap()) {
            val (table, col) = tables[type] ?: error("unmapped record type $type: add its table to the reconciliation")
            val found = h.createQuery("SELECT $col::text, count(*) FROM $table WHERE $col::text = ANY(:u) GROUP BY 1").bindArray("u", String::class.java, uuids.toList())
                .map { rs, _ -> rs.getString(1) to rs.getInt(2) }.list().toMap()
            val missing = uuids.count { it !in found }
            val doubled = found.count { it.value > 1 }
            if (missing > 0) out += "missing $missing $type rows of ${p.username}"
            if (doubled > 0) out += "duplicated $doubled $type rows of ${p.username}"
            val extra = h.createQuery("SELECT count(*) FROM $table WHERE user_id = :u AND $col IS NOT NULL AND NOT ($col::text = ANY(:k))")
                .bind("u", p.userId).bindArray("k", String::class.java, uuids.toList()).mapTo(Int::class.java).one()
            if (extra > 0) out += "extra $extra $type rows of ${p.username} the device never made"
        }
        val balances = h.createQuery("SELECT memo_client_uuid::text, COALESCE(sum(amount_mtk), 0) FROM app.due_ledger WHERE memo_client_uuid IN (SELECT client_uuid FROM app.memo WHERE user_id = :u) GROUP BY 1")
            .bind("u", p.userId).map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        val wrong = p.memoDue.count { (m, open) -> (balances[m] ?: 0L) != open }
        if (wrong > 0) out += "balance differs on $wrong memos of ${p.username}"
        val stray = h.createQuery("SELECT count(*) FROM app.due_ledger WHERE outlet_id = ANY(:o) AND NOT (memo_client_uuid::text = ANY(:m))")
            .bindArray("o", Long::class.javaObjectType, p.outlets).bindArray("m", String::class.java, p.memoDue.keys.toList()).mapTo(Long::class.java).one()
        if (stray > 0) out += "stray $stray ledger rows on the outlets of ${p.username}"
        out
    }
}

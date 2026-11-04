package com.aktcl.aron.backend.notify

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Inbox
import com.aktcl.aron.backend.platform.InboxItem
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.Secret
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.NotificationInbox
import com.aktcl.aron.contract.NotificationReadResult
import com.aktcl.aron.contract.Role
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-TSO-MAN-26 / SH-08: the notification inbox (build contract s5 B1 tests "inbox: idempotent producers, unread count,
 * read up_to_id idempotent, own rows only, prune"). A fresh migrated PostgreSQL 16 (V0133 table, its grants), the real
 * platform plugins and guard, and the two contract operations `GET /v1/me/notifications` and
 * `POST /v1/me/notifications/read`. Answers are decoded strictly into the contract's generated DTOs (an unknown member
 * fails), so the wire shape is the contract's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboxTest {
    private lateinit var fresh: FreshDb
    private val db: Database get() = fresh.db
    private val ids = HashMap<String, Long>()

    /** The app's clock (as_of, read_at); tokens are minted at it and verified against it, so no wall clock is read. */
    private val start: Instant = Instant.parse("2027-01-03T04:00:00Z")
    private val now = AtomicReference(start)
    private val clock = AronClock { now.get() }

    private val keys: JwtKeys by lazy {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pem = "-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().private.encoded) + "\n-----END PRIVATE KEY-----"
        JwtKeys(JwtKeys.parsePrivatePem(pem), "t")
    }
    private val strict = Json { ignoreUnknownKeys = false }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        db.jdbi.useHandle<Exception> { h ->
            for ((u, role) in listOf("tsoinbox1" to "TSO", "tsoinbox2" to "TSO", "srinbox1" to "SR")) {
                h.execute("INSERT INTO app.app_user (username, full_name, role, locale, pilot, must_change_password) VALUES ('$u', 'Inbox $u', '$role', 'bn', true, false)")
            }
            h.createQuery("SELECT username, id FROM app.app_user WHERE username LIKE '%inbox%'").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().forEach { (k, v) -> ids[k] = v }
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    /** Every test starts at the same instant (a test that moves the clock must not move the next one). */
    @BeforeEach
    fun resetClock() = now.set(start)

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun item(user: String, n: Int, kind: String = "ticket_updated", key: String = "k-$n", bn: Boolean = true): InboxItem {
        val ref = mapOf("ticket_updated" to "support_ticket", "leave_decided" to "leave_application", "final_submit_auto" to "zone_day").getValue(kind)
        return InboxItem(
            userId = ids.getValue(user), kind = kind, refType = ref,
            refId = if (ref == "zone_day") Inbox.zoneDayRefId(7, java.time.LocalDate.of(2027, 1, 2)) else UUID.nameUUIDFromBytes("$user-$n".toByteArray()).toString(),
            titleEn = "Ticket updated $n", titleBn = if (bn) "টিকিট আপডেট $n" else null,
            bodyEn = "Support replied on your ticket $n", bodyBn = if (bn) "সাপোর্ট আপনার টিকিটে উত্তর দিয়েছে $n" else null,
            dedupeKey = key,
        )
    }

    private fun add(vararg items: InboxItem): List<Boolean> = db.jdbi.inTransaction<List<Boolean>, Exception> { h -> items.map { Inbox.add(h, it) } }

    private fun scalar(sql: String): String? = db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }

    private fun count(sql: String): Int = scalar(sql)!!.toInt()

    private fun clear() = db.jdbi.useHandle<Exception> { h -> h.execute("DELETE FROM app.user_notification") }

    private fun webToken(user: String, role: Role): String = sign(user, role, "web", null)

    private fun phoneToken(user: String, role: Role, device: String): String = sign(user, role, role.wire.lowercase(), device)

    private fun sign(user: String, role: Role, flavour: String, device: String?): String {
        val at = now.get()
        val c = JWTClaimsSet.Builder().issuer("aron").audience("aron-api").subject(ids.getValue(user).toString()).claim("uname", user)
            .claim("role", role.wire).claim("sv", 1L).claim("flv", flavour).claim("perm", emptyList<String>()).claim("pii", false)
            .apply { if (device != null) { claim("did", 1L); claim("dvu", device) } }
            .claim("amr", if (device != null) listOf("pwd", "device") else listOf("pwd"))
            .issueTime(Date.from(at)).notBeforeTime(Date.from(at)).expirationTime(Date.from(at.plusSeconds(86_400))).jwtID(UUID.randomUUID().toString()).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("t").build(), c).apply { sign(keys.signer) }.serialize()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(100000), "cfg.api.rl.device_per_min" to JsonPrimitive(100000)))
        application {
            installAronPlatform(PlatformContext(clock, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(keys, clock), ScopeVersionLookup { 1 }, cfg, clock)
            routing { route("/v1") { inboxRoutes(InboxDeps(db, guard, clock)) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, token: String?, body: String? = null, device: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            token?.let { bearerAuth(it) }
            device?.let { header("X-Device-Id", it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private suspend fun ApplicationTestBuilder.inbox(token: String, query: String = "", device: String? = null): NotificationInbox {
        val r = send(HttpMethod.Get, "/v1/me/notifications$query", token, device = device)
        val text = r.bodyAsText()
        assertEquals(HttpStatusCode.OK, r.status, text)
        return strict.decodeFromString(NotificationInbox.serializer(), text)
    }

    private suspend fun ApplicationTestBuilder.read(token: String, upTo: Long, device: String? = null): Int {
        val r = send(HttpMethod.Post, "/v1/me/notifications/read", token, """{"up_to_id":$upTo}""", device)
        val text = r.bodyAsText()
        assertEquals(HttpStatusCode.OK, r.status, text)
        return strict.decodeFromString(NotificationReadResult.serializer(), text).unreadCount
    }

    // ---- producers -------------------------------------------------------------------------------------------------

    /** R-F-04: one row per (recipient, dedupe key); the same key reaches several users once each; a retry adds nothing. */
    @Test
    fun producersAreIdempotentPerRecipient() {
        clear()
        assertEquals(listOf(true, false), add(item("tsoinbox1", 1, key = "zone:7:auto"), item("tsoinbox1", 1, key = "zone:7:auto")))
        // A retried producer (another transaction, other texts, same key) adds nothing and changes nothing.
        assertEquals(listOf(false), add(item("tsoinbox1", 99, key = "zone:7:auto")))
        assertEquals("Ticket updated 1", scalar("SELECT title_en FROM app.user_notification WHERE dedupe_key = 'zone:7:auto'"))
        // The same key for other recipients: one row each (a zone auto-submit reaches every TSO covering the zone).
        val fanOut = db.jdbi.inTransaction<Set<Long>, Exception> { h ->
            Inbox.addAll(h, listOf("tsoinbox1", "tsoinbox2", "srinbox1").map { item(it, 1, kind = "final_submit_auto", key = "zone:7:auto") })
        }
        assertEquals(setOf(ids.getValue("tsoinbox2"), ids.getValue("srinbox1")), fanOut, "only the recipients with a new row are returned for the nudge")
        assertEquals(3, count("SELECT count(*) FROM app.user_notification WHERE dedupe_key = 'zone:7:auto'"))
        assertEquals("7:2027-01-02", scalar("SELECT ref_id FROM app.user_notification WHERE user_id = ${ids.getValue("tsoinbox2")}"))
        // The row commits with the producer's change or not at all.
        runCatching {
            db.jdbi.useTransaction<Exception> { h -> Inbox.add(h, item("tsoinbox2", 2, key = "rolled-back")); error("the producer's change failed") }
        }
        assertEquals(0, count("SELECT count(*) FROM app.user_notification WHERE dedupe_key = 'rolled-back'"))
        // Outside a transaction the producer is refused (R-F-04: the caller's transaction).
        assertFailsWith<IllegalStateException> { db.jdbi.useHandle<Exception> { h -> Inbox.add(h, item("tsoinbox1", 3, key = "no-tx")) } }
        assertEquals(0, count("SELECT count(*) FROM app.user_notification WHERE dedupe_key = 'no-tx'"))
        assertEquals("notification", Inbox.PUSH_REASON)
        assertEquals(Inbox.PUSH_REASON, PushReasons.NOTIFICATION)
        assertTrue(PushReasons.NOTIFICATION in PushReasons.ALL && PushReasons.TASK_ASSIGNED in PushReasons.ALL)
    }

    /** The V0133 bounds and the kind/ref_type pairs fail in the producer, before the database. */
    @Test
    fun aMalformedItemFailsInTheProducer() {
        val ok = item("tsoinbox1", 1)
        assertFailsWith<IllegalArgumentException> { ok.copy(kind = "announcement") }
        assertFailsWith<IllegalArgumentException> { ok.copy(refType = "zone_day") }
        assertFailsWith<IllegalArgumentException> { ok.copy(refId = "") }
        assertFailsWith<IllegalArgumentException> { ok.copy(refId = "x".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(titleEn = " ") }
        assertFailsWith<IllegalArgumentException> { ok.copy(titleEn = "t".repeat(121)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(titleBn = "t".repeat(121)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(bodyEn = "b".repeat(301)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(bodyBn = "b".repeat(301)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(dedupeKey = "") }
        assertFailsWith<IllegalArgumentException> { ok.copy(dedupeKey = "d".repeat(161)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(bodyEn = "a\u0000b") }
        assertFailsWith<IllegalArgumentException> { ok.copy(userId = 0) }
        // The longest values the columns take are accepted by the database too.
        clear()
        assertEquals(listOf(true), add(ok.copy(refId = "r".repeat(64), titleEn = "t".repeat(120), titleBn = "ট".repeat(120), bodyEn = "b".repeat(300), bodyBn = "ব".repeat(300), dedupeKey = "d".repeat(160))))
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    /** Newest first, paged by before_id, unread_count over every unread row (not the page); explicit nulls on the wire. */
    @Test
    fun unreadCountAndPaging() = app {
        clear()
        val tso = webToken("tsoinbox1", Role.TSO)
        val empty = inbox(tso)
        assertEquals(0, empty.unreadCount); assertEquals(emptyList(), empty.items); assertNull(empty.nextBeforeId)
        assertEquals("2027-01-03T04:00:00.000Z", empty.asOf)
        add(*(1..7).map { item("tsoinbox1", it, bn = it != 7) }.toTypedArray())
        val all = db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.user_notification WHERE user_id = :u ORDER BY id DESC").bind("u", ids.getValue("tsoinbox1")).mapTo(Long::class.java).list() }
        // Three pages of 3, 3 and 1.
        val p1 = inbox(tso, "?limit=3")
        assertEquals(all.take(3), p1.items.map { it.id }); assertEquals(7, p1.unreadCount); assertEquals(all[2], p1.nextBeforeId)
        val p2 = inbox(tso, "?limit=3&before_id=${p1.nextBeforeId}")
        assertEquals(all.subList(3, 6), p2.items.map { it.id }); assertEquals(7, p2.unreadCount); assertEquals(all[5], p2.nextBeforeId)
        val p3 = inbox(tso, "?limit=3&before_id=${p2.nextBeforeId}")
        assertEquals(all.takeLast(1), p3.items.map { it.id }); assertNull(p3.nextBeforeId)
        // An exact last page has no next page either.
        assertNull(inbox(tso, "?limit=7").nextBeforeId)
        assertEquals(all, inbox(tso).items.map { it.id }, "default page 50")
        // The row as the producer wrote it.
        val newest = p1.items.first()
        assertEquals("ticket_updated", newest.kind); assertEquals("support_ticket", newest.refType)
        assertEquals("Ticket updated 7", newest.titleEn); assertNull(newest.titleBn); assertNull(newest.bodyBn); assertNull(newest.readAt)
        assertEquals("টিকিট আপডেট 6", p1.items[1].titleBn)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(newest.createdAt), newest.createdAt)
        assertEquals(scalar("SELECT business_date::text FROM app.user_notification WHERE id = ${newest.id}"), newest.businessDate)
        // Required-nullable members are sent as explicit nulls (the contract lists them as required).
        val raw = Json.parseToJsonElement(send(HttpMethod.Get, "/v1/me/notifications?limit=1", tso).bodyAsText()).jsonObject
        val row = raw.getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonNull, row["title_bn"]); assertEquals(JsonNull, row["body_bn"]); assertEquals(JsonNull, row["read_at"])
        // Query validation (contract: before_id is an Id, limit 1..100).
        for (q in listOf("?limit=0", "?limit=101", "?limit=x", "?before_id=0", "?before_id=-4", "?before_id=abc")) {
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/v1/me/notifications$q", tso).status, q)
        }
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/v1/me/notifications?limit=100", tso).status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/v1/me/notifications", null).status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Post, "/v1/me/notifications/read", null, """{"up_to_id":1}""").status)
    }

    /** read up_to_id: read_at set once, a repeat changes nothing, an id below changes nothing, an id above is accepted. */
    @Test
    fun readUpToIdIsIdempotent() = app {
        clear()
        val device = UUID.randomUUID().toString()
        val tso = phoneToken("tsoinbox1", Role.TSO, device)
        add(*(1..5).map { item("tsoinbox1", it) }.toTypedArray())
        val ids5 = inbox(tso, device = device).items.map { it.id }   // newest first
        val third = ids5[2]
        now.set(Instant.parse("2027-01-03T05:00:00Z"))
        assertEquals(2, read(tso, third, device))
        val firstRead = scalar("SELECT max(read_at)::text FROM app.user_notification WHERE id <= $third")
        assertEquals(3, count("SELECT count(*) FROM app.user_notification WHERE read_at IS NOT NULL"))
        // A repeat later: same answer, read_at keeps the first time.
        now.set(Instant.parse("2027-01-03T06:00:00Z"))
        assertEquals(2, read(tso, third, device))
        assertEquals(2, read(tso, ids5[4], device), "an up_to_id below rows already read changes nothing")
        assertEquals(firstRead, scalar("SELECT max(read_at)::text FROM app.user_notification WHERE id <= $third"))
        assertEquals(1, count("SELECT count(DISTINCT read_at) FROM app.user_notification WHERE read_at IS NOT NULL"))
        val page = inbox(tso, device = device)
        assertEquals(2, page.unreadCount)
        assertEquals("2027-01-03T05:00:00.000Z", page.items.first { it.id == third }.readAt)
        assertNull(page.items.first().readAt)
        // An id above the newest row is accepted and reads everything.
        assertEquals(0, read(tso, Long.MAX_VALUE, device))
        assertEquals(0, inbox(tso, device = device).unreadCount)
        assertEquals(0, read(tso, Long.MAX_VALUE, device), "idempotent at zero")
        // A new row after the read is unread again.
        add(item("tsoinbox1", 6))
        assertEquals(1, inbox(tso, device = device).unreadCount)
        // Body validation (NotificationReadRequest: up_to_id Id, nothing else).
        for (b in listOf("""{"up_to_id":0}""", """{"up_to_id":-1}""", """{}""", """{"up_to_id":true}""", """{"up_to_id":5,"user_id":1}""", """{"up_to_id":1.5}""")) {
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/v1/me/notifications/read", tso, b, device).status, b)
        }
        // A phone call without its own X-Device-Id is refused by the guard.
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/v1/me/notifications", tso).status)
    }

    /** Own rows only: the caller never reads, counts or marks another user's rows, whatever ids it sends. */
    @Test
    fun ownRowsOnly() = app {
        clear()
        val t1 = webToken("tsoinbox1", Role.TSO)
        val t2 = webToken("tsoinbox2", Role.TSO)
        val device = UUID.randomUUID().toString()
        val sr = phoneToken("srinbox1", Role.SR, device)
        add(item("tsoinbox1", 1), item("tsoinbox2", 1), item("tsoinbox1", 2), item("tsoinbox2", 2), item("tsoinbox2", 3))
        val mine = inbox(t1)
        val theirs = inbox(t2)
        assertEquals(2, mine.unreadCount); assertEquals(3, theirs.unreadCount)
        assertTrue(mine.items.none { it.id in theirs.items.map { t -> t.id } })
        assertEquals(setOf("Ticket updated 1", "Ticket updated 2"), mine.items.map { it.titleEn }.toSet())
        // Paging from another user's id still answers own rows only.
        assertTrue(inbox(t1, "?before_id=${theirs.items.first().id + 1}").items.all { it.id in mine.items.map { m -> m.id } })
        // Marking read up to the highest id touches only the caller's rows.
        assertEquals(0, read(t1, theirs.items.first().id))
        assertEquals(3, inbox(t2).unreadCount)
        assertEquals(0, count("SELECT count(*) FROM app.user_notification WHERE user_id = ${ids.getValue("tsoinbox2")} AND read_at IS NOT NULL"))
        // A user without rows sees an empty inbox, never someone else's.
        val srInbox = inbox(sr, device = device)
        assertEquals(0, srInbox.unreadCount); assertEquals(emptyList(), srInbox.items)
        assertEquals(0, read(sr, Long.MAX_VALUE, device))
        assertEquals(3, inbox(t2).unreadCount)
        // A user id in the query is not a parameter of the operation: ignored, own rows still.
        assertEquals(mine.items.map { it.id }, inbox(t1, "?user_id=${ids.getValue("tsoinbox2")}").items.map { it.id })
    }

    // ---- prune -----------------------------------------------------------------------------------------------------

    /**
     * Rows past cfg.notify.inbox_retention_days (90; bounded 30..365) are deleted by the worker's job, in chunks, read or
     * unread; a second run deletes nothing. The worker's role (worker_rw) holds DELETE; the API's (api_rw) does not.
     */
    @Test
    fun pruneRunsAsTheWorkerAndKeepsTheWindow() {
        clear()
        val dbNow = db.jdbi.withHandle<OffsetDateTime, Exception> { h -> h.createQuery("SELECT now()").mapTo(OffsetDateTime::class.java).one() }.toInstant()
        val u = ids.getValue("tsoinbox1")
        db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.user_notification (user_id, kind, ref_type, ref_id, title_en, body_en, dedupe_key, created_at, business_date, read_at) " +
                    "SELECT $u, 'ticket_updated', 'support_ticket', 'r' || d, 't', 'b', 'age-' || d, now() - make_interval(days => d), " +
                    "app.dhaka_date(now() - make_interval(days => d)), CASE WHEN d % 2 = 0 THEN now() END " +
                    "FROM unnest(ARRAY[400, 100, 91, 89, 31, 29, 1]) d",
            )
        }
        val jobClock = AronClock { dbNow }
        withLogin("worker_rw") { worker ->
            val job = InboxPruneJob(worker, DbServerConfig(worker, RegistryDefaults(), jobClock), jobClock, chunk = 2)
            // Read from the database as the worker (RegistryDefaults does not know the key, so a failed read would throw).
            assertEquals(90, DbServerConfig(worker, RegistryDefaults(), jobClock).int(InboxPruneJob.RETENTION_KEY), "the registry default of V0134")
            assertEquals(90, job.retentionDays())
            assertEquals(3, job.runOnce(), "400, 100 and 91 days old, in two chunks")
            assertEquals("1,29,31,89", scalar("SELECT string_agg(substr(dedupe_key, 5), ',' ORDER BY substr(dedupe_key, 5)::int) FROM app.user_notification"))
            assertEquals(0, job.runOnce(), "idempotent")
            // A value below the key's minimum cannot shorten the window below 30 days; above the maximum it stops at 365.
            fun job(days: Int) = InboxPruneJob(worker, RegistryDefaults(overrides = mapOf(InboxPruneJob.RETENTION_KEY to JsonPrimitive(days))), jobClock)
            assertEquals(30, job(5).retentionDays()); assertEquals(365, job(1000).retentionDays())
            assertEquals(0, job(1000).runOnce())
            assertEquals(2, job(5).runOnce(), "89 and 31 days old; 29 and 1 stay")
            assertEquals("1,29", scalar("SELECT string_agg(substr(dedupe_key, 5), ',' ORDER BY substr(dedupe_key, 5)::int) FROM app.user_notification"))
            // The worker may also insert (the midnight auto Final Submit's rows).
            assertTrue(worker.jdbi.inTransaction<Boolean, Exception> { h -> Inbox.add(h, item("tsoinbox2", 1, kind = "final_submit_auto", key = "worker-row")) })
        }
        withLogin("api_rw") { api ->
            // The API inserts and marks read, but never deletes: the prune is the worker's.
            assertTrue(api.jdbi.inTransaction<Boolean, Exception> { h -> Inbox.add(h, item("tsoinbox1", 9, key = "api-row")) })
            assertEquals(0, InboxStore.markRead(api, u, Long.MAX_VALUE, dbNow))
            assertEquals(0, count("SELECT count(*) FROM app.user_notification WHERE user_id = $u AND read_at IS NULL"))
            val e = assertFailsWith<Exception> { InboxPruneJob(api, RegistryDefaults(overrides = mapOf(InboxPruneJob.RETENTION_KEY to JsonPrimitive(30))), AronClock { dbNow.plusSeconds(400L * 86400) }).runOnce() }
            assertEquals("42501", generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().first().sqlState, "permission denied")
        }
        assertEquals(4, count("SELECT count(*) FROM app.user_notification"), "nothing was deleted by the API's role")
    }

    /** Runs [block] on a pool whose login is only a member of [role] (the production grants, not the test owner's). */
    private fun withLogin(role: String, block: (Database) -> Unit) {
        val login = "inbox_${role}_" + fresh.name.takeLast(10).replace(Regex("[^a-z0-9_]"), "_")
        val pw = "pw-" + UUID.randomUUID()
        db.jdbi.useHandle<Exception> { h ->
            h.execute("CREATE ROLE $login LOGIN PASSWORD '$pw' IN ROLE $role")
            h.execute("GRANT CONNECT ON DATABASE ${fresh.name} TO $login")
        }
        try {
            val url = fresh.url.replace(Regex("([?&])user=[^&]*"), "$1user=$login").replace(Regex("([?&])password=[^&]*"), "$1password=$pw")
            Database.pool(url, login, Secret(pw), 2, "inbox-$role").use { ds -> block(Database(ds)) }
        } finally {
            db.jdbi.useHandle<Exception> { h -> h.execute("DROP OWNED BY $login"); h.execute("DROP ROLE $login") }
        }
    }

    /** D-ROLES-02: the inbox is the caller's own (no ADMIN module), and the OWN line widens nothing else under /v1/me. */
    @Test
    fun theInboxIsTheCallersOwnForTheAdminModuleGate() {
        assertEquals("own", com.aktcl.aron.backend.platform.AdminModules.moduleOf("GET", "/v1/me/notifications"))
        assertEquals("own", com.aktcl.aron.backend.platform.AdminModules.moduleOf("POST", "/v1/me/notifications/read"))
        assertNull(com.aktcl.aron.backend.platform.AdminModules.moduleOf("GET", "/v1/me/notifications-x"))
        assertNull(com.aktcl.aron.backend.platform.AdminModules.moduleOf("POST", "/v1/me/other"))
    }

    @Test
    fun theKindsAreTheContractsAndEachTakesItsRefType() {
        assertEquals(mapOf("ticket_updated" to "support_ticket", "leave_decided" to "leave_application", "final_submit_auto" to "zone_day"), Inbox.REF_TYPE_OF)
        assertEquals("12:2027-01-02", Inbox.zoneDayRefId(12, java.time.LocalDate.of(2027, 1, 2)))
        assertFalse(Inbox.REF_TYPE_OF.containsKey("announcement"), "announcements stay push-only (D-TSO-MAN-26)")
    }
}

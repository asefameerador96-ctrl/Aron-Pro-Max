package com.aktcl.aron.tso

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.notifications.TsoInboxPage
import com.aktcl.aron.feature.tso.notifications.TsoInboxes
import com.aktcl.aron.feature.tso.notifications.TsoNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The bell inbox (D-TSO-MAN-26, SH-08; build contract s5 A1 and s8 "inbox and bell"): the bell is the server's unread count minus
 * the reads this phone has not sent yet; opening the inbox marks everything up to the newest id read (idempotent `up_to_id`), kept on
 * the phone while offline and sent first on the next read; the newest page is kept for offline; reads happen only when asked (the
 * Dashboard's open and pull-to-refresh, a push, the inbox itself), never on a timer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoInboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Seen(val method: String, val path: String, val query: String?, val body: String)

    // the card reads run in parallel on OkHttp threads: a plain list can lose a request (a flaky count), so a thread-safe one
    private val seen = java.util.concurrent.CopyOnWriteArrayList<Seen>()
    private var transportDown = false
    private var readStatus = 200
    /** Requests held until released: a request whose "METHOD path" equals the key waits on its latch (a slow 2G read). */
    private val holds = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    /** The fake server's inbox: ids 3 (unread), 2 (unread), 1 (read). */
    private val readAt = mutableMapOf(3L to null, 2L to null, 1L to "2026-10-28T10:00:00Z")

    private fun page(): String {
        val rows = readAt.keys.sortedDescending().joinToString(",") { id ->
            val ra = readAt[id]?.let { "\"$it\"" } ?: "null"
            val (refType, refId, kind) = when (id) { 3L -> Triple("support_ticket", "6f1c0b9e-1111-4c4c-9a9a-000000000003", "ticket_updated"); 2L -> Triple("leave_application", "7", "leave_decided"); else -> Triple("zone_day", "9:2026-10-27", "final_submit_auto") }
            """{"id":$id,"kind":"$kind","ref_type":"$refType","ref_id":"$refId","title_en":"Title $id","title_bn":${if (id == 3L) "\"শিরোনাম\"" else "null"},"body_en":"Body $id","body_bn":null,"created_at":"2026-10-29T0$id:00:00Z","business_date":"2026-10-29","read_at":$ra}"""
        }
        return """{"as_of":"2026-10-29T08:30:00Z","unread_count":${readAt.values.count { it == null }},"items":[$rows],"next_before_id":null}"""
    }

    private fun client(): AronApiClient {
        val base = OkHttpClient.Builder().addInterceptor { ch ->
            val r = ch.request()
            if (transportDown) throw java.io.IOException("offline")
            val body = r.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            seen += Seen(r.method, r.url.encodedPath, r.url.query, body)
            holds["${r.method} ${r.url.encodedPath}"]?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val (code, answer) = when {
                r.url.encodedPath == "/v1/me/notifications/read" -> {
                    if (readStatus == 200) {
                        val up = Regex("\"up_to_id\":(\\d+)").find(body)!!.groupValues[1].toLong()
                        readAt.keys.filter { it <= up && readAt[it] == null }.forEach { readAt[it] = "2026-10-29T09:00:00Z" }
                        200 to """{"unread_count":${readAt.values.count { it == null }}}"""
                    } else readStatus to """{"type":"about:blank","title":"x","status":$readStatus,"code":"ERR_VALIDATION"}"""
                }
                r.url.encodedPath == "/v1/me/notifications" -> 200 to page()
                else -> 200 to "{}"
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x").header("X-Aron-Api", "1").body(answer.toResponseBody()).build()
        }.build()
        return AronApiClient(ApiOrigin.parse("https://api.example/"), base, ClientIdentity("1.0+1") { "dev-1" }, object : AccessTokenSource {
            override fun currentAccessToken(grant: Grant) = "t"
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
        })
    }

    private val prefs by lazy { ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("aron_ui_77", Context.MODE_PRIVATE).also { it.edit().clear().commit() } }
    /** A model whose reads run in the test's scope (the day's scope in the app). */
    private fun CoroutineScope.model(dir: java.io.File = tmp.newFolder("c")) = TsoInboxModel(TsoApi(client(), dir), prefs, this)

    @Test fun theBellIsTheServersUnreadCountAndNothingReadsWithoutACall() = runBlocking {
        val m = model()
        assertTrue("no read without a call (no timer)", seen.isEmpty())
        assertEquals(0, m.unread)
        m.refresh()
        assertEquals(2, m.unread)
        assertEquals(listOf("GET /v1/me/notifications"), seen.map { "${it.method} ${it.path}" })
        assertEquals("limit=50", seen.single().query) // no scope ids, ever (constraint 4)
    }

    @Test fun openingTheInboxMarksEverythingUpToTheNewestIdRead() = runBlocking {
        val m = model()
        m.open()
        assertEquals(listOf("GET /v1/me/notifications", "POST /v1/me/notifications/read"), seen.map { "${it.method} ${it.path}" })
        assertEquals("""{"up_to_id":3}""", seen[1].body)
        assertEquals(0, m.unread)
        assertNull(m.pendingUpTo)
        assertFalse(prefs.contains(TsoInboxModel.KEY_PENDING_READ))
        // the rows that were new when the list was read keep their dot for this visit
        assertEquals(listOf(3L, 2L), m.page!!.value!!.items.filter { it.unread }.map { it.id })
        // nothing unread: opening again sends no read
        seen.clear(); m.open()
        assertEquals(listOf("GET /v1/me/notifications"), seen.map { "${it.method} ${it.path}" })
    }

    @Test fun offlineTheKeptPageOpensAndThePendingReadComesOffTheBellThenGoesFirstOnline() = runBlocking {
        val dir = tmp.newFolder("c")
        model(dir).refresh() // an earlier online read keeps the newest page
        transportDown = true
        val m = model(dir)
        m.open()
        val p = m.page!!
        assertTrue("the kept copy", p.kept)
        assertEquals(listOf(3L, 2L, 1L), p.value!!.items.map { it.id })
        assertEquals(3L, m.pendingUpTo)
        assertEquals(3L, prefs.getLong(TsoInboxModel.KEY_PENDING_READ, 0L))
        assertEquals("unread minus pending reads", 0, m.unread)
        // a new model (process death) still knows the pending read
        assertEquals(3L, model(dir).pendingUpTo)
        // online again: the pending read goes first, then the page
        transportDown = false
        seen.clear()
        m.refresh()
        assertEquals(listOf("POST /v1/me/notifications/read", "GET /v1/me/notifications"), seen.map { "${it.method} ${it.path}" })
        assertEquals("""{"up_to_id":3}""", seen[0].body)
        assertNull(m.pendingUpTo)
        assertFalse(prefs.contains(TsoInboxModel.KEY_PENDING_READ))
        assertEquals(0, m.unread)
        // a replayed read changes nothing on the server (idempotent up_to_id)
        assertEquals(0, readAt.values.count { it == null })
    }

    @Test fun offlineWithNoCopySaysSoAndKeepsTheRead() = runBlocking {
        transportDown = true
        val m = model()
        m.open()
        assertNull(m.page!!.value)
        assertNull("nothing to mark without a page", m.pendingUpTo)
        assertEquals(0, m.unread)
    }

    @Test fun aRefusedReadIsDroppedNotRetried() = runBlocking {
        val m = model()
        m.refresh()
        readStatus = 400
        m.markAllRead()
        assertNull(m.pendingUpTo)
        seen.clear(); readStatus = 200
        m.refresh()
        assertEquals(listOf("GET /v1/me/notifications"), seen.map { "${it.method} ${it.path}" })
    }

    @Test fun theDashboardReadsTheBellWithItsCards() = runBlocking {
        val api = TsoApi(client(), tmp.newFolder("c"))
        val inbox = TsoInboxModel(api, prefs, this)
        TsoDashboardModel(api, { "2026-10-29" }, null, this, alsoOnLoad = { inbox.refresh() }).load()
        assertEquals(1, seen.count { it.path == "/v1/me/notifications" })
        assertEquals(2, inbox.unread)
    }

    @Test fun theBellTappedWhileTheDashboardReadsTheInboxJoinsThatReadAndMarksTheNewestId() = runBlocking {
        val slow = java.util.concurrent.CountDownLatch(1)
        holds["GET /v1/me/notifications"] = slow
        try {
            val api = TsoApi(client(), tmp.newFolder("c"))
            val inbox = TsoInboxModel(api, prefs, this)
            val dashboard = TsoDashboardModel(api, { "2026-10-29" }, null, this, alsoOnLoad = { inbox.refresh() })
            val dashboardScreen = launch { dashboard.load() } // the Dashboard's LaunchedEffect
            withTimeout(10_000) { while (seen.none { it.path == "/v1/me/notifications" }) delay(5) }
            assertTrue("the bell's read is running", inbox.refreshing)
            // the bell: the Dashboard leaves the composition (its effect is cancelled) and the inbox opens
            dashboardScreen.cancel()
            val inboxScreen = launch { inbox.open() }
            yield()
            assertTrue("the inbox waits for the read in flight", inboxScreen.isActive)
            slow.countDown()
            inboxScreen.join()
            assertEquals("one page read, joined, not two", 1, seen.count { it.method == "GET" && it.path == "/v1/me/notifications" })
            assertEquals("the newest id is marked read", """{"up_to_id":3}""", seen.single { it.path == "/v1/me/notifications/read" }.body)
            assertEquals(listOf(3L, 2L, 1L), inbox.page!!.value!!.items.map { it.id })
            assertEquals(0, inbox.unread)
            assertNull(inbox.pendingUpTo)
            assertFalse(inbox.refreshing)
        } finally {
            slow.countDown()
        }
    }

    // ---- the pure parts ------------------------------------------------------------------------------------------------------

    private fun n(id: Long, read: Boolean) = TsoNotification(id, "ticket_updated", "support_ticket", "u$id", "t", null, "b", null, "2026-10-29T00:00:00Z", "2026-10-29", if (read) "x" else null)

    @Test fun theBadgeIsUnreadMinusPendingReads() {
        val p = TsoInboxPage("2026-10-29T08:30:00Z", 5, listOf(n(10, false), n(9, false), n(8, true), n(7, false)), 6)
        assertEquals(5, TsoInboxes.badge(p, null))
        assertEquals(0, TsoInboxes.badge(p, 10)) // everything the server counted was at or below the newest id
        assertEquals(0, TsoInboxes.badge(p, 11))
        assertEquals(3, TsoInboxes.badge(p, 9)) // 9 and 7 come off
        assertEquals(4, TsoInboxes.badge(p, 7))
        assertEquals(0, TsoInboxes.badge(null, 9))
        assertEquals(0, TsoInboxes.badge(p.copy(unreadCount = 1), 9)) // never negative
    }

    @Test fun parsingTheTextsAndWhereARowLeads() {
        val p = TsoInboxes.parse(page())!!
        assertEquals(2, p.unreadCount)
        assertEquals(listOf(3L, 2L, 1L), p.items.map { it.id })
        assertEquals("শিরোনাম", p.items[0].title(bangla = true))
        assertEquals("Title 2", p.items[1].title(bangla = true)) // no Bangla text: the English one
        assertEquals(TsoScreen.TICKET_DETAIL, TsoInboxes.target(p.items[0])!!.screen)
        assertEquals("6f1c0b9e-1111-4c4c-9a9a-000000000003", TsoInboxes.target(p.items[0])!!.arg)
        assertEquals(TsoScreen.LEAVE, TsoInboxes.target(p.items[1])!!.screen)
        assertEquals(TsoScreen.FINAL_SUBMIT, TsoInboxes.target(p.items[2])!!.screen)
        assertEquals("""{"up_to_id":42}""", TsoInboxes.readBody(42))
        assertEquals(3, TsoInboxes.readResult("""{"unread_count":3}"""))
        assertNull(TsoInboxes.parse("""{"as_of":"x"}"""))
        assertEquals("4d ago", TsoInboxes.age(p.items[0], java.time.Instant.parse("2026-11-02T03:00:00Z").toEpochMilli(), com.aktcl.aron.rules.UiLocale.EN))
    }
}

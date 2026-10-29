package com.aktcl.aron.tso

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.media.MediaComponents
import com.aktcl.aron.core.media.MediaItem
import com.aktcl.aron.core.media.MediaRef
import com.aktcl.aron.core.media.MediaState
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.tickets.Delivery
import com.aktcl.aron.feature.tickets.DetailRead
import com.aktcl.aron.feature.tickets.EventKind
import com.aktcl.aron.feature.tickets.LocalEvent
import com.aktcl.aron.feature.tickets.TicketMirror
import com.aktcl.aron.feature.tickets.TicketOrigin
import com.aktcl.aron.feature.tickets.TicketRules
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * tso-manual A2 step 3, DEFINITION OF DONE for the ticket writes (CLAUDE.md): a ticket and its events are saved through the
 * one TSO outbox with a client UUID; the process is killed (the database closed and reopened from disk, every host object
 * rebuilt) and the same UUID is saved again: exactly ONE outbox row, ONE byte-identical payload, the mirror row
 * `tso.unsent.ticket.<uuid>` unchanged and one sync request per real change. The photos are claimed for their record before
 * it commits. The reads drop a mirror row once the server returns the record, keep their copy for offline, and never send a
 * scope id. A real file database and a real media store are used, so "kill and relaunch" really goes through the disk.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TicketWriteReplayTest {
    @get:Rule val tmp = TemporaryFolder()

    private val user = 7L
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var databases: UserDatabases
    private var nowMs = 1_792_928_040_000L // 2026-10-25 17:34 Dhaka
    private var syncs = 0

    private val env = object : TsoEnv {
        override val client: AronApiClient get() = error("no network in a write test")
        override fun nowMs() = nowMs
        override fun elapsedRealtimeMs() = 5_000L
        override fun bootCount() = 3
        override fun clockOffsetMs(): Long? = 0L
        override fun businessDate() = "2026-10-25"
    }
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) { syncs++ } }
    private val clock = object : WallClock { override fun nowMs() = nowMs; override fun elapsedRealtimeMs() = 5_000L }

    @Before fun open() {
        File(app.filesDir, "media").deleteRecursively()
        databases = UserDatabases(app) { null }
    }

    @After fun close() { databases.close(user) }

    /** The process dies: the database handle and every host object are gone; the next launch opens the same file again. */
    private fun relaunch() { databases.close(user); databases.allowOpen(user); databases = UserDatabases(app) { null } }

    private fun media() = MediaComponents(app, user, clock, { "2026-10-25" })

    /** A claim that counts its calls and goes through the real media store, as TicketsHostDeps does. */
    private var claims = 0
    private val realClaim: suspend (String, MediaRef) -> Unit = { photo, ref -> claims++; media().attach(photo, ref, stamp = null) }
    private fun device(claim: (suspend (String, MediaRef) -> Unit)? = null) =
        TicketDevice(databases, user, TsoOutbox(env, databases, scheduler) { false }, claim ?: realClaim)

    private fun rows(): List<OutboxEntity> = runBlocking { databases.of(user).outboxDao().nextPending(10_000) }
    private fun rowsOf(type: RecordType) = rows().filter { it.recordType == type.wire }
    private fun mirror(prefix: String): Map<String, String> = runBlocking { databases.of(user).referenceDao().metaWithPrefix(prefix).associate { it.key to it.value } }
    private fun sql(q: String, vararg args: Any) = runBlocking { databases.of(user).openHelper.writableDatabase.execSQL(q, args) }

    /** A photo staged in the media store (CAPTURED, not claimed), as a camera capture or a gallery stage leaves it. */
    private fun staged(uuid: String) = runBlocking {
        val jpeg = ByteArray(100) { 1 }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
        media().store.put(MediaItem(uuid, "2026-10-25", "2026-10-25T11:34:00Z", nowMs, sha, null, jpeg.size, 10, 10), jpeg, null)
    }

    private val ticketUuid = "0a6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f60"
    private val photoUuid = "1b6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f61"
    private val types = listOf(com.aktcl.aron.feature.tickets.IssueType("login_otp", "Login OTP issue", null, 15, icon = 6))

    private fun draft(uuid: String = ticketUuid, photos: List<String> = emptyList()) =
        TicketRules.newDraft(uuid, TicketOrigin.QUICK, "login_otp").copy(description = "OTP never arrives", photoUuids = photos)

    private suspend fun saveTicket(d: TicketDevice, uuid: String = ticketUuid, photos: List<String> = emptyList()): Boolean {
        val dr = draft(uuid, photos)
        return d.ticket(uuid, TicketRules.payload(dr, types[0]), dr.photoUuids, TicketRules.local(dr, types[0], nowMs))
    }

    // ---------- ticket ----------
    @Test fun aTicketSurvivesRelaunchAndReplayIsOneRowOnePayloadAndItsPhotoIsClaimedFirst() = runBlocking {
        staged(photoUuid)
        assertTrue(saveTicket(device(), photos = listOf(photoUuid)))
        val before = rowsOf(RecordType.SUPPORT_TICKET).single()
        val mirrorBefore = mirror(TicketDevice.TICKET_MIRROR)
        assertEquals(setOf("tso.unsent.ticket.$ticketUuid"), mirrorBefore.keys)
        assertEquals(1, syncs)
        assertEquals(1, claims)
        val photo = media().store.get(photoUuid)!!
        assertEquals(MediaState.ATTACHED, photo.state)
        assertEquals(MediaRef("support", "support_ticket", ticketUuid), photo.ref)

        relaunch()
        assertEquals(before.payloadJson, rowsOf(RecordType.SUPPORT_TICKET).single().payloadJson)
        assertEquals(mirrorBefore, mirror(TicketDevice.TICKET_MIRROR))

        // the form is rebuilt from its saved draft and Submit is tapped again with the same uuid
        nowMs += 60_000
        assertFalse("a replay adds nothing", saveTicket(device(), photos = listOf(photoUuid)))
        assertEquals(1, rowsOf(RecordType.SUPPORT_TICKET).size)
        assertEquals(before.payloadJson, rowsOf(RecordType.SUPPORT_TICKET).single().payloadJson)
        assertEquals(before.payloadSha256, rowsOf(RecordType.SUPPORT_TICKET).single().payloadSha256)
        assertEquals(mirrorBefore, mirror(TicketDevice.TICKET_MIRROR))
        assertEquals("no second sync request for a no-op", 1, syncs)
        assertEquals("a replay claims nothing again", 1, claims)

        val rec = JSONObject(before.payloadJson)
        assertEquals("support_ticket", rec.getString("type"))
        assertEquals(ticketUuid, rec.getString("client_uuid"))
        assertEquals(ticketUuid, rec.getString("family_uuid"))
        assertEquals(0, rec.getInt("rank"))
        assertEquals("2026-10-25", rec.getString("business_date"))
        val p = rec.getJSONObject("payload")
        assertEquals("login_otp", p.getString("issue_type_code"))
        assertEquals("Login OTP issue", p.getString("title")) // quick: title = the issue's label_en
        assertEquals("OTP never arrives", p.getString("description"))
        assertEquals("quick", p.getString("source"))
        assertFalse("no affected user: the member is absent, never a scope or user id", p.has("affected_username"))
        assertEquals(setOf("issue_type_code", "title", "description", "source"), p.keys().asSequence().toSet())

        // the mirror is the module's own shape: the unsent row reads back as New, not sent yet
        val unsent = device().outbox().tickets.single()
        assertEquals(ticketUuid, unsent.uuid)
        assertNull(unsent.number)
        assertEquals("new", unsent.status)
        assertEquals(Delivery.Pending, unsent.delivery)
    }

    @Test fun aKillBetweenTheClaimAndTheCommitRetriesToOneRowAndOneClaim() = runBlocking {
        staged(photoUuid)
        // the photo is claimed, then the process dies before the outbox commit
        val killed = device { photo, ref -> media().attach(photo, ref, stamp = null); throw kotlinx.coroutines.CancellationException("killed") }
        try { saveTicket(killed, photos = listOf(photoUuid)); fail("killed") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(rows().isEmpty())
        assertTrue(mirror(TicketDevice.TICKET_MIRROR).isEmpty())
        assertEquals(MediaState.ATTACHED, media().store.get(photoUuid)!!.state)

        relaunch()
        assertTrue("the retry from the saved draft commits", saveTicket(device(), photos = listOf(photoUuid)))
        assertEquals(1, rowsOf(RecordType.SUPPORT_TICKET).size)
        assertEquals("the photo stays the ticket's, claimed once", MediaRef("support", "support_ticket", ticketUuid), media().store.get(photoUuid)!!.ref)
        assertFalse(saveTicket(device(), photos = listOf(photoUuid)))
        assertEquals(1, rows().size)
    }

    /** The photo port as TicketsHostDeps builds it: the real media store, the record check on the real outbox. */
    private fun photoPort() = MediaTicketPhotos({ media() }, { media() }, { 3 }) { device().holds(it) }

    @Test fun aPhotoRemovedAfterAClaimWhoseCommitFailedIsNeverSentWithTheRetry() = runBlocking {
        val kept = "1b6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f6a"
        val removed = "1b6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f6b"
        staged(kept)
        staged(removed)
        // both photos are claimed, then the commit fails (full disk) before any row is written
        val full = device { photo, ref -> media().attach(photo, ref, stamp = null); if (photo == removed) throw java.io.IOException("disk full") }
        try { saveTicket(full, photos = listOf(kept, removed)); fail("the commit failed") } catch (_: java.io.IOException) { }
        assertTrue(rows().isEmpty())
        assertEquals(MediaState.ATTACHED, media().store.get(removed)!!.state)

        // the form still shows both; the rep removes one (TicketFormState.removePhoto -> discard): the claimed photo of a
        // record that was never saved is dropped, not kept for the retry
        photoPort().discard(removed)
        assertNull(media().store.get(removed))

        // the retry saves the ticket with the other photo only
        assertTrue(saveTicket(device(), photos = listOf(kept)))
        assertEquals(1, rowsOf(RecordType.SUPPORT_TICKET).size)
        assertEquals(MediaRef("support", "support_ticket", ticketUuid), media().store.get(kept)!!.ref)
        assertNull("the removed picture can never upload: it is gone", media().store.get(removed))
        assertEquals(listOf(kept), media().store.all().map { it.mediaUuid })

        // a photo of a saved record is never dropped, whatever the form says
        photoPort().discard(kept)
        assertEquals(MediaState.ATTACHED, media().store.get(kept)!!.state)
    }

    @Test fun aFormLeftAfterAKilledSaveDropsItsClaimedPhotosAndKeepsOtherRecordsPhotos() = runBlocking {
        val photo = "1b6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f6c"
        val onlyStaged = "1b6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f6d"
        val visitPhoto = "1b6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f6e"
        staged(photo)
        staged(onlyStaged)
        staged(visitPhoto)
        media().attach(visitPhoto, MediaRef("outlet_capture", "visit", "7c6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f60"), stamp = null)
        val killed = device { p, ref -> media().attach(p, ref, stamp = null); throw kotlinx.coroutines.CancellationException("killed") }
        try { saveTicket(killed, photos = listOf(photo)); fail("killed") } catch (_: kotlinx.coroutines.CancellationException) { }

        relaunch()
        // the rep leaves the restored form without saving: every photo it lists is dropped (claimed or only staged)
        photoPort().discard(photo)
        photoPort().discard(onlyStaged)
        assertNull(media().store.get(photo))
        assertNull(media().store.get(onlyStaged))
        // a photo claimed by another feature's record is never touched by the ticket port
        photoPort().discard(visitPhoto)
        assertEquals(MediaState.ATTACHED, media().store.get(visitPhoto)!!.state)
        assertTrue(rows().isEmpty())
    }

    @Test fun aPurgedUuidIsNeverQueuedAgain() = runBlocking {
        sql("INSERT INTO purged_uuid(client_uuid, purged_at) VALUES (?, ?)", ticketUuid, "2026-10-20T00:00:00.000Z")
        assertFalse(saveTicket(device()))
        assertTrue(rows().isEmpty())
        assertTrue(mirror(TicketDevice.TICKET_MIRROR).isEmpty())
        assertEquals(0, syncs)
    }

    @Test fun moreThanThreePhotosIsRefusedBeforeAnythingIsWritten() = runBlocking {
        val four = (1..4).map { "2b6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f6$it" }
        four.forEach { staged(it) }
        try { saveTicket(device(), photos = four); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(rows().isEmpty())
        assertEquals(0, claims)
    }

    // ---------- events ----------
    @Test fun aCommentAndTheCloseReviewAreOneRowEachAndReplayChangesNothing() = runBlocking {
        assertTrue(saveTicket(device()))
        val commentUuid = "3c6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f62"
        val closeUuid = TicketRules.closeUuid(ticketUuid)
        val comment = com.aktcl.aron.feature.tickets.CommentDraft(commentUuid, "Still no OTP after the reset")
        val c = LocalEvent(commentUuid, ticketUuid, EventKind.COMMENT, comment.body, nowMs)
        val close = LocalEvent(closeUuid, ticketUuid, EventKind.CLOSE_REVIEW, null, nowMs)
        assertTrue(device().event(commentUuid, TicketRules.commentPayload(ticketUuid, comment), emptyList(), c))
        assertTrue(device().event(closeUuid, TicketRules.closePayload(ticketUuid), emptyList(), close))
        val before = rows().associate { it.clientUuid to it.payloadJson }
        assertEquals(3, syncs)

        relaunch()
        assertFalse(device().event(commentUuid, TicketRules.commentPayload(ticketUuid, comment), emptyList(), c))
        // a second Submit Review & Close (double tap, a second screen, a retry after a kill) has the same name-based uuid
        assertEquals(closeUuid, TicketRules.closeUuid(ticketUuid))
        assertFalse(device().event(TicketRules.closeUuid(ticketUuid), TicketRules.closePayload(ticketUuid), emptyList(), close))
        assertEquals(before, rows().associate { it.clientUuid to it.payloadJson })
        assertEquals(3, syncs)

        val events = rowsOf(RecordType.SUPPORT_TICKET_EVENT).associateBy { it.clientUuid }
        val cp = JSONObject(events.getValue(commentUuid).payloadJson).getJSONObject("payload")
        assertEquals(mapOf("ticket_uuid" to ticketUuid, "kind" to "comment", "body" to "Still no OTP after the reset"), cp.keys().asSequence().associateWith { cp.getString(it) })
        val xp = JSONObject(events.getValue(closeUuid).payloadJson).getJSONObject("payload")
        assertFalse("a close review has no body member (the contract's body is never null)", xp.has("body"))
        assertEquals("close_review", xp.getString("kind"))
        assertEquals(closeUuid, JSONObject(events.getValue(closeUuid).payloadJson).getString("family_uuid"))

        // the two mirror families never mix: one ticket, two events
        assertEquals(setOf(TicketDevice.EVENT_MIRROR + commentUuid, TicketDevice.EVENT_MIRROR + closeUuid), mirror(TicketDevice.EVENT_MIRROR).keys)
        val box = device().outbox()
        assertEquals(listOf(ticketUuid), box.tickets.map { it.uuid })
        assertEquals(setOf(commentUuid, closeUuid), box.events.map { it.uuid }.toSet())
        assertEquals(TicketMirror.eventToJson(c), mirror(TicketDevice.EVENT_MIRROR).getValue(TicketDevice.EVENT_MIRROR + commentUuid))
    }

    // ---------- delivery (D-A2-15) ----------
    @Test fun deliveryFollowsTheOutboxRow() = runBlocking {
        assertTrue(saveTicket(device()))
        fun delivery() = runBlocking { device().outbox().tickets.single().delivery }
        assertEquals(Delivery.Pending, delivery())
        sql("UPDATE outbox SET state = 'in_flight' WHERE client_uuid = ?", ticketUuid)
        assertEquals(Delivery.Pending, delivery())
        sql("UPDATE outbox SET state = 'acked' WHERE client_uuid = ?", ticketUuid)
        assertEquals("acked, not read back yet", Delivery.Sent, delivery())
        sql("UPDATE outbox SET state = 'rejected', last_code = 'issue_type_unknown' WHERE client_uuid = ?", ticketUuid)
        assertEquals(Delivery.Refused("issue_type_unknown"), delivery())
        sql("UPDATE outbox SET state = 'quarantined', last_code = 'scope_out_of_reach' WHERE client_uuid = ?", ticketUuid)
        assertEquals(Delivery.Refused("scope_out_of_reach"), delivery())
        sql("DELETE FROM outbox WHERE client_uuid = ?", ticketUuid)
        assertEquals("a row purged after its ack is on the server", Delivery.Sent, delivery())
    }

    // ---------- reads ----------
    private data class Seen(val method: String, val path: String, val query: String?)

    private val seen = mutableListOf<Seen>()
    private val status = mutableMapOf<String, Int>()
    private var transportDown = false
    private val answers = mutableMapOf<String, String>()

    private fun ticketJson(uuid: String, no: String, status: String = "new", title: String = "Login OTP issue") = """
        {"ticket_uuid":"$uuid","ticket_no":"$no","issue_type_code":"login_otp","issue_type_label_en":"Login OTP issue","issue_type_label_bn":null,
         "category_label_en":"Tickets","title":"$title","description":"OTP never arrives","source":"quick","priority":"p1","status":"$status",
         "requester":{"user_id":7,"username":"tso1","full_name":"TSO One","role":"TSO"},"affected_username":null,
         "business_date":"2026-10-25","captured_at":"2026-10-25T11:30:00.000Z","created_at":"2026-10-25T11:31:00.000Z","updated_at":"2026-10-25T11:31:00.000Z",
         "first_response_at":null,"resolved_at":null,"closed_at":null,"cancelled_at":null,"resolution_code":null,
         "resolution_label_en":null,"resolution_notes":null,"cancel_reason":null,"version":1,"attachment_count":0}
    """.trimIndent()

    private fun client(): AronApiClient {
        val base = OkHttpClient.Builder().addInterceptor { ch ->
            val r = ch.request()
            if (transportDown) throw java.io.IOException("offline")
            seen += Seen(r.method, r.url.encodedPath, r.url.query)
            val key = r.url.encodedPath + (r.url.queryParameter("cursor")?.let { "#$it" } ?: "")
            val code = status[r.url.encodedPath] ?: 200
            val body = if (code == 200) answers[key] ?: "{}" else """{"type":"about:blank","title":"x","status":$code,"code":"ERR_NOT_FOUND"}"""
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x").header("X-Aron-Api", "1").body(body.toResponseBody()).build()
        }.build()
        return AronApiClient(ApiOrigin.parse("https://api.example/"), base, ClientIdentity("1.0+1") { "dev-1" }, object : AccessTokenSource {
            override fun currentAccessToken(grant: Grant) = "t"
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
        })
    }

    private val cacheDir by lazy { tmp.newFolder("tso_api_7", "tickets") }
    private fun source() = HostTicketSource(TicketApi(client(), cacheDir), device(), { nowMs })

    @Test fun aListReadDropsTheMirrorOfEveryTicketTheServerHasKeepsTheCopyAndSendsNoScope() = runBlocking {
        val other = "4d6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f63"
        assertTrue(saveTicket(device()))
        assertTrue(saveTicket(device(), uuid = other))
        assertEquals(2, device().outbox().tickets.size)

        // page 1 holds the first ticket, page 2 is the end; the second ticket is not on the server yet
        answers["/v1/support/tickets"] = """{"items":[${ticketJson(ticketUuid, "INC100001")}],"next_cursor":"c2"}"""
        answers["/v1/support/tickets#c2"] = """{"items":[${ticketJson("5e6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f64", "INC100000", "resolved", "Old one")}],"next_cursor":null}"""
        answers["/v1/support/tickets/summary"] = """{"as_of":"2026-10-25T11:34:00Z","total":2,"new":1,"in_progress":0,"resolved":1,"closed":0,"cancelled":0,"open":1}"""
        val snap = source().list()!!
        assertTrue(snap.complete)
        assertEquals(listOf("INC100001", "INC100000"), snap.tickets.map { it.number })
        assertEquals(1_792_928_040_000L, snap.asOfMs) // the server's own stamp
        assertEquals("the phone's read time, for the fresh-copy rule (D-A2-30)", nowMs, snap.readAtMs)
        assertEquals(2L, snap.summary!!.total)

        // the requests: three GETs, the page size and the cursor only (never a user, zone or scope id)
        assertEquals(listOf("GET"), seen.map { it.method }.distinct())
        assertEquals(listOf("/v1/support/tickets", "/v1/support/tickets", "/v1/support/tickets/summary"), seen.map { it.path })
        assertEquals(listOf("limit=200", "limit=200&cursor=c2", null), seen.map { it.query })

        // the read-back ticket's "not sent yet" row is gone; the other one stays
        assertEquals(setOf(TicketDevice.TICKET_MIRROR + other), mirror(TicketDevice.TICKET_MIRROR).keys)
        assertEquals(listOf(other), device().outbox().tickets.map { it.uuid })

        // offline after a relaunch: the copy is there, with the server's stamp; the list read gives null and changes nothing
        relaunch()
        transportDown = true
        assertNull(source().list())
        val copy = source().cached()!!
        assertEquals(snap.tickets, copy.tickets)
        assertEquals(snap.summary, copy.summary)
        assertEquals(snap.asOfMs, copy.asOfMs)
        assertEquals(snap.readAtMs, copy.readAtMs)
        assertTrue(copy.complete)
        // a copy kept before the read time was recorded reads back without one (never fresh: the next open reads)
        assertNull(TicketCopy.decode(TicketCopy.encode(listOf("""{"items":[],"next_cursor":null}"""), null, true, 5L))!!.readAtMs)
        assertEquals(setOf(TicketDevice.TICKET_MIRROR + other), mirror(TicketDevice.TICKET_MIRROR).keys)

        // a page that fails half way keeps the old copy whole
        transportDown = false
        status["/v1/support/tickets/summary"] = 500
        assertNull(source().list())
        assertEquals(snap.tickets, source().cached()!!.tickets)
    }

    @Test fun aListCutAtThePageCapIsMarkedIncomplete() = runBlocking {
        answers["/v1/support/tickets"] = """{"items":[${ticketJson(ticketUuid, "INC100009")}],"next_cursor":"c1"}"""
        for (i in 1..TicketApi.MAX_PAGES) answers["/v1/support/tickets#c$i"] = """{"items":[],"next_cursor":"c${i + 1}"}"""
        answers["/v1/support/tickets/summary"] = """{"as_of":"2026-10-25T11:34:00Z","total":1500,"new":3,"in_progress":0,"resolved":7,"closed":1480,"cancelled":10,"open":3}"""
        val snap = source().list()!!
        assertFalse(snap.complete)
        assertEquals(TicketApi.MAX_PAGES, seen.count { it.path == "/v1/support/tickets" })
        assertEquals(1500L, snap.summary!!.total)
    }

    @Test fun aDetailReadDropsTheEventMirrorsItReturnsAndAnUnsentTicketIsNotFound() = runBlocking {
        val commentUuid = "3c6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f62"
        val pendingUuid = "6f6c1d5e-4b7a-4c2e-9f10-2b1c3d4e5f65"
        assertTrue(saveTicket(device()))
        assertTrue(device().event(commentUuid, TicketRules.commentPayload(ticketUuid, com.aktcl.aron.feature.tickets.CommentDraft(commentUuid, "hello")), emptyList(), LocalEvent(commentUuid, ticketUuid, EventKind.COMMENT, "hello", nowMs)))
        assertTrue(device().event(pendingUuid, TicketRules.commentPayload(ticketUuid, com.aktcl.aron.feature.tickets.CommentDraft(pendingUuid, "second")), emptyList(), LocalEvent(pendingUuid, ticketUuid, EventKind.COMMENT, "second", nowMs)))
        answers["/v1/support/tickets/$ticketUuid"] = """{"ticket":${ticketJson(ticketUuid, "INC100001", "in_progress")},"events":[
            {"event_uuid":"$commentUuid","kind":"comment","body":"hello","actor_is_support":false,"actor_display":"TSO One","from_status":null,"to_status":null,"resolution_code":null,"created_at":"2026-10-25T11:40:00.000Z","attachments":[]}
          ],"attachments":[]}"""
        val d = (source().detail(ticketUuid) as DetailRead.Found).detail
        assertEquals("INC100001", d.ticket.number)
        assertEquals(listOf(commentUuid), d.events.map { it.uuid })
        assertEquals("the read-back comment's row is gone, the unsent one stays", setOf(TicketDevice.EVENT_MIRROR + pendingUuid), mirror(TicketDevice.EVENT_MIRROR).keys)
        assertTrue("the ticket the server returned is no longer unsent", mirror(TicketDevice.TICKET_MIRROR).isEmpty())

        // offline: the kept thread
        transportDown = true
        assertEquals(DetailRead.Unavailable, source().detail(ticketUuid))
        assertEquals(d, source().cachedDetail(ticketUuid))

        // a ticket the server does not hold (unsent, or not the caller's): not found, and no copy is served
        transportDown = false
        status["/v1/support/tickets/$ticketUuid"] = 404
        assertEquals(DetailRead.NotFound, source().detail(ticketUuid))
        assertNull(source().cachedDetail(ticketUuid))
        // a value that is not a UUID never reaches a path
        assertEquals(DetailRead.NotFound, source().detail("../summary"))
        assertTrue(seen.none { it.path.contains("..") })
    }

    // ---------- bundle: issue types and the photo limit ----------
    @Test fun issueTypesAndThePhotoLimitComeFromTheBundle() = runBlocking {
        assertTrue(source().issueTypes().isEmpty())
        assertEquals("registry default", 3, device().maxPhotos(nowMs))
        val dao = databases.of(user).referenceDao()
        dao.insertSections(listOf(BundleSectionEntity("code_lists", """[{"list_key":"ticket_issue_type","items":[
            {"code":"login_otp","label_en":"Login OTP issue","label_bn":null,"sort":15,"attrs":{"priority":"p1","icon":6}},
            {"code":"app_crash_order_submit","label_en":"App crashes on order submit","label_bn":null,"sort":1,"attrs":{"priority":"p1","icon":0}}]}]""")))
        assertEquals(listOf("app_crash_order_submit", "login_otp"), source().issueTypes().map { it.code })

        fun cfg(v: String) = runBlocking {
            sql("DELETE FROM config_value WHERE key = ?", TicketDevice.KEY_MAX_PHOTOS)
            dao.insertConfig(listOf(ConfigValueEntity(key = TicketDevice.KEY_MAX_PHOTOS, valueJson = v, scopeType = "global", scopeId = null, effectiveFrom = null, effectiveTo = null, configVersion = 4, requiresAck = false, scheduled = false)))
        }
        cfg("1"); assertEquals(1, device().maxPhotos(nowMs))
        cfg("0"); assertEquals("0 hides the photo actions", 0, device().maxPhotos(nowMs))
        cfg("9"); assertEquals("never above the contract's 3", 3, device().maxPhotos(nowMs))
        cfg("\"x\""); assertEquals(3, device().maxPhotos(nowMs))
    }

    @Test fun thePhotoPortCapIsTheConfiguredLimitClamped() {
        var cfg = 2
        val photos = MediaTicketPhotos({ null }, { error("no media in this test") }, maxNow = { cfg })
        assertEquals(2, photos.max)
        cfg = 7; assertEquals(3, photos.max)
        cfg = -1; assertEquals(0, photos.max)
        // without the on-screen camera the capture does nothing (it never opens a second camera host)
        assertFalse(runBlocking { photos.capture(photoUuid) })
    }

    // ---------- navigation ----------
    @Test fun aTicketPushOpensTheTicketDashboardAndASaveOpensAllTickets() {
        assertEquals(TsoScreen.TICKET_DASHBOARD, TicketPush.target("ticket_updated"))
        assertEquals(TsoScreen.TICKET_DASHBOARD, TicketPush.target(" ticket_updated "))
        assertNull(TicketPush.target("task_assigned"))
        assertNull(TicketPush.target("notification"))
        assertNull(TicketPush.target(null))

        val calls = mutableListOf<String>()
        val nav = object : TsoNav {
            override fun open(screen: TsoScreen, arg: String?) { calls += "open:$screen" }
            override fun back() { calls += "back" }
            override fun home() { calls += "home" }
        }
        TicketNav.afterSave(nav)
        assertEquals(listOf("back", "open:TICKET_LIST"), calls)

        // A1's tap handler: the Ticket Dashboard opens with the reason, so it reads online even with a fresh copy
        calls.clear()
        val args = mutableListOf<String?>()
        val nav2 = object : TsoNav {
            override fun open(screen: TsoScreen, arg: String?) { calls += "open:$screen"; args += arg }
            override fun back() { calls += "back" }
            override fun home() { calls += "home" }
        }
        assertTrue(TicketPush.open(nav2, "ticket_updated"))
        assertFalse(TicketPush.open(nav2, "task_assigned"))
        assertEquals(listOf("open:TICKET_DASHBOARD"), calls)
        assertEquals(listOf<String?>(TicketPush.REASON), args)
        assertNotNull(TicketDevice.delivery(null))
    }
}

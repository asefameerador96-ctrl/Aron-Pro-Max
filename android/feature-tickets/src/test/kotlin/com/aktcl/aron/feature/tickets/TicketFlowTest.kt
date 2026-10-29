package com.aktcl.aron.feature.tickets

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline-first ticket flow with a fake host outbox and a fake server (s5 A2 tests; constraint 1 and 2): offline
 * quick and manual create, double tap, kill mid-save, offline close and comment, replay after reconnect, refusal shown,
 * and the device count equal to the server count.
 */
class TicketFlowTest {
    private val server = FakeServer()
    private val device = FakeDevice(server)
    private val uuids = Uuids()
    private val photos = FakePhotos()
    private val d = deps(device, photos, uuids)

    /** A form whose draft lives in a state holder, as rememberSaveable keeps it. */
    private fun form(origin: TicketOrigin, issue: String? = null) = mutableStateOf(TicketRules.newDraft(uuids.next(), origin, issue))

    /** The draft as the saved-instance Bundle carries it across process death. */
    private fun throughBundle(draft: TicketDraft): TicketDraft {
        val saved = with(TicketSavers.Draft) { SaverScope { true }.save(draft) }!!
        return TicketSavers.Draft.restore(saved)!!
    }

    @Test fun offlineQuickCreateIsSavedAtOnceAndShownNotSentYet() = runTest {
        val draft = form(TicketOrigin.QUICK, "outlet_related")
        val f = TicketFormState(d, TYPES, draft)
        f.setDescription("DHK-344-011 shows no sales for 25 Oct")
        assertTrue(f.submit())
        assertEquals(FormPhase.Saved, f.phase)
        assertTrue(draft.value.queued)
        assertEquals(1, device.outboxRows.size)
        val rec = device.outboxRows.values.single()
        assertEquals(draft.value.uuid, rec.uuid)
        assertEquals("Outlet Related Issue", rec.ticket!!.title)
        assertEquals("quick", rec.ticket.source)
        assertEquals(0, server.rows.size) // nothing left the phone

        val dash = TicketDashboardState(d)
        dash.load()
        assertTrue(dash.ui.offline)
        assertEquals(1L, dash.ui.counts.total)
        assertEquals(1L, dash.ui.counts.new)
        assertEquals("100%", dash.ui.counts.openPctText(com.aktcl.aron.rules.UiLocale.EN))
        val row = dash.ui.recent.single()
        assertNull(row.number)
        assertEquals(Delivery.Pending, row.delivery)
        assertTrue(f.consumeSavedToast())
        assertFalse(f.consumeSavedToast()) // one toast, not again after a recreation
    }

    @Test fun offlineManualCreateValidatesFirstThenSaves() = runTest {
        val draft = form(TicketOrigin.MANUAL)
        val f = TicketFormState(d, TYPES, draft)
        assertFalse(f.submit())
        assertEquals(FormPhase.Invalid(listOf(TicketFormError.ISSUE_TYPE, TicketFormError.TITLE_EMPTY, TicketFormError.DESCRIPTION_EMPTY)), f.phase)
        assertEquals(0, device.queueCalls)
        f.setIssueType("login_otp")
        f.setTitle("OTP does not come")
        assertEquals(FormPhase.Editing, f.phase) // an edit clears the errors
        assertFalse(f.submit())
        assertEquals(FormPhase.Invalid(listOf(TicketFormError.DESCRIPTION_EMPTY)), f.phase)
        f.setDescription("Since 9 am on SR phone 2")
        f.setAffected("sr1001")
        f.capture()
        assertTrue(f.submit())
        val rec = device.outboxRows.values.single()
        assertEquals("OTP does not come", rec.ticket!!.title)
        assertEquals("manual", rec.ticket.source)
        assertEquals("sr1001", rec.ticket.affectedUsername)
        assertEquals(draft.value.photoUuids, rec.photos) // the photo is claimed with the record
        assertEquals(1, rec.photos.size)
        // the form is done: edits are ignored
        f.setTitle("changed")
        assertEquals("OTP does not come", draft.value.title)
    }

    @Test fun aDoubleTapQueuesOneTicket() = runTest {
        val draft = form(TicketOrigin.QUICK, "login_otp")
        val f = TicketFormState(d, TYPES, draft)
        device.gate = CompletableDeferred()
        val first = launch { f.submit() }
        val second = launch { f.submit() }
        runCurrent()
        assertEquals(FormPhase.Saving, f.phase)
        device.gate!!.complete(Unit)
        first.join(); second.join()
        assertEquals(1, device.queueCalls) // the second tap never reached the queue
        assertEquals(1, device.outboxRows.size)
        assertFalse(f.submit()) // and a tap after the save does nothing
        assertEquals(1, device.queueCalls)
    }

    @Test fun aKillMidSaveBeforeTheCommitLeavesNothingAndTheRetryIsOneTicket() = runTest {
        val draft = form(TicketOrigin.QUICK, "login_otp")
        val f = TicketFormState(d, TYPES, draft)
        device.gate = CompletableDeferred()
        val job = launch { f.submit() }
        runCurrent()
        job.cancel() // the process dies while the outbox write waits
        job.join()
        assertEquals(0, device.outboxRows.size)
        assertFalse(draft.value.queued)
        device.gate = null

        // the relaunch restores the same draft from the saved state: same uuid
        val restored = mutableStateOf(throughBundle(draft.value))
        val again = TicketFormState(d, TYPES, restored)
        assertTrue(again.submit())
        assertEquals(listOf(draft.value.uuid), device.outboxRows.keys.toList())
    }

    @Test fun aKillRightAfterTheCommitStillGivesOneTicketOnRetry() = runTest {
        val draft = form(TicketOrigin.MANUAL, "login_otp")
        draft.value = draft.value.copy(title = "OTP", description = "No OTP")
        val f = TicketFormState(d, TYPES, draft)
        device.afterCommit = CancellationException("killed")
        val job = launch { f.submit() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, device.outboxRows.size) // the row is in, the screen never heard
        assertFalse(draft.value.queued)

        val again = TicketFormState(d, TYPES, mutableStateOf(throughBundle(draft.value)))
        assertTrue(again.submit()) // the queue says "already there": saved, one row
        assertEquals(FormPhase.Saved, again.phase)
        assertEquals(1, device.outboxRows.size)
        device.online = true
        device.sync()
        assertEquals(1, server.rows.size)
    }

    @Test fun aFullDiskShowsTheFailureAndTheRetryKeepsTheUuid() = runTest {
        val draft = form(TicketOrigin.QUICK, "login_otp")
        val f = TicketFormState(d, TYPES, draft)
        device.failure = IllegalStateException("disk full")
        assertFalse(f.submit())
        assertEquals(FormPhase.Failed, f.phase)
        device.failure = null
        assertTrue(f.submit())
        assertEquals(listOf(draft.value.uuid), device.outboxRows.keys.toList())
    }

    @Test fun photosAreStagedUpToTheLimitAndDroppedWhenRemovedOrAbandoned() = runTest {
        val small = deps(device, FakePhotos(max = 2), uuids)
        val draft = form(TicketOrigin.QUICK, "outlet_related")
        val f = TicketFormState(small, TYPES, draft)
        f.addPicked(listOf("content://1", "content://2", "content://3"))
        assertEquals(2, draft.value.photoUuids.size) // the limit, not the pick count
        assertFalse(f.canAddPhoto)
        f.capture()
        assertEquals(2, draft.value.photoUuids.size)
        val gone = draft.value.photoUuids.first()
        f.removePhoto(gone)
        assertEquals(1, draft.value.photoUuids.size)
        assertTrue(gone in (small.photos as FakePhotos).discarded)
        f.abandon()
        assertEquals(0, draft.value.photoUuids.size)
        assertEquals(2, (small.photos as FakePhotos).discarded.size)
    }

    // ---- the server side of the story

    /** One ticket raised on the phone, synced, then resolved by support; the copy and the detail read once. */
    private suspend fun resolvedTicket(): String {
        val draft = form(TicketOrigin.QUICK, "login_otp")
        TicketFormState(d, TYPES, draft).submit()
        device.online = true
        device.sync()
        server.resolve(draft.value.uuid, "Checked.", T0)
        TicketDetailState(d, draft.value.uuid, mutableStateOf(CommentDraft(uuids.next()))).load() // stores the detail
        TicketDashboardState(d).load() // stores the list
        return draft.value.uuid
    }

    @Test fun offlineCloseIsOneTapOneEventAndShowsClosed() = runTest {
        val id = resolvedTicket()
        device.online = false
        val st = TicketDetailState(d, id, mutableStateOf(CommentDraft(uuids.next())))
        st.load()
        assertTrue(st.ui.offline)
        assertEquals("INC100001", st.ui.ticket!!.number)
        assertTrue(st.ui.canClose)
        assertFalse(st.ui.canComment) // resolved: no composer (D-TSO-MAN-21)
        assertTrue(st.ui.events.any { it.actorIsSupport && it.body!!.startsWith("[TicketID: INC100001] - Resolved:") })

        val first = launch { st.close() }
        val second = launch { st.close() }
        first.join(); second.join()
        assertEquals(DetailPhase.ClosedSaved, st.phase)
        assertEquals(TicketStatus.CLOSED, st.ui.status)
        assertFalse(st.ui.canClose)
        assertEquals(Delivery.Pending, st.ui.close!!.delivery)
        val closes = device.outboxRows.values.filter { it.event?.kind == EventKind.CLOSE_REVIEW }
        assertEquals(1, closes.size)
        assertEquals(TicketRules.closeUuid(id), closes.single().uuid)

        // a second detail screen (or a relaunch) sees the pending close and offers no button
        val other = TicketDetailState(d, id, mutableStateOf(CommentDraft(uuids.next())))
        other.load()
        assertFalse(other.ui.canClose)
        assertFalse(other.close())
        assertEquals(1, device.outboxRows.values.count { it.event?.kind == EventKind.CLOSE_REVIEW })

        device.online = true
        device.sync()
        assertEquals("closed", server.rows.getValue(id).ticket.status)
    }

    @Test fun offlineCommentOnAnOpenTicketIsQueuedOnceWithANewUuidForTheNext() = runTest {
        val draft = form(TicketOrigin.QUICK, "login_otp")
        TicketFormState(d, TYPES, draft).submit()
        val id = draft.value.uuid
        device.online = true
        device.sync()
        server.startWork(id)
        val comment = mutableStateOf(CommentDraft(uuids.next()))
        TicketDetailState(d, id, comment).load()
        device.online = false

        val st = TicketDetailState(d, id, comment)
        st.load()
        assertTrue(st.ui.canComment)
        assertFalse(st.ui.canClose)
        assertFalse(st.sendComment())
        assertEquals(DetailPhase.CommentInvalid(CommentError.EMPTY), st.phase)
        st.editComment("Still no OTP on SR phone 2")
        val firstUuid = comment.value.uuid
        val a = launch { st.sendComment() }
        val b = launch { st.sendComment() }
        a.join(); b.join()
        assertEquals(DetailPhase.CommentSaved, st.phase) // the second tap saw the fresh empty comment: no error
        assertEquals(1, device.outboxRows.values.count { it.event?.kind == EventKind.COMMENT })
        assertEquals(firstUuid, device.outboxRows.values.single { it.event?.kind == EventKind.COMMENT }.uuid)
        assertTrue(comment.value.uuid != firstUuid && comment.value.body.isEmpty())
        val mine = st.ui.events.single { it.uuid == firstUuid }
        assertTrue(mine.mine)
        assertEquals(Delivery.Pending, mine.delivery)

        device.online = true
        device.sync()
        st.load()
        assertEquals(Delivery.Sent, st.ui.events.single { it.uuid == firstUuid }.delivery)
        assertEquals(1, server.rows.getValue(id).events.count { it.uuid == firstUuid })
    }

    @Test fun aCommentTheServerRefusesShowsItsReason() = runTest {
        val draft = form(TicketOrigin.QUICK, "login_otp")
        TicketFormState(d, TYPES, draft).submit()
        val id = draft.value.uuid
        device.online = true
        device.sync()
        val comment = mutableStateOf(CommentDraft(uuids.next()))
        val st = TicketDetailState(d, id, comment)
        st.load()
        device.online = false
        st.editComment("one more detail")
        assertTrue(st.sendComment())
        server.resolve(id, "Checked.", T0) // support resolved it while the phone was offline
        device.online = true
        device.sync()
        val refusedUuid = device.outboxRows.values.single { it.event?.kind == EventKind.COMMENT }.uuid
        st.load()
        assertEquals(Delivery.Refused("ticket_state_invalid"), st.ui.events.single { it.uuid == refusedUuid }.delivery)
        assertEquals(TicketStatus.RESOLVED, st.ui.status)
        assertFalse(st.ui.canComment)
        assertTrue(st.ui.canClose)
    }

    @Test fun aReplayAfterReconnectNeverDoublesAndTheDeviceCountEqualsTheServerCount() = runTest {
        // offline: two quick tickets, one manual, then a comment on the first (still unsent)
        val q1 = form(TicketOrigin.QUICK, "login_otp")
        val q2 = form(TicketOrigin.QUICK, "outlet_related")
        val m = form(TicketOrigin.MANUAL, "final_submission")
        TicketFormState(d, TYPES, q1).submit()
        TicketFormState(d, TYPES, q2).submit()
        TicketFormState(d, TYPES, m).apply { setTitle("Zone 7 not submitted"); setDescription("Get Sales Data spins") }.submit()
        val c = mutableStateOf(CommentDraft(uuids.next()))
        val det = TicketDetailState(d, q1.value.uuid, c)
        det.load()
        assertNotNull(det.ui.ticket) // the unsent ticket itself
        assertTrue(det.ui.canComment)
        det.editComment("Also on the AMO phone")
        assertTrue(det.sendComment())
        assertEquals(4, device.pending())

        // reconnect: the server takes everything, but the answers are lost
        device.online = true
        device.sync(ackLost = true)
        assertEquals(3, server.rows.size)
        assertEquals(4, device.pending())
        // the replay sends all four again: nothing doubles
        device.sync()
        assertEquals(3, server.rows.size)
        assertEquals(1, server.rows.getValue(q1.value.uuid).events.size)
        assertEquals(0, device.pending())
        assertEquals(listOf("INC100001", "INC100002", "INC100003"), server.list().map { it.number })

        // and a third time for good measure
        device.outboxRows.values.forEach { it.state = FakeDevice.State.Pending }
        device.sync()
        assertEquals(3, server.rows.size)
        assertEquals(1, server.rows.getValue(q1.value.uuid).events.size)

        // device count == server count
        val dash = TicketDashboardState(d)
        dash.load()
        assertFalse(dash.ui.offline)
        assertEquals(server.summary(), dash.ui.counts)
        assertEquals(3L, dash.ui.counts.total)
        assertTrue(dash.ui.recent.all { it.number != null && it.delivery == Delivery.Sent })
        val list = TicketListState(d)
        list.load()
        assertEquals(server.rows.keys, list.ui.all.map { it.uuid }.toSet())
        assertEquals(emptyList<Ticket>(), device.outbox().tickets) // every ticket mirror row dropped once listed
        TicketDetailState(d, q1.value.uuid, mutableStateOf(CommentDraft(uuids.next()))).load()
        assertEquals(TicketOutbox(), device.outbox()) // and the comment's once its thread was read back
    }

    @Test fun anUnsentTicketOpensFromThePhoneAndAGoneOneSaysNotFound() = runTest {
        val q = form(TicketOrigin.QUICK, "login_otp")
        TicketFormState(d, TYPES, q).submit()
        device.online = true // online, but the ticket has not synced: the server answers 404
        val st = TicketDetailState(d, q.value.uuid, mutableStateOf(CommentDraft(uuids.next())))
        st.load()
        assertEquals(Delivery.Pending, st.ui.ticket!!.delivery)
        assertFalse(st.ui.notFound)
        val gone = TicketDetailState(d, "99999999-9999-4999-8999-999999999999", mutableStateOf(CommentDraft(uuids.next())))
        gone.load()
        assertTrue(gone.ui.notFound)
        assertNull(gone.ui.ticket)
    }
}

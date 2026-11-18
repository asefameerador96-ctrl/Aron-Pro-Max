package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.programmes.Basket
import com.aktcl.aron.feature.programmes.LocalRedeemed
import com.aktcl.aron.feature.programmes.LoyaltyGift
import com.aktcl.aron.feature.programmes.SaveOutcome
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lane android-sr-programmes: a confirmed redemption is saved once, a replay writes nothing, and the next balance reads it back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProgrammeStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-11-18T05:00:00.000Z" }
    private var syncs = 0
    private val meta = CaptureMetaProvider { rid ->
        CaptureMeta(businessDate = "2026-11-18", capturedAt = "2026-11-18T05:00:00.000Z", capturedElapsedMs = 1_000, bootCount = 1, clockOffsetMs = 0, capturedOffline = true,
            routeId = rid, bundleVersion = "2026-11-18:1", configVersion = 1)
    }
    private val store = ProgrammeStore(db, repo, meta) { syncs++ }
    private val chair = LoyaltyGift(1, 3, "Chair", null, 200, emptyList(), null, true)

    @After fun tearDown() = db.close()

    private fun plan(uuid: String, qty: Int) = Basket(10, 3, 500).let { b -> (1..qty).fold(b) { acc, _ -> acc.plus(chair) } }.plan(listOf(chair), uuid, ClientIds::newUuid, "2026-11-18T05:00:00.000Z")

    @Test fun savedOnceAReplayWritesNothingAndTheBalanceReadsItBack() = runTest {
        val uuid = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), plan(uuid, 2)))
        assertEquals(SaveOutcome.ALREADY_SAVED, store.saveProgramme(meta.meta(7), plan(uuid, 2))) // a double tap or a retry after a kill and relaunch: the same redemption, not a second one
        val r = store.redemptions()
        assertEquals(1, r.size); assertEquals(400L, r[0].points); assertEquals("pending", r[0].state)
        assertEquals(2, db.outboxDao().countInState("pending")) // the redemption and its one line
        // the bundle was built before the redemption: the phone holds it back from the balance (500 - 400)
        assertEquals(400L, LocalRedeemed.points(r, 10, 3, emptySet()))
        assertTrue(syncs >= 1)
    }

    @Test fun aSaveTheGuardRefusesWritesNothingAndSaysSo() = runTest {
        val uuid = ClientIds.newUuid()
        val bad = plan(uuid, 1).let { it.dropLast(1) } // the redemption without its line: line_count says 1
        assertEquals(SaveOutcome.REFUSED, store.saveProgramme(meta.meta(7), bad))
        assertEquals(0, store.redemptions().size)
        assertEquals(0, db.outboxDao().countInState("pending"))
    }

    @Test fun aGiftChoiceIsSavedOnceAndReadBackWithItsState() = runTest {
        val uuid = ClientIds.newUuid()
        val rec = com.aktcl.aron.feature.programmes.GiftChoiceModel.plan(10, 4, "2026-Q4", uuid)
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), listOf(rec)))
        assertEquals(SaveOutcome.ALREADY_SAVED, store.saveProgramme(meta.meta(7), listOf(rec))) // a double tap: the same record
        val c = store.giftChoices()
        assertEquals(1, c.size); assertEquals(4L, c[0].giftId); assertEquals("2026-Q4", c[0].quarter); assertEquals("pending", c[0].state)
        assertEquals(1, db.outboxDao().countInState("pending"))
    }

    @Test fun aDifferentBasketUnderTheSameUuidIsAConflictNotSaved() = runTest {
        val uuid = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), plan(uuid, 1)))
        assertEquals(SaveOutcome.CONFLICT, store.saveProgramme(meta.meta(7), plan(uuid, 2))) // two chairs under the uuid that held one
        val r = store.redemptions()
        assertEquals(1, r.size); assertEquals(200L, r[0].points) // the first basket stands, nothing was changed
        assertEquals(2, db.outboxDao().countInState("pending"))
    }

    @Test fun theSameBasketAfterAKillAndReturnWithAnotherShownBalanceIsAlreadySavedNotAConflict() = runTest {
        val uuid = ClientIds.newUuid()
        fun planWith(balance: Long) = Basket(10, 3, balance).plus(chair).plan(listOf(chair), uuid, ClientIds::newUuid, "2026-11-18T05:00:00.000Z")
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), planWith(500)))
        // after the kill and return the reloaded balance already has the first redemption subtracted (500 - 200 = 300): same uuid, same basket
        assertEquals(SaveOutcome.ALREADY_SAVED, store.saveProgramme(meta.meta(7), planWith(300)))
        assertEquals(1, store.redemptions().size)
        assertEquals(2, db.outboxDao().countInState("pending")) // one redemption and its line, never a second redemption
    }

    // ---- gift photo: gates before the claim, the claim before the commit, a gone photo saves nothing ----
    private val claims = ArrayList<Pair<String, Long>>() // photo uuid to the number of records the phone held for the record uuid WHEN claimed
    private var claimFails = false
    private val releases = ArrayList<Pair<String, String>>() // photo uuid to the record uuid it was given back from
    private var afterClaim: suspend () -> Unit = {}
    private fun photoStore(r: CaptureRepository = repo) = ProgrammeStore(db, r, meta, claimPhoto = { photo, rec, _ ->
        if (claimFails) throw IllegalStateException("gone")
        claims += photo to db.captureDao().amoRecordsOfFamily(rec).size.toLong()
        afterClaim()
    }, releasePhoto = { photo, rec -> releases += photo to rec; true }) { syncs++ }
    private val fixEntity = com.aktcl.aron.feature.outlet.FixReading("ok", 23.79, 90.40, 10.0, false).toEntity(ClientIds.newUuid(), "x", "gift_photo", 0)
    private fun photoPlan(uuid: String, photo: String, assignment: Long = 71) =
        com.aktcl.aron.feature.programmes.GiftPhotoModel.planAstha(10, 4, assignment, photo, kotlinx.serialization.json.buildJsonObject { put("lat", kotlinx.serialization.json.JsonPrimitive(23.79)) }, uuid)

    @Test fun theGiftPhotoIsClaimedBeforeTheRecordCommitsAndAReplayDoesNotClaimAgain() = runTest {
        val s = photoStore(); val uuid = ClientIds.newUuid(); val photo = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo), photo, fixEntity))
        assertEquals(listOf(photo to 0L), claims) // claimed while the phone held no record yet: before the commit
        assertEquals(SaveOutcome.ALREADY_SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo), photo, fixEntity))
        assertEquals(1, claims.size); assertEquals(1, db.outboxDao().countInState("pending"))
    }

    @Test fun aPhotoThatIsGoneSavesNothingAndAnotherPhotoUnderTheSameUuidIsAlreadySavedWithoutTouchingThePhoto() = runTest {
        val s = photoStore(); val uuid = ClientIds.newUuid(); val photo = ClientIds.newUuid()
        claimFails = true
        assertEquals(SaveOutcome.REFUSED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo), photo, fixEntity))
        assertEquals(0, db.outboxDao().countInState("pending")) // never a record without its photo
        claimFails = false
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo), photo, fixEntity))
        val before = claims.size
        assertEquals(SaveOutcome.ALREADY_SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, ClientIds.newUuid()), photo, fixEntity)) // the first photo of the subject wins
        assertEquals(before, claims.size) // and the later one is not claimed
    }

    @Test fun withoutACheckInNothingIsClaimedAndNothingIsSaved() = runTest {
        val gated = CaptureRepository(db, requireCheckIn = true) { "2026-11-18T05:00:00.000Z" }
        val s = photoStore(gated); val uuid = ClientIds.newUuid(); val photo = ClientIds.newUuid()
        assertEquals(SaveOutcome.CHECK_IN_FIRST, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo), photo, fixEntity))
        assertTrue(claims.isEmpty()); assertEquals(0, db.outboxDao().countInState("pending"))
    }

    // ---- the lost-answer rule's read side ----
    @Test fun rowsSentButUnansweredReadAsUncertainAndARestoredPendingRowKeepsItsFirstAck() {
        val rs = ProgrammeStore.Companion
        assertEquals("uncertain", rs.readState(true, "in_flight", null, true)); assertEquals("uncertain", rs.readState(true, "pending", null, true))
        assertEquals("rejected", rs.readState(true, "rejected", null, true)) // the server's refusal is an answer
        assertEquals("acked", rs.readState(false, "pending", "2026-11-17T09:00:00Z", true)) // a restore put it back to pending: the server held it once
        assertEquals("pending", rs.readState(false, "pending", "2026-11-17T09:00:00Z", false)) // not for redemptions
        assertEquals("pending", rs.readState(false, null, null, true))
    }

    @Test fun aSavedRedemptionInTheUncertainListReadsAsUncertain() = runTest {
        val uuid = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), plan(uuid, 1)))
        assertEquals("pending", store.redemptions().single().state)
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(com.aktcl.aron.core.database.repo.ReferenceRepository.KEY_BUNDLE_UNCERTAIN, uuid))
        assertEquals("uncertain", store.redemptions().single().state)
    }

    @Test fun aRetryAfterAKillMakesOneRecordBecauseTheRecordUuidIsFixedBySubject() = runTest {
        val s = photoStore()
        val assignment = 71L; val quarter = "2026-Q4"
        val uuid = com.aktcl.aron.feature.programmes.GiftPhotoModel.asthaUuid(assignment, quarter)
        assertEquals(uuid, com.aktcl.aron.feature.programmes.GiftPhotoModel.asthaUuid(assignment, quarter)) // the same on every attempt, also after a relaunch
        // killed after the capture and the claim, before the commit: nothing is held
        claimFails = true
        assertEquals(SaveOutcome.REFUSED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        assertEquals(0, db.outboxDao().countInState("pending"))
        // the relaunch retries with a NEW capture: one record
        claimFails = false
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        // killed after the commit, retried (another shutter, a second tap): still one record
        assertEquals(SaveOutcome.ALREADY_SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        assertEquals(1, db.outboxDao().countInState("pending")); assertEquals(1, claims.size)
    }

    @Test fun theCampaignUnitUuidIsFixedBySubjectAndUnitNumbersOfDifferentGiftLinesDoNotCollide() {
        val a = com.aktcl.aron.feature.programmes.CampaignGroup("r1", 10, 4, 3, emptySet()); val b = a.copy(giftId = 5)
        val m = com.aktcl.aron.feature.programmes.CampaignPhotoModel
        assertEquals(m.unitUuid(a, 1), m.unitUuid(a, 1))
        assertTrue(m.unitUuid(a, 1) != m.unitUuid(a, 2)); assertTrue(m.unitUuid(a, 1) != m.unitUuid(b, 1))
    }

    // ---- the Developer options gate (D-OWNER-MDM-02): first in every programme writer ----
    private fun <T> withGateClosed(block: () -> T): T {
        val before = com.aktcl.aron.core.common.ActionGate.check
        com.aktcl.aron.core.common.ActionGate.check = { false }
        try { return block() } finally { com.aktcl.aron.core.common.ActionGate.check = before }
    }

    @Test fun withTheGateClosedNoProgrammeRecordIsWrittenAndNoPhotoIsClaimed() = runTest {
        val s = photoStore()
        withGateClosed {
            kotlinx.coroutines.runBlocking {
                assertThrows(com.aktcl.aron.core.common.GateClosedException::class.java) { kotlinx.coroutines.runBlocking { s.saveProgramme(meta.meta(7), plan(ClientIds.newUuid(), 1)) } }
                assertThrows(com.aktcl.aron.core.common.GateClosedException::class.java) { kotlinx.coroutines.runBlocking { s.saveGiftPhoto(meta.meta(7), photoPlan(ClientIds.newUuid(), ClientIds.newUuid()), ClientIds.newUuid(), fixEntity) } }
                assertThrows(com.aktcl.aron.core.common.GateClosedException::class.java) { kotlinx.coroutines.runBlocking { repo.recordProgramme(plan(ClientIds.newUuid(), 1).map { com.aktcl.aron.core.database.entity.AmoRecordEntity(it.clientUuid, meta.meta(7), it.type, it.familyUuid, it.rank, it.payload.toString()) }) } }
            }
        }
        assertEquals(0, db.outboxDao().countInState("pending")); assertTrue(claims.isEmpty())
    }

    /** Source scan in the style of EveryWriterIsGatedTest: the first statement of every `save*` function of the store is the gate, so a writer added later cannot forget it. */
    @Test fun everySaveFunctionOfTheStoreCallsTheGateFirst() {
        val rel = "src/main/kotlin/com/aktcl/aron/sr/ProgrammeStore.kt"
        val src = listOf(java.io.File(rel), java.io.File("android/app-sr/$rel")).first { it.isFile }.readText()
        val heads = Regex("""\n    suspend fun (save\w+)\(""").findAll(src).toList()
        assertTrue("the scan found ${heads.size} save functions: it is broken", heads.size >= 2)
        val bad = heads.filter { m ->
            val body = src.substring(m.range.first, src.indexOf("\n    }\n", m.range.first).let { if (it < 0) src.length else it })
            val lines = body.lines().dropWhile { !it.contains("suspend fun") }.drop(1).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
            lines.firstOrNull()?.startsWith("com.aktcl.aron.core.common.ActionGate.requireOpen()") != true
        }.map { it.groupValues[1] }
        assertEquals("these writers do not call ActionGate.requireOpen() first: $bad", emptyList<String>(), bad)
    }

    // ---- a refused photo can be retaken (final pass, blocking 4) and a cancelled save gives its claim back (blocking 2) ----
    private val gp = com.aktcl.aron.feature.programmes.GiftPhotoModel
    private suspend fun refuse(uuid: String) { db.outboxDao().applyAck(uuid, "rejected", "schema_invalid", null, "2026-11-18T06:00:00.000Z") }
    private suspend fun accept(uuid: String) { db.outboxDao().applyAck(uuid, "acked", null, 9L, "2026-11-18T06:00:00.000Z") }

    @Test fun refusedThenRetakeThenAcceptedIsOneAcceptedPhotoUnderANewAttemptKey() = runTest {
        val s = photoStore()
        val a0 = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        assertEquals(gp.asthaUuid(71, "2026-Q4"), a0.uuid); assertEquals(0, a0.attempt); assertTrue(!a0.alreadySaved) // attempt 0 keeps the key shape already held
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(a0.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        assertTrue(s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }.alreadySaved) // waiting: THE photo of the subject
        refuse(a0.uuid)
        val a1 = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        assertEquals(1, a1.attempt); assertTrue(a1.uuid != a0.uuid); assertTrue(!a1.alreadySaved) // the retake is a NEW record the server can accept
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(a1.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        accept(a1.uuid)
        val again = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        assertEquals(a1.uuid, again.uuid); assertTrue(again.alreadySaved) // never a third photo
        assertEquals(1, s.giftPhotos().count { it.assignmentId == 71L && it.state != "rejected" }) // exactly one that is not refused
    }

    @Test fun refusedThenRetakeThenKillThenRetakeMakesOneRecord() = runTest {
        val s = photoStore()
        val a0 = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        s.saveGiftPhoto(meta.meta(7), photoPlan(a0.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity); refuse(a0.uuid)
        val a1 = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        claimFails = true // killed after the retake's capture and before its commit: nothing is held under attempt 1
        assertEquals(SaveOutcome.REFUSED, s.saveGiftPhoto(meta.meta(7), photoPlan(a1.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        claimFails = false
        val relaunch = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        assertEquals(a1.uuid, relaunch.uuid) // the same attempt key after the relaunch
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(relaunch.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        assertEquals(SaveOutcome.ALREADY_SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(relaunch.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        assertEquals(1, s.giftPhotos().count { it.state != "rejected" })
    }

    @Test fun aCampaignUnitHasItsOwnAttemptKeysAndAnAttemptZeroKeyShapeIsUnchanged() {
        val g = com.aktcl.aron.feature.programmes.CampaignGroup("r1", 10, 4, 3, emptySet()); val m = com.aktcl.aron.feature.programmes.CampaignPhotoModel
        assertEquals(m.unitUuid(g, 1), m.unitUuid(g, 1, 0)); assertTrue(m.unitUuid(g, 1) != m.unitUuid(g, 1, 1)); assertTrue(m.unitUuid(g, 1, 1) != m.unitUuid(g, 2, 1))
    }

    @Test fun aSubjectRefusedManyTimesStopsAtTheBoundAndReadsAsAlreadySaved() = runTest {
        val s = photoStore()
        repeat(ProgrammeStore.MAX_PHOTO_ATTEMPTS) {
            val a = s.giftPhotoAttempt { gp.asthaUuid(72, "2026-Q4", it) }
            assertEquals(it, a.attempt)
            s.saveGiftPhoto(meta.meta(7), photoPlan(a.uuid, ClientIds.newUuid(), 72), ClientIds.newUuid(), fixEntity); refuse(a.uuid)
        }
        assertTrue(s.giftPhotoAttempt { gp.asthaUuid(72, "2026-Q4", it) }.alreadySaved)
    }

    @Test fun aSaveCancelledBetweenTheClaimAndTheCommitGivesTheClaimBack() = runTest {
        val s = photoStore(); val uuid = ClientIds.newUuid(); val photo = ClientIds.newUuid()
        afterClaim = { kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel() } // Back pressed right after the claim
        val job = launch { s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo), photo, fixEntity) }
        job.join()
        afterClaim = {}
        assertEquals(0, db.outboxDao().countInState("pending")) // no record
        assertEquals(listOf(photo to uuid), releases) // and the claim was handed back, not left attached to the fixed uuid
        // the retake commits under the same uuid
        val photo2 = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo2), photo2, fixEntity))
    }

    @Test fun aCancelAfterTheCommitDoesNotReleaseTheClaimOfACommittedRecord() = runTest {
        val s = photoStore(); val uuid = ClientIds.newUuid(); val photo = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(uuid, photo), photo, fixEntity))
        assertEquals(emptyList<Pair<String, String>>(), releases)
    }

    @Test fun aRecordThePhoneGaveUpOnIsStillTheSubjectsPhotoAndARetakeReplaysTheSameKey() = runTest {
        val s = photoStore()
        val a0 = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        assertEquals(SaveOutcome.SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(a0.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        // the server committed X0 but its answers were lost; after the retry limit the phone marks it rejected(retry_exhausted)
        db.outboxDao().applyAck(a0.uuid, "rejected", "retry_exhausted", null, "2026-11-18T06:00:00.000Z")
        val again = s.giftPhotoAttempt { gp.asthaUuid(71, "2026-Q4", it) }
        assertEquals(a0.uuid, again.uuid); assertTrue(again.alreadySaved) // the SAME key: no second server record can arise
        assertEquals(SaveOutcome.ALREADY_SAVED, s.saveGiftPhoto(meta.meta(7), photoPlan(again.uuid, ClientIds.newUuid()), ClientIds.newUuid(), fixEntity))
        assertEquals("uncertain", s.giftPhotos().single().state) // and the screen does not show the subject as open
        for (code in listOf("transport_timeout", "transport_malformed", "ack_mismatch")) {
            val x = ClientIds.newUuid(); db.outboxDao().insert(listOf(com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = x, recordType = "amo_record", familyUuid = x, rank = 0, businessDate = "2026-11-18", payloadJson = "{}", payloadSha256 = "x", state = "rejected", createdAt = "t", lastCode = code)))
            assertTrue(code, !ProgrammeStore.isDefiniteRefusal(db.outboxDao().byClientUuid(x)))
        }
    }

    @Test fun aDefiniteServerRefusalIsTheOnlyThingThatOpensANewAttempt() = runTest {
        for (code in listOf("schema_invalid", "scope_out_of_reach", "gift_locked", "gift_not_for_tier")) {
            val x = ClientIds.newUuid(); db.outboxDao().insert(listOf(com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = x, recordType = "amo_record", familyUuid = x, rank = 0, businessDate = "2026-11-18", payloadJson = "{}", payloadSha256 = "x", state = "rejected", createdAt = "t", lastCode = code)))
            assertTrue(code, ProgrammeStore.isDefiniteRefusal(db.outboxDao().byClientUuid(x)))
        }
        assertTrue(!ProgrammeStore.isDefiniteRefusal(null))
    }

    // ---- real outbox rows written through CaptureRepository: quarantine, the acknowledged set by record type, the read side (final pass, round 3) ----
    private val astha = com.aktcl.aron.feature.programmes.AsthaModel.parse(
        """{"astha_as_of":"2026-11-17T10:00:00Z","astha_targets":[
          {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"1000","std_achieved":"100","memo_target":0,"memo_achieved":0,"value_target_mtk":"5000000","value_achieved_mtk":"1000000","by_pack":[]},
          {"route_id":7,"outlet_id":null,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":50,"memo_achieved":10,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
          {"route_id":7,"outlet_id":501,"brand_id":11,"month":"2026-11","std_target":"100","std_achieved":"10","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}""",
        """{"nodes":[{"id":11,"level":"brand","name":"Marise","parent_id":1,"sort":1},{"id":21,"level":"variant","name":"V","parent_id":11,"sort":1}],"skus":[{"sku_id":105,"variant_id":21,"category_code":"cigarette","base_per_pack":20}]}""")!!
    private fun stdOf(d: com.aktcl.aron.feature.programmes.AsthaData) = d.rows.first { it.outletId == null && it.brandId == 11L }.stdAchievedMilli

    /** A memo captured the way production captures it (visit, memo, line through CaptureRepository), so the outbox rows are the real ones. */
    private suspend fun captureMemo(date: String = "2026-11-18", route: Long = 7): String {
        val memoUuid = ClientIds.newUuid(); val lineUuid = ClientIds.newUuid(); val visit = ClientIds.newUuid()
        val m = meta.meta(route).copy(businessDate = date, capturedAt = "${date}T05:00:00.000Z")
        val fix = com.aktcl.aron.core.database.entity.GeoFixEntity(ClientIds.newUuid(), visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(com.aktcl.aron.core.database.entity.VisitEntity(visit, m, "sr_call", 501, "${date}T05:00:00.000Z", 1, true, null, fix.clientUuid, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        repo.recordSale(com.aktcl.aron.core.database.repo.SaleCapture(
            com.aktcl.aron.core.database.entity.MemoEntity(memoUuid, m, visit, 501, "sr334001-261118-${memoUuid.take(3)}", "sale", "${date}T05:05:00.000Z", date, "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000, 0, false, null, 1, 0, 0),
            listOf(com.aktcl.aron.core.database.entity.MemoLineEntity(lineUuid, m, memoUuid, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
        return memoUuid
    }

    /** What BundleDownloader.fetch records under the sync lock when it sends a bundle request: the unanswered and quarantined rows and the acknowledged set, from the real outbox. */
    private suspend fun sendBundleRequest(date: String = "2026-11-18") {
        val ob = db.outboxDao()
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(com.aktcl.aron.core.database.repo.ReferenceRepository.KEY_BUNDLE_UNCERTAIN, (ob.sentUnanswered() + ob.quarantinedUuids()).distinct().joinToString(",")))
        val from = java.time.LocalDate.parse(date).withDayOfMonth(1).minusMonths(2).toString()
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(com.aktcl.aron.core.database.repo.ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE, com.aktcl.aron.core.database.repo.ReferenceRepository.encodeAcked((ob.ackedMemoOrderUuids(from) + ob.ackedProgrammeOrderUuids()).distinct())))
    }
    private suspend fun shown(): com.aktcl.aron.feature.programmes.AsthaData {
        val memos = store.memosOfMonth("2026-11")
        return com.aktcl.aron.feature.programmes.AsthaLocal.apply(astha, memos, store.ackedBeforeRequest(), "2026-11")
    }
    private suspend fun heldAndAwaiting(): Pair<Int, Int> { val memos = store.memosOfMonth("2026-11"); return com.aktcl.aron.feature.programmes.AsthaLocal.heldForReview(memos) to com.aktcl.aron.feature.programmes.AsthaLocal.awaiting(memos, store.ackedBeforeRequest()) }

    @Test fun aPendingMemoIsAddedAnAcknowledgedOneBeforeTheRequestIsNot() = runTest {
        val a = captureMemo(); val p = captureMemo()
        db.outboxDao().applyAck(db.outboxDao().nextPending(10).first { it.recordType == "memo" && it.clientUuid == a }.clientUuid, "acked", null, 1L, "2026-11-18T05:10:00.000Z")
        sendBundleRequest()
        assertEquals(100_000L + 3_000L, stdOf(shown())) // only the pending memo of the two (3 base units, thousandths)
    }

    @Test fun aQuarantinedMemoIsShownAsHeldAndNeverAdded() = runTest {
        val q = captureMemo()
        db.outboxDao().applyAck(q, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        for (recordedOrder in listOf(false, true)) {
            if (recordedOrder) sendBundleRequest()
            assertEquals(100_000L, stdOf(shown())) // never added, with or without a recorded order
            assertEquals(1 to 0, heldAndAwaiting().let { it.first to (it.second) }) // its own line; not counted as unanswered
        }
    }

    @Test fun releasedOnTheServerBeforeTheSnapshotButHeardAfterTheRequestIsNotAdded() = runTest {
        val q = captureMemo()
        db.outboxDao().applyAck(q, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        sendBundleRequest() // the request leaves while the memo is held (the server may release it at any moment, whenever the phone hears)
        assertEquals(1, db.outboxDao().applyResolution(q, "acked", "resolved_accepted", "2026-11-18T05:20:00.000Z")) // the resolution reaches the phone AFTER the request
        assertEquals(100_000L, stdOf(shown())) // not added: the server figure may already hold it
        assertEquals("uncertain", store.memosOfMonth("2026-11").single().state)
        assertEquals(1, heldAndAwaiting().second) // awaiting until the next bundle
        // the next bundle request: it is now acknowledged and in the set, so it is inside the figure
        sendBundleRequest()
        assertEquals(100_000L, stdOf(shown())); assertEquals(0, heldAndAwaiting().second)
    }

    @Test fun aResolutionHeardAfterTheRequestWithNoHeldRecordIsAwaitingNeverAdded() = runTest {
        val q = captureMemo()
        db.outboxDao().applyAck(q, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        db.outboxDao().applyResolution(q, "acked", "resolved_accepted_with_fix", "2026-11-18T05:20:00.000Z")
        // a request that was sent before the resolution was heard and that did not record the held row (an older bundle): the set exists and lacks it
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(com.aktcl.aron.core.database.repo.ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE, com.aktcl.aron.core.database.repo.ReferenceRepository.encodeAcked(emptyList())))
        assertEquals(100_000L, stdOf(shown())); assertEquals(1, heldAndAwaiting().second)
    }

    @Test fun aResolutionHeardBeforeTheRequestIsInTheSetAndInsideTheFigure() = runTest {
        val q = captureMemo()
        db.outboxDao().applyAck(q, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        db.outboxDao().applyResolution(q, "acked", "resolved_accepted", "2026-11-18T05:20:00.000Z") // accepted (or accepted with a fix) and heard, then the request
        sendBundleRequest()
        assertEquals(100_000L, stdOf(shown())); assertEquals(0 to 0, heldAndAwaiting())
    }

    @Test fun aDiscardedQuarantinedMemoIsGone() = runTest {
        val q = captureMemo()
        db.outboxDao().applyAck(q, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        db.outboxDao().applyResolution(q, "rejected", "resolved_rejected", "2026-11-18T05:20:00.000Z")
        sendBundleRequest()
        assertEquals(100_000L, stdOf(shown())); assertEquals(0 to 0, heldAndAwaiting())
    }

    @Test fun theWireTypesOfARedemptionAndAGiftChoiceAreTheOnesTheOrderListsAndTheirAcknowledgementReachesTheSet() = runTest {
        val red = ClientIds.newUuid(); val choice = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), plan(red, 1)))
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), listOf(com.aktcl.aron.feature.programmes.GiftChoiceModel.plan(10, 4, "2026-Q4", choice))))
        val types = db.outboxDao().nextPending(20).associate { it.clientUuid to it.recordType }
        assertEquals("redemption", types[red]); assertEquals("gift_choice", types[choice]) // there is no amo_record wire type
        db.outboxDao().applyAck(red, "acked", null, 1L, "2026-11-18T05:10:00.000Z"); db.outboxDao().applyAck(choice, "acked", null, 2L, "2026-11-18T05:10:00.000Z")
        sendBundleRequest()
        val set = store.ackedBeforeRequest()!!
        assertTrue(red in set && choice in set)
        // the points balance: the redemption is inside the bundle's balance, not subtracted a second time
        assertEquals(0L, com.aktcl.aron.feature.programmes.LocalRedeemed.points(store.redemptions(), 10, 3, set))
        val assignment = com.aktcl.aron.feature.programmes.GiftAssignment(10, "2026-Q4", 4, null, false, false, 71)
        assertEquals(com.aktcl.aron.feature.programmes.GiftState.Chosen(4), com.aktcl.aron.feature.programmes.GiftChoiceData(emptyList(), listOf(assignment)).stateOf(10, "2026-Q4", store.giftChoices(), set)) // the bundle's value wins once the set holds the acknowledged choice
    }

    @Test fun aRedemptionAcknowledgedLastMonthIsStillInTheSetAndNotSubtractedTwice() = runTest {
        val red = ClientIds.newUuid()
        val oldMeta = meta.meta(7).copy(businessDate = "2026-09-20", capturedAt = "2026-09-20T05:00:00.000Z")
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(oldMeta, plan(red, 1)))
        db.outboxDao().applyAck(red, "acked", null, 1L, "2026-09-20T05:10:00.000Z")
        sendBundleRequest("2026-11-18") // two months later: the memo window does not reach back, the programme set has no date cut
        assertTrue(red in store.ackedBeforeRequest()!!)
        assertEquals(0L, com.aktcl.aron.feature.programmes.LocalRedeemed.points(store.redemptions(), 10, 3, store.ackedBeforeRequest()))
    }

    @Test fun theReadSideIsCleanOnlyWithNothingOutstandingAndCountsTheMemosNotInTheFigure() = runTest {
        // an October memo: acknowledged, then pending ones
        val ok = captureMemo("2026-10-05"); db.outboxDao().applyAck(ok, "acked", null, 1L, "2026-10-05T06:00:00.000Z")
        sendBundleRequest()
        assertEquals(true, store.readSideOf(listOf("2026-10"), 7)["2026-10"]!!.clean) // acknowledged before the last bundle request: nothing outstanding
        val p = captureMemo("2026-10-06"); val q = captureMemo("2026-10-07")
        db.outboxDao().applyAck(q, "quarantined", "late_sync", null, "2026-10-07T06:00:00.000Z")
        val side = store.readSideOf(listOf("2026-10", "2026-09"), 7)
        assertEquals(false, side["2026-10"]!!.clean); assertEquals(2, side["2026-10"]!!.awaiting) // a pending one and a quarantined one
        assertEquals(true, side["2026-09"]!!.clean)
        assertEquals(true, store.readSideOf(listOf("2026-10"), 9)["2026-10"]!!.clean) // another route's memos are not this route's
        // acknowledged AFTER the last bundle request: still outstanding for a read
        db.outboxDao().applyAck(p, "acked", null, 3L, "2026-10-06T07:00:00.000Z")
        assertEquals(1, store.readSideOf(listOf("2026-10"), 7)["2026-10"]!!.awaiting) // only the quarantined one: a memo acknowledged before the read was sent is committed and inside the read (never overstated)
    }

    @Test fun aPendingEditOfAMemoOfTheMonthIsOutstandingEvenWhenItIsDatedInALaterMonth() = runTest {
        val original = captureMemo("2026-10-05"); db.outboxDao().applyAck(original, "acked", null, 1L, "2026-10-05T06:00:00.000Z")
        assertEquals(true, store.readSideOf(listOf("2026-10"), 7)["2026-10"]!!.clean)
        // the SR edits it on 3 November: the edit is a new memo that supersedes the original, dated in November, still pending
        val edit = ClientIds.newUuid(); val visit = ClientIds.newUuid(); val line = ClientIds.newUuid()
        val m = meta.meta(7).copy(businessDate = "2026-11-03", capturedAt = "2026-11-03T05:00:00.000Z")
        val fix = com.aktcl.aron.core.database.entity.GeoFixEntity(ClientIds.newUuid(), visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(com.aktcl.aron.core.database.entity.VisitEntity(visit, m, "sr_call", 501, "2026-11-03T05:00:00.000Z", 1, true, null, fix.clientUuid, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val editFix = fix.copy(clientUuid = ClientIds.newUuid(), ownerClientUuid = edit, purpose = "memo_edit")
        repo.recordSale(com.aktcl.aron.core.database.repo.SaleCapture(
            com.aktcl.aron.core.database.entity.MemoEntity(edit, m, visit, 501, "sr334001-261103-001", "sale", "2026-11-03T05:05:00.000Z", "2026-11-03", "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000, 0, false, null, 1, 0, 0, supersedesClientUuid = original, editReasonCode = "wrong_qty", editFixClientUuid = editFix.clientUuid),
            listOf(com.aktcl.aron.core.database.entity.MemoLineEntity(line, m, edit, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
            editFix = editFix,
        ))
        val side = store.readSideOf(listOf("2026-10"), 7)["2026-10"]!!
        assertEquals(false, side.clean); assertEquals(1, side.awaiting)
        db.outboxDao().applyAck(edit, "acked", null, 2L, "2026-11-03T06:00:00.000Z") // acknowledged before a LATER read: committed, not outstanding
        assertEquals(true, store.readSideOf(listOf("2026-10"), 7)["2026-10"]!!.clean)
    }

    // ---- a held or unanswered REDEMPTION stays subtracted (conservative), once, with a note ----
    private suspend fun redemptionOf(points: Int): String { val u = ClientIds.newUuid(); assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), plan(u, points / 200))); return u }
    private suspend fun balance(bundle: Long) = com.aktcl.aron.feature.programmes.PointsBalance.of(bundle, store.redemptions(), 10, 3, store.ackedBeforeRequest())
    private suspend fun held() = com.aktcl.aron.feature.programmes.LocalRedeemed.heldPoints(store.redemptions(), 10, 3, store.ackedBeforeRequest())

    @Test fun aHeldRedemptionIsSubtractedOnceAndNamed() = runTest {
        val r = redemptionOf(400)
        db.outboxDao().applyAck(r, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        assertEquals(100L, balance(500)); assertEquals(400L, held()) // no recorded order: still subtracted once
        sendBundleRequest() // the request leaves while it is held: the bundle may or may not carry its deduction
        assertEquals(100L, balance(500)); assertEquals(400L, held())
    }

    @Test fun acceptedOnTheServerBeforeTheBundleButHeardAfterTheRequestIsStillSubtractedOnceNotTwice() = runTest {
        val r = redemptionOf(400)
        db.outboxDao().applyAck(r, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        sendBundleRequest()
        db.outboxDao().applyResolution(r, "acked", "resolved_accepted", "2026-11-18T05:20:00.000Z") // heard after the request
        assertEquals(100L, balance(500)) // 500 (it may already carry the deduction) minus the held 400, conservative, not 500 - 400 - 400
        assertEquals(400L, held())
        // the bundle that follows carries it and the set holds it: nothing subtracted any more
        sendBundleRequest()
        assertEquals(500L, balance(500)); assertEquals(0L, held())
    }

    @Test fun aDiscardedHeldRedemptionGivesItsPointsBack() = runTest {
        val r = redemptionOf(400)
        db.outboxDao().applyAck(r, "quarantined", "late_sync", null, "2026-11-18T05:10:00.000Z")
        db.outboxDao().applyResolution(r, "rejected", "resolved_rejected", "2026-11-18T05:20:00.000Z")
        assertEquals(500L, balance(500)); assertEquals(0L, held())
    }

    @Test fun aRedemptionSentButUnansweredWhenTheRequestLeftIsSubtractedOnceAndNamed() = runTest {
        val r = redemptionOf(200)
        db.outboxDao().applyAck(r, "pending", "transport_timeout", null, "2026-11-18T05:10:00.000Z") // a failed attempt: sent, no answer
        sendBundleRequest()
        assertEquals("uncertain", store.redemptions().single().state)
        assertEquals(300L, balance(500)); assertEquals(200L, held())
    }
}

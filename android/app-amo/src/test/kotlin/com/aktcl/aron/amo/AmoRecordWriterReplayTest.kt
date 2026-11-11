package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.SurveyEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.feature.amo.AmoBrand
import com.aktcl.aron.feature.amo.JointRubric
import com.aktcl.aron.feature.amo.JointState
import com.aktcl.aron.feature.amo.PerfState
import com.aktcl.aron.feature.amo.PriceRow
import com.aktcl.aron.feature.amo.RubricCriterion
import com.aktcl.aron.feature.dayclose.CountRow
import com.aktcl.aron.feature.dayclose.RoomDaySubmitWriter
import com.aktcl.aron.feature.dayclose.SubmitGate
import com.aktcl.aron.feature.outlet.AnswerType
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.OutletRequestDraft
import com.aktcl.aron.feature.outlet.OutletRequestKind
import com.aktcl.aron.feature.outlet.SurveyAnswer
import com.aktcl.aron.feature.outlet.SurveyQuestion
import com.aktcl.aron.feature.outlet.SurveyResponseRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Kill-and-relaunch and replay proof for every AMO write path (CLAUDE.md definition of done). A real file database is used: "kill" closes the
 * open database and drops every host object (writer, repository), "relaunch" opens the same file again. For each path: the operation is
 * started and finished, the process dies, the same operation (same client uuid, or the same screen state) is replayed on the new objects,
 * and exactly ONE set of outbox rows with byte-identical payloads remains. A failure in the middle of an operation rolls back all of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoRecordWriterReplayTest {
    private val user = 11L
    private lateinit var databases: UserDatabases
    private lateinit var capture: CaptureRepository
    private lateinit var writer: AmoRecordWriter
    private var syncs = 0
    private var failMetaAt = -1
    private var metaCalls = 0
    private val date = "2026-10-17"
    private val route = 10231L

    private val claims = mutableListOf<List<String>>()
    // A4 (D-AMO-MAN-12): the new-outlet verification takes the fix and the photo captured on the form
    @Test fun aVerificationUsesTheFixCapturedWithThePhotoAndClaimsThePhotoOnce() = runBlocking {
        val request = ClientIds.newUuid(); val rec = ClientIds.newUuid(); val photo = ClientIds.newUuid()
        val captured = FixReading("ok", 23.5, 90.25, 6.0, false)
        assertTrue(writer.decideRequest(rec, request, true, 4L, "Urban", null, photo, captured))
        val outer = kotlinx.serialization.json.Json.parseToJsonElement(payloads("outlet_request_verification").single()) as kotlinx.serialization.json.JsonObject
        val p = (outer["payload"] as? kotlinx.serialization.json.JsonObject) ?: outer
        val fix = (p["fix"] as? kotlinx.serialization.json.JsonObject) ?: error("no inline fix in: $outer")
        assertEquals(23.5, (fix["lat"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(photo, (p["photo_uuid"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(1, claims.count { it[0] == photo })
        relaunch()
        assertTrue(writer.decideRequest(rec, request, true, 4L, "Urban", null, photo, captured)) // replay after a swipe-kill
        assertEquals(1, payloads("outlet_request_verification").size)
    }

    // contract 1.11.1: the route and cluster chosen on the form travel in the payload (never the envelope route)
    @Test fun aVerificationCarriesTheFormsRouteAndClusterAndAReplayKeepsOneRow() = runBlocking {
        val request = ClientIds.newUuid(); val rec = ClientIds.newUuid()
        assertTrue(writer.decideRequest(rec, request, true, 4L, "Urban", null, null, null, 5001L, 77L))
        fun payload(): kotlinx.serialization.json.JsonObject { val o = kotlinx.serialization.json.Json.parseToJsonElement(payloads("outlet_request_verification").single()) as kotlinx.serialization.json.JsonObject; return (o["payload"] as? kotlinx.serialization.json.JsonObject) ?: o }
        assertFalse("integer, not a string", (payload()["route_id"] as kotlinx.serialization.json.JsonPrimitive).isString)
        assertEquals("5001", (payload()["route_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse((payload()["cluster_id"] as kotlinx.serialization.json.JsonPrimitive).isString)
        assertEquals("77", (payload()["cluster_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        relaunch()
        assertTrue(writer.decideRequest(rec, request, true, 4L, "Urban", null, null, null, 5001L, 77L))
        assertEquals(1, payloads("outlet_request_verification").size)
    }

    @Test fun aRejectionCarriesNoRouteOrClusterAndNullsAreOmitted() = runBlocking {
        assertTrue(writer.decideRequest(ClientIds.newUuid(), ClientIds.newUuid(), false, null, null, null, null))
        val o = kotlinx.serialization.json.Json.parseToJsonElement(payloads("outlet_request_verification").single()) as kotlinx.serialization.json.JsonObject
        val p = (o["payload"] as? kotlinx.serialization.json.JsonObject) ?: o
        assertTrue("route_id" !in p && "cluster_id" !in p)
    }

    @Test fun aDecisionTheServerRefusedShowsItsCodeAndLeavesTheRequestDecidable() = runBlocking {
        val request = ClientIds.newUuid(); val rec = ClientIds.newUuid()
        assertTrue(writer.decideRequest(rec, request, true, 4L, "Urban", null, null, null, 9999L, 77L))
        assertEquals(null, writer.refusedDecisionCode(request))
        db.outboxDao().applyAck(rec, "rejected", "unknown_route", null, "2026-10-17T05:00:00.000Z")
        db.outboxDao().applyAck(rec, "quarantined", "scope_out_of_reach", null, "2026-10-17T05:00:00.000Z") // terminal rows never move: the first ack stands
        assertEquals("unknown_route", writer.refusedDecisionCode(request))
        assertFalse(writer.decidedRequests().contains(request)) // the request is decidable again
        assertTrue(writer.decideRequest(ClientIds.newUuid(), request, true, 4L, "Urban", null, null, null, 5001L, 77L)) // the corrected form is a new record
        assertEquals(2, db.captureDao().amoRecordsOfType("outlet_request_verification").size)
    }

    // ---- the form's save (decideFromForm): what the AMO picked, reject, refusal, hold
    private val formDrafts get() = FormDrafts(ApplicationProvider.getApplicationContext<Application>(), user)
    private fun payloadOf(request: String): kotlinx.serialization.json.JsonObject = payloads("outlet_request_verification").map { j ->
        val o = kotlinx.serialization.json.Json.parseToJsonElement(j) as kotlinx.serialization.json.JsonObject; (o["payload"] as? kotlinx.serialization.json.JsonObject) ?: o
    }.first { (it["request_uuid"] as kotlinx.serialization.json.JsonPrimitive).content == request }
    private fun choice(verify: Boolean = true, route: Long? = null, cluster: Long? = null, newOutlet: Boolean = false, photo: String? = null, fix: FixReading? = null) = VerifyChoice(verify, 4L, "Urban", photo, fix, route, cluster, newOutlet)

    @Test fun anUntouchedVerifySaveOmitsRouteAndClusterAndOnlyThePickedOnesAreSent() = runBlocking {
        val r1 = ClientIds.newUuid(); val key1 = "verify:$r1"
        assertTrue(writer.decideFromForm(formDrafts, key1, r1, formDrafts.uuid(key1), choice()).ok)
        assertTrue("route_id" !in payloadOf(r1) && "cluster_id" !in payloadOf(r1))
        val r2 = ClientIds.newUuid(); val key2 = "verify:$r2"
        assertTrue(writer.decideFromForm(formDrafts, key2, r2, formDrafts.uuid(key2), choice(route = 5001L, cluster = 77L)).ok)
        assertEquals("5001", (payloadOf(r2)["route_id"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun aRejectOmitsEverythingTheVerifyCarriesEvenForANewOutletWithCapture() = runBlocking {
        val photo = ClientIds.newUuid(); val captured = FixReading("ok", 23.5, 90.25, 6.0, false)
        val v = ClientIds.newUuid(); val kv = "verify:$v"
        assertTrue(writer.decideFromForm(formDrafts, kv, v, formDrafts.uuid(kv), choice(verify = true, route = 5001L, cluster = 77L, newOutlet = true, photo = photo, fix = captured)).ok)
        val yes = payloadOf(v)
        listOf("sub_channel_id", "geo_class", "photo_uuid", "route_id", "cluster_id").forEach { assertTrue("the verify carries $it", it in yes) }
        assertEquals(23.5, ((yes["fix"] as kotlinx.serialization.json.JsonObject)["lat"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 0.0)
        val r = ClientIds.newUuid(); val kr = "verify:$r"
        assertTrue(writer.decideFromForm(formDrafts, kr, r, formDrafts.uuid(kr), choice(verify = false, route = 5001L, cluster = 77L, newOutlet = true, photo = photo, fix = captured)).ok)
        val no = payloadOf(r)
        listOf("sub_channel_id", "geo_class", "photo_uuid", "route_id", "cluster_id").forEach { assertTrue("the reject must not carry $it", it !in no) }
        // the contract requires a fix on every decision: a reject's is the fresh reading, never the captured one
        assertEquals(23.79, ((no["fix"] as kotlinx.serialization.json.JsonObject)["lat"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 0.0)
        assertEquals("discarded", (no["decision"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun aCorrectedSaveAfterARefusalTakesANewRecordUuidAndAHeldDecisionBlocksANewOne() = runBlocking {
        val r = ClientIds.newUuid(); val key = "verify:$r"; val first = formDrafts.uuid(key)
        assertTrue(writer.decideFromForm(formDrafts, key, r, first, choice(route = 9999L)).ok)
        db.outboxDao().applyAck(first, "rejected", "unknown_route", null, "2026-10-17T05:00:00.000Z")
        assertEquals("rejected" to "unknown_route", writer.refusedDecision(r))
        val corrected = writer.decideFromForm(formDrafts, key, r, first, choice(route = 5001L))
        assertTrue(corrected.ok); assertTrue("a new record, not the refused one", corrected.recordUuid != first)
        assertEquals("an older refusal is not reported once a newer decision exists", null, writer.refusedDecision(r))
        assertEquals(2, db.captureDao().amoRecordsOfType("outlet_request_verification").size)
        // the newest decision is the one reported (order by capture time, then insertion)
        db.outboxDao().applyAck(corrected.recordUuid, "quarantined", "scope_out_of_reach", null, "2026-10-17T05:00:01.000Z")
        assertEquals("quarantined" to "scope_out_of_reach", writer.refusedDecision(r))
        val blocked = writer.decideFromForm(formDrafts, key, r, corrected.recordUuid, choice(route = 5001L))
        assertTrue(blocked.held && !blocked.ok)
        assertEquals("held for review: nothing more queued", 2, db.captureDao().amoRecordsOfType("outlet_request_verification").size)
    }

    @Test fun aMockedCapturedFixIsNeverQueuedAsAVerification() = runBlocking {
        val mocked = FixReading("ok", 23.5, 90.25, 6.0, true)
        assertFalse(writer.decideRequest(ClientIds.newUuid(), ClientIds.newUuid(), true, 4L, "Urban", null, ClientIds.newUuid(), mocked))
        assertEquals(0, payloads("outlet_request_verification").size)
    }

    private var stamped: String? = null

    private val metaProvider = CaptureMetaProvider { rid ->
        metaCalls++
        if (failMetaAt in 1..metaCalls) error("the process died while writing")
        CaptureMeta(
            businessDate = date, capturedAt = "2026-10-17T04:00:00.000Z", capturedElapsedMs = 1_000, bootCount = 3, clockOffsetMs = 0L, capturedOffline = true,
            routeId = rid.takeIf { it != 0L }, bundleVersion = "$date:1", configVersion = 1L,
        )
    }
    private val ok = FixReading("ok", 23.79, 90.40, 8.0, false)

    private fun open() {
        databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null }
        val db = runBlocking { databases.of(user) }
        capture = CaptureRepository(db) { "2026-10-17T04:00:00.000Z" }
        writer = AmoRecordWriter(db, capture, metaProvider, { route }, { ClientIds.newUuid() }, { syncs++ }, { ok },
            { photo, purpose, _, ref, fix -> claims += listOf(photo, purpose, ref, fix?.clientUuid.orEmpty()) }, { _, _ -> stamped },
        )
    }

    @Before fun start() = open()
    @After fun stop() { databases.close(user) }

    /** The process dies: the database handle and every host object are gone; the next launch opens the same file again. */
    private fun relaunch() { databases.close(user); open() } // a fresh UserDatabases and fresh host objects, as a new process has

    private val db get() = runBlocking { databases.of(user) }
    private fun outbox(type: String? = null) = runBlocking { db.outboxDao().nextPending(10_000) }.filter { type == null || it.recordType == type }
    private fun payloads(type: String? = null) = outbox(type).map { it.payloadJson }.sorted()

    private fun visit(kind: String = "amo_control_call"): String = runBlocking {
        val u = ClientIds.newUuid(); val f = com.aktcl.aron.core.database.entity.GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", 23.79, 90.40, 8.0, provider = "fused", fixTime = "2026-10-17T04:00:00.000Z", fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false,
            autoTimeEnabled = true, mockAppPresent = false,
        )
        capture.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = metaProvider.meta(route), visitKind = kind, outletId = 50001, openedAt = "2026-10-17T04:00:00.000Z", sequenceNo = 1, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50, geoLocationBasis = "master",
                geoOutletLat = 23.79, geoOutletLng = 90.40, geoAction = "sale_allowed",
            ),
            f,
        )
        u
    }

    // ---------- joint call ----------
    private val rubric = JointRubric(1, 1, listOf(RubricCriterion(1, "A", null, "score_1_5", true), RubricCriterion(2, "B", null, "score_1_5", true)))

    @Test fun jointCallSurvivesRelaunchAndReplayWritesNothingMore() = runBlocking {
        val v = visit(); val base = outbox().size
        writer.saveJointCall(v, rubric, JointState(mapOf(1L to 4, 2L to 5)), 7L)
        val rows = payloads("call_assessment") + payloads("call_assessment_answer"); assertEquals(3, rows.size); assertEquals(1, syncs)
        relaunch()
        assertEquals(rows, payloads("call_assessment") + payloads("call_assessment_answer"))
        writer.saveJointCall(v, rubric, JointState(mapOf(1L to 4, 2L to 5)), 7L) // the same Save again after the kill
        assertEquals("a replay adds nothing", base + 3, outbox().size); assertEquals(rows, payloads("call_assessment") + payloads("call_assessment_answer"))
    }

    @Test fun aFailureInTheMiddleOfAJointCallSaveRollsBackEverything() = runBlocking {
        val v = visit(); val base = outbox().size
        failMetaAt = metaCalls + 3 // dies while writing the third record
        assertTrue(runCatching { writer.saveJointCall(v, rubric, JointState(mapOf(1L to 4, 2L to 5)), 7L) }.isFailure)
        failMetaAt = -1; relaunch()
        assertEquals("nothing half-written", base, outbox().size)
        writer.saveJointCall(v, rubric, JointState(mapOf(1L to 4, 2L to 5)), 7L)
        assertEquals(base + 3, outbox().size)
    }

    // ---------- SR performance assessment ----------
    private val brands = listOf(AmoBrand(1, "A", null), AmoBrand(2, "B", null))

    @Test fun assessmentSurvivesRelaunchAndReplayWritesNothingMore() = runBlocking {
        val v = visit(); val base = outbox().size
        val state = PerfState(present = setOf(1L, 2L), oos = setOf(2L), posm = true)
        writer.saveAssessment(v, brands, state)
        val rows = payloads("distribution_check") + payloads("distribution_check_line"); val n = outbox().size - base; assertTrue(n >= 3)
        relaunch(); writer.saveAssessment(v, brands, state)
        assertEquals(base + n, outbox().size); assertEquals(rows, payloads("distribution_check") + payloads("distribution_check_line"))
    }

    @Test fun aJointCallWithNoDefaultStarAndAnUnratedItemIsRefusedAndWritesNothing() = runBlocking {
        val v = visit(); val base = outbox().size
        assertTrue(runCatching { writer.saveJointCall(v, rubric, JointState(mapOf(1L to 4)), 7L, defaultStar = null) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(base, outbox().size)
        writer.saveJointCall(v, rubric, JointState(mapOf(1L to 4, 2L to 2)), 7L, defaultStar = null) // all rated: saved
        assertTrue(outbox().size > base)
    }

    @Test fun anAssessmentWithoutThePosmAnswerIsRefusedAndWritesNothing() = runBlocking {
        val v = visit(); val base = outbox().size
        assertTrue(runCatching { writer.saveAssessment(v, brands, PerfState(present = setOf(1L), posm = null)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(base, outbox().size)
    }

    @Test fun aFailureInTheMiddleOfAnAssessmentRollsBackEverything() = runBlocking {
        val v = visit(); val base = outbox().size
        failMetaAt = metaCalls + 2
        assertTrue(runCatching { writer.saveAssessment(v, brands, PerfState(present = setOf(1L, 2L), oos = setOf(2L), posm = true)) }.isFailure)
        failMetaAt = -1; relaunch()
        assertEquals(base, outbox().size)
        writer.saveAssessment(v, brands, PerfState(present = setOf(1L, 2L), oos = setOf(2L), posm = true))
        assertTrue("the replay after the rollback writes the whole assessment", outbox().size >= base + 3)
    }

    // ---------- survey ----------
    private val question = SurveyQuestion(1, AnswerType.BOOL, "q1", true, false)
    private fun surveyRows(v: String) = listOf(SurveyResponseRow(ClientIds.newUuid(), v, 1, 1, question, SurveyAnswer(bool = true)))

    @Test fun surveySurvivesRelaunchAndReplayWritesNothingMore() = runBlocking {
        val v = visit(); val base = outbox().size; val survey = SurveyEntity(1, 1, "S", null)
        writer.saveSurvey(v, survey, surveyRows(v))
        val rows = payloads("survey_response"); assertEquals(1, rows.size)
        relaunch(); writer.saveSurvey(v, survey, surveyRows(v)) // the screen is rebuilt and Save is pressed again: new row uuids, same visit
        assertEquals(base + 1, outbox().size); assertEquals(rows, payloads("survey_response"))
    }

    @Test fun aSurveyPhotoNotClaimedBeforeAKillIsClaimedWhenSaveIsReplayed() = runBlocking {
        val v = visit(); val survey = SurveyEntity(1, 1, "S", null); val photo = ClientIds.newUuid()
        val rows = listOf(SurveyResponseRow(ClientIds.newUuid(), v, 1, 1, question, SurveyAnswer(bool = true, photoUuid = photo)))
        writer.saveSurvey(v, survey, rows)
        val first = claims.size; assertEquals(1, first)
        relaunch(); claims.clear() // the process died before the claim: the new process has no claim, the row names the photo
        writer.saveSurvey(v, survey, surveyRows(v))
        assertEquals(listOf(photo, "survey", rows[0].clientUuid, ""), claims.single())
        assertEquals(1, payloads("survey_response").size)
    }

    // ---------- price compliance ----------
    private val prices = listOf(PriceRow(1, "A", "10", "retail", 120_000), PriceRow(2, "B", "20", "retail", 240_000))

    @Test fun priceComplianceSurvivesRelaunchAndReplayWritesNothingMore() = runBlocking {
        val v = visit(); val base = outbox().size; val typed = mapOf(1L to "120", 2L to "260")
        writer.savePriceCompliance(v, prices, typed, 500)
        val rows = payloads("price_compliance_check"); assertEquals(2, rows.size)
        relaunch(); writer.savePriceCompliance(v, prices, typed, 500)
        assertEquals(base + 2, outbox().size); assertEquals(rows, payloads("price_compliance_check"))
        writer.savePriceCompliance(v, prices, typed + (1L to "121"), 500) // a changed value for a recorded SKU is not recorded again
        assertEquals(base + 2, outbox().size)
    }

    // ---------- task assignment ----------
    @Test fun taskAssignmentSurvivesRelaunchAndTheSameFormUuidQueuesOne() = runBlocking {
        val u = ClientIds.newUuid()
        assertTrue(writer.assignTask(u, "stock_check", 7L, 50001L, "Stock check", null, "2026-10-18"))
        val rows = payloads("task"); assertEquals(1, rows.size); assertEquals(1, syncs)
        relaunch()
        assertFalse("the same form uuid (restored by the system after a process death) queues nothing", writer.assignTask(u, "stock_check", 7L, 50001L, "Stock check", null, "2026-10-18"))
        assertEquals(rows, payloads("task"))
    }

    // ---------- verification decisions ----------
    @Test fun aVerificationDecisionSurvivesRelaunchAndNeverDoublesForTheSameRequest() = runBlocking {
        val request = ClientIds.newUuid(); val rec = ClientIds.newUuid()
        assertTrue(writer.decideRequest(rec, request, true, 4L, "Urban", null, null))
        val rows = payloads("outlet_request_verification"); assertEquals(1, rows.size)
        relaunch()
        assertTrue(writer.decideRequest(rec, request, true, 4L, "Urban", null, null)) // same record uuid
        assertTrue(writer.decideRequest(ClientIds.newUuid(), request, false, null, null, null, null)) // another form for the same request
        assertEquals("exactly one decision for the request", rows, payloads("outlet_request_verification"))
        assertTrue(writer.decidedRequests().contains(request))
    }

    @Test fun noUsableFixQueuesNothing() = runBlocking {
        val bad = AmoRecordWriter(db, capture, metaProvider, { route }, { ClientIds.newUuid() }, { syncs++ }, { FixReading("timeout", null, null, null, false) }, { _, _, _, _, _ -> })
        assertFalse(bad.decideRequest(ClientIds.newUuid(), ClientIds.newUuid(), true, 4L, "Urban", null, null))
        assertEquals(0, payloads("outlet_request_verification").size)
    }

    // ---------- exceptions review ----------
    @Test fun anExceptionReviewSurvivesRelaunchAndNeverDoubles() = runBlocking {
        val u = ClientIds.newUuid()
        assertTrue(writer.reviewSignal(u, 42L, "confirmed", "seen"))
        val rows = payloads("risk_review"); assertEquals(1, rows.size)
        relaunch()
        assertFalse(writer.reviewSignal(u, 42L, "confirmed", "seen"))
        assertFalse("a second review of the same signal with a new uuid", writer.reviewSignal(ClientIds.newUuid(), 42L, "dismissed", null))
        assertEquals(rows, payloads("risk_review"))
    }

    // ---------- Update Base and the own outlet requests ----------
    private fun draft(uuid: String, kind: OutletRequestKind = OutletRequestKind.LOCATION) = OutletRequestDraft(
        uuid, kind, 50001L, null, null, null, null, null, null, ok, emptyList(), null, pointLat = 23.7905, pointLng = 90.4003,
    )

    @Test fun updateBaseSurvivesRelaunchAndAReplayWithANewFixUuidWritesNothing() = runBlocking {
        val u = ClientIds.newUuid()
        writer.commitOutletRequest(draft(u))
        val rows = payloads("outlet_change_request"); assertEquals(1, rows.size)
        relaunch() // the fix-uuid map of the old writer is gone: the replay builds a different fix uuid
        writer.commitOutletRequest(draft(u)); writer.commitOutletRequest(draft(u)) // a double tap
        assertEquals(rows, payloads("outlet_change_request"))
        assertEquals(1, db.captureDao().outletRequests().size)
    }

    @Test fun updateBaseReusesTheFixUuidAlreadyStampedOnItsPhotoAfterARelaunch() = runBlocking {
        val u = ClientIds.newUuid(); val photo = ClientIds.newUuid()
        stamped = ClientIds.newUuid()
        writer.commitOutletRequest(draft(u).copy(photoUuids = listOf(photo)))
        assertEquals(stamped, claims.single()[3])
        assertEquals(1, db.captureDao().outletRequests().size)
    }

    // ---------- stock correction ----------
    @Test fun aStockCorrectionIsOneAdjustmentAndASecondSavePostsNothing() = runBlocking {
        val issue = StockMovementEntity(ClientIds.newUuid(), metaProvider.meta(0L), "issue", 100, 100, "stick", 1, 100, null, false)
        capture.recordStock(listOf(issue))
        assertTrue(writer.correctStock(date, 100, 80, "count_error", "stick")); val rows = payloads("stock_movement"); assertEquals(2, rows.size)
        relaunch(); assertFalse(writer.correctStock(date, 100, 80, "count_error", "stick")) // the same Save again after the kill: the balance is already 80
        assertEquals(rows, payloads("stock_movement"))
    }

    // ---------- sales submit ----------
    @Test fun aSupervisorSalesSubmitIsOneRecordAndTheDayRefusesASecond() = runBlocking {
        fun submitter() = RoomDaySubmitWriter(db, capture, { metaProvider.meta(0L) }, { "cig" }, scope = "supervisor_day")
        val gate = SubmitGate(true, emptyList(), null)
        val counts = listOf(CountRow("memo", 0, 0))
        submitter().queue(date, gate, counts)
        val rows = payloads("day_submit"); assertEquals(1, rows.size)
        relaunch()
        val refused = runCatching { submitter().queue(date, gate, counts) }.exceptionOrNull()
        assertTrue("a replay is refused while the day stands submitted: $refused", refused is IllegalStateException && refused.message.orEmpty().contains("submitted"))
        assertEquals(rows, payloads("day_submit")); assertTrue(capture.submitStands(date))
    }

    // ---------- the sale (SR stack on the AMO's stock) ----------
    @Test fun aReplayedNumberedSaleReturnsItsNumberAndBurnsNone() = runBlocking {
        val v = visit()
        val memo = com.aktcl.aron.core.database.entity.MemoEntity(
            clientUuid = ClientIds.newUuid(), meta = metaProvider.meta(route), visitClientUuid = v, outletId = 50001, memoNo = "amo5001-261017-001", memoKind = "sale",
            committedAt = "2026-10-17T04:05:00.000Z", priceListDate = date, priceType = "outlet", grossMtk = 1000, offerDiscountMtk = 0, drpDiscountMtk = 0,
            qcDeductionMtk = 0, roundAdjMtk = 0, netMtk = 1000, paidMtk = 1000, dueMtk = 0, isCredit = false, lineCount = 1, discountLineCount = 0, qcLineCount = 0,
            offerVersionIdsJson = "[]",
        )
        val line = com.aktcl.aron.core.database.entity.MemoLineEntity(ClientIds.newUuid(), metaProvider.meta(route), memo.clientUuid, 1, 100, "sale", 1, "pack", 1, 1, "outlet", date, 1000, 1, 1000)
        val sale = com.aktcl.aron.core.database.repo.SaleCapture(memo, listOf(line), emptyList(), emptyList())
        val numbering = com.aktcl.aron.core.database.repo.MemoNumbering("amo5001", 0, 500)
        val no = capture.recordNumberedSale(sale, numbering)
        val rows = payloads("memo") + payloads("memo_line"); assertEquals(2, rows.size)
        val counter = counterDump()
        relaunch()
        assertEquals("the replay returns the number it got", no, capture.recordNumberedSale(sale, numbering))
        assertEquals("no number was burnt", counter, counterDump())
        assertEquals(rows, payloads("memo") + payloads("memo_line"))
    }

    private fun counterDump(): String = db.openHelper.readableDatabase.query("SELECT * FROM memo_counter").use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString(",") { c.getString(it) ?: "" }) }.joinToString(";")
    }

    // ---------- force sale reason (D-AMO-MAN-08, screenshot 23) ----------
    private fun forceSaleVisit(reason: String): String = runBlocking {
        val u = ClientIds.newUuid(); val photo = ClientIds.newUuid(); val f = com.aktcl.aron.core.database.entity.GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", 23.80, 90.50, 8.0, provider = "fused", fixTime = "2026-10-17T04:00:00.000Z", fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 1, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false,
            autoTimeEnabled = true, mockAppPresent = false,
        )
        capture.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = metaProvider.meta(route), visitKind = "amo_control_call", outletId = 50002, openedAt = "2026-10-17T04:00:00.000Z", sequenceNo = 1, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "out_of_range", geoDistanceM = 900.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50, geoLocationBasis = "master",
                geoOutletLat = 23.79, geoOutletLng = 90.40, geoAction = "force_sale", geoForceReasonCode = reason, geoForcePhotoUuid = photo,
            ),
            f,
        )
        u
    }

    @Test fun theForceSaleReasonIsOnTheVisitSurvivesRelaunchAndAReplayedOpenAddsNoSecondVisit() = runBlocking {
        for (reason in listOf("internet_problem", "location_change")) {
            val v = forceSaleVisit(reason)
            val rows = payloads("visit").filter { it.contains(v) }
            assertEquals(1, rows.size)
            assertEquals(true, rows[0].contains("\"force_reason_code\":\"$reason\"")) // the visit record carries the chosen reason with geo.action force_sale
            assertEquals(true, rows[0].contains("\"action\":\"force_sale\""))
            assertEquals(true, rows[0].contains("\"force_sale_reason\":\"$reason\"")) // contract 1.11.1: the top-level field carries the same user reason
            relaunch()
            assertEquals(rows, payloads("visit").filter { it.contains(v) }) // byte-identical after the kill
            // the same visit uuid committed again (a retried commit after a kill) is refused and queues no second visit
            runCatching {
                capture.recordVisitOpen(
                    VisitEntity(
                        clientUuid = v, meta = metaProvider.meta(route), visitKind = "amo_control_call", outletId = 50002, openedAt = "2026-10-17T04:00:00.000Z", sequenceNo = 1, planned = true,
                        fixClientUuid = ClientIds.newUuid(), geoVerdict = "out_of_range", geoDistanceM = 900.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50, geoLocationBasis = "master", geoOutletLat = 23.79, geoOutletLng = 90.40, geoAction = "force_sale", geoForceReasonCode = reason,
                    ),
                    com.aktcl.aron.core.database.entity.GeoFixEntity(ClientIds.newUuid(), v, "visit_open", "ok", 23.8, 90.5, 8.0, provider = "fused", fixTime = "2026-10-17T04:00:00.000Z", fixElapsedRealtimeMs = 900, fixAgeMs = 10, timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false),
                )
            }
            assertEquals(rows, payloads("visit").filter { it.contains(v) })
        }
    }

    /**
     * M3 (lead gate on f35466e0): the real path reason -> override photo -> visit, with a FAKE photo port (no camera). The controller, the visit flow
     * and the committer are the production ones; only the fix source and the photo pipeline are faked. The chosen reason must land on the stored visit
     * as force_reason_code, the photo must be claimed once, and replaying the commit (a kill after the commit) must not queue a second visit.
     */
    @Test fun reasonThenOverridePhotoThenVisitStoresTheChosenReasonAndAReplayAddsNoSecondVisit() = runBlocking {
        for (reason in listOf(com.aktcl.aron.feature.outlet.ForceReason.INTERNET_PROBLEM, com.aktcl.aron.feature.outlet.ForceReason.LOCATION_CHANGE)) {
            val outlet = com.aktcl.aron.feature.outlet.VisitOutlet(50002, route, "Rahim Store", "DHK-1", 23.79, 90.40, "master", 100, 50)
            val far = FixReading("ok", 23.80, 90.50, 8.0, false)
            val fixes = object : com.aktcl.aron.feature.outlet.LocationFixSource {
                override suspend fun readFix(purpose: String, refreshCount: Int) = far
            }
            val photos = object : com.aktcl.aron.feature.outlet.PhotoPipeline {
                override suspend fun captureAndCompress(photoUuid: String) = com.aktcl.aron.feature.outlet.CapturedPhoto(photoUuid, "/fake.jpg", 1)
                override suspend fun discard(photoUuid: String) = Unit
            }
            var committed: VisitEntity? = null; var committedFix: com.aktcl.aron.core.database.entity.GeoFixEntity? = null
            val flow = com.aktcl.aron.feature.outlet.VisitFlow(
                fixes, metaProvider,
                { v, f -> claims += listOf(v.geoForcePhotoUuid.orEmpty(), "force_sale", v.clientUuid, f.clientUuid); capture.recordVisitOpen(v, f); committed = v; committedFix = f },
                com.aktcl.aron.feature.outlet.VisitSession(), { com.aktcl.aron.feature.outlet.GeoSettings.DEFAULT }, { ClientIds.newUuid() }, { "2026-10-17T04:00:00.000Z" }, { 1 },
            )
            val cap = com.aktcl.aron.feature.outlet.GeoPhotoCapture(fixes, photos, "outlet_capture")
            flow.open(outlet); repeat(3) { flow.refresh() }
            cap.shutter()
            val controller = com.aktcl.aron.feature.outlet.ForceSaleController(flow, cap, com.aktcl.aron.feature.outlet.OutletRequests({ }), { true })
            val res = controller.confirm(reason, outlet)
            assertTrue("started, was $res", res is com.aktcl.aron.feature.outlet.ForceSaleResult.Started)
            val v = committed!!
            assertEquals(reason.code, v.geoForceReasonCode)
            assertEquals("force_sale", v.geoAction)
            val rows = payloads("visit").filter { it.contains(v.clientUuid) }
            assertEquals(1, rows.size)
            assertTrue(rows[0].contains("\"force_reason_code\":\"${reason.code}\""))
            assertTrue("contract 1.11.1: the top-level force_sale_reason carries the same user reason", rows[0].contains("\"force_sale_reason\":\"${reason.code}\""))
            assertEquals(1, claims.count { it[0] == v.geoForcePhotoUuid && it[0].isNotEmpty() }) // the override photo is claimed once
            // a second confirm (a double tap) is refused: the flow is already open
            assertTrue(controller.confirm(reason, outlet) is com.aktcl.aron.feature.outlet.ForceSaleResult.NotAvailable)
            relaunch()
            runCatching { capture.recordVisitOpen(v, committedFix!!) } // the same visit committed again after a kill
            assertEquals(rows, payloads("visit").filter { it.contains(v.clientUuid) })
        }
    }

    /** The no-outlet-location reason is a geo field only; a non-force visit never carries force_sale_reason. */
    @Test fun noOutletLocationAndNonForceVisitsCarryNoForceSaleReason() = runBlocking {
        val v = forceSaleVisit("no_outlet_location")
        val row = payloads("visit").single { it.contains(v) }
        assertEquals(true, row.contains("\"force_reason_code\":\"no_outlet_location\""))
        assertEquals(false, row.contains("force_sale_reason"))
        val plain = visit()
        assertEquals(false, payloads("visit").single { it.contains(plain) }.contains("force_sale_reason"))
    }
}

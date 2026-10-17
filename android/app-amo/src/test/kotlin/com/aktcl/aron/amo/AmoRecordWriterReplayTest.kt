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
import com.aktcl.aron.feature.amo.AssignedSr
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
import com.aktcl.aron.feature.stock.StockAdjustments
import com.aktcl.aron.feature.stock.StockTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        writer = AmoRecordWriter(db, capture, metaProvider, { route }, { ClientIds.newUuid() }, { syncs++ }, { ok }, { _, _, _, _, _ -> })
    }

    @Before fun start() = open()
    @After fun stop() { databases.close(user) }

    /** The process dies: the database handle and every host object are gone; the next launch opens the same file again. */
    private fun relaunch() { databases.close(user); databases.allowOpen(user); open() }

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

    @Test fun aFailureInTheMiddleOfAnAssessmentRollsBackEverything() = runBlocking {
        val v = visit(); val base = outbox().size
        failMetaAt = metaCalls + 2
        assertTrue(runCatching { writer.saveAssessment(v, brands, PerfState(present = setOf(1L, 2L), oos = setOf(2L), posm = true)) }.isFailure)
        failMetaAt = -1; relaunch()
        assertEquals(base, outbox().size)
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
        assertFalse("the same form uuid after the kill queues nothing", writer.assignTask(u, "stock_check", 7L, 50001L, "Stock check", null, "2026-10-18"))
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

    // ---------- stock correction ----------
    @Test fun aStockCorrectionIsOneAdjustmentAndASecondSavePostsNothing() = runBlocking {
        val issue = StockMovementEntity(ClientIds.newUuid(), metaProvider.meta(0L), "issue", 100, 100, "stick", 1, 100, null, false)
        capture.recordStock(listOf(issue))
        suspend fun correct(right: Long) {
            val current = StockTracker.of(db, date)[100]?.current ?: 0L // a fresh read, as the screen does
            StockAdjustments.correctTotal(100, current, right, "count_error", "stick", metaProvider.meta(0L))?.let { capture.recordStock(listOf(it)) }
        }
        correct(80); val rows = payloads("stock_movement"); assertEquals(2, rows.size)
        relaunch(); correct(80) // the same Save again after the kill: the balance is already 80
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
        assertTrue("a replay is refused while the day stands submitted", runCatching { submitter().queue(date, gate, counts) }.isFailure)
        assertEquals(rows, payloads("day_submit")); assertTrue(capture.submitStands(date))
    }

    // ---------- the sale (SR stack on the AMO's stock) ----------
    @Test fun aReplayedSaleWithTheSameMemoUuidIsRefusedAndLeavesOneMemo() = runBlocking {
        val v = visit()
        val memo = com.aktcl.aron.core.database.entity.MemoEntity(
            clientUuid = ClientIds.newUuid(), meta = metaProvider.meta(route), visitClientUuid = v, outletId = 50001, memoNo = "amo5001-261017-001", memoKind = "sale",
            committedAt = "2026-10-17T04:05:00.000Z", priceListDate = date, priceType = "outlet", grossMtk = 1000, offerDiscountMtk = 0, drpDiscountMtk = 0,
            qcDeductionMtk = 0, roundAdjMtk = 0, netMtk = 1000, paidMtk = 1000, dueMtk = 0, isCredit = false, lineCount = 1, discountLineCount = 0, qcLineCount = 0,
            offerVersionIdsJson = "[]",
        )
        val line = com.aktcl.aron.core.database.entity.MemoLineEntity(ClientIds.newUuid(), metaProvider.meta(route), memo.clientUuid, 1, 100, "sale", 1, "pack", 1, 1, "outlet", date, 1000, 1, 1000)
        val sale = com.aktcl.aron.core.database.repo.SaleCapture(memo, listOf(line), emptyList(), emptyList())
        capture.recordSale(sale)
        val rows = payloads("memo") + payloads("memo_line"); assertEquals(2, rows.size)
        relaunch()
        assertTrue("the replay is refused, not doubled", runCatching { capture.recordSale(sale) }.isFailure)
        assertEquals(rows, payloads("memo") + payloads("memo_line")); assertNull(null)
    }
}

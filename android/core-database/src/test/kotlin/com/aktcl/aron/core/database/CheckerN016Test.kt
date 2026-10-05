package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Independent checker for N-016. Every test here encodes a defect; they are expected to FAIL on 6dc8488. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerN016Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
    }
    @After fun tearDown() = db.close()

    private fun payloadOf(type: String): JsonObject = runBlocking {
        Json.parseToJsonElement(db.outboxDao().nextPending(100).first { it.recordType == type }.payloadJson).jsonObject["payload"]!!.jsonObject
    }

    // D1 (blocker): a fix without a position (timeout / location off / permission denied) drops the REQUIRED
    // members lat, lng and accuracy_m (GeoFix.required) because RecordMapping.json has explicitNulls = false.
    // The server is strict (s3.1 item 2) -> 400/schema_invalid, a final non-retryable rejection of the record.
    @Test
    fun aFixWithoutAPositionStillCarriesEveryRequiredGeoFixMember() = runTest {
        val (att, f) = TestRows.attendance()
        repo.recordAttendance(att, f.copy(fixStatus = "timeout", lat = null, lng = null, accuracyM = null, gnssJson = null))
        val fix = payloadOf("attendance_event")["fix"]!!.jsonObject
        val missing = ContractYaml.requiredNames("GeoFix") - fix.keys
        assertTrue("GeoFix required members missing from the outbox payload: $missing", missing.isEmpty())
    }

    @Test
    fun aVisitOpenedWithoutAFixPositionCarriesEveryRequiredGeoFixMember() = runTest {
        val (visit, f) = TestRows.visit()
        repo.recordVisitOpen(
            visit.copy(geoVerdict = "no_fix", geoDistanceM = null, geoAction = "force_sale", geoForceReasonCode = "gps_unavailable"),
            f.copy(fixStatus = "location_off", lat = null, lng = null, accuracyM = null, gnssJson = null),
        )
        val fix = payloadOf("visit")["fix"]!!.jsonObject
        val missing = ContractYaml.requiredNames("GeoFix") - fix.keys
        assertTrue("GeoFix required members missing from the visit payload: $missing", missing.isEmpty())
    }

    // D2 (minor): route_id is required on visit-family records (docs/24 s4.3, RecordEnvelope.route_id description),
    // and client_uuid must be a lower-case UUID v4 (Uuid pattern). The repository commits and enqueues both violations.
    @Test
    fun aVisitFamilyRecordWithoutRouteIdIsRefused() = runTest {
        val (visit, f) = TestRows.visit()
        try {
            repo.recordVisitOpen(visit.copy(meta = TestRows.meta(routeId = null)), f)
            fail("a visit without route_id was committed: ${db.outboxDao().nextPending(1).single().payloadJson.take(200)}")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun aNonContractClientUuidIsRefused() = runTest {
        val upper = ClientIds.newUuid().uppercase()
        val (visit, f) = TestRows.visit(uuid = upper)
        try {
            repo.recordVisitOpen(visit, f)
            fail("client_uuid $upper (fails the contract Uuid pattern) was committed to the outbox")
        } catch (_: IllegalArgumentException) { }
    }

    // D3 (minor): applyAck takes any state string. A Day-2 worker passing the server's ack status ("accepted",
    // "duplicate") strands the row in a state that is neither pending (never resent) nor acked (never purged, never
    // counted as stored). No CHECK constraint on outbox.state either.
    @Test
    fun applyAckRefusesAStateOutsideTheOutboxStateMachine() = runTest {
        val (visit, f) = TestRows.visit()
        repo.recordVisitOpen(visit, f)
        val changed = runCatching { db.outboxDao().applyAck(visit.clientUuid, "accepted", null, 1, "2026-10-05T05:00:00.000Z") }.getOrDefault(0)
        val state = db.outboxDao().byClientUuid(visit.clientUuid)!!.state
        assertTrue("outbox row moved to non-state '$state' (changed=$changed)", state in OutboxState.ALL)
    }

    // D4 (minor): a memo that references an edit fix (edit_fix_client_uuid) is committed without that fix; the memo
    // row points at a geo_fix that does not exist and the payload silently omits edit_fix.
    @Test
    fun aMemoReferencingAnEditFixCannotBeCommittedWithoutIt() = runTest {
        val (visit, f) = TestRows.visit()
        repo.recordVisitOpen(visit, f)
        val sale = TestRows.sale(visit.clientUuid)
        val edited = sale.copy(memo = sale.memo.copy(editFixClientUuid = ClientIds.newUuid(), supersedesClientUuid = ClientIds.newUuid(), editReasonCode = "qty_wrong"))
        try {
            repo.recordSale(edited)
            fail("memo committed with edit_fix_client_uuid but no edit fix; payload: ${payloadOf("memo").keys}")
        } catch (_: IllegalArgumentException) { }
    }

    // D5 (minor): QC lines committed in the memo transaction are not checked to belong to that memo; a line with a
    // foreign (or null) memo_client_uuid and applied_to_memo = true is enqueued and will make the server's
    // qc_deduction check fail (arithmetic_mismatch quarantine) or attach to another memo.
    @Test
    fun qcLinesCommittedWithAMemoMustReferenceThatMemo() = runTest {
        val (visit, f) = TestRows.visit()
        repo.recordVisitOpen(visit, f)
        val sale = TestRows.sale(visit.clientUuid)
        val bad = sale.copy(qcLines = sale.qcLines.map { it.copy(memoClientUuid = ClientIds.newUuid()) })
        try {
            repo.recordSale(bad)
            fail("QC line with a foreign memo_client_uuid committed with the memo")
        } catch (_: IllegalArgumentException) { }
    }

    // ---- second re-check (b35af37) ----
    @Test
    fun anEditedMemoWithoutEditFixOrReasonIsRefused() = runTest {
        val (visit, f) = TestRows.visit()
        repo.recordVisitOpen(visit, f)
        val sale = TestRows.sale(visit.clientUuid)
        val edit = sale.copy(memo = sale.memo.copy(supersedesClientUuid = ClientIds.newUuid(), editReasonCode = null, editFixClientUuid = null))
        try {
            repo.recordSale(edit)
            fail("edited memo committed without edit_fix and edit_reason_code")
        } catch (_: IllegalArgumentException) { }
    }
    @Test
    fun aMemoForAnotherOutletThanItsVisitIsRefused() = runTest {
        val (visit, f) = TestRows.visit(outletId = 50001)
        repo.recordVisitOpen(visit, f)
        val sale = TestRows.sale(visit.clientUuid)
        try {
            repo.recordSale(sale.copy(memo = sale.memo.copy(outletId = 50002)))
            fail("memo committed for outlet 50002 inside a visit to outlet 50001")
        } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
}

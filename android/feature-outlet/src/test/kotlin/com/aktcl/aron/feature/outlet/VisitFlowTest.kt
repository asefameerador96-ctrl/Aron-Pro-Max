package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.rules.MockPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** F-SR-017 / F-SR-019: opens the visit and runs the geo check with no network; every case is offline by construction. */
class VisitFlowTest {
    private class FakeFixes(private val queue: ArrayDeque<FixReading>) : LocationFixSource {
        var reads = 0
        val purposes = mutableListOf<String>()
        override suspend fun readFix(purpose: String): FixReading {
            reads++
            purposes += purpose
            return queue.removeFirst()
        }
    }

    private class Recorder : VisitCommitter {
        val visits = mutableListOf<Pair<VisitEntity, GeoFixEntity>>()
        override suspend fun commit(visit: VisitEntity, fix: GeoFixEntity) { visits += visit to fix }
    }

    private val outlet = VisitOutlet(
        outletId = 50001, routeId = 10231, name = "Rahim Store", code = "DHK-344-003",
        lat = 23.7938, lng = 90.4042, locationBasis = "master", radiusM = 100, maxAccuracyM = 50,
    )

    private fun fix(lat: Double = 23.7938, lng: Double = 90.4042, acc: Double? = 10.0, mock: Boolean = false, status: String = "ok") =
        FixReading(status = status, lat = if (status == "ok") lat else null, lng = if (status == "ok") lng else null, accuracyM = acc, isMock = mock)

    private fun flow(fixes: List<FixReading>, settings: GeoSettings = GeoSettings.DEFAULT, rec: Recorder = Recorder(), session: VisitSession = VisitSession()): Triple<VisitFlow, Recorder, FakeFixes> {
        val src = FakeFixes(ArrayDeque(fixes))
        val meta = CaptureMetaProvider { r ->
            CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1000, 3, 0, true, r, null, "2026-10-07:1", false, 5)
        }
        var seq = 0
        val f = VisitFlow(src, meta, rec, session, { settings }, { UUID.randomUUID().toString() }, { "2026-10-07T04:00:00.000Z" }, { ++seq })
        return Triple(f, rec, src)
    }

    private fun farLat() = 23.7938 + 0.01 // about 1.1 km

    @Test fun inRangeOpensTheVisitAtOnceWithOneFix() = runTest {
        val (f, rec, src) = flow(listOf(fix()))
        val st = f.open(outlet)
        assertTrue(st is VisitUiState.Open)
        assertEquals(1, src.reads)
        assertEquals(1, rec.visits.size)
        val (v, gf) = rec.visits[0]
        assertEquals("in_range", v.geoVerdict)
        assertEquals("sale_allowed", v.geoAction)
        assertEquals(gf.clientUuid, v.fixClientUuid)
        assertEquals(v.clientUuid, gf.ownerClientUuid)
        assertEquals(0, gf.refreshCount)
        assertTrue((st as VisitUiState.Open).visit.geoValidated)
    }

    @Test fun outOfRangeCommitsNothingAndOffersRefresh() = runTest {
        val (f, rec, _) = flow(listOf(fix(lat = farLat())))
        val st = f.open(outlet) as VisitUiState.NeedsDecision
        assertEquals("out_of_range", st.result.verdict.wire)
        assertTrue(st.refreshLeft)
        assertFalse(st.forceSaleAvailable)
        assertTrue(rec.visits.isEmpty())
    }

    @Test fun refreshReEvaluatesAndCanFlipToInRange() = runTest {
        val (f, rec, src) = flow(listOf(fix(lat = farLat()), fix()))
        f.open(outlet)
        val st = f.refresh()
        assertTrue(st is VisitUiState.Open)
        assertEquals(listOf("visit_open", "refresh"), src.purposes)
        assertEquals(1, rec.visits[0].second.refreshCount)
    }

    @Test fun refreshIsCappedAtRefreshMaxThenForceSaleOpens() = runTest {
        val far = fix(lat = farLat())
        val (f, rec, src) = flow(List(4) { far })
        var st = f.open(outlet)
        repeat(3) { st = f.refresh() }
        st as VisitUiState.NeedsDecision
        assertFalse(st.refreshLeft)
        assertTrue(st.forceSaleAvailable)
        assertEquals(4, src.reads)
        assertEquals(st, f.refresh()) // capped: no further read
        assertEquals(4, src.reads)
        assertTrue(rec.visits.isEmpty())
    }

    @Test fun forceSaleNeedsReasonAndPhotoAndRecordsThem() = runTest {
        val far = fix(lat = farLat())
        val (f, rec, _) = flow(List(4) { far })
        f.open(outlet); repeat(3) { f.refresh() }
        val photo = UUID.randomUUID().toString()
        val st = f.forceSale("location_change", photo) as VisitUiState.Open
        val v = rec.visits.single().first
        assertEquals("force_sale", v.geoAction)
        assertEquals("out_of_range", v.geoVerdict)
        assertEquals("location_change", v.geoForceReasonCode)
        assertEquals(photo, v.geoForcePhotoUuid)
        assertEquals("force_sale", rec.visits.single().second.purpose)
        assertTrue(st.visit.photoValidated)
        assertFalse(st.visit.geoValidated)
    }

    @Test fun forceSaleWithoutPhotoIsRefused() = runTest {
        val far = fix(lat = farLat())
        val (f, rec, _) = flow(List(4) { far })
        f.open(outlet); repeat(3) { f.refresh() }
        val e = runCatching { f.forceSale("internet_problem", null) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(rec.visits.isEmpty())
    }

    @Test fun forceSaleBeforeRefreshesAreUsedUpIsRefused() = runTest {
        val (f, _, _) = flow(listOf(fix(lat = farLat())))
        f.open(outlet)
        assertTrue(runCatching { f.forceSale("internet_problem", UUID.randomUUID().toString()) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun mockedFixIsNeverValid() = runTest {
        val (f, rec, _) = flow(listOf(fix(mock = true))) // at the outlet's own coordinates
        val st = f.open(outlet) as VisitUiState.NeedsDecision
        assertEquals("mocked", st.result.verdict.wire)
        assertTrue(st.forceSaleAvailable)
        assertTrue(rec.visits.isEmpty())
    }

    @Test fun mockBlockPolicyRecordsTheVisitAndBlocks() = runTest {
        val s = GeoSettings.DEFAULT.copy(policyBase = GeoSettings.DEFAULT.policyBase.copy(mockPolicy = MockPolicy.BLOCK_SALE))
        val session = VisitSession()
        val (f, rec, _) = flow(listOf(fix(mock = true)), s, session = session)
        assertTrue(f.open(outlet) is VisitUiState.Blocked)
        assertEquals("blocked", rec.visits.single().first.geoAction)
        assertNull(session.current.value)
    }

    @Test fun noFixTimeoutOffersRefreshThenForce() = runTest {
        val (f, _, _) = flow(List(4) { fix(status = "timeout", acc = null) })
        var st = f.open(outlet) as VisitUiState.NeedsDecision
        assertEquals("no_fix", st.result.verdict.wire)
        repeat(3) { st = f.refresh() as VisitUiState.NeedsDecision }
        assertTrue(st.forceSaleAvailable)
    }

    @Test fun noOutletLocationForcesSaleByPolicy() = runTest {
        val (f, _, _) = flow(listOf(fix()))
        val st = f.open(outlet.copy(lat = null, lng = null, locationBasis = "none")) as VisitUiState.NeedsDecision
        assertEquals("no_outlet_location", st.result.verdict.wire)
        assertTrue(st.forceSaleAvailable)
    }

    @Test fun openVisitIsPublishedForTheSaleScreens() = runTest {
        val session = VisitSession()
        val (f, rec, _) = flow(listOf(fix()), session = session)
        f.open(outlet)
        assertEquals(rec.visits.single().first.clientUuid, session.current.value?.visitUuid)
        session.close()
        assertNull(session.current.value)
    }

    @Test fun visitRecordUsesTheOutletsResolvedRadiusAndBasis() = runTest {
        val (f, rec, _) = flow(listOf(fix()))
        f.open(outlet)
        val v = rec.visits.single().first
        assertEquals(100, v.geoRadiusMUsed)
        assertEquals(50, v.geoMaxAccuracyMUsed)
        assertEquals("master", v.geoLocationBasis)
        assertEquals(1, v.sequenceNo)
    }

    @Test fun permissionDeniedAndLocationOffBlockWithNoForceSale() = runTest {
        listOf("permission_denied", "location_off").forEach { st ->
            val (f, rec, _) = flow(List(4) { fix(status = st, acc = null) })
            val r = f.open(outlet)
            assertTrue(r is VisitUiState.LocationBlocked); assertTrue(rec.visits.isEmpty())
            assertTrue(runCatching { f.forceSale("internet_problem", UUID.randomUUID().toString()) }.isFailure)
        }
    }

    @Test fun readingFixIsShownDuringTheRead() = runTest {
        val seen = mutableListOf<VisitUiState>()
        lateinit var fl: VisitFlow
        val src = object : LocationFixSource { override suspend fun readFix(purpose: String): FixReading { seen += fl.state.value; return fix() } }
        val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, r, null, "2026-10-07:1", false, 5) }
        fl = VisitFlow(src, meta, Recorder(), VisitSession(), { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "x" }, { 1 })
        fl.open(outlet)
        assertEquals(VisitUiState.ReadingFix, seen.single())
    }

    @Test fun concurrentForceSaleCommitsOnce() = runTest {
        val far = fix(lat = farLat())
        val (f, rec, _) = flow(List(4) { far })
        f.open(outlet); repeat(3) { f.refresh() }
        val photo = UUID.randomUUID().toString()
        val a = async { runCatching { f.forceSale("location_change", photo) } }
        val b = async { runCatching { f.forceSale("location_change", photo) } }
        a.await(); b.await()
        assertEquals(1, rec.visits.size)
    }

    @Test fun concurrentRefreshRespectsTheCap() = runTest {
        val far = fix(lat = farLat())
        val (f, _, src) = flow(List(10) { far })
        f.open(outlet); repeat(2) { f.refresh() } // one refresh left
        val a = async { runCatching { f.refresh() } }
        val b = async { runCatching { f.refresh() } }
        a.await(); b.await()
        assertEquals(4, src.reads)
    }

    @Test fun photoIdMustBeAUuid() = runTest {
        val far = fix(lat = farLat())
        val (f, rec, _) = flow(List(4) { far })
        f.open(outlet); repeat(3) { f.refresh() }
        assertTrue(runCatching { f.forceSale("location_change", "photo-1") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(rec.visits.isEmpty())
    }

    @Test fun committerFailureIsRecoverableAndRetryReusesTheIds() = runTest {
        var fail = true
        val seen = mutableListOf<Pair<VisitEntity, GeoFixEntity>>()
        val committer = VisitCommitter { v, g -> seen += v to g; if (fail) error("disk") }
        val src = FakeFixes(ArrayDeque(listOf(fix())))
        val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, r, null, "2026-10-07:1", false, 5) }
        val session = VisitSession()
        val f = VisitFlow(src, meta, committer, session, { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "x" }, { 1 })
        assertTrue(f.open(outlet) is VisitUiState.CommitFailed); assertNull(session.current.value)
        fail = false
        assertTrue(f.retryCommit() is VisitUiState.Open)
        assertEquals(seen[0].first.clientUuid, seen[1].first.clientUuid); assertEquals(seen[0].second.clientUuid, seen[1].second.clientUuid)
    }

    @Test fun blockedVisitIsHandedToTheCloser() = runTest {
        val s = GeoSettings.DEFAULT.copy(policyBase = GeoSettings.DEFAULT.policyBase.copy(mockPolicy = MockPolicy.BLOCK_SALE))
        var closed: OpenVisit? = null
        val src = FakeFixes(ArrayDeque(listOf(fix(mock = true))))
        val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, r, null, "2026-10-07:1", false, 5) }
        val f = VisitFlow(src, meta, Recorder(), VisitSession(), { s }, { UUID.randomUUID().toString() }, { "x" }, { 1 }, { closed = it })
        f.open(outlet)
        assertEquals("blocked", closed?.geoAction)
    }

    @Test fun openingASecondOutletWhileOneIsOpenIsRefused() = runTest {
        val (f, _, _) = flow(listOf(fix(), fix()))
        f.open(outlet)
        assertTrue(runCatching { f.open(outlet.copy(outletId = 2)) }.exceptionOrNull() is IllegalStateException)
    }

    private class MemStore : OpenVisitStore {
        val rows = mutableMapOf<String, OpenVisitRow>()
        override suspend fun begin(visitUuid: String, outletId: Long, routeId: Long, startedAtIso: String) { rows[visitUuid] = OpenVisitRow(visitUuid, outletId, routeId, startedAtIso) }
        override suspend fun findOpen(businessDate: String) = rows.values.firstOrNull()
        override suspend fun clear(visitUuid: String) { rows.remove(visitUuid) }
    }

    @Test fun openRowExistsAtOnceWithNoOutboxRecordAndSurvivesARelaunch() = runTest {
        val store = MemStore(); val rec = Recorder()
        val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, r, null, "2026-10-07:1", false, 5) }
        val far = fix(lat = farLat())
        val src = FakeFixes(ArrayDeque(listOf(far, fix())))
        val f1 = VisitFlow(src, meta, rec, VisitSession(), { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "x" }, { 1 }, openStore = store)
        f1.open(outlet) // out of range: not final
        assertEquals(1, store.rows.size); assertTrue(rec.visits.isEmpty())
        // kill and relaunch: a new flow finds the OPEN row and continues with the same visit uuid
        val f2 = VisitFlow(src, meta, rec, VisitSession(), { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "x" }, { 1 }, openStore = store)
        val row = f2.pendingOpenRow("2026-10-07")!!
        assertTrue(f2.resume(row, outlet) is VisitUiState.Open)
        assertEquals(row.visitUuid, rec.visits.single().first.clientUuid)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun configCheckRunsOnRefreshAndNeverBlocksEvenWhenItThrows() = runTest {
        var calls = 0
        val far = fix(lat = farLat())
        val src = FakeFixes(ArrayDeque(listOf(far, far)))
        val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, r, null, "2026-10-07:1", false, 5) }
        val f = VisitFlow(src, meta, Recorder(), VisitSession(), { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "x" }, { 1 }, configCheck = { calls++; error("offline") })
        f.open(outlet); assertTrue(f.refresh() is VisitUiState.NeedsDecision)
        assertEquals(1, calls)
    }
}

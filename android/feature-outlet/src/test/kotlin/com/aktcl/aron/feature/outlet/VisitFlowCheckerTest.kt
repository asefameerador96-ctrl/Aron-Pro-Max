package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Independent checker (docs/26 s4) for F-SR-017 / F-SR-019: each case is expected to FAIL on the current VisitFlow. */
class VisitFlowCheckerTest {
    private val outlet = VisitOutlet(
        outletId = 50001, routeId = 10231, name = "Rahim Store", code = "DHK-344-003",
        lat = 23.7938, lng = 90.4042, locationBasis = "master", radiusM = 100, maxAccuracyM = 50,
    )
    private val meta = CaptureMetaProvider { r ->
        CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1000, 3, 0, true, r, null, "2026-10-07:1", false, 5)
    }
    private fun far() = FixReading("ok", 23.7938 + 0.01, 90.4042, 10.0, false)
    private fun near() = FixReading("ok", 23.7938, 90.4042, 10.0, false)

    private class Rec(val gate: CompletableDeferred<Unit>? = null, var failNext: Boolean = false) : VisitCommitter {
        val visits = mutableListOf<Pair<VisitEntity, GeoFixEntity>>()
        override suspend fun commit(visit: VisitEntity, fix: GeoFixEntity) {
            gate?.await()
            if (failNext) { failNext = false; throw IllegalStateException("db error") }
            visits += visit to fix
        }
    }

    private class Src(val next: () -> FixReading) : LocationFixSource {
        var reads = 0
        var gate: CompletableDeferred<Unit>? = null
        var onRead: (() -> Unit)? = null
        override suspend fun readFix(purpose: String): FixReading {
            reads++
            onRead?.invoke()
            gate?.await()
            return next()
        }
    }

    private fun flow(src: LocationFixSource, rec: VisitCommitter) =
        VisitFlow(src, meta, rec, VisitSession(), { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "2026-10-07T04:00:00.000Z" }, { 1 })

    /** D-74 / F-SR-018: a denied location permission BLOCKS the sale; a force sale needs a fix. */
    @Test fun permissionDeniedNeverOffersForceSale() = runTest {
        val src = Src { FixReading("permission_denied", null, null, null, false) }
        val f = flow(src, Rec())
        var st = f.open(outlet)
        repeat(3) { if (st is VisitUiState.NeedsDecision && (st as VisitUiState.NeedsDecision).refreshLeft) st = f.refresh() }
        assertFalse("force sale offered with no fix (permission denied)", st is VisitUiState.NeedsDecision && (st as VisitUiState.NeedsDecision).forceSaleAvailable)
    }

    /** A double tap on Force Sale must not commit two visits. */
    @Test fun concurrentForceSaleCommitsOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rec = Rec(gate)
        val f = flow(Src { far() }, rec)
        f.open(outlet); repeat(3) { f.refresh() }
        val photo = UUID.randomUUID().toString()
        launch { runCatching { f.forceSale("location_change", photo) } }
        launch { runCatching { f.forceSale("location_change", photo) } }
        runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals("two visits committed for one force sale", 1, rec.visits.size)
    }

    /** A double tap on Refresh must not exceed cfg.geo.refresh_max (3): at most 1 + 3 reads. */
    @Test fun concurrentRefreshRespectsCap() = runTest {
        val src = Src { far() }
        val f = flow(src, Rec())
        f.open(outlet); f.refresh(); f.refresh() // refreshCount 2, one refresh left
        src.gate = CompletableDeferred()
        launch { runCatching { f.refresh() } }
        launch { runCatching { f.refresh() } }
        runCurrent(); src.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue("reads ${src.reads} exceed 1 + refresh_max", src.reads <= 4)
    }

    /** The UI must see ReadingFix while the fix is being read (to disable Refresh / Force Sale). */
    @Test fun readingFixIsVisibleDuringTheRead() = runTest {
        val src = Src { near() }
        lateinit var f: VisitFlow
        var seen: VisitUiState? = null
        src.onRead = { seen = f.state.value }
        f = flow(src, Rec())
        f.open(outlet)
        assertEquals(VisitUiState.ReadingFix, seen)
    }

    /** A committer failure must not leave the screen stuck in ReadingFix with no way to retry. */
    @Test fun committerFailureDoesNotLeaveReadingFix() = runTest {
        val f = flow(Src { near() }, Rec(failNext = true))
        runCatching { f.open(outlet) }
        assertFalse("stuck in ReadingFix after a failed commit", f.state.value == VisitUiState.ReadingFix)
    }

    /** Contract DeviceGeoVerdict.force_photo_uuid is a Uuid; nothing downstream checks it. */
    @Test fun forcePhotoUuidMustBeAUuid() = runTest {
        val rec = Rec()
        val f = flow(Src { far() }, rec)
        f.open(outlet); repeat(3) { f.refresh() }
        runCatching { f.forceSale("location_change", "photo-1") }
        assertTrue("non-UUID force_photo_uuid committed", rec.visits.isEmpty())
    }
}

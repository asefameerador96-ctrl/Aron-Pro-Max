package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** F-SR-018. */
class ForceSaleTest {
    private val outlet = VisitOutlet(1, 2, "Rahim Store", "DHK-1", 23.7938, 90.4042, "master", 100, 50)
    private val far = FixReading("ok", 23.8038, 90.4042, 10.0, false)
    private val here = FixReading("ok", 23.7938, 90.4042, 8.0, false)
    private val visits = mutableListOf<Pair<VisitEntity, GeoFixEntity>>()
    private val saved = mutableListOf<OutletRequestDraft>()
    private var granted = true
    private var photoOk = true

    private val fixes = object : LocationFixSource {
        var next = far
        override suspend fun readFix(purpose: String, refreshCount: Int) = if (purpose == "outlet_capture") here else next
    }
    private val pipeline = object : PhotoPipeline {
        override suspend fun captureAndCompress(photoUuid: String) = if (photoOk) CapturedPhoto(photoUuid, "/t.jpg", 1) else null
        override suspend fun discard(photoUuid: String) = Unit
    }

    private suspend fun ready(): Triple<ForceSaleController, VisitFlow, GeoPhotoCapture> {
        val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, r, null, "2026-10-07:1", false, 5) }
        val flow = VisitFlow(fixes, meta, { v, f -> visits += v to f }, VisitSession(), { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "x" }, { 1 })
        val cap = GeoPhotoCapture(fixes, pipeline, "outlet_capture", newUuid = { UUID.randomUUID().toString() })
        val reqs = OutletRequests({ saved += it })
        flow.open(outlet); repeat(3) { flow.refresh() }
        return Triple(ForceSaleController(flow, cap, reqs, { granted }), flow, cap)
    }

    @Test fun reasonAndPhotoLetTheSaleProceedWithPhotoValidatedAndNotGeoValidated() = runTest {
        val (c, _, cap) = ready(); cap.shutter()
        val r = c.confirm(ForceReason.INTERNET_PROBLEM, outlet) as ForceSaleResult.Started
        assertTrue(r.visit.photoValidated); assertFalse(r.visit.geoValidated); assertEquals("internet_problem", r.visit.forceReasonCode)
        assertEquals("force_sale", visits.single().first.geoAction); assertFalse(r.locationRequestRaised); assertTrue(saved.isEmpty())
    }

    @Test fun locationChangeRaisesALocationRequestWithTheSamePhotoAndOriginVisit() = runTest {
        val (c, _, cap) = ready(); cap.shutter()
        val r = c.confirm(ForceReason.LOCATION_CHANGE, outlet) as ForceSaleResult.Started
        assertTrue(r.locationRequestRaised)
        val d = saved.single()
        assertEquals(OutletRequestKind.LOCATION, d.kind); assertEquals(r.visit.visitUuid, d.originVisitUuid)
        assertEquals(listOf(cap.state.value.photo!!.photoUuid), d.photoUuids); assertEquals(1L, d.outletId)
    }

    @Test fun missingReasonOrPhotoIsRefusedAndNothingIsCommitted() = runTest {
        val (c, _, _) = ready()
        val r = c.confirm(null, outlet) as ForceSaleResult.Invalid
        assertEquals(setOf(ForceSaleMissing.REASON, ForceSaleMissing.PHOTO), r.missing); assertTrue(visits.isEmpty())
    }

    @Test fun deniedLocationPermissionBlocksTheSale() = runTest {
        val (c, _, cap) = ready(); cap.shutter(); granted = false
        val r = c.confirm(ForceReason.INTERNET_PROBLEM, outlet) as ForceSaleResult.Invalid
        assertTrue(ForceSaleMissing.LOCATION_PERMISSION in r.missing); assertTrue(visits.isEmpty())
    }

    @Test fun forceSaleIsNotAvailableWhileRefreshesRemain() = runTest {
        val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 3, 0, true, r, null, "2026-10-07:1", false, 5) }
        val flow = VisitFlow(fixes, meta, { v, f -> visits += v to f }, VisitSession(), { GeoSettings.DEFAULT }, { UUID.randomUUID().toString() }, { "x" }, { 1 })
        flow.open(outlet)
        val c = ForceSaleController(flow, GeoPhotoCapture(fixes, pipeline, "outlet_capture"), OutletRequests({}), { true })
        assertEquals(ForceSaleResult.NotAvailable, c.confirm(ForceReason.INTERNET_PROBLEM, outlet))
    }
}

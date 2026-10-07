package com.aktcl.aron.feature.outlet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-037, 038, 039, 076, N-040: forms validate and queue offline. */
class OutletRequestTest {
    private val saved = mutableListOf<OutletRequestDraft>()
    private val svc = OutletRequests({ saved += it }, { "00000000-0000-4000-8000-000000000042" })
    private val ok = FixReading("ok", 23.79, 90.40, 8.0, false)
    private val photo = listOf("00000000-0000-4000-8000-000000000001")

    private fun errors(f: OutletRequestForm) = (svc.validate(f) as RequestValidation.Invalid).errors

    @Test fun newShopSavesOfflineWithNoConfirmation() = runTest {
        val f = OutletRequestForm(OutletRequestKind.NEW, name = "Rahim Store", ownerName = "Rahim", mobile = "০১৭১২৩৪৫৬৭৮", clusterId = 7, fix = ok, photoUuids = photo)
        val r = svc.submit(f)
        assertTrue(r is RequestValidation.Ok)
        val d = saved.single()
        assertEquals("01712345678", d.contactNumber); assertEquals("new", d.kind.wire); assertEquals(null, d.outletId)
        assertEquals(7L, d.clusterId); assertEquals("00000000-0000-4000-8000-000000000042", d.requestUuid)
    }

    @Test fun newShopNeedsClusterNameOwnerMobileGeoAndPhoto() {
        val e = errors(OutletRequestForm(OutletRequestKind.NEW, name = "R", mobile = "123"))
        assertTrue(e.containsAll(setOf(RequestError.NAME_INVALID, RequestError.OWNER_INVALID, RequestError.MOBILE_INVALID, RequestError.CLUSTER_REQUIRED, RequestError.GEO_REQUIRED, RequestError.PHOTO_REQUIRED)))
    }

    @Test fun failedFixDoesNotCountAsGeo() {
        val bad = FixReading("timeout", null, null, null, false)
        assertTrue(RequestError.GEO_REQUIRED in errors(OutletRequestForm(OutletRequestKind.NEW, name = "Rahim", ownerName = "Rahim", mobile = "01712345678", clusterId = 1, fix = bad, photoUuids = photo)))
    }

    @Test fun infoChangeNeedsGeoPhotoAndConfirmation() {
        val base = OutletRequestForm(OutletRequestKind.INFO, outletId = 5, name = "Rahim 2", ownerName = "Rahim", mobile = "01712345678", fix = ok, photoUuids = photo)
        assertEquals(setOf(RequestError.CONFIRMATION_REQUIRED), errors(base))
        assertTrue(svc.validate(base.copy(confirmed = true)) is RequestValidation.Ok)
        assertTrue(RequestError.PHOTO_REQUIRED in errors(base.copy(photoUuids = emptyList(), confirmed = true)))
    }

    @Test fun closeAsksConfirmationAndWarnsAboutOpenDues() = runTest {
        val f = OutletRequestForm(OutletRequestKind.CLOSE, outletId = 5, closeReasonCode = "shop_closed", openDueMtk = 125_000)
        assertEquals(setOf(RequestError.CONFIRMATION_REQUIRED), errors(f))
        val r = svc.submit(f.copy(confirmed = true)) as RequestValidation.Ok
        assertTrue(r.warnOpenDues); assertEquals(1, saved.size)
        assertFalse((svc.validate(f.copy(confirmed = true, openDueMtk = 0)) as RequestValidation.Ok).warnOpenDues)
    }

    @Test fun clusterChangeNeedsANewClusterAndAReason() {
        val f = OutletRequestForm(OutletRequestKind.CLUSTER, outletId = 5, clusterId = 3, currentClusterId = 3, confirmed = true)
        assertEquals(setOf(RequestError.CLUSTER_UNCHANGED, RequestError.REASON_REQUIRED), errors(f))
        assertTrue(svc.validate(f.copy(clusterId = 4, note = "moved to Gulshan cluster")) is RequestValidation.Ok)
    }

    @Test fun routeAddNeedsOutletAndNote() {
        assertEquals(setOf(RequestError.OUTLET_REQUIRED, RequestError.REASON_REQUIRED), errors(OutletRequestForm(OutletRequestKind.ROUTE_ADD)))
        assertTrue(svc.validate(OutletRequestForm(OutletRequestKind.ROUTE_ADD, outletId = 9, note = "belongs on my route")) is RequestValidation.Ok)
    }

    @Test fun invalidSubmitQueuesNothing() = runTest {
        svc.submit(OutletRequestForm(OutletRequestKind.NEW)); assertTrue(saved.isEmpty())
    }

    @Test fun limitsAreEnforced() {
        val many = (1..5).map { "00000000-0000-4000-8000-00000000000$it" }
        assertTrue(RequestError.TOO_MANY_PHOTOS in errors(OutletRequestForm(OutletRequestKind.NEW, photoUuids = many)))
        assertTrue(RequestError.NOTE_TOO_LONG in errors(OutletRequestForm(OutletRequestKind.ROUTE_ADD, outletId = 1, note = "x".repeat(501))))
    }
}

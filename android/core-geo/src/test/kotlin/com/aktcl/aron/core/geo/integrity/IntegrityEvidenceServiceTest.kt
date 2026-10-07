package com.aktcl.aron.core.geo.integrity

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityEvidenceServiceTest {
    private val device = "5b0f3c0e-8d4c-4a51-9c63-1f1f2a8e7d10"
    private val nonce = "n".repeat(43)

    @Test fun aTokenIsBoundToTheServerNonceAndTheDevice() = runTest {
        var hashSeen: String? = null
        val s = IntegrityEvidenceService({ nonce }, object : IntegrityTokenSource {
            override suspend fun token(requestHash: String): IntegrityResult { hashSeen = requestHash; return IntegrityResult.Evidence("tok", "") }
        }, { device })
        assertEquals(IntegrityResult.Evidence("tok", nonce), s.collect())
        assertEquals(IntegrityCodec.requestHash(nonce, device), hashSeen)
    }

    @Test fun offlineOrAFailingNonceCallGivesTheOfflineMarker() = runTest {
        val never = object : IntegrityTokenSource { override suspend fun token(requestHash: String) = error("must not be called") }
        assertEquals(IntegrityResult.Unavailable(IntegrityUnavailable.OFFLINE), IntegrityEvidenceService({ null }, never, { device }).collect())
        assertEquals(
            IntegrityResult.Unavailable(IntegrityUnavailable.OFFLINE),
            IntegrityEvidenceService({ throw java.io.IOException("no route") }, never, { device }).collect(),
        )
    }

    @Test fun aPhoneWithoutGoogleServicesSendsTheMarkerInsteadOfFailing() = runTest {
        val noGms = object : IntegrityTokenSource {
            override suspend fun token(requestHash: String) = IntegrityResult.Unavailable(IntegrityUnavailable.NO_PLAY_SERVICES, "gms=1")
        }
        val r = IntegrityEvidenceService({ nonce }, noGms, { device }).collect()
        assertEquals(IntegrityUnavailable.NO_PLAY_SERVICES, (r as IntegrityResult.Unavailable).reason)
    }

    @Test fun refreshIsDueAfterTheConfiguredHoursOrAClockRollback() {
        val h = 3_600_000L
        assertTrue(IntegrityEvidenceService.due(null, 0, 24))
        assertFalse(IntegrityEvidenceService.due(0, 24 * h - 1, 24))
        assertTrue(IntegrityEvidenceService.due(0, 24 * h, 24))
        assertTrue(IntegrityEvidenceService.due(10 * h, 9 * h, 24))
        assertTrue(IntegrityEvidenceService.due(0, 168 * h, 9_999)) // clamped to 168 h
        assertTrue(IntegrityEvidenceService.due(0, h, 0)) // clamped to 1 h
    }

    @Test fun markersHaveStableWireNames() {
        assertEquals(listOf("no_play_services", "not_configured", "offline", "api_error", "timeout"), IntegrityUnavailable.entries.map { it.wire })
    }
}

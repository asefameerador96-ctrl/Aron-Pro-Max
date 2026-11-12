package com.aktcl.aron.sr

import com.aktcl.aron.core.sync.BundleOutcome
import com.aktcl.aron.feature.home.DataUpdatePhase
import com.aktcl.aron.feature.home.FirstBundleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The phase mapping of the SR data-update page (SrApp's first-bundle branch) and its version text. */
class SrDataUpdateTest {
    @Test fun noAnswerYetOrADownloadInFlightIsTheRunningPage() {
        assertEquals(DataUpdatePhase.RUNNING, SrDataUpdate.ui(downloading = false, outcome = null, progress = null).phase) // right after a relaunch
        val r = SrDataUpdate.ui(downloading = true, outcome = BundleOutcome.OFFLINE, progress = 14 to 87)
        assertEquals(DataUpdatePhase.RUNNING, r.phase)
        assertNull(r.reason)
        assertEquals(14, r.stepsDone); assertEquals(87, r.stepCount)
    }

    @Test fun aStoppedAttemptIsThePromptWithItsReason() {
        assertEquals(FirstBundleState.OFFLINE, SrDataUpdate.ui(false, BundleOutcome.OFFLINE, null).reason)
        assertEquals(FirstBundleState.AUTH, SrDataUpdate.ui(false, BundleOutcome.AUTH_REQUIRED, null).reason)
        assertEquals(FirstBundleState.FAILED, SrDataUpdate.ui(false, BundleOutcome.FAILED, null).reason)
        assertEquals(FirstBundleState.FAILED, SrDataUpdate.ui(false, BundleOutcome.NOT_READY, null).reason)
        assertEquals(DataUpdatePhase.PROMPT, SrDataUpdate.ui(false, BundleOutcome.FAILED, null).phase)
    }

    @Test fun theVersionIsTheNameOnlyNeverNameAndCode() {
        assertEquals(BuildConfig.VERSION_NAME, SrDataUpdate.versionName())
        assertFalse(SrDataUpdate.versionName().contains("("))
    }
}

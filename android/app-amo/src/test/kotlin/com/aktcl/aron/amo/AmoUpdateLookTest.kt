package com.aktcl.aron.amo

import com.aktcl.aron.core.system.update.DownloadUi
import org.junit.Assert.assertEquals
import org.junit.Test

class AmoUpdateLookTest {
    @Test fun optionalUpdateIsTheScreen05LookUntilItDownloads() {
        assertEquals(AmoUpdateLook.OPTIONAL_PROMPT, amoUpdateLook(false, DownloadUi.Idle))
        assertEquals(AmoUpdateLook.OPTIONAL_PROMPT, amoUpdateLook(false, DownloadUi.Failed))
    }

    @Test fun mandatoryUpdateIsTheIllustratedScreen07() {
        assertEquals(AmoUpdateLook.MANDATORY_PROMPT, amoUpdateLook(true, DownloadUi.Idle))
        assertEquals(AmoUpdateLook.MANDATORY_PROMPT, amoUpdateLook(true, DownloadUi.NeedsUnknownSources))
    }

    @Test fun anyDownloadInProgressIsTheProcessingScreen() {
        assertEquals(AmoUpdateLook.UPDATING, amoUpdateLook(true, DownloadUi.Downloading(46)))
        assertEquals(AmoUpdateLook.UPDATING, amoUpdateLook(false, DownloadUi.Downloading(0)))
    }
}

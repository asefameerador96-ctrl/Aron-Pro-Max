package com.aktcl.aron.core.system.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SYS-020: only an APK signed like the installed app (or its planned key rotation) and named by the release is installed. */
class SignerRuleTest {
    private val old = "1".repeat(64); private val new = "2".repeat(64); private val evil = "9".repeat(64)
    private fun one(vararg h: String) = Signers(false, h.toList())

    @Test fun sameSignerIsAccepted() = assertTrue(AndroidUpdater.signerAccepted(one(old), one(old), old))
    @Test fun aRotatedKeyWhoseHistoryHoldsTheInstalledSignerIsAccepted() = assertTrue(AndroidUpdater.signerAccepted(one(old), one(old, new), new))
    @Test fun aForeignSignerIsRefused() = assertFalse(AndroidUpdater.signerAccepted(one(old), one(evil), evil))
    @Test fun theReleaseMustNameTheCurrentSigner() = assertFalse(AndroidUpdater.signerAccepted(one(old), one(old, new), old))
    @Test fun aRotatedInstallRefusesAnApkStillOnTheOldKeyOnly() = assertFalse(AndroidUpdater.signerAccepted(one(old, new), one(old), old))
    @Test fun emptySignersAreRefused() {
        assertFalse(AndroidUpdater.signerAccepted(one(), one(old), old))
        assertFalse(AndroidUpdater.signerAccepted(one(old), one(), old))
    }
    @Test fun severalSignersMustMatchExactly() {
        assertTrue(AndroidUpdater.signerAccepted(Signers(true, listOf(old, new)), Signers(true, listOf(new, old)), new))
        assertFalse(AndroidUpdater.signerAccepted(Signers(true, listOf(old, new)), Signers(true, listOf(old, evil)), old))
        assertFalse(AndroidUpdater.signerAccepted(Signers(true, listOf(old, new)), one(old, new), new))
    }
    @Test fun hexCaseOfTheReleaseRecordDoesNotMatter() = assertTrue(AndroidUpdater.signerAccepted(one("a".repeat(64)), one("a".repeat(64)), "A".repeat(64)))
}

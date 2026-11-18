package com.aktcl.aron.core.geo.spoof

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real probes on Robolectric's package manager and AppOps. Real phones are walked separately (docs/status/android-integrity.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSpoofProbesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private class Clock(var el: Long = 1_000) : WallClock {
        override fun nowMs() = 1_800_000_000_000L
        override fun elapsedRealtimeMs() = el
        override fun bootCount() = 1
    }

    private fun install(pkg: String, uid: Int): Int {
        val info = PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg; this.uid = uid } }
        shadowOf(app.packageManager).installPackage(info)
        return uid
    }

    @Test fun nothingInstalledMeansNothingToReport() {
        val f = AndroidSpoofProbes(app, Clock(), listOf("com.lexa.fakegps")).facts()
        assertFalse(f.fakeGpsAppInstalled); assertFalse(f.mockAppSelected)
    }

    @Test fun anInstalledListedAppIsSoftUntilItIsTheSelectedMockApp() {
        val uid = install("com.lexa.fakegps", 10_222)
        // On a real phone the op of an app that is not the selected mock app is MODE_ERRORED (the platform default); Robolectric's default is ALLOWED.
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(58 /* OP_MOCK_LOCATION */, uid, "com.lexa.fakegps", AppOpsManager.MODE_ERRORED)
        val f = AndroidSpoofProbes(app, Clock(), listOf("com.lexa.fakegps")).facts()
        assertTrue(f.fakeGpsAppInstalled); assertFalse("installed but not selected is soft only", f.mockAppSelected)
    }

    @Test fun theSelectedMockAppIsFoundThroughAppOps() {
        val uid = install("com.lexa.fakegps", 10_222)
        val ops = app.getSystemService(AppOpsManager::class.java)
        shadowOf(ops).setMode(58 /* OP_MOCK_LOCATION */, uid, "com.lexa.fakegps", AppOpsManager.MODE_ALLOWED)
        val f = AndroidSpoofProbes(app, Clock(), listOf("com.lexa.fakegps")).facts()
        assertTrue(f.mockAppSelected)
    }

    @Test fun factsAreCachedForTenMinutesAndRereadAfterwards() {
        val clock = Clock()
        val p = AndroidSpoofProbes(app, clock, listOf("com.lexa.fakegps"), ttlMs = 10 * 60_000L)
        assertFalse(p.facts().fakeGpsAppInstalled)
        install("com.lexa.fakegps", 10_222)
        clock.el += 5 * 60_000; assertFalse("inside the TTL the answer is cached", p.facts().fakeGpsAppInstalled)
        clock.el += 6 * 60_000; assertTrue("after the TTL it is read again", p.facts().fakeGpsAppInstalled)
        install("com.lexa.fakegps", 10_222); p.invalidate(); assertTrue(p.facts().fakeGpsAppInstalled)
    }

    private fun fixAt(clock: Clock) = com.aktcl.aron.core.geo.RawLocation(23.80, 90.36, 8.0, provider = "fused", timeMs = clock.nowMs(), elapsedRealtimeNanos = clock.el * 1_000_000L, isMock = false)

    /** Review low: a rep who follows the refusal text (turn off and uninstall the fake-GPS app, open the outlet again) was refused again until the 10-minute cache ran out. */
    @Test fun aRefusalThatRestsOnTheCachedFactsIsNotRepeatedAfterTheRepFixedItWithinTheTtl() {
        val clock = Clock(); val p = AndroidSpoofProbes(app, clock, listOf("com.lexa.fakegps"), ttlMs = 10 * 60_000L)
        val uid = install("com.lexa.fakegps", 10_222)
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(58, uid, "com.lexa.fakegps", AppOpsManager.MODE_ALLOWED)
        val checker = AndroidSpoofChecker(p, PreviousFixStore.Memory(), clock)
        assertTrue("selected mock app: refused", checker.check(fixAt(clock), null, false).codes.contains(SpoofCode.MOCK_APP_SELECTED))
        shadowOf(app.packageManager).removePackage("com.lexa.fakegps")
        clock.el += 60_000 // one minute later, far inside the TTL: the rep went to Settings and uninstalled it
        assertFalse("the next fix sees the change", checker.check(fixAt(clock), null, false).codes.contains(SpoofCode.MOCK_APP_SELECTED))
    }

    @Test fun aSoftSignalAloneDoesNotDropTheCache() {
        val clock = Clock(); val p = AndroidSpoofProbes(app, clock, listOf("com.lexa.fakegps"), ttlMs = 10 * 60_000L)
        val uid = install("com.lexa.fakegps", 10_222) // installed, NOT selected: soft only (a real phone's default op is MODE_ERRORED; Robolectric's is ALLOWED)
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(58, uid, "com.lexa.fakegps", AppOpsManager.MODE_ERRORED)
        val checker = AndroidSpoofChecker(p, PreviousFixStore.Memory(), clock)
        assertTrue(checker.check(fixAt(clock), null, false).codes.contains(SpoofCode.FAKE_GPS_APP_INSTALLED))
        shadowOf(app.packageManager).removePackage("com.lexa.fakegps"); clock.el += 60_000
        assertTrue("no hard code: the cache (and its cost) stays", checker.check(fixAt(clock), null, false).codes.contains(SpoofCode.FAKE_GPS_APP_INSTALLED))
    }

    @Test fun theResourceListIsReadFromTheOneResourceFile() {
        val list = FakeGpsPackages.list(app)
        assertTrue(list.size >= 10); assertTrue("com.lexa.fakegps" in list); assertEquals(list.size, list.toSet().size)
    }

    @Test fun anEmptyOrBrokenLocationServiceNeverThrows() {
        val p = AndroidSpoofProbes(app, Clock(), emptyList())
        p.networkFix(1_000_000_000L) // no provider: null, no exception
        p.facts()
    }

    @Test fun aSelectedMockAppThatIsNotOnTheListIsFoundWhenThePlatformShowsIt() {
        // The package requests ACCESS_MOCK_LOCATION and is visible to this app (what an ordinary app can see without QUERY_ALL_PACKAGES:
        // the manifest <queries> packages and the few the platform always shows). Not on the fake-GPS list, still found.
        val info = PackageInfo().apply {
            packageName = "com.unlisted.mockprovider"; requestedPermissions = arrayOf("android.permission.ACCESS_MOCK_LOCATION")
            applicationInfo = ApplicationInfo().apply { packageName = "com.unlisted.mockprovider"; uid = 10_555 }
        }
        shadowOf(app.packageManager).installPackage(info)
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(58, 10_555, "com.unlisted.mockprovider", AppOpsManager.MODE_ALLOWED)
        assertTrue(AndroidSpoofProbes(app, Clock(), listOf("com.lexa.fakegps")).facts().mockAppSelected)
    }

    @Test fun anInvisibleMockAppIsNotSeenAndNothingIsClaimed() {
        // Not installed from this app's point of view (the platform hides it): no claim either way.
        val f = AndroidSpoofProbes(app, Clock(), listOf("com.lexa.fakegps")).facts()
        assertFalse(f.mockAppSelected); assertFalse(f.fakeGpsAppInstalled)
    }

    @Test fun thePreviousFixInPrefsIsGoneAfterClearAndThePrefsFileHoldsNothing() {
        val store = PrefsPreviousFixStore(app)
        store.save(PreviousFix(23.7925, 90.4078, 123_456)); assertEquals(PreviousFix(23.7925, 90.4078, 123_456), store.load())
        store.clear()
        assertEquals(null, store.load()); assertEquals(null, PrefsPreviousFixStore(app).load())
        assertTrue(app.getSharedPreferences("aron-geo-previous-fix", Context.MODE_PRIVATE).all.isEmpty())
    }
}

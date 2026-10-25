package com.aktcl.aron.feature.attendance

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.LocationFixSource
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-NIGHT-CHECKOUT-01: no check-out without the day's Sales Submit committed on this phone (SR and AMO share this flow and
 * screen; the AMO frame passes the same flow). Offline throughout: the gate is a local read.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckoutNeedsSubmitTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val committed = mutableListOf<Pair<AttendanceEventEntity, GeoFixEntity>>()
    private val fixes = object : LocationFixSource { override suspend fun readFix(purpose: String, refreshCount: Int) = FixReading("ok", 23.79, 90.40, 9.0, false) }
    private val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 1, 0, true, r, null, "2026-10-07:1", false, 5) }

    private fun flow(gate: (suspend () -> Boolean)?) = AttendanceFlow(
        fixes, meta, { e, f -> committed += e to f }, { null }, { "2026-10-07T1${++n % 10}:00:00.000Z" }, { 17 * 60 + 30 },
        newUuid = { "00000000-0000-4000-8000-%012d".format(++n) }, salesSubmitted = gate,
    )

    @Test fun checkOutStaysOffUntilTheLocalSubmitAndThePressReadsItAgain() = runTest {
        var submitted = false
        val f = flow { submitted }
        f.checkIn()
        assertTrue(f.state.value.needsSalesSubmit)
        assertFalse("before the first read: off", f.state.value.checkOutEnabled)
        f.refreshSubmit()
        assertFalse(f.state.value.checkOutEnabled)
        assertTrue(f.checkOut() is AttendanceResult.Ignored) // no bypass, even from a stale screen
        assertEquals(1, committed.size)
        submitted = true // Sales Submit committed locally (offline is enough)
        f.refreshSubmit()
        assertFalse(f.state.value.needsSalesSubmit)
        assertTrue(f.state.value.checkOutEnabled)
        assertTrue(f.checkOut() is AttendanceResult.Done)
        assertEquals("check_out", committed.last().first.kind)
    }

    /** Checker: the gate read suspends; a double hold-complete must still write one check-out. */
    @Test fun aDoublePressWritesOneCheckOut() = runTest {
        val f = flow { kotlinx.coroutines.yield(); true }
        f.checkIn(); f.refreshSubmit()
        val results = listOf(async { f.checkOut() }, async { f.checkOut() }).map { it.await() }
        assertEquals(1, results.count { it is AttendanceResult.Done })
        assertEquals(1, committed.count { it.first.kind == "check_out" })
    }

    @Test fun theCheckOutHintGivesWayToTheBannerAfterTheTime() {
        render(AppLanguage.EN, checkedIn.copy(checkOutTimeReached = true)) {}
        compose.onNodeWithText("Check-out opens at 05:00 PM.").assertDoesNotExist()
    }

    @Test fun aSubmitVoidedAfterTheScreenOpenedStillBlocksThePress() = runTest {
        var submitted = true
        val f = flow { submitted }
        f.checkIn(); f.refreshSubmit()
        assertTrue(f.state.value.checkOutEnabled)
        submitted = false // the server voided the cycle (the day is reopened)
        assertTrue(f.checkOut() is AttendanceResult.Ignored)
        assertTrue(f.state.value.needsSalesSubmit)
    }

    @Test fun aFailingReadKeepsCheckOutOffAndNoGateMeansTheOldRule() = runTest {
        val f = flow { error("db") }
        f.checkIn(); f.refreshSubmit()
        assertFalse(f.state.value.checkOutEnabled)
        assertTrue(f.checkOut() is AttendanceResult.Ignored)
        val tso = flow(null) // a role without the gate
        tso.checkIn(); tso.refreshSubmit()
        assertFalse(tso.state.value.needsSalesSubmit)
        assertTrue(tso.checkOut() is AttendanceResult.Done)
    }

    private fun render(language: AppLanguage, state: AttendanceState, offline: Boolean = false, onOpen: (() -> Unit)?) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { AttendanceContent(state, "17:00", {}, {}, nowIso = { "2026-10-07T11:30:00.000Z" }, onOpenSalesSubmit = onOpen, offline = offline) }
            }
        }
        compose.waitForIdle()
    }

    private val checkedIn = AttendanceState(checkedInAt = "2026-10-07T03:00:00.000Z", showCheckInPrompt = false, checkInEnabled = false, checkOutEnabled = false, needsSalesSubmit = true)

    @Test fun theScreenSaysSalesSubmitFirstInBanglaAndLinksToIt() {
        var opened = 0
        render(AppLanguage.BN, checkedIn) { opened++ }
        compose.onNodeWithText("আগে Sales Submit করুন। আজকের বিক্রি সাবমিট করার পর চেক আউট চালু হবে।").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsNotEnabled()
        compose.onNodeWithText("Sales Submit খুলুন").performClick()
        assertEquals(1, opened)
    }

    @Test fun englishTooAndTheNoticeGoesOnceSubmitted() {
        render(AppLanguage.EN, checkedIn) {}
        compose.onNodeWithText("Sales Submit first. Check-out opens once today's sales are submitted.").assertExists()
        compose.onNodeWithText("Open Sales Submit").assertExists()
    }

    /** D-NIGHT-CHECKOUT-02: offline the notice says why; check-out waits for the network (no bypass). */
    @Test fun offlineTheNoticeSaysWhy() {
        render(AppLanguage.BN, checkedIn, offline = true) {}
        compose.onNodeWithText("অফলাইন — Sales Submit হয়নি। নেটওয়ার্ক ফিরলেই ডেটা নিজে থেকে যাবে; তারপর সাবমিট করে চেক আউট করুন।").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsNotEnabled()
    }

    @Test fun afterSubmitTheNoticeIsGoneAndCheckOutIsOn() {
        render(AppLanguage.EN, checkedIn.copy(needsSalesSubmit = false, checkOutEnabled = true)) {}
        compose.onNodeWithTag(AttendanceTags.SUBMIT_FIRST).assertDoesNotExist()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsEnabled()
    }
}

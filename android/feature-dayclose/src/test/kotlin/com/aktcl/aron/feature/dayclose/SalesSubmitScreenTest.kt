package com.aktcl.aron.feature.dayclose

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SalesSubmitScreenTest {
    @get:Rule val compose = createComposeRule()
    private val clean = OutboxStates(0, 0, 40, 0, 0)

    @Test fun enabledAfterSyncWarnsOnDuesAndSubmitsOnce() {
        var submitted = 0
        val counts = listOf(CountRow("memo", 6, 6))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(2, 125_000), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { it }, {}, { submitted++ }) } }
        compose.onNodeWithText("Online").assertExists()
        compose.onNodeWithText("2 retailers still owe 125.00 ৳. You can still submit.").assertExists()
        // the tap opens the hold step (screens-sr s11); only the press-and-hold commits
        compose.onNodeWithText("Submit sales").assertIsEnabled().performClick()
        compose.waitForIdle()
        // SS4: with dues the manual asks first in a Yes / No dialog; No leaves the day open, Yes opens the hold step
        compose.onNodeWithText("2 retailers still owe you. Do you want to submit your sales?").assertExists()
        compose.onNodeWithText("No").performClick(); compose.waitForIdle()
        compose.onNodeWithTag(SalesSubmitTags.HOLD).assertDoesNotExist()
        compose.onNodeWithText("Submit sales").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Yes").performClick(); compose.waitForIdle()
        assertEquals(0, submitted)
        compose.onNodeWithTag(SalesSubmitTags.HOLD).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(1, submitted)
        compose.onNodeWithTag(SalesSubmitTags.HOLD).assertDoesNotExist()
    }

    @Test fun blankServerColumnBlocksAndNamesTheMismatch() {
        val counts = listOf(CountRow("memo", 6, null))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { it }, {}, {}) } }
        compose.onNodeWithText("Submit sales").assertIsNotEnabled()
        compose.onNodeWithText("memo: device 6, server —.").assertExists()
    }

    @Test fun settledShowsTheSuccessStateInsteadOfTheTable() {
        val counts = listOf(CountRow("memo", 6, 6))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), true)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Settled, { it }, {}, {}) } }
        compose.onNodeWithText("Your sales for today are submitted.").assertExists()
        compose.onNodeWithText("6 records are on the server.").assertExists()
        compose.onNodeWithText("Matched").assertDoesNotExist() // the table gives way to the success state
        compose.onNodeWithText("Submit sales").assertIsNotEnabled()
    }

    @Test fun mismatchBannerOffersSyncAgain() {
        var synced = 0
        val counts = listOf(CountRow("memo", 6, 5))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { it }, { synced++ }, {}) } }
        compose.onNodeWithText("Sync again").performClick()
        assertEquals(1, synced)
    }

    @Test fun offlineTheEnabledPrimarySaysTheSubmitGoesWhenConnected() {
        // screens-sr s11: the submit is queued as the last event of the day, so the label says it will wait for the network
        val counts = listOf(CountRow("memo", 6, 6))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(false, counts, gate, SubmitProgress.Idle, { it }, {}, {}) } }
        compose.onNodeWithText("Submit sales (sends when connected)").assertIsEnabled()
    }

    @Test fun countsStackUnderTheItemAtLargeFontScale() {
        // screens-sr s0: numbers never wrap or clip; from fontScale.vertical the table reflows to caption-over-value cells
        val counts = listOf(CountRow("memo", 1234, 1234))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { "Memos of the day" }, {}, {}) } }
        }
        val item = compose.onNodeWithText("Memos of the day").getBoundsInRoot()
        val device = compose.onNodeWithText("Device").getBoundsInRoot()
        compose.onNodeWithText("Device").assertIsDisplayed()
        assertTrue("the counts sit under the item, not clipped in a fixed column", device.top >= item.bottom)
    }

    @Test fun syncNeedsConnectionAndSuccessShowsOnlyWhenSettled() {
        val gate = SalesSubmitRules.gate(clean, emptyList(), DuesAtSubmit(0, 0), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(false, emptyList(), gate, SubmitProgress.Queued, { it }, {}, {}) } }
        compose.onNodeWithText("Sync data").assertIsNotEnabled()
        compose.onNodeWithText("Submit is saved and will be sent when you are online.").assertExists()
        compose.onNodeWithText("Your sales for today are submitted.").assertDoesNotExist()
        compose.onNodeWithText("Submit sales").assertIsNotEnabled()
    }

    @Test fun groupedRowsShowTheManualsNineAndTheSubmitStillGoesOutOnce() {
        var submitted = 0
        val counts = listOf(CountRow("memo", 6, 6), CountRow("distribution_check", 3, 3), CountRow("distribution_check_line", 9, 9))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        val groups = listOf(listOf("memo"), listOf("distribution_check", "distribution_check_line"))
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { "<" + it + ">" }, {}, { submitted++ }, rowGroups = groups) } }
        compose.onNodeWithText("<memo>").assertExists()
        compose.onNodeWithText("<distribution_check>").assertExists()
        compose.onNodeWithText("<distribution_check_line>").assertDoesNotExist() // folded into its group row
        compose.onAllNodesWithText("12").assertCountEquals(2) // 3 + 9 in the device column and in the server column
        compose.onNodeWithText("Submit sales").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SalesSubmitTags.HOLD).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(1, submitted)
    }

    @Test fun withoutGroupsTheTableIsOneRowPerTypeAsBefore() {
        val counts = listOf(CountRow("distribution_check", 3, 3), CountRow("distribution_check_line", 9, 9))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { "<" + it + ">" }, {}, {}) } }
        compose.onNodeWithText("<distribution_check>").assertExists()
        compose.onNodeWithText("<distribution_check_line>").assertExists()
    }

    /** The stronger proof: the SAME view model drives the composed screen without and then with the nine groups; what reaches the writer is identical. */
    @Test fun theGroupedAndTheUngroupedScreenSubmitTheSamePerTypeRowsAndGate() {
        class Day : DaySource {
            override suspend fun outboxStates(businessDate: String) = OutboxStates(0, 0, 10, 0, 0)
            override suspend fun deviceCounts(businessDate: String) = mapOf("memo" to 6, "distribution_check" to 3, "distribution_check_line" to 9)
            override suspend fun dues(businessDate: String) = DuesAtSubmit(0, 0)
            override suspend fun alreadySubmitted(businessDate: String) = false
            override suspend fun submitSettled(businessDate: String) = false
        }
        val sched = object : com.aktcl.aron.core.sync.SyncScheduler { override fun requestSync(userId: Long, trigger: com.aktcl.aron.contract.SyncTrigger) {} }
        val server = mapOf("memo" to 6, "distribution_check" to 3, "distribution_check_line" to 9)
        val sent = ArrayList<Pair<SubmitGate, List<CountRow>>>()
        fun newVm() = SalesSubmitViewModel(1, { "2026-10-05" }, Day(), { server }, { _, g, c -> sent += g to c }, sched, { true })
        val ungrouped = newVm().also { it.refresh() }
        val grouped = newVm().also { it.refresh() }
        val groups = listOf(listOf("memo"), listOf("distribution_check", "distribution_check_line"))
        var useGrouped by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            AronTheme(AppLanguage.EN) {
                val vm = if (useGrouped) grouped else ungrouped
                val st by vm.state.collectAsState()
                SalesSubmitScreen(true, st.counts, st.gate, st.progress, { it }, {}, vm::submit, rowGroups = if (useGrouped) groups else null)
            }
        }
        fun holdSubmit() {
            compose.onNodeWithText("Submit sales").assertIsEnabled().performClick(); compose.waitForIdle()
            compose.onNodeWithTag(SalesSubmitTags.HOLD).performSemanticsAction(SemanticsActions.OnLongClick); compose.waitForIdle()
        }
        compose.onNodeWithText("distribution_check_line").assertExists()
        holdSubmit()
        useGrouped = true; compose.waitForIdle()
        compose.onNodeWithText("distribution_check_line").assertDoesNotExist() // the grouped view really is on screen
        holdSubmit()
        assertEquals(2, sent.size)
        assertEquals(sent[0].first, sent[1].first) // the gate
        assertEquals(sent[0].second, sent[1].second) // the per-type rows, device and server, in the same order
        assertEquals(listOf("distribution_check", "distribution_check_line", "memo"), sent[1].second.map { it.recordType })
    }
}

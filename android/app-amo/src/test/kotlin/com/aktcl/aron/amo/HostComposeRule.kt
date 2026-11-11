package com.aktcl.aron.amo

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * The Compose rule of the app-amo host tests (D-AMO-CALLS-32). The default `createEmptyComposeRule()` runs the test Recomposer on an
 * `UnconfinedTestDispatcher`: a coroutine of the composition (a `LaunchedEffect`, `rememberAsyncLoad`, `collectAsState`) that awaited a Room query is
 * resumed INLINE on Room's executor thread, and the test's `ApplyingContinuationInterceptor` then calls `Snapshot.sendApplyNotifications()` there, which wakes
 * the Recomposer on that same thread: a whole frame, including every effect that enters composition, runs on `test-room-io-N`. A `LifecycleStartEffect`
 * (activity-compose, `BackHandler`) then fails with "Method addObserver must be called on the main thread" (CI run 42973387505, AmoControlCallHostTest;
 * measured here: 1 of 150 effect runs off the main thread, twice in a row).
 *
 * A `StandardTestDispatcher` as the effect context is honoured by the test environment as the Recomposer's dispatcher: a resumption from another thread is only
 * QUEUED on its scheduler, never run inline on the thread that resumed it, so the composition, its effects and its frames run on the main thread only. WHAT RUNS
 * THE QUEUE, precisely (measured, D-AMO-CALLS-33): `waitUntil` advances one frame per poll and so runs the queued work; `performClick` and the other actions idle
 * only while Compose itself reports pending work; a bare `waitForIdle()` does NOT call `runCurrent` on the scheduler, so work that finished on another thread
 * after the last frame stays queued until the next `waitUntil` poll or frame advance (`mainClock.advanceTimeByFrame()`). A test that asserts a NEGATIVE ("nothing
 * happened") must therefore settle the day's work and advance a frame first. (The v2 rule `androidx.compose.ui.test.junit4.v2.createEmptyComposeRule` was not used:
 * the v1 factory with this dispatcher is what the suites were proven on.)
 */
fun hostComposeRule(): ComposeTestRule = createEmptyComposeRule(StandardTestDispatcher())

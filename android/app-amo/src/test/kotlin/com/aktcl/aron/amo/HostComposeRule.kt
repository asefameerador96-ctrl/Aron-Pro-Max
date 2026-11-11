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
 * QUEUED on its scheduler, and the scheduler is run by the test's own idle loops (`waitUntil`, `performClick`, `waitForIdle`, which run on the main thread). So the
 * composition, its effects and its frames run on the main thread only, in every test, loaded machine or not. Nothing else about the rule changes.
 */
fun hostComposeRule(): ComposeTestRule = createEmptyComposeRule(StandardTestDispatcher())

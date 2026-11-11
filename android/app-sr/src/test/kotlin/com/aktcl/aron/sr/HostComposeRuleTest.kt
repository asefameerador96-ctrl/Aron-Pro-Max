package com.aktcl.aron.sr

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * D-AMO-CALLS-32, applied to app-sr by D-QA-CI-17: [hostComposeRule] keeps the composition on the main thread. A composition coroutine awaits a task that finishes on Room's executor
 * thread (40 times, each time changing state so a new composable enters composition); with the default rule's Unconfined Recomposer some of those frames
 * ran on `test-room-io-N` (1 of 150 effect runs, measured twice with 200 resumptions, the cause of the `LifecycleRegistry.addObserver` failure of CI run 42973387505); with this
 * rule none can: the resumption is only queued and the main thread's own frame advance runs it. Only 40 resumptions, so the run is a few seconds even on a loaded runner (D-AMO-CALLS-34).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HostComposeRuleTest {
    @get:org.junit.Rule val compose: ComposeTestRule = hostComposeRule()

    private companion object { const val RESUMPTIONS = 40 }

    @Test fun everyEffectOfAFrameTriggeredFromARoomThreadRunsOnTheMainThread() {
        val off = AtomicInteger(); val total = AtomicInteger(); val lastN = AtomicInteger(-1); val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        val room = DrainableExecutor()
        try { ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.setContent {
                    var n by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) { repeat(RESUMPTIONS) { withContext(room.asCoroutineDispatcher()) { Thread.sleep(1) }; n = it + 1 } } // a Room query ends on a Room thread, then state changes
                    key(n) {
                        DisposableEffect(n) {
                            total.incrementAndGet(); lastN.set(n)
                            if (Looper.myLooper() != Looper.getMainLooper()) { off.incrementAndGet(); names.add(Thread.currentThread().name) }
                            onDispose {}
                        }
                    }
                }
            }
            // 120 s is only a hang guard for a broken run (D-AMO-CALLS-34): the outcome does not depend on it, the loop ends on the expected count. waitUntil is the
            // idiom of the host tests (a frame advance and an idle per poll); the end condition is the LAST value (n == 40), not a count of effect runs: two state changes that land before one frame make ONE recomposition, so the count of effect runs is not fixed. (A hand-rolled spin loop waiting for the count 41 stalled at a random count for exactly that reason.)
            compose.waitUntil(120_000) { lastN.get() == RESUMPTIONS }
        } } finally { room.shutdown() }
        assertEquals("the composition reached the last state", RESUMPTIONS, lastN.get())
        assertTrue("composition made progress: ${total.get()} effect runs", total.get() >= 2)
        assertEquals("effects run off the main thread: $names", 0, off.get())
    }
}

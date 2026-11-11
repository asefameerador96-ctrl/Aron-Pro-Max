package com.aktcl.aron.amo

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
 * D-AMO-CALLS-32: [hostComposeRule] keeps the composition on the main thread. A composition coroutine awaits a task that finishes on Room's executor
 * thread (200 times, each time changing state so a new composable enters composition); with the default rule's Unconfined Recomposer some of those frames
 * ran on `test-room-io-N` (1 of 150 effect runs, measured twice, the cause of the `LifecycleRegistry.addObserver` failure of CI run 42973387505); with this
 * rule none can: the resumption is only queued and the main thread's own wait loop runs it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HostComposeRuleTest {
    @get:org.junit.Rule val compose: ComposeTestRule = hostComposeRule()

    @Test fun everyEffectOfAFrameTriggeredFromARoomThreadRunsOnTheMainThread() {
        val off = AtomicInteger(); val total = AtomicInteger(); val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        val room = DrainableExecutor()
        try { ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.setContent {
                    var n by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) { repeat(200) { withContext(room.asCoroutineDispatcher()) { Thread.sleep(2) }; n = it + 1 } } // a Room query ends on a Room thread, then state changes
                    key(n) {
                        DisposableEffect(n) {
                            total.incrementAndGet()
                            if (Looper.myLooper() != Looper.getMainLooper()) { off.incrementAndGet(); names.add(Thread.currentThread().name) }
                            onDispose {}
                        }
                    }
                }
            }
            compose.waitUntil(30_000) { total.get() >= 150 } // the idiom of the host tests: it must make progress, too
        } } finally { room.shutdown() }
        assertTrue("composition made progress: ${total.get()}", total.get() >= 150)
        assertEquals("effects run off the main thread: $names", 0, off.get())
    }
}

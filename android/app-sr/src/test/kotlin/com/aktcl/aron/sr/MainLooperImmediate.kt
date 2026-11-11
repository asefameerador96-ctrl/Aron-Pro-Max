package com.aktcl.aron.sr

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * Test `Dispatchers.Main` for the host tests: eager when already on the main thread, a post to the main looper from any other thread. Unlike
 * `UnconfinedTestDispatcher` it never resumes Main work on the thread that resumed it (a Room IO thread), so the Compose test Recomposer
 * cannot apply a frame off the UI thread.
 */
object MainLooperImmediate {
    val dispatcher get() = Handler(Looper.getMainLooper()).asCoroutineDispatcher("test-main").immediate

    /** Runs the work posted to the main looper when called on the main thread: a test that blocks (runBlocking poll) calls it on each poll. */
    fun idle() { if (Looper.myLooper() == Looper.getMainLooper()) org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle() }
}

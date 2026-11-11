package com.aktcl.aron.amo

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * CI run 41750277921 (the SR's SrLatePolicyTest; AmoLatePolicyTest has the same pattern): a view model's read still to come
 * when the test ends is held open on purpose (a gate keeps it waiting until [AmoLockFixture.close] is running). The read
 * finishes on the open database, the view model is cleared and joined, the day's own scopes are the fixture's, and nothing
 * escapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoLockFixtureCloseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
        Dispatchers.resetMain()
    }

    @Test fun aViewModelsReadStillToComeFinishesOnTheOpenDatabaseThenTheViewModelIsClearedAndJoined() {
        val f = AmoLockFixture(app, latePolicy = null)
        // AmoDay's scopes (the print scope, the visit flow's config check) hang off the fixture's job
        assertEquals("the day's own scopes are the fixture's", 2, f.work.dayJob.children.count())
        f.creditMemo(f.controlCall())
        val vm = f.memoViewModel()
        val scope = vm.viewModelScope.coroutineContext.job
        val gate = CountDownLatch(1)
        val read = AtomicReference<Int?>(null)
        // as Mark Paid's reload after its write: the test's last wait is over, the view model still has a read to do
        vm.viewModelScope.launch { withContext(Dispatchers.IO) { gate.await() }; read.set(f.db.captureDao().memosOn(f.date).size) }
        thread(isDaemon = true) { Thread.sleep(300); gate.countDown() } // the read starts while close() runs
        f.close()
        assertEquals("the read ran, on the open database", 1, read.get())
        assertTrue("the view model was cleared and its scope joined", scope.isCancelled && scope.isCompleted)
        assertTrue("the day's work was cancelled and joined", f.work.dayJob.isCompleted)
        assertFalse("then the database closed", f.db.isOpen)
        assertEquals("nothing escaped", listOf<Throwable>(), crashes.toList())
    }
}

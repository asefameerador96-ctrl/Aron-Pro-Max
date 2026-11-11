package com.aktcl.aron.sr

import android.content.Context
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
 * CI run 41750277921: SrLatePolicyTest's Mark Paid left its view model's reload reading after the fixture had closed the
 * database ("connection pool has been closed"), which failed the next policy's "nothing escaped" and, through
 * kotlinx-coroutines-test's collector, SrRefusalHostsTest. Here that shape is held open on purpose (a gate keeps the read
 * waiting until [SrLockFixture.close] is running): the read finishes on the open database, the view model is cleared and
 * joined, the day's own scopes are the fixture's, and nothing escapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrLockFixtureCloseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
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

    /** Opens the gate a little after the test reaches close(): the read the gate holds starts while close() runs. */
    private fun openLater(gate: CountDownLatch) = thread(isDaemon = true) { Thread.sleep(300); gate.countDown() }

    @Test fun aViewModelsReadStillToComeFinishesOnTheOpenDatabaseThenTheViewModelIsClearedAndJoined() {
        val f = SrLockFixture(context, latePolicy = null)
        f.creditMemo(f.openCall())
        val vm = f.memoViewModel()
        val scope = vm.viewModelScope.coroutineContext.job
        val gate = CountDownLatch(1)
        val read = AtomicReference<Int?>(null)
        // as Mark Paid's reload after its write: the test's last wait is over, the view model still has a read to do
        vm.viewModelScope.launch { withContext(Dispatchers.IO) { gate.await() }; read.set(f.db.captureDao().memosOn(f.date).size) }
        openLater(gate)
        f.close()
        assertEquals("the read ran, on the open database", 1, read.get())
        assertTrue("the view model was cleared and its scope joined", scope.isCancelled && scope.isCompleted)
        assertFalse("then the database closed", f.db.isOpen)
        assertEquals("nothing escaped", listOf<Throwable>(), crashes.toList())
    }

    @Test fun theDaysOwnWorkIsTheFixturesAndFinishesBeforeTheDatabaseCloses() {
        val f = SrLockFixture(context, latePolicy = null)
        // SrDay's scopes (background, which also runs the visit flow's config check; the print runner; the print scope)
        assertEquals("the day's own scopes are the fixture's", 3, f.work.dayJob.children.count())
        f.stockedCatalog()
        val gate = CountDownLatch(1)
        val read = AtomicReference<Int?>(null)
        // a print the SR started: it outlives the screen by design (PrintRunner), so the fixture must wait for it
        f.day.printRunner.run({ withContext(Dispatchers.IO) { gate.await() }; f.db.captureDao().stockOn(f.date).size }) { read.set(it) }
        openLater(gate)
        f.close()
        assertEquals("the print's read ran, on the open database", 1, read.get())
        assertTrue("the day's work was cancelled and joined", f.work.dayJob.isCompleted)
        assertFalse(f.db.isOpen)
        assertEquals("nothing escaped", listOf<Throwable>(), crashes.toList())
    }
}

package com.aktcl.aron.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.session.TrustedClockSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** F-SYS-032: a crash reaches the outbox at the next start with its stack, never a phone number or an outlet name. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ErrorReporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val wall = 1_791_165_600_000L // 2026-10-05T02:00Z
    private lateinit var dir: File

    @After fun tearDown() = db.close()

    private var signedIn: Long? = null

    private fun reporter(): ErrorReporter = runBlocking {
        val raw = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        ReferenceRepository(db).apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)
        dir = tmp.newFolder("errors")
        ErrorReporter(dir, { db }, TrustedClockSource(tmp.newFile(), { 41 }, { 10_000_000L }, { wall }), "1.0.3+10003", { true }, currentUser = { signedIn })
    }

    private suspend fun errorRows() = db.outboxDao().nextPending(100).filter { it.recordType == "app_error" }
    private fun payload(row: com.aktcl.aron.core.database.entity.OutboxEntity): JsonObject = Json.parseToJsonElement(row.payloadJson).jsonObject["payload"]!!.jsonObject

    @Test fun theScrubberKeepsTheShapeAndDropsPersonalData() {
        val s = ErrorScrubber.scrub("Outlet 'Rahim Store' owner রহিম phone +8801712345678 or 01712345678 paid 125000 a@b.com at com.aktcl.aron.Foo.bar(Foo.kt:42)")!!
        assertFalse(s.contains("Rahim")); assertFalse(s.contains("রহিম")); assertFalse(s.contains("1712345678"))
        assertFalse(s.contains("125000")); assertFalse(s.contains("a@b.com"))
        assertTrue(s.contains("com.aktcl.aron.Foo.bar(Foo.kt:42)"))
    }

    @Test fun aCrashFileBecomesOneScrubbedRecordAtTheNextStart() = runBlocking {
        val r = reporter()
        r.writeCrash(IllegalStateException("no price for Store 50000 of Owner 50000"), "crash")
        assertTrue("nothing in the database while the process dies", errorRows().isEmpty())
        assertEquals(1, r.drain(7))
        val p = payload(errorRows().single())
        assertEquals(setOf("occurred_at", "kind", "exception_class", "message", "stack", "screen", "app_version"), p.keys)
        assertEquals("crash", p["kind"]!!.jsonPrimitive.content)
        assertEquals("java.lang.IllegalStateException", p["exception_class"]!!.jsonPrimitive.content)
        assertFalse(p["message"]!!.jsonPrimitive.content.contains("Store 50000"))
        assertFalse(p["stack"]!!.jsonPrimitive.content.contains("Owner 50000"))
        assertTrue(p["stack"]!!.jsonPrimitive.content.contains("ErrorReporterTest"))
        assertEquals("2026-10-05T02:00:00.000Z", p["occurred_at"]!!.jsonPrimitive.content)
        assertEquals(0, r.drain(7))
    }

    @Test fun aDrainCutShortNeverQueuesTwice() = runBlocking {
        val r = reporter()
        r.writeCrash(RuntimeException("x"), "crash")
        val file = dir.listFiles()!!.single { it.name.endsWith(".json") }
        val copy = file.readText()
        r.drain(7)
        File(dir, file.name).writeText(copy) // as if the process died before the file was deleted
        assertEquals(0, r.drain(7))
        assertEquals(1, errorRows().size)
    }

    @Test fun aCrashLoopKeepsAtMostTwentyFiles() = runBlocking {
        val r = reporter()
        repeat(25) { r.writeCrash(RuntimeException("loop"), "crash") }
        assertEquals(ErrorReporter.MAX_FILES, dir.listFiles { f -> f.name.endsWith(".json") }!!.size)
    }

    /** F-SYS-032 follow-up: a crash before Hilt built the graph is kept as a file of nobody and drained at the next start. */
    @Test fun aCrashBeforeTheGraphExistsIsKeptAndTheFullHandlerTakesOver() = runBlocking {
        val r = reporter()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var previousCalls = 0
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousCalls++ }
        try {
            // The device clock is 3 hours off; the crash happened 60 s (elapsed) before the drain, on the same boot (41).
            val deviceClock = wall + 3 * 3_600_000L
            ErrorReporter.installEarly(dir, "1.0.3+10003", nowMs = { deviceClock }, elapsedMs = { 10_000_000L - 60_000L }, bootCount = { 41 })
            ErrorReporter.installEarly(dir, "1.0.3+10003", nowMs = { deviceClock }, elapsedMs = { 0L }) // once
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("hilt graph"))
            assertEquals(1, previousCalls)
            assertEquals(1, dir.listFiles { f -> f.name.endsWith(".u0.json") }!!.size)
            // The full handler replaces the early one and chains to the original handler, not to the early one.
            r.install()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("later"))
            assertEquals(2, previousCalls)
            assertEquals(2, dir.listFiles { f -> f.name.endsWith(".json") }!!.size)
            assertEquals(2, r.drain(7))
            val early = errorRows().map { payload(it) }.single { it["exception_class"]!!.jsonPrimitive.content == "java.lang.IllegalStateException" }
            assertEquals("crash", early["kind"]!!.jsonPrimitive.content)
            // Rebuilt from the monotonic clock on the same boot: trusted time minus 60 s, not the device clock.
            assertEquals(com.aktcl.aron.core.sync.SyncEngine.iso(wall - 60_000L), early["occurred_at"]!!.jsonPrimitive.content)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun theHandlerWritesTheFileAndStillCallsThePreviousHandler() = runBlocking {
        val r = reporter()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var previousCalled = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousCalled = true }
        try {
            r.install()
            r.install() // once
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertTrue(previousCalled)
            assertEquals(1, dir.listFiles { f -> f.name.endsWith(".json") }!!.size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun aHandledErrorAndAnAnrAreQueued() = runBlocking {
        val r = reporter()
        r.handled(7, IllegalArgumentException("bad"), screen = "sale.review")
        r.drain(7, listOf(wall - 60_000 to "Input dispatching timed out"))
        val kinds = errorRows().map { payload(it)["kind"]!!.jsonPrimitive.content }.sorted()
        assertEquals(listOf("anr", "handled"), kinds)
    }

    /** Checker: tokens, SAS query strings and usernames never leave the phone (docs/21). */
    @Test fun secretsAndUsernamesAreScrubbed() {
        val s = ErrorScrubber.scrub("PUT https://aronst.blob.core.windows.net/photos/a.jpg?sv=2024&se=2026&sig=AbC%2F123 failed; Authorization: Bearer abc.def; token eyJhbGciOi.eyJzdWIi.c2ln for sr334001")!!
        assertFalse(s.contains("sig=")); assertFalse(s.contains("AbC")); assertFalse(s.contains("abc.def"))
        assertFalse(s.contains("eyJhbGciOi")); assertFalse(s.contains("sr334001"))
        assertTrue(s.contains("https://aronst.blob.core.windows.net/photos/a.jpg?<q>"))
    }

    /** Checker: on a shared phone a crash of user A waits for A; B's start never takes it. */
    @Test fun aCrashOfAnotherUserWaitsForThatUser() = runBlocking {
        val r = reporter()
        signedIn = 8
        r.writeCrash(RuntimeException("of eight"), "crash")
        assertEquals(0, r.drain(7))
        assertEquals(1, r.drain(8))
    }

    /** Checker: an ANR is queued once, and the marker moves only over ANRs that are queued. */
    @Test fun anAnrIsQueuedOnceAndTheMarkerFollowsWhatWasQueued() = runBlocking {
        val r = reporter()
        val anr = listOf(wall - 60_000 to "Input dispatching timed out")
        assertEquals(1 to (wall - 60_000), r.drainCounted(7, anr))
        assertEquals(0 to (wall - 60_000), r.drainCounted(7, anr)) // relaunch before the marker was written
        assertEquals(1, errorRows().size)
    }

    @Test fun aCrashLoopIsCappedPerDay() = runBlocking {
        val r = reporter()
        repeat(4) { repeat(ErrorReporter.MAX_PER_RUN) { r.writeCrash(RuntimeException("loop"), "crash") }; r.drain(7) }
        assertEquals(ErrorReporter.MAX_PER_DAY, errorRows().size)
    }
}

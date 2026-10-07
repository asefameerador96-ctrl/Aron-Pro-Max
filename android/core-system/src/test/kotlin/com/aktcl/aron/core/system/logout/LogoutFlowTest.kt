package com.aktcl.aron.core.system.logout

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogoutFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    inner class Phone(var records: Int = 0, var photos: Int = 0) : LogoutPorts {
        val log = mutableListOf<String>()
        val dbDir by lazy { tmp.newFolder("databases") }
        val filesDir by lazy { tmp.newFolder("files") }
        init {
            LogoutFlow.databaseFiles(dbDir, 7).take(2).forEach { it.writeText("db") }
            LogoutFlow.mediaDir(filesDir, 7).apply { mkdirs(); File(this, "a.jpg").writeText("x") }
            LogoutFlow.databaseFiles(dbDir, 8).first().writeText("other user")
        }
        override suspend fun unsentRecords() = records.also { log += "count" }
        override suspend fun unsentPhotos() = photos
        override suspend fun endSession() { log += "end" }
        override fun requestSync() { log += "sync" }
        override suspend fun closeUserDatabase() { log += "close" }
        override suspend fun forgetCredentials() { log += "forget" }
        override fun userFiles() = LogoutFlow.databaseFiles(dbDir, 7) + LogoutFlow.mediaDir(filesDir, 7)
        fun userDataExists() = LogoutFlow.databaseFiles(dbDir, 7).first().exists() && LogoutFlow.mediaDir(filesDir, 7).exists()
    }

    @Test fun srLogoutKeepsTheDatabaseAndTheEngineKeepsUploading() = runTest {
        val p = Phone(records = 12, photos = 3)
        assertEquals(LogoutResult.LoggedOut(wiped = false), LogoutFlow(AppRole.SR, p).logout())
        assertTrue(p.userDataExists())
        assertEquals(listOf("end", "sync"), p.log) // no count needed, no close, never a wipe
    }

    @Test fun amoBehavesLikeSr() = runTest {
        val p = Phone(records = 1)
        assertEquals(LogoutResult.LoggedOut(wiped = false), LogoutFlow(AppRole.AMO, p).logout())
        assertTrue(p.userDataExists())
    }

    @Test fun tsoWithUnsentItemsIsRefusedWithTheCountAndNothingChanges() = runTest {
        val p = Phone(records = 4, photos = 1)
        val flow = LogoutFlow(AppRole.TSO, p)
        assertEquals(LogoutCheck.Refused(5), flow.check())
        assertEquals(LogoutResult.Refused(5), flow.logout())
        assertTrue(p.userDataExists())
        assertFalse("end" in p.log)
        flow.syncNow()
        assertEquals("sync", p.log.last())
    }

    @Test fun tsoUnsentPhotosAloneAlsoRefuse() = runTest {
        assertEquals(LogoutCheck.Refused(2), LogoutFlow(AppRole.TSO, Phone(photos = 2)).check())
    }

    @Test fun aFullyReconciledTsoIsLoggedOutThenWipedOnlyForThatUser() = runTest {
        val p = Phone()
        assertEquals(LogoutResult.LoggedOut(wiped = true), LogoutFlow(AppRole.TSO, p).logout())
        assertFalse(LogoutFlow.databaseFiles(p.dbDir, 7).any { it.exists() })
        assertFalse(LogoutFlow.mediaDir(p.filesDir, 7).exists())
        assertTrue(LogoutFlow.databaseFiles(p.dbDir, 8).first().exists()) // another user on the phone is untouched
        assertEquals(listOf("count", "end", "count", "close", "forget"), p.log) // counted again after the session ended
    }

    @Test fun anItemCapturedAfterTheDialogStopsTheWipe() = runTest {
        val p = Phone()
        val flow = LogoutFlow(AppRole.TSO, p)
        assertEquals(LogoutCheck.Proceed(wipe = true), flow.check())
        p.records = 1 // a capture lands between the confirmation and the tap
        assertEquals(LogoutResult.Refused(1), flow.logout())
        assertTrue(p.userDataExists())
    }

    @Test fun aCountThatCannotBeReadNeverWipes() = runTest {
        val p = object : LogoutPorts by Phone() {
            override suspend fun unsentRecords(): Int = throw IllegalStateException("database locked")
        }
        try { LogoutFlow(AppRole.TSO, p).logout(); error("must not proceed") } catch (_: IllegalStateException) { }
    }
}

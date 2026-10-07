package com.aktcl.aron.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Checker (ec28160 re-check, finding 2): during a TSO wipe a sync or media worker must not reopen, and cache, the database
 * whose files are about to be deleted; its writes would go to unlinked files and be lost.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerUserDatabasesCloseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aClosedUserCannotBeReopenedUntilTheWipeEnds(): Unit = runBlocking {
        val dbs = UserDatabases(context) { null }
        val first = dbs.of(7)
        first.outboxDao().unsentCount()
        dbs.close(7)
        try {
            dbs.of(7)
            fail("a closed user's database must not reopen while its files are removed")
        } catch (_: IllegalStateException) {
        }
        LogoutFiles.delete(context, 7)
        dbs.allowOpen(7)
        val again = dbs.of(7)
        assertNotSame(first, again)
        assertEquals(0, again.outboxDao().unsentCount())
        assertTrue("the reopened database has a file on disk", context.getDatabasePath(AronDatabase.fileName(7)).exists())
        dbs.close(7)
        dbs.allowOpen(7)
    }

    @Test
    fun otherUsersStayOpenDuringOneUsersWipe(): Unit = runBlocking {
        val dbs = UserDatabases(context) { null }
        dbs.of(8).outboxDao().unsentCount()
        dbs.close(9)
        assertEquals(0, dbs.of(8).outboxDao().unsentCount())
        dbs.allowOpen(9)
    }

    private object LogoutFiles {
        fun delete(context: Context, userId: Long) {
            listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(AronDatabase.fileName(userId) + it).delete() }
        }
    }
}

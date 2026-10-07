package com.aktcl.aron.core.system.logout

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Independent checker, F-SYS-022 (T1). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckerF022Test {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The count is read once, before endSession(). endSession() suspends (SessionRepository.logout takes loginMutex and
     * hops to IO); any row committed in that window (a background capture, a worker's write, a UI coroutine finishing a
     * save) is then deleted with the database. Nothing re-counts after the session has ended and before the close.
     */
    @Test fun aRowCommittedWhileTheSessionIsEndingIsNotWiped() = runTest {
        val dbDir = tmp.newFolder("databases"); val filesDir = tmp.newFolder("files")
        LogoutFlow.databaseFiles(dbDir, 7).first().writeText("db")
        var records = 0
        val ports = object : LogoutPorts {
            override suspend fun unsentRecords() = records
            override suspend fun unsentPhotos() = 0
            override suspend fun endSession() { records = 1 } // committed while logout was suspended here
            override fun requestSync() {}
            override suspend fun closeUserDatabase() {}
            override fun userFiles(): List<File> = LogoutFlow.databaseFiles(dbDir, 7) + LogoutFlow.mediaDir(filesDir, 7)
        }
        val result = LogoutFlow(AppRole.TSO, ports).logout()
        assertNotEquals(LogoutResult.LoggedOut(wiped = true), result)
        assertTrue("an unsent row was wiped", LogoutFlow.databaseFiles(dbDir, 7).first().exists())
    }

    /** English number grammar: N = 1 reads "1 items not yet sent" (no plurals resource). */
    @Test fun oneUnsentItemIsSingularInEnglish() {
        rule.setContent { AronTheme(AppLanguage.EN) { UnsentItemsDialog(1, {}, {}) } }
        rule.onNodeWithText("1 item not yet sent", substring = true).assertExists()
    }
}

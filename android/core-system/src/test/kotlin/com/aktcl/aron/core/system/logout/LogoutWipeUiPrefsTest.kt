package com.aktcl.aron.core.system.logout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * SH-17, D-TSO-MAN-37 (tso-manual A1): the TSO wipe removes this user's UI preferences (`aron_ui_<id>.xml`: Sunlight, the
 * dashboard's channel choice) with the database and photos, keeps another user's, and keeps everything while items are unsent
 * (the logout is refused, G-man-tso-01).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LogoutWipeUiPrefsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")

    private fun ports(unsent: Int): LogoutPorts {
        val real = DatabaseLogoutPorts(ctx, 7, db = { error("not used") }, endSessionAction = {}, requestSyncAction = {}, unsentPhotosCount = { 0 }, closeDatabaseAction = {})
        return object : LogoutPorts {
            override suspend fun unsentRecords() = unsent
            override suspend fun unsentPhotos() = 0
            override suspend fun endSession() {}
            override fun requestSync() {}
            override suspend fun closeUserDatabase() {}
            override fun userFiles(): List<File> = real.userFiles()
        }
    }

    private fun seed() {
        ctx.getSharedPreferences("aron_ui_7", Context.MODE_PRIVATE).edit().putBoolean("sunlight", true).putString("tso_dash_channel_mode", "CALLS").commit()
        ctx.getSharedPreferences("aron_ui_8", Context.MODE_PRIVATE).edit().putBoolean("sunlight", true).commit()
    }

    @Test fun theUsersUiPreferencesAreAmongTheUsersFiles() {
        seed()
        val files = DatabaseLogoutPorts(ctx, 7, { error("not used") }, {}, {}, { 0 }, {}).userFiles()
        assertTrue(files.any { it.name == "aron_ui_7.xml" })
        assertFalse(files.any { it.name == "aron_ui_8.xml" })
        assertEquals(File(prefsDir, "aron_ui_7.xml"), DatabaseLogoutPorts.uiPreferences(prefsDir, 7))
    }

    @Test fun theWipeRemovesThemAndKeepsAnotherUsers() = runTest {
        seed()
        assertTrue(File(prefsDir, "aron_ui_7.xml").exists())
        val r = LogoutFlow(AppRole.TSO, ports(unsent = 0)).logout()
        assertEquals(LogoutResult.LoggedOut(wiped = true), r)
        assertFalse(File(prefsDir, "aron_ui_7.xml").exists())
        assertTrue(File(prefsDir, "aron_ui_8.xml").exists())
    }

    @Test fun aRefusedLogoutKeepsThem() = runTest {
        seed()
        assertEquals(LogoutResult.Refused(2), LogoutFlow(AppRole.TSO, ports(unsent = 2)).logout())
        assertTrue(File(prefsDir, "aron_ui_7.xml").exists())
    }
}

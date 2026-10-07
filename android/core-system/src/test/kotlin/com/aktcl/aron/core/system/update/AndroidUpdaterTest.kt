package com.aktcl.aron.core.system.update

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private val rel = ReleaseInfo("1.2.3", 12, "arm64-v8a", java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3)).joinToString("") { "%02x".format(it) }, 3, "https://x/y.apk", "c".repeat(64))

    @Test fun aFileThatIsNotAnApkIsNeverInstalled() = runTest {
        val f = tmp.newFile("x.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals(ApkCheck.UNREADABLE, AndroidUpdater(app).verify(f, rel))
        assertEquals(InstallStart.Refused(ApkCheck.UNREADABLE), AndroidUpdater(app).install(f, rel, flowOf(true)))
    }

    @Test fun bytesThatAreNotTheReleaseAreRefusedBeforeTheOs() = runTest {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val f = tmp.newFile("z.apk").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        assertEquals(InstallStart.Refused(ApkCheck.UNREADABLE), AndroidUpdater(app).install(f, rel, flowOf(true)))
    }

    @Test fun withoutInstallPermissionTheGuidanceIsShownFirst() = runTest {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val f = tmp.newFile("y.apk")
        assertEquals(InstallStart.NeedsUnknownSources, AndroidUpdater(app).install(f, rel, flowOf(true)))
        assertEquals("package:${app.packageName}", AndroidUpdater(app).unknownSourcesIntent().dataString)
    }

    @Test fun theStatusReceiverNeverStartsANonSystemActivity() {
        val evil = Intent().setComponent(ComponentName(app.packageName, "com.evil.Steal"))
        val status = Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, evil)
        InstallStatusReceiver().onReceive(app, status)
        assertNull(shadowOf(app as android.app.Application).nextStartedActivity)
    }
}

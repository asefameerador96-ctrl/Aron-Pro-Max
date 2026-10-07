package com.aktcl.aron.core.system.language

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.system.R
import com.aktcl.aron.core.ui.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** F-SYS-019 on a real activity lifecycle: switch, recreate, relaunch. The Galaxy A06 run is device check D-S3. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LanguageSwitchTest {
    /** A shell like the apps' MainActivity: resources follow the stored language from attachBaseContext. */
    class ShellActivity : Activity() {
        var created = 0
        override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))
        override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); created++ }
        fun backLabel(): String = getString(R.string.perm_gate_back)
    }

    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun freshInstall() { app.getSharedPreferences("aron_ui", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun banglaAtFirstRunThenEnglishAtOnceWithoutConfirmationAndAfterRelaunch() {
        val c = Robolectric.buildActivity(ShellActivity::class.java).setup()
        assertEquals("ফিরে যান", c.get().backLabel())

        assertTrue(LanguageSwitch.select(c.get(), AppLanguage.EN))
        c.recreate()
        assertEquals("Back", c.get().backLabel())

        // Relaunch (process kill, new activity): the choice holds.
        c.pause().stop().destroy()
        val again = Robolectric.buildActivity(ShellActivity::class.java).setup()
        assertEquals("Back", again.get().backLabel())
        assertEquals(AppLanguage.EN, LanguageSwitch.current(app))

        // And back to Bangla the same way.
        assertTrue(LanguageSwitch.select(again.get(), AppLanguage.BN))
        again.recreate()
        assertEquals("ফিরে যান", again.get().backLabel())
    }

    @Test fun selectingTheCurrentLanguageDoesNothing() {
        val c = Robolectric.buildActivity(ShellActivity::class.java).setup()
        assertFalse(LanguageSwitch.select(c.get(), AppLanguage.BN))
    }

    @Test fun theChoiceIsCommittedBeforeTheRecreate() {
        val c = Robolectric.buildActivity(ShellActivity::class.java).setup()
        LanguageSwitch.select(c.get(), AppLanguage.EN)
        // Read straight from storage, as a killed-and-restarted process would.
        assertEquals("en", app.getSharedPreferences("aron_ui", Context.MODE_PRIVATE).getString("language", null))
    }

    @Test fun textBuiltOutsideAnActivityFollowsTheRepNotThePhone() {
        AppLocale.set(app, AppLanguage.BN)
        assertEquals("ফিরে যান", LanguageSwitch.localized(app).getString(R.string.perm_gate_back))
        AppLocale.set(app, AppLanguage.EN)
        assertEquals("Back", LanguageSwitch.localized(app).getString(R.string.perm_gate_back))
    }
}

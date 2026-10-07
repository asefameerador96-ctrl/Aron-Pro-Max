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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.util.Locale

/** Independent checker, F-SYS-019 (T1). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckerF019Test {
    class Shell : Activity() {
        override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))
        override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    }
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun fresh() { app.getSharedPreferences("aron_ui", Context.MODE_PRIVATE).edit().clear().commit(); Locale.setDefault(Locale.US) }

    @Test fun banglaNeverLeaksIntoTheProcessDefaultLocale() {
        val c = Robolectric.buildActivity(Shell::class.java).setup()
        LanguageSwitch.select(c.get(), AppLanguage.EN)
        LanguageSwitch.select(c.get(), AppLanguage.BN)
        assertEquals(Locale.US, Locale.getDefault())
        assertEquals("1234", String.format("%d", 1234))
    }

    @Test fun choiceIsOnDiskSynchronouslyForAKillRightAfterTheTap() {
        val c = Robolectric.buildActivity(Shell::class.java).setup()
        LanguageSwitch.select(c.get(), AppLanguage.EN)
        // a fresh process reads the file; commit() means the in-memory map and the file agree already
        assertEquals("en", app.getSharedPreferences("aron_ui", Context.MODE_PRIVATE).getString("language", null))
        val relaunched = Robolectric.buildActivity(Shell::class.java).setup()
        assertEquals("Back", relaunched.get().getString(R.string.perm_gate_back))
    }

    @Test fun localizedFollowsTheStoredChoiceNotTheSystemLanguage() {
        Locale.setDefault(Locale.forLanguageTag("bn-BD"))
        AppLocale.set(app, AppLanguage.EN)
        assertEquals("Back", LanguageSwitch.localized(app).getString(R.string.perm_gate_back))
    }
}

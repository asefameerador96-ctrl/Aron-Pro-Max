package com.aktcl.aron.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AppLocaleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun banglaAtFirstRunThenTheSwitchSticksAcrossRelaunch() {
        assertEquals(AppLanguage.BN, AppLocale.current(context))
        assertEquals("অফলাইন: আপনার কাজ এই ফোনে সংরক্ষিত আছে, পরে সিঙ্ক হবে", AppLocale.wrap(context).getString(R.string.core_ui_offline_mode))
        assertTrue(AppLocale.set(context, AppLanguage.EN))
        assertFalse(AppLocale.set(context, AppLanguage.EN))
        assertEquals(AppLanguage.EN, AppLocale.current(context))
        assertEquals("Offline: your work is saved on this phone and will sync later", AppLocale.wrap(context).getString(R.string.core_ui_offline_mode))
        assertTrue(AppLocale.set(context, AppLanguage.BN))
        assertEquals("bn", AppLocale.wrap(context).resources.configuration.locales[0].language)
    }
}

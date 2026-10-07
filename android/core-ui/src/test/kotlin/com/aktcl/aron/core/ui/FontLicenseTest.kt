package com.aktcl.aron.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** OFL section 2: the copyright notices and the licence travel with every copy of the bundled fonts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FontLicenseTest {
    @Test
    fun theOflTextShipsInTheApk() {
        val text = ApplicationProvider.getApplicationContext<Context>().assets.open("licenses/OFL-noto-fonts.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(text.contains("The Noto Project Authors"))
        assertTrue(text.contains("noto_sans_bengali_regular.ttf"))
        assertTrue(text.contains("noto_sans_bengali_medium.ttf"))
    }
}

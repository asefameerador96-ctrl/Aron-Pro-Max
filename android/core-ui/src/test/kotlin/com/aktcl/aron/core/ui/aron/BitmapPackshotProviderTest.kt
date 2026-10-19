package com.aktcl.aron.core.ui.aron

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PARITY-SPEC s2.6: a memory hit on the first frame, a load that lands later, and a failing source is the silhouette. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BitmapPackshotProviderTest {
    @get:Rule val rule = createComposeRule()
    private val bmp = Bitmap.createBitmap(146, 237, Bitmap.Config.RGB_565)

    private var painted: Painter? = null

    private fun show(p: PackshotProvider) = rule.setContent {
        AronTheme(AppLanguage.BN) {
            CompositionLocalProvider(LocalPackshotProvider provides p) {
                painted = LocalPackshotProvider.current.painter("7")
                PackshotImage("7", Modifier.size(56.dp, 90.dp), contentDescription = "pack")
            }
        }
    }

    @Test fun aMemoryHitPaintsOnTheFirstFrame() {
        rule.mainClock.autoAdvance = false
        show(BitmapPackshotProvider(load = { kotlinx.coroutines.awaitCancellation() }, cached = { bmp }))
        rule.mainClock.advanceTimeByFrame()
        assertEquals(146f, painted!!.intrinsicSize.width)
    }

    @Test fun aLoadedPictureReplacesTheSilhouette() {
        show(BitmapPackshotProvider(load = { id -> if (id == "7") bmp else null }))
        rule.waitForIdle()
        assertEquals(237f, painted!!.intrinsicSize.height)
        rule.onAllNodesWithContentDescription("pack").assertCountEquals(1)
    }

    @Test fun aFailingSourceIsTheSilhouetteNeverACrash() {
        show(BitmapPackshotProvider(load = { error("disk gone") }, cached = { error("disk gone") }))
        rule.waitForIdle()
        assertNull(painted)
        rule.onAllNodesWithContentDescription("pack").assertCountEquals(1)
    }

    @Test fun categoryCodesMapToGlyphs() {
        assertEquals(PackCategory.Bidi, packCategoryOf("bidi"))
        assertEquals(PackCategory.Lighter, packCategoryOf("LIGHTER"))
        assertEquals(PackCategory.Match, packCategoryOf("match"))
        assertEquals(PackCategory.Cigarette, packCategoryOf("cigarette"))
        assertEquals(PackCategory.Cigarette, packCategoryOf("unknown"))
    }
}

package com.aktcl.aron.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PARITY-SPEC s2.6: the cached pack picture, else the silhouette (never a letter circle, never a spinner, never a crash). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PackshotImageTest {
    @get:Rule val rule = createComposeRule()
    private val sku = PackshotSku(1, "MaxR-10S", "cigarette")

    private fun show(provider: PackshotProvider) = rule.setContent {
        AronTheme(AppLanguage.BN) {
            CompositionLocalProvider(LocalPackshotProvider provides provider) { PackshotImage(sku, Modifier.size(48.dp, 78.dp)) }
        }
    }

    @Test fun noPictureShowsTheSilhouette() {
        show(PackshotProvider.None)
        rule.onNodeWithTag(PackshotTags.SILHOUETTE, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aPictureReplacesTheSilhouette() {
        val bmp = Bitmap.createBitmap(146, 237, Bitmap.Config.RGB_565)
        show(PackshotProvider { bmp })
        rule.waitForIdle()
        rule.onNodeWithTag(PackshotTags.IMAGE, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aMemoryHitShowsOnTheFirstFrame() {
        val bmp = Bitmap.createBitmap(146, 237, Bitmap.Config.RGB_565)
        rule.mainClock.autoAdvance = false
        show(object : PackshotProvider {
            override suspend fun load(sku: PackshotSku): Bitmap? = kotlinx.coroutines.awaitCancellation()
            override fun cached(sku: PackshotSku): Bitmap = bmp
        })
        rule.onNodeWithTag(PackshotTags.IMAGE, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aFailingProviderIsTheSilhouette() {
        show(object : PackshotProvider {
            override suspend fun load(sku: PackshotSku): Bitmap? = error("disk gone")
            override fun cached(sku: PackshotSku): Bitmap? = error("disk gone")
        })
        rule.waitForIdle()
        rule.onNodeWithTag(PackshotTags.SILHOUETTE, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun everyCategoryHasASilhouette() {
        rule.setContent {
            AronTheme(AppLanguage.EN) {
                androidx.compose.foundation.layout.Row {
                    listOf("cigarette", "bidi", "lighter", "match", "other").forEach { PackSilhouette(it, Modifier.size(48.dp, 78.dp)) }
                }
            }
        }
        rule.waitForIdle()
    }
}

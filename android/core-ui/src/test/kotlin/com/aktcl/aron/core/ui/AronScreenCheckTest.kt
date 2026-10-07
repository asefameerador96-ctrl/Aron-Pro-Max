package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.uitesting.AronScreenCheck
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** AUD-TP-5: the shared screen check works at 320 dp (font scale 1.0 and 1.3, bn and en), passes on kit rows and fails on clipping. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class AronScreenCheckTest {
    @get:Rule val rule = createComposeRule()

    @Test fun kitRowAndButtonPassEveryCase() {
        AronScreenCheck.everyCase(
            rule,
            keyTexts = { lang -> listOf(if (lang == AppLanguage.BN) "সংরক্ষণ" else "Save") },
            minTexts = 2,
        ) {
            Column {
                AronListRow(stringResource(R.string.core_ui_kit_gallery_outlet), trailing = localizedNumber(1250), onClick = {})
                AronPrimaryButton(stringResource(R.string.core_ui_kit_gallery_save), onClick = {})
            }
        }
    }

    @Test fun clippedTextIsCaught() {
        rule.setContent { Text("a very long label that cannot fit in a narrow box without wrapping", Modifier.width(40.dp), maxLines = 1) }
        val failed = runCatching { AronScreenCheck.assertNoTruncation(rule, "negative case") }.isFailure
        assertTrue("assertNoTruncation must fail on a clipped label", failed)
    }
}

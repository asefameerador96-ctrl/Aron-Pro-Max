package com.aktcl.aron.core.uitesting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertTrue
import java.util.Locale

/**
 * AUD-TP-5: one shared Robolectric Compose check that every screen row uses as a standard case. A screen test is
 * `@RunWith(RobolectricTestRunner::class) @GraphicsMode(NATIVE) @Config(sdk = [34], qualifiers = "w320dp-h640dp")`
 * (320 dp is the narrowest supported phone; the legacy graphics mode measures text at 1 px per character and makes the
 * overflow check meaningless) and calls [AronScreenCheck.everyCase] with the screen content. For each of font scale 1.0 and
 * 1.3 and for Bangla and English it asserts: the key texts are displayed, no text is clipped, every clickable node is at
 * least 48 dp, and in Bangla no ASCII digit is shown. Goldens are not used here (docs/32 s2a item 8b keeps them for four screens).
 */
object AronScreenCheck {
    val FontScales = listOf(1.0f, 1.3f)
    val Languages = listOf(AppLanguage.BN, AppLanguage.EN)
    private val AsciiDigit = Regex("[0-9]")

    /**
     * Runs [content] in every case. [keyTexts] gives the texts that must be displayed in that language; [allowAsciiDigits] lists
     * texts that legitimately carry ASCII digits in Bangla (identifiers such as phone or outlet codes, never amounts).
     * [minClickables] guards against a screen that silently rendered nothing.
     */
    fun everyCase(
        rule: ComposeContentTestRule,
        keyTexts: (AppLanguage) -> List<String>,
        allowAsciiDigits: Set<String> = emptySet(),
        minTexts: Int = 3,
        minClickables: Int = 1,
        content: @Composable () -> Unit,
    ) {
        // a compose rule accepts setContent once per test: the case is state, and each case is applied by changing it
        var current by mutableStateOf(FontScales.first() to Languages.first())
        rule.setContent {
            val (scale, language) = current
            // stringResource reads the Context's locale, not the theme: give the screen a context in the case's language
            val base = LocalContext.current
            val config = android.content.res.Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) }
            val localized = base.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = scale),
            ) { AronTheme(language, dark = false) { content() } }
        }
        for (scale in FontScales) for (language in Languages) {
            val label = "language=$language fontScale=$scale"
            current = scale to language
            rule.waitForIdle()
            keyTexts(language).forEach { t -> runCatching { rule.onNodeWithText(t).assertIsDisplayed() }.onFailure { throw AssertionError("$label: key text not displayed: $t", it) } }
            assertNoTruncation(rule, label, minTexts)
            assertTouchTargets(rule, label, minClickables)
            if (language == AppLanguage.BN) assertBengaliDigits(rule, label, allowAsciiDigits)
        }
    }

    /** No text may be cut off, run past its box, or overflow its height (reads the UNMERGED tree: merged rows hide trailing texts). */
    fun assertNoTruncation(rule: ComposeContentTestRule, label: String = "", minTexts: Int = 1) {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$label: only ${nodes.size} text nodes, expected at least $minTexts", nodes.size >= minTexts)
        val bad = nodes.mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.firstOrNull() ?: return@mapNotNull null
            val len = r.layoutInput.text.text.trimEnd().length
            val cutOff = r.getLineEnd(r.lineCount - 1, visibleEnd = true) < len
            val tooWide = (0 until r.lineCount).any { r.layoutInput.constraints.hasBoundedWidth && r.getLineRight(it) > r.layoutInput.constraints.maxWidth + 1f }
            if (cutOff || tooWide || r.didOverflowHeight) "${node.config.getOrNull(SemanticsProperties.Text)} cutOff=$cutOff tooWide=$tooWide overflowH=${r.didOverflowHeight}" else null
        }
        assertTrue("$label: text clipped: $bad", bad.isEmpty())
    }

    /** Every clickable or long-clickable node is at least 48 dp in both directions. */
    fun assertTouchTargets(rule: ComposeContentTestRule, label: String = "", minClickables: Int = 1) {
        val clickable = rule.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).fetchSemanticsNodes()
        assertTrue("$label: only ${clickable.size} clickable nodes, expected at least $minClickables", clickable.size >= minClickables)
        with(rule.density) {
            val small = clickable.filter { it.size.height.toDp() < 47.5.dp || it.size.width.toDp() < 47.5.dp }
                .map { "${it.config.getOrNull(SemanticsProperties.Text)} size=${it.size}" }
            assertTrue("$label: touch targets under 48 dp: $small", small.isEmpty())
        }
    }

    /** In Bangla every shown amount and count uses Bengali digits; texts in [allow] (identifiers) are exempt. */
    fun assertBengaliDigits(rule: ComposeContentTestRule, label: String = "", allow: Set<String> = emptySet()) {
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { a -> a.text } }
        val ascii = texts.filter { AsciiDigit.containsMatchIn(it) && allow.none { a -> it.contains(a) } }
        assertTrue("$label: ASCII digits in Bangla: $ascii", ascii.isEmpty())
    }
}

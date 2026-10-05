package com.aktcl.aron.core.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.aktcl.aron.core.common.AppLanguage

/**
 * The Bangla/English switch. The label is written in the language it switches to, in that language's font, so a
 * user who cannot read the current language can still find it.
 */
@Composable
fun LanguageToggle(current: AppLanguage, onSelect: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    val target = if (current == AppLanguage.BN) AppLanguage.EN else AppLanguage.BN
    val label = stringResource(if (target == AppLanguage.EN) R.string.core_ui_language_english else R.string.core_ui_language_bangla)
    val family: FontFamily = AronFonts.forLanguage(target)
    TextButton(onClick = { onSelect(target) }, modifier = modifier) {
        Text(text = label, fontFamily = family)
    }
}

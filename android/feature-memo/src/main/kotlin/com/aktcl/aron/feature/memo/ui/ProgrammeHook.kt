package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.AronTextButton
import com.aktcl.aron.feature.memo.R

/** The vendor's programme pages whose figures wait for the owner's programme go-ahead (docs/27, standing rule 2). */
enum class ProgrammeHook { TARGET, KPI, BRAND_KPI }

object ProgrammeHookTags { const val SCREEN = "programme_hook" }

/** The screen title the host puts on its bar for a hook page (manual 10, 60, 61). */
@Composable
fun programmeHookTitle(kind: ProgrammeHook): String = stringResource(
    when (kind) { ProgrammeHook.TARGET -> R.string.hook_target_title; ProgrammeHook.KPI -> R.string.hook_kpi_title; ProgrammeHook.BRAND_KPI -> R.string.hook_brand_title },
)

/**
 * A hook page for a deferred programme (target and achievement by SKU, KPI Dashboard, Brand wise KPI): it says what the earlier app shows
 * on this page and that the figures arrive later. It shows no figure, invents none and never routes a button to another screen; the way back is
 * the bar's back and the button here. Works offline (it reads nothing).
 */
@Composable
fun ProgrammeHookContent(kind: ProgrammeHook, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val lines = when (kind) {
        ProgrammeHook.TARGET -> listOf(stringResource(R.string.hook_target_what))
        ProgrammeHook.KPI -> listOf(R.string.hook_kpi_volume, R.string.hook_kpi_memo, R.string.hook_kpi_blm, R.string.hook_kpi_cpr).map { stringResource(it) }
        ProgrammeHook.BRAND_KPI -> listOf(stringResource(R.string.hook_brand_what))
    }
    ScrollColumn(
        modifier.background(look.page).testTag(ProgrammeHookTags.SCREEN), contentPadding = PaddingValues(AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        AronInfoCard(stringResource(R.string.hook_note))
        lines.forEach { line ->
            WhiteCard {
                Row(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    Text(line, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = look.purple)
                    Text(stringResource(R.string.hook_later), style = MaterialTheme.typography.labelLarge, color = look.inkMuted, textAlign = TextAlign.End)
                }
            }
        }
        AronTextButton(stringResource(R.string.hook_back), onBack, Modifier.fillMaxWidth())
    }
}

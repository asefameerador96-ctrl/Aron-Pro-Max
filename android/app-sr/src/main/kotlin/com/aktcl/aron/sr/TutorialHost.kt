package com.aktcl.aron.sr

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.home.TutorialJson
import com.aktcl.aron.feature.home.TutorialListContent

/**
 * Tutorial list (F-SR-048): the rows come from the bundle's `tutorials` section on the phone; playback hands the https link to the
 * system player or browser ONLY when the rep taps a row and the phone is online. Nothing autoplays and nothing is downloaded in the
 * background: this app never opens the media URL itself.
 */
@Composable
fun TutorialHost(day: SrDay, onBack: () -> Unit) {
    val context = LocalContext.current
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    var json by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(day) { json = day.tutorialsJson() }
    val items = remember(json, bangla) { TutorialJson.parse(json, bangla) }
    TutorialListContent(
        items, online = day.isOnline(), onBack = onBack,
        onPlay = { t ->
            val url = t.url ?: return@TutorialListContent
            if (day.isOnline()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
    )
}

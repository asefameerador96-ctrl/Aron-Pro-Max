package com.aktcl.aron.feature.tso

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronCard

/** A white kit card carrying one sentence: banners, hints and empty states (no coloured container: the words carry the meaning). */
@Composable
internal fun TsoNote(text: String, modifier: Modifier = Modifier) {
    AronCard(modifier) { Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalAronLook.current.ink) }
}

/** Section title: the kit's purple header with the short red rule (PARITY-SPEC s1). */
@Composable
internal fun TsoSectionTitle(text: String, modifier: Modifier = Modifier) {
    com.aktcl.aron.core.ui.aron.AronSectionHeader(text, modifier)
}


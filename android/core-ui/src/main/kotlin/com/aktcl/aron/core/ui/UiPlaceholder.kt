package com.aktcl.aron.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Placeholder for android:core-ui (docs/24 s1.2, s2.1). Replace with the module's screens, view models and use cases. */
object UiPlaceholder {
    const val MODULE: String = "core-ui"
}

@Composable
fun UiPlaceholderScreen() {
    Text(text = UiPlaceholder.MODULE)
}

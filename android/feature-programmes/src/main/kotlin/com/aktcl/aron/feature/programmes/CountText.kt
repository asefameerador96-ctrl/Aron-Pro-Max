package com.aktcl.aron.feature.programmes

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.PluralsRes
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

/** A count line with the right plural form (English "1 memo is", "2 memos are"); the number is written with the locale's digits and grouping. */
@Composable
internal fun countText(@PluralsRes id: Int, n: Int, locale: UiLocale): String =
    LocalContext.current.resources.getQuantityString(id, n, Formats.integer(n.toLong(), locale))

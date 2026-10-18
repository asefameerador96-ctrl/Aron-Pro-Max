package com.aktcl.aron.feature.memo.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.memo.R

/** The four category words of the memo (M3 subtotals "মোট সিগারেট" ...); an unknown code has no label and gets no subtotal row. */
object MemoCategories {
    private fun res(code: String?): Int? = when (code) {
        "cigarette" -> R.string.memo_cat_cigarette
        "bidi" -> R.string.memo_cat_bidi
        "lighter" -> R.string.memo_cat_lighter
        "match" -> R.string.memo_cat_match
        else -> null
    }

    /** The label in the app language, for the print (a plain read of the string resources). */
    fun label(context: Context, code: String?): String = res(code)?.let { context.getString(it) }.orEmpty()

    /** The label in the app language, for the screen. */
    @Composable
    fun label(code: String?): String = res(code)?.let { stringResource(it) }.orEmpty()
}

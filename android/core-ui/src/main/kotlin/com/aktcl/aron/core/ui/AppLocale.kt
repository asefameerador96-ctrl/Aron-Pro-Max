package com.aktcl.aron.core.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.aktcl.aron.core.common.AppLanguage
import java.util.Locale

/**
 * The per-app language (docs/24 s5.6): Bangla at first run, switchable to English. Kept in a small preferences file
 * (UI preference only, never business data, s5.2) because it must be readable synchronously in `attachBaseContext`
 * before any coroutine runs. Activities call [wrap] in `attachBaseContext` and `recreate()` after [set].
 */
object AppLocale {
    private const val PREFS = "aron_ui"
    private const val KEY = "language"

    fun current(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        if (stored == null) {
            prefs.edit().putString(KEY, AppLanguage.DEFAULT.tag).apply()
            return AppLanguage.DEFAULT
        }
        return AppLanguage.fromTag(stored)
    }

    /** Stores [language]; returns true when it changed (the caller then recreates the activity). */
    @SuppressLint("ApplySharedPref") // commit, not apply: the recreated activity reads the value immediately
    fun set(context: Context, language: AppLanguage): Boolean {
        if (current(context) == language) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
        return true
    }

    /** A context whose resources resolve in the stored language (values-bn or the English default). */
    fun wrap(base: Context): Context = wrap(base, current(base))

    fun wrap(base: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(if (language == AppLanguage.BN) "bn-BD" else "en")
        // The process default locale is deliberately NOT changed: String.format, SimpleDateFormat and friends without an
        // explicit Locale would then write Bengali digits into wire payloads, memo numbers and timestamps.
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

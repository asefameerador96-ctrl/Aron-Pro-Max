package com.aktcl.aron.core.system.language

import android.app.Activity
import android.content.Context
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale

/**
 * The Settings language switch (F-SYS-019): one tap, no confirmation. The choice is committed synchronously (so a kill
 * right after the tap keeps it), then the activity is recreated so every screen, digit and font follows at once. Screen
 * state survives through `rememberSaveable` and drafts through Room (docs/24 s5.8), so nothing typed is lost.
 */
object LanguageSwitch {
    /** Returns true when the language changed (and the activity is being recreated). */
    fun select(activity: Activity, language: AppLanguage): Boolean {
        if (!AppLocale.set(activity, language)) return false
        activity.recreate()
        return true
    }

    fun current(context: Context): AppLanguage = AppLocale.current(context)

    /**
     * A context in the rep's language for text built outside an activity (a worker's notification, a print job). The
     * application context alone would follow the phone's system language instead.
     */
    fun localized(context: Context): Context = AppLocale.wrap(context.applicationContext)
}

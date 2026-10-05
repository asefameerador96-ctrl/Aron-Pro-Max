package com.aktcl.aron.core.common

/**
 * The two app languages (docs/24 s5.6, docs/23 Q2): Bangla is the default at first run; English is the switch.
 * [tag] is the BCP 47 tag used for the per-app locale and for `UserSummary.locale` on the wire.
 */
enum class AppLanguage(val tag: String) {
    BN("bn"),
    EN("en"),
    ;

    companion object {
        val DEFAULT: AppLanguage = BN

        /** Maps a stored or server tag (`bn`, `en`, `bn-BD`, `en-US`) to a language; anything else is the default. */
        fun fromTag(tag: String?): AppLanguage = when (tag?.substringBefore('-')?.substringBefore('_')?.lowercase()) {
            "en" -> EN
            "bn" -> BN
            else -> DEFAULT
        }
    }
}

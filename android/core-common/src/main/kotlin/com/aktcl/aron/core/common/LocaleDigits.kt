package com.aktcl.aron.core.common

/**
 * Digits shown per language (docs/24 s5.6): Bengali digits (০-৯) when the app language is Bangla, ASCII otherwise.
 * Counts are grouped in threes (1,234,567), as F-SYS-051 says and as `Formats.money` does; the digits follow the language
 * (১,২৩৪,৫৬৭ in Bangla). Compact lakh/crore text is a separate helper of the dashboards, not this one.
 * Typed input (OTP, phone numbers, quantities) is normalised back to ASCII before validation (contract `PhoneBd`,
 * `BindDeviceRequest.otp`).
 */
object LocaleDigits {
    private const val BENGALI_ZERO = '০'

    /** Replaces every ASCII digit with its Bengali digit; other characters are untouched. */
    fun toBengali(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in '0'..'9') BENGALI_ZERO + (c - '0') else c)
    }

    /** Replaces every Bengali digit with its ASCII digit; other characters are untouched. */
    fun toAscii(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in BENGALI_ZERO..'৯') '0' + (c - BENGALI_ZERO) else c)
    }

    /** Converts the digits of an already formatted text (a date, a version, a time) for [language]. */
    fun localize(text: String, language: AppLanguage): String =
        if (language == AppLanguage.BN) toBengali(text) else toAscii(text)

    /** Formats an integer grouped in threes (1,234,567) in the digits of [language]. Money is not formatted here (`Formats.money`). */
    fun formatInteger(value: Long, language: AppLanguage, grouping: Boolean = true): String {
        val plain = if (grouping) groupWestern(value) else value.toString()
        return localize(plain, language)
    }

    /** 1234567 -> "1,234,567"; -1000 -> "-1,000". Long.MIN_VALUE is handled through its string form. */
    fun groupWestern(value: Long): String {
        val raw = value.toString()
        val negative = raw.startsWith('-')
        val digits = if (negative) raw.substring(1) else raw
        if (digits.length <= 3) return raw
        val groups = ArrayDeque<String>()
        var end = digits.length
        while (end > 0) {
            val start = maxOf(0, end - 3)
            groups.addFirst(digits.substring(start, end))
            end = start
        }
        return (if (negative) "-" else "") + groups.joinToString(",")
    }
}

package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.Instant

/** Server timestamps are UTC instants; a date shown to the SR is the Asia/Dhaka calendar date (docs/24 s3.8), never the first ten characters of the UTC text. */
object DhakaTime {
    /** The Dhaka date `yyyy-MM-dd` of an ISO instant (`2026-10-31T18:00:00Z` is 2026-11-01); a plain date text passes through; anything else is null. */
    fun dateOf(text: String?): String? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return s
        val ms = runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull() ?: return null
        return BusinessDate.of(ms).toString()
    }

    /** The epoch milliseconds of an ISO instant, or null. */
    fun ms(text: String?): Long? = text?.let { runCatching { Instant.parse(it.trim()).toEpochMilliseconds() }.getOrNull() }
}

package com.aktcl.aron.feature.home

import kotlinx.datetime.LocalDate

/** What the day may do with the cached bundle (F-SR-063, docs/24 s5 "Stale bundle"). */
sealed interface BundleFreshness {
    /** Age in business days; 0 is today's bundle. */
    val ageDays: Int
    val canSell: Boolean
    val showBanner: Boolean

    /** Memos and dues are shown but cannot be changed. */
    val readOnly: Boolean
    val markRecordsStale: Boolean

    /** Check-in and check-out never depend on the bundle. */
    val checkInAllowed: Boolean get() = true

    data object Fresh : BundleFreshness {
        override val ageDays = 0
        override val canSell = true
        override val showBanner = false
        override val readOnly = false
        override val markRecordsStale = false
    }

    /** Older than today but within `cfg.bundle.stale_max_days`: sells with a banner and flags every row `bundle_stale`. */
    data class Stale(override val ageDays: Int) : BundleFreshness {
        override val canSell = true
        override val showBanner = true
        override val readOnly = false
        override val markRecordsStale = true
    }

    /** Beyond the limit: memos and dues read-only, no new sale until a bundle downloads. */
    data class Expired(override val ageDays: Int) : BundleFreshness {
        override val canSell = false
        override val showBanner = true
        override val readOnly = true
        override val markRecordsStale = true
    }

    /** No bundle at all (first install offline). */
    data object Missing : BundleFreshness {
        override val ageDays = Int.MAX_VALUE
        override val canSell = false
        override val showBanner = true
        override val readOnly = true
        override val markRecordsStale = true
    }

    companion object {
        /** [bundleDate] is the bundle's `valid_for_business_date`; [today] the trusted Dhaka business date; limit default 2. */
        fun of(bundleDate: LocalDate?, today: LocalDate, staleMaxDays: Int = 2): BundleFreshness {
            if (bundleDate == null) return Missing
            val age = today.toEpochDays() - bundleDate.toEpochDays()
            return when {
                age <= 0 -> Fresh // a bundle for today, or a prefetched one for tomorrow opened early
                age <= staleMaxDays -> Stale(age)
                else -> Expired(age)
            }
        }
    }
}

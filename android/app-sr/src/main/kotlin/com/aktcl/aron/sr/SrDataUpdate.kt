package com.aktcl.aron.sr

import com.aktcl.aron.core.sync.BundleOutcome
import com.aktcl.aron.feature.home.DataUpdatePhase
import com.aktcl.aron.feature.home.FirstBundleState

/** What the SR's data-update page shows for the bundle download of the moment (SR manual 08). */
data class SrDataUpdateUi(val phase: DataUpdatePhase, val reason: FirstBundleState?, val done: Int?, val total: Int?)

/** The pure mapping behind the data-update page of [SrApp], so it is tested without a screen. */
object SrDataUpdate {
    /** The one version number the page shows: the version name (the login footer and Settings agree). */
    fun versionName(): String = BuildConfig.VERSION_NAME

    /**
     * Running while a download is in flight or none has answered yet; otherwise the prompt with the reason of the stopped attempt
     * (offline, sign-in ended, any other failure).
     */
    fun ui(downloading: Boolean, outcome: BundleOutcome?, progress: Pair<Int, Int>?): SrDataUpdateUi {
        val running = downloading || outcome == null
        return SrDataUpdateUi(
            phase = if (running) DataUpdatePhase.RUNNING else DataUpdatePhase.PROMPT,
            reason = when {
                running -> null
                outcome == BundleOutcome.OFFLINE -> FirstBundleState.OFFLINE
                outcome == BundleOutcome.AUTH_REQUIRED -> FirstBundleState.AUTH
                else -> FirstBundleState.FAILED
            },
            done = progress?.first, total = progress?.second,
        )
    }
}

package com.aktcl.aron.sr

import com.aktcl.aron.core.sync.BundleOutcome
import com.aktcl.aron.feature.home.ConfigUpdate

/**
 * SRM-B-52: what the Update Configuration tile tells the rep for a bundle outcome. A newer snapshot already on the phone
 * (OLDER_IGNORED) and a 304 both mean the phone holds the latest; a refused sign-in, a failure or no outcome is a failure.
 */
internal fun configUpdateOf(outcome: BundleOutcome?): ConfigUpdate = when (outcome) {
    BundleOutcome.APPLIED, BundleOutcome.PREFETCH_PROMOTED -> ConfigUpdate.UPDATED
    BundleOutcome.UNCHANGED, BundleOutcome.OLDER_IGNORED -> ConfigUpdate.ALREADY_CURRENT
    else -> ConfigUpdate.FAILED
}

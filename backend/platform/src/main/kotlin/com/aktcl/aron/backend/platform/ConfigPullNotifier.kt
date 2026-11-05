package com.aktcl.aron.backend.platform

/**
 * A data-only `config_pull` push to the phones of the given users (D-TSO-MAN-57, D-TSO-MAN-62; X4a ask 11): each phone pulls the
 * CONFIG delta and nothing else; no business data travels, and nothing polls. Called AFTER a config change committed (the
 * ConfigCommitListener seam of backend config), once per committed change of `cfg.day.late_sync_after_final_policy`, with the
 * users of the closed zone-days; implemented by backend:notify. Best effort and asynchronous: a lost push only leaves the phone
 * on the next config delta, which its own triggers make anyway.
 */
fun interface ConfigPullNotifier {
    /** Pushes one `config_pull` to every live phone of [userIds]; [reason] is for the logs. */
    fun configPull(reason: String, userIds: Collection<Long>)

    companion object {
        val NONE = ConfigPullNotifier { _, _ -> }
    }
}

package com.aktcl.aron.backend.platform

/**
 * A data-only `config_pull` push to the phones of the users inside a config scope (D-TSO-MAN-57, X4a ask 11): the phone pulls the
 * CONFIG delta and nothing else; no business data travels. [ConfigService][com.aktcl.aron.backend.config] calls it AFTER the
 * config change committed, once per change that moved `cfg.day.late_sync_after_final_policy`; implemented by backend:notify.
 * Best effort and asynchronous: a lost push only leaves the phone on the next config delta, which its own triggers make anyway.
 */
fun interface ConfigPullNotifier {
    /** [scopes]: the (scope_type, scope_id) pairs the change wrote; `global` has id 0. */
    fun configPull(reason: String, scopes: List<Pair<String, Long>>)

    companion object {
        val NONE = ConfigPullNotifier { _, _ -> }
    }
}

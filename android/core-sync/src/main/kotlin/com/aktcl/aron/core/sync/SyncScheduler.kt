package com.aktcl.aron.core.sync

import com.aktcl.aron.contract.SyncTrigger

/**
 * The one call feature modules make after committing a capture (docs/24 s4.7). Stable interface published for the feature
 * lanes on 2026-10-07; the WorkManager-backed implementation (debounce, constraints, expedited Sales Submit, periodic only
 * while rows are pending) is F-SYS-011/F-SYS-046. Calling it never blocks and never touches the network on the caller's
 * thread: it only schedules. Captures are already safe in Room and the outbox before this is called.
 */
interface SyncScheduler {
    /**
     * Asks for an upload of [userId]'s outbox. [trigger] is `write_debounce` after an ordinary save, `day_submit` for Sales
     * Submit (sent at once), `checkout` after check-out (sent after a random jitter), `manual` for the Sync button.
     */
    fun requestSync(userId: Long, trigger: SyncTrigger = SyncTrigger.WRITE_DEBOUNCE)

    /** Does nothing: for previews, tests, and screens built before F-SYS-011 lands. */
    object None : SyncScheduler {
        override fun requestSync(userId: Long, trigger: SyncTrigger) = Unit
    }
}

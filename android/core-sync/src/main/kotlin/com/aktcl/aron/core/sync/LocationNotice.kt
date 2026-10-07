package com.aktcl.aron.core.sync

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.ConsentRepository
import com.aktcl.aron.core.database.repo.ConsentStamp
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.session.TrustedClockSource

/** What the shells need to decide on the notice screen (F-SYS-075). */
data class NoticeState(
    /** Not accepted yet for [LocationNotice.POLICY_VERSION]: the screen shows after login. */
    val needed: Boolean,
    /** `cfg.app.location_notice_required` (default true): no sale (and no work screen) before acceptance; else "later" is offered. */
    val required: Boolean,
)

/**
 * The employee-location notice (F-SYS-075, docs/21 s4.7, D-120): shown after the first login of each user, Bangla and
 * English, offline like everything else. Acceptance is stored and queued once ([ConsentRepository]) and rides the next
 * upload; the network is never needed to accept. [POLICY_VERSION] moves when HR and legal change the text (D-570): every
 * user then sees and accepts the new text once.
 */
class LocationNotice(
    private val db: suspend (userId: Long) -> AronDatabase,
    private val clock: TrustedClockSource,
    private val scheduler: SyncScheduler,
    /** Live connectivity at capture (the envelope's `captured_offline`), not the login mode. */
    private val offline: () -> Boolean,
) {
    suspend fun state(userId: Long): NoticeState {
        val database = db(userId)
        val accepted = ConsentRepository(database).acceptedAt(POLICY_VERSION) != null
        return NoticeState(needed = !accepted, required = required(ReferenceRepository(database)))
    }

    /** True when a sale may start: accepted, or the setting does not require it. */
    suspend fun allowsSale(userId: Long): Boolean = state(userId).let { !it.needed || !it.required }

    /**
     * Stores the acceptance of the notice shown at [shownAtMs] (trusted ms) in [locale] (`bn` or `en`) and asks for an
     * upload when a record was queued. A second call queues nothing.
     */
    suspend fun accept(userId: Long, locale: String, shownAtMs: Long): Boolean {
        val database = db(userId)
        val ref = ReferenceRepository(database)
        val now = clock.nowMs()
        val meta = CaptureMeta(
            businessDate = clock.businessDate().toString(),
            capturedAt = SyncEngine.iso(now),
            capturedElapsedMs = clock.elapsedRealtimeMs(),
            bootCount = clock.bootCountNow(),
            clockOffsetMs = clock.clockOffsetMs(),
            capturedOffline = runCatching { offline() }.getOrDefault(true),
            routeId = null,
            bundleVersion = ref.bundleVersion(),
            configVersion = ref.configVersionHeld(),
        )
        val queued = ConsentRepository(database).accept(POLICY_VERSION, locale, ConsentStamp(ClientIds.newUuid(), meta, SyncEngine.iso(minOf(shownAtMs, now))))
        if (queued) runCatching { scheduler.requestSync(userId) }
        return queued
    }

    private suspend fun required(ref: ReferenceRepository): Boolean =
        ref.config(ConsentRepository.CFG_REQUIRED, SyncEngine.iso(clock.nowMs()))?.trim()?.removeSurrounding("\"") != "false"

    companion object {
        /** No validated network now (the probe the SR day uses for `captured_offline`). */
        fun offlineProbe(context: android.content.Context): () -> Boolean = {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true
        }

        /** Version of the notice text in the string resources (`location_notice_*`); bump with every text change. */
        const val POLICY_VERSION = 1
    }
}

package com.aktcl.aron.core.sync.push

import kotlin.random.Random

/** What an FCM data message makes the phone fetch. Never an upload (docs/24 s4.7, docs/17 s6.7). */
enum class PushPullKind(val wire: String) {
    /** `GET /v1/sync/delta` of the held day: tasks, routes, prices and the config riding with it. */
    BUNDLE("bundle"),

    /** `GET /v1/config/delta`. */
    CONFIG("config"),

    /**
     * `GET /v1/config/delta` for an urgent key (F-SYS-073: kill switch, `min_version`, `blocked_versions`, `sync_hold_s`, a
     * revert): its own job, so a waiting ordinary pull (up to 120 s) never holds it back, and a small reserve over the
     * daily cap ([com.aktcl.aron.core.sync.ResumeConfigCheck.URGENT_RESERVE]).
     */
    CONFIG_URGENT("config_urgent"),

    /** `GET /v1/devices/me/commands` and apply (lane mdm): needs no signed-in user. */
    MDM("mdm"),
}

/** What the phone shows for a push; the push itself carries no business data (N-037). */
sealed interface PushNotice {
    /** `sync_nudge` with `reason = task_assigned`: a fixed Bangla/English text; the task arrives with the pull. */
    data object TaskAssigned : PushNotice

    // tso-manual A1 (D-TSO-MAN-26, 31): three fixed Bangla/English notices; the push carries the reason only, never business data.

    /** `sync_nudge` with `reason = notification`: a row was added to the user's bell inbox; the TSO app re-reads its unread count. */
    data object InboxUpdated : PushNotice

    /** `sync_nudge` with `reason = ticket_updated`: support changed one of the user's tickets; the tap opens the ticket dashboard. */
    data object TicketUpdated : PushNotice

    /**
     * `sync_nudge` with `reason = day_final_submitted`: the zone-day was final submitted while the late-sale policy holds or
     * refuses late captures (quarantine or reject), so its text says new sales are closed and the SR and AMO apps lock its
     * capture once they hold the day state and the policy (both pulled). Owner decision 2026-11-04: the server never sends it
     * under accept_and_flag, the shipped default; that close sends `day_state_changed`, which shows nothing.
     */
    data object DayFinalSubmitted : PushNotice

    /** `announcement`: the text the admin typed, Bangla when the app runs in Bangla and a Bangla text was given. */
    data class Announcement(val titleEn: String, val titleBn: String?, val bodyEn: String, val bodyBn: String?) : PushNotice {
        fun title(bangla: Boolean) = if (bangla && !titleBn.isNullOrBlank()) titleBn else titleEn
        fun body(bangla: Boolean) = if (bangla && !bodyBn.isNullOrBlank()) bodyBn else bodyEn
    }
}

/**
 * One FCM data message (N-038), read from the data keys the backend sends (backend `notify/Push.kt` and
 * `NotificationRequest`): `kind` = `sync_nudge` (with `reason`, e.g. `task_assigned`), `announcement`, `config_pull` or
 * `bundle_pull`; optional `pull_after_s`, `urgent`, `title_en`/`title_bn`/`body_en`/`body_bn`. The docs/19 s4.1 form
 * `{type: "cfg", version, pull_after_s}` is read as a config pull. Anything else is ignored.
 */
data class PushMessage(
    val kind: String,
    val reason: String?,
    val pulls: Set<PushPullKind>,
    val notice: PushNotice?,
    /** The server's `pull_after_s`, already clamped to the allowed window; null when it sent none. */
    val pullAfterS: Int?,
    val urgent: Boolean,
    /**
     * X4a fix round 4 (checker medium): the "Day closed ... new sales are closed" notice of an earlier `day_final_submitted`
     * is taken off the tray ([PushNotices.withdrawDay]). True for `day_state_changed` and for the `config_pull` of reason
     * [REASON_LATE_POLICY_RELAXED] only: the server sends them when the late-sale policy in force is accept_and_flag, so that notice
     * is no longer true.
     */
    val withdrawsDayNotice: Boolean = false,
) {
    /**
     * How long to wait before pulling: the server's `pull_after_s`, else a random spread so a fan-out never arrives as one
     * wave (T-2-55): 0 to 20 s for an urgent push or a task (the rep is waiting for it), 0 to 120 s otherwise
     * (`cfg.ops.push_jitter_s`).
     */
    fun pullDelayMs(random: Random): Long {
        val seconds = pullAfterS ?: random.nextInt(0, jitterCapS(urgent || reason == REASON_TASK_ASSIGNED) + 1)
        return seconds * 1000L
    }

    companion object {
        const val KIND_SYNC_NUDGE = "sync_nudge"
        const val KIND_ANNOUNCEMENT = "announcement"
        const val KIND_CONFIG_PULL = "config_pull"
        const val KIND_BUNDLE_PULL = "bundle_pull"
        const val KIND_MDM_NUDGE = "mdm_nudge"
        const val REASON_TASK_ASSIGNED = "task_assigned"
        // tso-manual A1: the reasons of the TSO manual build (backend notify `PushReasons`, B3 `PUSH_DAY_FINAL_SUBMITTED`)
        const val REASON_NOTIFICATION = "notification"
        const val REASON_TICKET_UPDATED = "ticket_updated"
        const val REASON_DAY_FINAL_SUBMITTED = "day_final_submitted"

        /**
         * X4a fix round (owner decision 2026-11-04): a zone-day close under accept_and_flag (backend `PUSH_DAY_STATE_CHANGED`),
         * and since fix round 4 also the push after an admin returns the late-sale policy to accept_and_flag on a closed
         * zone-day (backend `LatePolicyPush`). The server accepts and flags a late sale, so no notice is shown and an earlier
         * "Day closed" notice is withdrawn; the pulls bring the day state and the policy, which unlocks a phone that held a
         * locking one.
         */
        const val REASON_DAY_STATE_CHANGED = "day_state_changed"

        /**
         * X4a ask 11 (D-TSO-MAN-57, backend `LatePolicyPush.LATE_POLICY_REASON`): the reason of the `config_pull` the server sends once when an admin
         * changes `cfg.day.late_sync_after_final_policy`. It is a CONFIG pull only (urgent, so outside the daily cap): no bundle, no
         * sync, no notice, no business data; the phone reads the new policy and its day lock follows at once. Idempotent: a repeat
         * is one more conditional config read (ETag), nothing is applied twice.
         */
        const val REASON_LATE_POLICY_CHANGED = "late_sync_policy_changed"

        /**
         * D-TSO-MAN-62 (one mechanism): the same `config_pull`, sent when the policy now in force no longer locks (an admin returned it to
         * accept_and_flag). Besides pulling the config, the phone takes an earlier "Day closed ... new sales are closed" notice off the
         * tray ([withdrawsDayNotice]): nothing may tell a rep that sales are closed while the server accepts them. Under a locking
         * policy the reason is [REASON_LATE_POLICY_CHANGED] and nothing is withdrawn.
         */
        const val REASON_LATE_POLICY_RELAXED = "late_sync_policy_relaxed"

        /**
         * The window of a late-policy `config_pull`'s `pull_after_s` (`cfg.ops.config_pull_spread_s`, registry max 900): 20 s at pilot
         * size, minutes at fleet size, so the phones do not pull in one second. Any other urgent push keeps [URGENT_JITTER_S].
         */
        const val POLICY_PULL_MAX_S = 900

        /** The fixed notice of a `sync_nudge` reason; an unknown reason pulls without a notice. */
        fun noticeFor(reason: String?): PushNotice? = when (reason) {
            REASON_TASK_ASSIGNED -> PushNotice.TaskAssigned
            REASON_NOTIFICATION -> PushNotice.InboxUpdated
            REASON_TICKET_UPDATED -> PushNotice.TicketUpdated
            REASON_DAY_FINAL_SUBMITTED -> PushNotice.DayFinalSubmitted
            else -> null // REASON_DAY_STATE_CHANGED among them: a close under accept_and_flag shows nothing
        }

        /**
         * What a `sync_nudge` of [reason] pulls: the bundle delta, and for the two day reasons also the config delta. X4a fix
         * round (checker low): the phone locks only once it holds both the day state (bundle) and a locking late-sale policy
         * (config); the config is not part of the bundle delta, so an admin's switch to quarantine or reject made before the
         * close reaches the phone with the close's own push instead of at the next resume check. Fix round 4 (checker
         * medium): `day_state_changed` pulls the config too, so a phone still holding a locking policy that an admin has
         * since withdrawn unlocks with the push (a close under accept_and_flag, or the push after the policy change).
         */
        fun nudgePulls(reason: String?): Set<PushPullKind> =
            if (reason == REASON_DAY_FINAL_SUBMITTED || reason == REASON_DAY_STATE_CHANGED) setOf(PushPullKind.BUNDLE, PushPullKind.CONFIG) else setOf(PushPullKind.BUNDLE)
        const val ORDINARY_JITTER_S = 120
        const val URGENT_JITTER_S = 20

        fun jitterCapS(urgent: Boolean) = if (urgent) URGENT_JITTER_S else ORDINARY_JITTER_S

        fun parse(data: Map<String, String>): PushMessage? {
            val kind = data["kind"]?.trim() ?: if (data["type"]?.trim() == "cfg") KIND_CONFIG_PULL else return null
            val urgent = data["urgent"]?.trim()?.lowercase() == "true"
            val reason = data["reason"]?.trim()?.takeIf { it.isNotEmpty() }
            // A value outside the window is clamped, never trusted: a bad sender cannot hold a pull back for hours.
            val policyPull = kind == KIND_CONFIG_PULL && (reason == REASON_LATE_POLICY_CHANGED || reason == REASON_LATE_POLICY_RELAXED)
            val pullAfter = data["pull_after_s"]?.trim()?.toIntOrNull()?.coerceIn(0, if (policyPull) POLICY_PULL_MAX_S else jitterCapS(urgent))
            return when (kind) {
                KIND_SYNC_NUDGE -> PushMessage(
                    kind, reason, nudgePulls(reason), noticeFor(reason), pullAfter, urgent, withdrawsDayNotice = reason == REASON_DAY_STATE_CHANGED,
                )
                KIND_BUNDLE_PULL -> PushMessage(kind, reason, setOf(PushPullKind.BUNDLE), null, pullAfter, urgent)
                KIND_MDM_NUDGE -> PushMessage(kind, reason, setOf(PushPullKind.MDM), null, pullAfter, urgent = true)
                KIND_CONFIG_PULL -> PushMessage(
                    kind, reason, setOf(if (urgent) PushPullKind.CONFIG_URGENT else PushPullKind.CONFIG), null, pullAfter, urgent,
                    withdrawsDayNotice = reason == REASON_LATE_POLICY_RELAXED,
                )
                KIND_ANNOUNCEMENT -> {
                    val titleEn = data["title_en"]?.trim()?.take(80)
                    val bodyEn = data["body_en"]?.trim()?.take(300)
                    if (titleEn.isNullOrEmpty() || bodyEn.isNullOrEmpty()) return null
                    val notice = PushNotice.Announcement(titleEn, data["title_bn"]?.trim()?.take(80), bodyEn, data["body_bn"]?.trim()?.take(300))
                    PushMessage(kind, reason, emptySet(), notice, pullAfter, urgent)
                }
                else -> null
            }
        }
    }
}

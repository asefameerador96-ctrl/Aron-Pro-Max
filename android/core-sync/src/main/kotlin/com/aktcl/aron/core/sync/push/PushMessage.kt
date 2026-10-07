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
}

/** What the phone shows for a push; the push itself carries no business data (N-037). */
sealed interface PushNotice {
    /** `sync_nudge` with `reason = task_assigned`: a fixed Bangla/English text; the task arrives with the pull. */
    data object TaskAssigned : PushNotice

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
        const val REASON_TASK_ASSIGNED = "task_assigned"
        const val ORDINARY_JITTER_S = 120
        const val URGENT_JITTER_S = 20

        fun jitterCapS(urgent: Boolean) = if (urgent) URGENT_JITTER_S else ORDINARY_JITTER_S

        fun parse(data: Map<String, String>): PushMessage? {
            val kind = data["kind"]?.trim() ?: if (data["type"]?.trim() == "cfg") KIND_CONFIG_PULL else return null
            val urgent = data["urgent"]?.trim()?.lowercase() == "true"
            val reason = data["reason"]?.trim()?.takeIf { it.isNotEmpty() }
            // A value outside the window is clamped, never trusted: a bad sender cannot hold a pull back for hours.
            val pullAfter = data["pull_after_s"]?.trim()?.toIntOrNull()?.coerceIn(0, jitterCapS(urgent))
            return when (kind) {
                KIND_SYNC_NUDGE -> PushMessage(
                    kind, reason, setOf(PushPullKind.BUNDLE),
                    if (reason == REASON_TASK_ASSIGNED) PushNotice.TaskAssigned else null, pullAfter, urgent,
                )
                KIND_BUNDLE_PULL -> PushMessage(kind, reason, setOf(PushPullKind.BUNDLE), null, pullAfter, urgent)
                KIND_CONFIG_PULL -> PushMessage(
                    kind, reason, setOf(if (urgent) PushPullKind.CONFIG_URGENT else PushPullKind.CONFIG), null, pullAfter, urgent,
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

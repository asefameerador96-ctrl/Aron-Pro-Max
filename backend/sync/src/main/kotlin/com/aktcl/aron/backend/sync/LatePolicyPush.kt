package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Nudger
import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * tso-manual X4a fix round 4 (checker medium; owner decision 2026-11-04, D-TSO-MAN-HO-02): the push after a committed
 * change of `cfg.day.late_sync_after_final_policy`.
 *
 * The SR and AMO phones lock a final-submitted route-day only while they hold a locking policy (quarantine or reject,
 * `FinalSubmitLock.lockApplies`), and the policy is config: no push followed a config change, so a phone locked under
 * quarantine kept refusing calls, sales and Mark Paid, and kept its "Day closed ... sales are not accepted" banner and tray
 * notice, after an admin returned the key to accept_and_flag (a revert, a rollback or a new value) and the server already
 * accepted and flagged its sales. The other direction (a quarantine set after a close sent the silent `day_state_changed`)
 * waited for the next config delta too (D-X4a-28 case c, ask 11).
 *
 * Now, after every committed version that touches the key, the SR and AMO users of every zone-day of today or yesterday
 * (Asia/Dhaka) with a live Final Submit ([zoneFieldUsers], the close's own recipients) get one data-only nudge whose reason
 * is the close's rule applied to the policy now in force ([pushReasonFor]): `day_final_submitted` under quarantine or reject
 * (the phone shows "Day closed", pulls the day state and the policy, and locks), else `day_state_changed` (the phone shows
 * nothing, withdraws an earlier "Day closed" notice, pulls the day state and the policy, and unlocks). Only the phones that
 * can hold a lock now are nudged (a zone that is not closed holds no lock; its phones pull the policy with the close's own
 * push), so this is never a national fan-out. A lost nudge only delays the phone to its next config delta, as before.
 *
 * The policy is read from the committed rows at [clock] now (the global row valid then, else the registry default, as
 * `DbServerConfig` reads it), not through the 30 s ServerConfig cache, so the reason follows the change just committed. A
 * change dated later (a scheduled start or end) is delivered by the pull; the phone applies it at its own start on trusted
 * time (`ReferenceRepository.configInForce`).
 */
class LatePolicyPush(private val db: Database, private val nudger: Nudger, private val clock: AronClock = AronClock.SYSTEM) {
    private val log = LoggerFactory.getLogger("aron.day")

    /** A committed config version touched [keys]: nudges only when the late-sale policy is among them. Never throws. */
    fun onConfigCommitted(keys: Set<String>) {
        if (IngestService.LATE_POLICY_KEY !in keys) return
        runCatching { send() }.onFailure { log.warn("late policy push failed: ${it.javaClass.simpleName}") }
    }

    /** Sends the nudges now; returns the reason and the users nudged (for tests and the log). */
    fun send(): Pair<String, List<Long>> {
        val now = clock.now()
        val today = LocalDate.ofInstant(now, DHAKA)
        val (policy, users) = db.jdbi.withHandle<Pair<String, List<Long>>, Exception> { h -> latePolicyAt(h, now) to closedZoneFieldUsers(h, today.minusDays(1), today) }
        val reason = pushReasonFor(policy)
        users.forEach { u -> runCatching { nudger.nudge(u, reason) } }
        log.info("late policy changed policy=$policy reason=$reason users=${users.size}")
        return reason to users
    }

    companion object {
        private val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

        /** The close's push rule for a policy ([finalSubmitPushReason] reads the policy from the ServerConfig instead). */
        fun pushReasonFor(policy: String): String =
            if (policy in IngestService.LOCKING_LATE_POLICIES) PUSH_DAY_FINAL_SUBMITTED else PUSH_DAY_STATE_CHANGED

        /** The late-sale policy in force at [at] from the committed rows: the global row valid then, else the registry default. */
        internal fun latePolicyAt(h: Handle, at: Instant): String {
            val raw = h.createQuery(
                """
                SELECT COALESCE(
                  (SELECT value FROM app.cfg_value WHERE key = :k AND scope_type = 'global' AND effective_from <= :at
                      AND (effective_to IS NULL OR effective_to > :at) ORDER BY effective_from DESC LIMIT 1),
                  (SELECT default_value FROM app.cfg_key WHERE key = :k))::text
                """.trimIndent(),
            ).bind("k", IngestService.LATE_POLICY_KEY).bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).mapTo(String::class.java).findOne().orElse(null)
            return IngestService.latePolicyOf(raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() })
        }

        /** The field users ([zoneFieldUsers]) of every zone-day from [from] to [to] whose Final Submit is live (not reopened). */
        internal fun closedZoneFieldUsers(h: Handle, from: LocalDate, to: LocalDate): List<Long> =
            h.createQuery("SELECT zone_id, business_date FROM app.final_submit WHERE reopened_at IS NULL AND business_date BETWEEN :f AND :t ORDER BY zone_id, business_date")
                .bind("f", from).bind("t", to).map { rs, _ -> rs.getLong(1) to rs.getObject(2, LocalDate::class.java) }.list()
                .flatMap { (zone, date) -> zoneFieldUsers(h, zone, date) }.distinct().sorted()
    }
}

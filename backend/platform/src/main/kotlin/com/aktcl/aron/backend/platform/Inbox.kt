package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.NotificationKind
import org.jdbi.v3.core.Handle
import java.time.LocalDate

/**
 * One notice for one user's bell inbox (`app.user_notification`, V0133; D-TSO-MAN-26). The texts are fixed by the
 * producer in both languages (constraint 8); [titleBn] and [bodyBn] may be null only while no Bangla text exists, and
 * the app then shows the English text.
 *
 * - [kind] is a contract `NotificationKind` wire value and fixes [refType]: `ticket_updated` with `support_ticket`,
 *   `leave_decided` with `leave_application`, `final_submit_auto` with `zone_day`.
 * - [refId] is the ticket UUID, the leave UUID, or `<zone_id>:<business_date>` for a zone-day ([Inbox.zoneDayRefId]);
 *   1 to 64 characters (D-F1-06).
 * - [dedupeKey] is the producer's idempotency key, unique per recipient (R-F-04): the same key for the same user is
 *   stored once; the same key may reach several users. 1 to 160 characters.
 *
 * The bounds are the V0133 column CHECKs and the contract's `NotificationItem`, checked here so a producer bug fails in
 * the producer with a clear message rather than as a database error.
 */
data class InboxItem(
    val userId: Long,
    val kind: String,
    val refType: String,
    val refId: String,
    val titleEn: String,
    val titleBn: String?,
    val bodyEn: String,
    val bodyBn: String?,
    val dedupeKey: String,
) {
    init {
        require(userId > 0) { "userId must be a user id" }
        require(kind in Inbox.REF_TYPE_OF) { "kind $kind is not a NotificationKind" }
        require(Inbox.REF_TYPE_OF[kind] == refType) { "kind $kind takes ref_type ${Inbox.REF_TYPE_OF[kind]}, not $refType" }
        require(refId.length in 1..64) { "ref_id is 1..64 characters" }
        require(titleEn.isNotBlank() && titleEn.length <= 120) { "title_en is 1..120 characters" }
        require(titleBn == null || titleBn.length <= 120) { "title_bn is at most 120 characters" }
        require(bodyEn.length <= 300) { "body_en is at most 300 characters" }
        require(bodyBn == null || bodyBn.length <= 300) { "body_bn is at most 300 characters" }
        require(dedupeKey.isNotEmpty() && dedupeKey.length <= 160) { "dedupe_key is 1..160 characters" }
        // PostgreSQL text cannot hold U+0000.
        require(listOf(refId, titleEn, titleBn, bodyEn, bodyBn, dedupeKey).none { it != null && '\u0000' in it }) { "a text holds U+0000" }
    }
}

/**
 * The notification inbox producer (D-TSO-MAN-26, SH-08; fixed API for lanes B1 and B3, build contract s5 B1).
 *
 * [add] inserts one row with `INSERT ... ON CONFLICT (user_id, dedupe_key) DO NOTHING` (R-F-04) on the caller's handle,
 * inside the caller's transaction, so the notice commits with the change it reports (a ticket update, a leave decision,
 * a midnight auto Final Submit) or not at all, and a retried producer adds nothing. It never sends a push: after the
 * commit the caller nudges each recipient whose row is new with reason [PUSH_REASON] (`Nudger.nudge(userId,
 * Inbox.PUSH_REASON)`); the phone then re-reads `GET /v1/me/notifications`. The rows are read and marked read by
 * backend:notify (InboxApi.kt) and pruned by its InboxPruneJob in the worker after `cfg.notify.inbox_retention_days`.
 *
 * Grants (V0133): the API (`api_rw`) and the worker (`worker_rw`, also through `jobs_rw`) may insert.
 */
object Inbox {
    /** Push `reason` of a `sync_nudge` that tells the phone its inbox has a new row (backend:notify PushReasons). */
    const val PUSH_REASON = "notification"

    /** The `ref_type` each contract `NotificationKind` takes (V0133 CHECKs, contract `NotificationItem`). */
    val REF_TYPE_OF: Map<String, String> = NotificationKind.entries.associate { k ->
        k.wire to when (k) {
            NotificationKind.TICKET_UPDATED -> "support_ticket"
            NotificationKind.LEAVE_DECIDED -> "leave_application"
            NotificationKind.FINAL_SUBMIT_AUTO -> "zone_day"
        }
    }

    /**
     * Inserts [item] unless the recipient already holds a row with its dedupe key. Returns true when a row was inserted
     * (nudge that user after the commit), false for a repeat. Must run inside the producer's transaction.
     */
    fun add(h: Handle, item: InboxItem): Boolean {
        check(h.isInTransaction) { "Inbox.add runs inside the producer's transaction (R-F-04)" }
        return h.createUpdate(
            """
            INSERT INTO app.user_notification (user_id, kind, ref_type, ref_id, title_en, title_bn, body_en, body_bn, dedupe_key)
            VALUES (:user, :kind, :refType, :refId, :titleEn, :titleBn, :bodyEn, :bodyBn, :dedupe)
            ON CONFLICT (user_id, dedupe_key) DO NOTHING
            """.trimIndent(),
        ).bind("user", item.userId).bind("kind", item.kind).bind("refType", item.refType).bind("refId", item.refId)
            .bind("titleEn", item.titleEn).bind("titleBn", item.titleBn).bind("bodyEn", item.bodyEn).bind("bodyBn", item.bodyBn)
            .bind("dedupe", item.dedupeKey).execute() == 1
    }

    /** [add] for each item in order; returns the recipients that got a new row (the users to nudge after the commit). */
    fun addAll(h: Handle, items: Iterable<InboxItem>): Set<Long> = items.filter { add(h, it) }.mapTo(LinkedHashSet()) { it.userId }

    /** The `ref_id` of a zone-day notice: `<zone_id>:<business_date>` (D-F1-06), e.g. `7:2026-10-25`. */
    fun zoneDayRefId(zoneId: Long, businessDate: LocalDate): String = "$zoneId:$businessDate"
}

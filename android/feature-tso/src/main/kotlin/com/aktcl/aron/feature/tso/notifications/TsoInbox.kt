package com.aktcl.aron.feature.tso.notifications

import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.rules.TicketMath
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One row of the bell inbox (contract 1.10.0 `NotificationItem`, D-TSO-MAN-26): the texts are fixed by the producer in both
 * languages (`*_bn` may be null: the English text shows), plus what it is about ([refType], [refId]) and when it was read.
 */
data class TsoNotification(
    val id: Long,
    val kind: String,
    val refType: String,
    val refId: String,
    val titleEn: String,
    val titleBn: String?,
    val bodyEn: String,
    val bodyBn: String?,
    val createdAt: String,
    val businessDate: String,
    val readAt: String?,
) {
    val unread: Boolean get() = readAt == null
    fun title(bangla: Boolean): String = if (bangla && !titleBn.isNullOrBlank()) titleBn else titleEn
    fun body(bangla: Boolean): String = if (bangla && !bodyBn.isNullOrBlank()) bodyBn else bodyEn
}

/** One page of `GET /v1/me/notifications` (`NotificationInbox`): newest first, the server's unread count and the next page's cursor. */
data class TsoInboxPage(val asOf: String, val unreadCount: Int, val items: List<TsoNotification>, val nextBeforeId: Long?) {
    /** The newest id on the page: opening the inbox marks everything up to it read. */
    val newestId: Long? get() = items.maxOfOrNull { it.id }
}

/** Where tapping a row goes (an improvement: the inbox has no screenshot); null when it has no screen of its own. */
data class TsoInboxTarget(val screen: TsoScreen, val arg: String?)

/**
 * The notifications inbox of the bell (D-TSO-MAN-26, SH-08): parsing, the bell's count with the reads this phone has not sent yet,
 * and where a row leads. The count is read when the Dashboard opens, on pull-to-refresh and on the push reason `notification`, never
 * on a timer. No floating point, no network.
 */
object TsoInboxes {
    private fun obj(json: String?): JsonObject? = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    /** A `NotificationInbox` answer; null when unreadable. A malformed row is skipped, never shown half. */
    fun parse(json: String?): TsoInboxPage? {
        val o = obj(json) ?: return null
        val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { r ->
            TsoNotification(
                l(r, "id") ?: return@mapNotNull null, s(r, "kind") ?: return@mapNotNull null, s(r, "ref_type").orEmpty(), s(r, "ref_id").orEmpty(),
                s(r, "title_en") ?: return@mapNotNull null, s(r, "title_bn"), s(r, "body_en").orEmpty(), s(r, "body_bn"),
                s(r, "created_at").orEmpty(), s(r, "business_date").orEmpty(), s(r, "read_at"),
            )
        }.sortedByDescending { it.id }
        val unread = (o["unread_count"] as? JsonPrimitive)?.intOrNull ?: return null
        return TsoInboxPage(s(o, "as_of") ?: return null, unread.coerceAtLeast(0), items, l(o, "next_before_id"))
    }

    /** The answer of `POST /v1/me/notifications/read` (`NotificationReadResult`): the unread count left; null when unreadable. */
    fun readResult(json: String?): Int? = obj(json)?.let { (it["unread_count"] as? JsonPrimitive)?.intOrNull }?.coerceAtLeast(0)

    /** The body of `POST /v1/me/notifications/read`: everything up to [upTo] (an id the server gave; idempotent). */
    fun readBody(upTo: Long): String = """{"up_to_id":$upTo}"""

    /**
     * The bell's count: the server's unread count minus the reads this phone made but has not sent yet ([pendingUpTo], kept until the
     * server confirms). A pending read at or above the page's newest id clears every row the server counted (none newer existed when
     * it counted); below it, the unread rows of the page up to that id come off. Never negative.
     */
    fun badge(page: TsoInboxPage?, pendingUpTo: Long?): Int {
        if (page == null) return 0
        val unread = page.unreadCount.coerceAtLeast(0)
        val up = pendingUpTo ?: return unread
        val newest = page.newestId ?: return unread
        if (up >= newest) return 0
        return (unread - page.items.count { it.unread && it.id <= up }).coerceAtLeast(0)
    }

    /**
     * Where a row leads: a ticket update opens that ticket (its uuid as the argument), a leave decision opens Leave, a midnight auto
     * Final Submit opens Final Submit. Anything else stays in the inbox.
     */
    fun target(n: TsoNotification): TsoInboxTarget? = when (n.refType) {
        "support_ticket" -> n.refId.takeIf { it.isNotBlank() }?.let { TsoInboxTarget(TsoScreen.TICKET_DETAIL, it) }
        "leave_application" -> TsoInboxTarget(TsoScreen.LEAVE, null)
        "zone_day" -> TsoInboxTarget(TsoScreen.FINAL_SUBMIT, null)
        else -> null
    }

    /** "4d ago", "just now" (TicketMath, as the tickets): [nowMs] is trusted time; an unreadable stamp prints nothing. */
    fun age(n: TsoNotification, nowMs: Long, locale: UiLocale): String =
        runCatching { TicketMath.ageText(nowMs, java.time.Instant.parse(n.createdAt).toEpochMilli(), locale) }.getOrDefault("")
}

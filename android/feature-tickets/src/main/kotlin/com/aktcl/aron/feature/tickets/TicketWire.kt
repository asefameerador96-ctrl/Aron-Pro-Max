package com.aktcl.aron.feature.tickets

import com.aktcl.aron.contract.SupportTicket
import com.aktcl.aron.contract.SupportTicketDetail
import com.aktcl.aron.contract.SupportTicketEvent
import com.aktcl.aron.contract.SupportTicketPage
import com.aktcl.aron.contract.SupportTicketSummary
import com.aktcl.aron.rules.TicketCounts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Server answers (contract 1.10.0: SupportTicketPage, SupportTicketSummary, SupportTicketDetail) mapped to the screens'
 * model, so a host only fetches and stores the bodies. A body that does not decode gives null (the kept copy stays); one
 * bad row is dropped, never the page.
 */
object TicketWire {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** A contract Timestamp to epoch ms; null when unreadable. */
    fun instantMs(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    fun ticket(t: SupportTicket): Ticket? {
        val at = instantMs(t.capturedAt) ?: instantMs(t.createdAt) ?: return null
        return Ticket(
            uuid = t.ticketUuid, number = t.ticketNo, issueTypeCode = t.issueTypeCode, issueLabelEn = t.issueTypeLabelEn,
            issueLabelBn = t.issueTypeLabelBn, title = t.title, description = t.description, priority = t.priority, status = t.status,
            capturedAtMs = at, version = t.version, attachmentCount = t.attachmentCount, delivery = Delivery.Sent,
            resolutionNotes = t.resolutionNotes,
        )
    }

    fun event(e: SupportTicketEvent): TicketEvent? {
        val at = instantMs(e.createdAt) ?: return null
        return TicketEvent(e.eventUuid, e.kind, e.body, e.actorIsSupport, e.actorDisplay, at, e.toStatus, e.attachments)
    }

    /** One page of `GET /v1/support/tickets`: the rows and the next cursor. */
    fun page(body: String): Pair<List<Ticket>, String?>? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val rows = root["items"]?.jsonArray ?: return null
        val items = rows.mapNotNull { el -> runCatching { json.decodeFromJsonElement(SupportTicket.serializer(), el) }.getOrNull()?.let(::ticket) }
        items to (root["next_cursor"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** A whole page decoded strictly (tests and hosts that want the generated type). */
    fun pageDto(body: String): SupportTicketPage? = runCatching { json.decodeFromString(SupportTicketPage.serializer(), body) }.getOrNull()

    /** `GET /v1/support/tickets/summary` as the shared counts (no percentages on the wire, R-F-14). */
    fun summary(body: String): Pair<TicketCounts, Long?>? = runCatching {
        val s = json.decodeFromString(SupportTicketSummary.serializer(), body)
        TicketCounts(s.total.toLong(), s.new.toLong(), s.inProgress.toLong(), s.resolved.toLong(), s.closed.toLong(), s.cancelled.toLong()) to instantMs(s.asOf)
    }.getOrNull()

    /** `GET /v1/support/tickets/{uuid}`. */
    fun detail(body: String): TicketDetail? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val t = ticket(json.decodeFromJsonElement(SupportTicket.serializer(), root["ticket"] ?: return null)) ?: return null
        val events = root["events"]?.jsonArray.orEmpty().mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(SupportTicketEvent.serializer(), el) }.getOrNull()?.let(::event)
        }
        val att = root["attachments"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        TicketDetail(t, events.sortedWith(compareBy<TicketEvent> { it.atMs }.thenBy { it.uuid }), att)
    }.getOrNull()

    /** A whole detail decoded strictly. */
    fun detailDto(body: String): SupportTicketDetail? = runCatching { json.decodeFromString(SupportTicketDetail.serializer(), body) }.getOrNull()
}

/**
 * The host's mirror rows (one per unsent ticket or event, written with the outbox row in one transaction) as JSON, so the
 * host never re-implements the shape. [Delivery] is not stored: the host reads it from the outbox row's state.
 */
object TicketMirror {
    private val json = Json { ignoreUnknownKeys = true }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    fun ticketToJson(t: Ticket): String = JsonObject(
        mapOf(
            "uuid" to JsonPrimitive(t.uuid), "issue_type_code" to JsonPrimitive(t.issueTypeCode),
            "issue_label_en" to JsonPrimitive(t.issueLabelEn), "issue_label_bn" to (t.issueLabelBn?.let(::JsonPrimitive) ?: JsonNull),
            "title" to JsonPrimitive(t.title), "description" to JsonPrimitive(t.description), "priority" to JsonPrimitive(t.priority),
            "captured_at_ms" to JsonPrimitive(t.capturedAtMs), "photo_count" to JsonPrimitive(t.attachmentCount),
        ),
    ).toString()

    fun ticketFromJson(text: String, delivery: Delivery = Delivery.Pending): Ticket? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        Ticket(
            uuid = str(o, "uuid")!!, number = null, issueTypeCode = str(o, "issue_type_code")!!, issueLabelEn = str(o, "issue_label_en")!!,
            issueLabelBn = str(o, "issue_label_bn"), title = str(o, "title")!!, description = str(o, "description").orEmpty(),
            priority = str(o, "priority") ?: TicketPriority.P1.wire, status = TicketStatus.NEW.wire,
            capturedAtMs = (o["captured_at_ms"] as JsonPrimitive).longOrNull!!, attachmentCount = (o["photo_count"] as? JsonPrimitive)?.intOrNull ?: 0,
            delivery = delivery,
        )
    }.getOrNull()

    fun eventToJson(e: LocalEvent): String = JsonObject(
        mapOf(
            "uuid" to JsonPrimitive(e.uuid), "ticket_uuid" to JsonPrimitive(e.ticketUuid), "kind" to JsonPrimitive(e.kind),
            "body" to (e.body?.let(::JsonPrimitive) ?: JsonNull), "at_ms" to JsonPrimitive(e.atMs), "photo_count" to JsonPrimitive(e.photoCount),
        ),
    ).toString()

    fun eventFromJson(text: String, delivery: Delivery = Delivery.Pending): LocalEvent? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        LocalEvent(
            uuid = str(o, "uuid")!!, ticketUuid = str(o, "ticket_uuid")!!, kind = str(o, "kind")!!, body = str(o, "body"),
            atMs = (o["at_ms"] as JsonPrimitive).longOrNull!!, photoCount = (o["photo_count"] as? JsonPrimitive)?.intOrNull ?: 0, delivery = delivery,
        )
    }.getOrNull()
}

/**
 * The bundle section `code_lists` (an array of `{list_key, items[]}`, BundleService.codeLists) read for
 * `ticket_issue_type`, in sort order. A row without a valid code or English label is dropped; a missing or out-of-range
 * icon falls back to (sort - 1) mod 8 (V0131).
 */
object IssueTypes {
    const val LIST_KEY = "ticket_issue_type"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(codeListsJson: String?): List<IssueType> {
        val root = runCatching { json.parseToJsonElement(codeListsJson ?: return emptyList()) }.getOrNull() as? JsonArray ?: return emptyList()
        val list = root.firstOrNull { (it as? JsonObject)?.get("list_key")?.let { k -> (k as? JsonPrimitive)?.contentOrNull } == LIST_KEY } as? JsonObject
            ?: return emptyList()
        val items = list["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { item(it) }.distinctBy { it.code }.sortedWith(compareBy<IssueType> { it.sort }.thenBy { it.code })
    }

    private fun item(el: JsonElement): IssueType? = runCatching {
        val o = el.jsonObject
        fun s(k: String, from: JsonObject = o) = (from[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        val code = s("code") ?: return null
        val labelEn = s("label_en")?.takeIf { it.isNotBlank() } ?: return null
        if (!TicketLimits.ISSUE_CODE.matches(code)) return null
        val sort = (o["sort"] as? JsonPrimitive)?.intOrNull ?: 0
        val attrs = o["attrs"] as? JsonObject ?: JsonObject(emptyMap())
        val icon = (attrs["icon"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..7 } ?: Math.floorMod(sort - 1, 8)
        IssueType(
            code = code, labelEn = labelEn, labelBn = s("label_bn")?.takeIf { it.isNotBlank() }, sort = sort,
            priority = TicketPriority.of(s("priority", attrs)) ?: TicketPriority.P1, icon = icon,
            category = s("category", attrs) ?: "tickets", urgency = s("urgency", attrs) ?: "high", hintEn = s("hint_en", attrs),
        )
    }.getOrNull()
}

package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.Inbox
import com.aktcl.aron.backend.platform.InboxItem
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.contract.NotificationKind
import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.util.UUID

/**
 * A config value as the transaction sees it (tso-manual B3): the newest global `cfg_value` row in force, else the
 * registry default of `cfg_key`. Both paths of a rule read it on their own handle, so a POST and an upload in the same
 * moment judge with the same value, and a test sets it with a `cfg_value` row.
 */
internal object HandleConfig {
    fun global(h: Handle, key: String): JsonElement? = h.createQuery(
        """
        SELECT COALESCE((SELECT v.value FROM app.cfg_value v WHERE v.key = k.key AND v.scope_type = 'global' AND v.effective_from <= now()
                           AND (v.effective_to IS NULL OR v.effective_to > now()) ORDER BY v.effective_from DESC LIMIT 1), k.default_value)::text
          FROM app.cfg_key k WHERE k.key = :k AND k.retired_at IS NULL
        """.trimIndent(),
    ).bind("k", key).mapTo(String::class.java).findOne().orElse(null)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    fun int(h: Handle, key: String, fallback: Int): Int = runCatching { global(h, key)?.jsonPrimitive?.intOrNull }.getOrNull() ?: fallback
    fun bool(h: Handle, key: String, fallback: Boolean): Boolean = runCatching { global(h, key)?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: fallback
}

/**
 * One refusal of a TSO capture, the same on both paths (tso-manual B3): the POST answers [problem] with a field error on
 * [field] ([reason]); ingest refuses the record with [outcome] (its status in the code catalogue decides quarantine or
 * final rejection, and its reason text is what the phone shows after "Not accepted:", LV-16).
 */
data class CaptureRefusal(val field: String, val reason: String, val outcome: RecordOutcomeCode, val conflict: Boolean, val detail: String) {
    fun record() = RecordRefusal(outcome, detail)
}

/**
 * The rules of a leave application (D-TSO-MAN-33, LV-17), shared by `POST /v1/leave` and the `leave_application` sync
 * record ([LeaveApplicationHandler]), in this order after the applicant's role (TSO only; else 403 on the POST and
 * `scope_out_of_reach` on sync):
 * 1. the leave type is a `leave_type` code valid on the first day (unless the list is not seeded): 400 `unknown_code`,
 *    sync `schema_invalid` (final);
 * 2. the first day lies within `cfg.leave.allow_past_days` (31) before and `cfg.leave.allow_ahead_days` (365) after the
 *    day the TSO applied (the record's business date; today for the POST): 400 `out_of_window`, sync
 *    `business_date_out_of_window` (held for review);
 * 3. with `cfg.leave.block_overlap` (default false) only: no other pending or approved leave of the TSO over the same
 *    calendar days: 409 `overlap`, sync `content_duplicate` (held for review).
 * The last day is `from_date + days - 1` in calendar days, Fridays and holidays counted.
 */
object LeaveRules {
    data class Limits(val pastDays: Int, val aheadDays: Int, val blockOverlap: Boolean)

    fun limits(h: Handle) = Limits(
        HandleConfig.int(h, "cfg.leave.allow_past_days", 31).coerceIn(0, 366),
        HandleConfig.int(h, "cfg.leave.allow_ahead_days", 365).coerceIn(0, 366),
        HandleConfig.bool(h, "cfg.leave.block_overlap", false),
    )

    fun toDate(from: LocalDate, days: Int): LocalDate = from.plusDays(days - 1L)

    fun check(h: Handle, userId: Long, leaveUuid: UUID, typeCode: String, from: LocalDate, days: Int, appliedOn: LocalDate): CaptureRefusal? {
        if (!codeValid(h, "leave_type", typeCode, from)) {
            return CaptureRefusal("leave_type_code", "unknown_code", RecordOutcomeCode.SCHEMA_INVALID, false, "leave_type $typeCode is not a leave type on $from")
        }
        val lim = limits(h)
        if (from.isBefore(appliedOn.minusDays(lim.pastDays.toLong())) || from.isAfter(appliedOn.plusDays(lim.aheadDays.toLong()))) {
            return CaptureRefusal(
                "from_date", "out_of_window", RecordOutcomeCode.BUSINESS_DATE_OUT_OF_WINDOW, false,
                "from_date $from outside $appliedOn -${lim.pastDays}/+${lim.aheadDays} days (cfg.leave.allow_past_days, cfg.leave.allow_ahead_days)",
            )
        }
        if (lim.blockOverlap) {
            val overlap = h.createQuery(
                "SELECT count(*) FROM app.leave_application l WHERE l.user_id = :uid AND l.client_uuid <> :u AND l.voided_at IS NULL AND l.status IN ('pending','approved') AND l.from_date <= :to AND l.to_date >= :f",
            ).bind("uid", userId).bind("u", leaveUuid).bind("f", from).bind("to", toDate(from, days)).mapTo(Int::class.java).one()
            if (overlap > 0) return CaptureRefusal("from_date", "overlap", RecordOutcomeCode.CONTENT_DUPLICATE, true, "another pending or approved leave covers $from..${toDate(from, days)}")
        }
        return null
    }

    /** A code of [listKey] valid on [on]; true while the list is not seeded at all (the pattern check is all there is). */
    internal fun codeValid(h: Handle, listKey: String, code: String, on: LocalDate): Boolean {
        val total = h.createQuery("SELECT count(*) FROM app.code_list_item WHERE list_key = :k").bind("k", listKey).mapTo(Int::class.java).one()
        if (total == 0) return true
        return h.createQuery("SELECT count(*) FROM app.code_list_item WHERE list_key = :k AND code = :c AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)")
            .bind("k", listKey).bind("c", code).bind("d", on).mapTo(Int::class.java).one() > 0
    }
}

/**
 * The ingest side of leave (tso-manual B3, D-TSO-MAN-33, LV-16, LV-17): a `leave_application` record is judged by
 * [LeaveRules] exactly as `POST /v1/leave` is, so the phone's queued application and an online one are refused for the
 * same reasons; the ack's code carries the reason the phone shows ("Not accepted: <reason>"). A replay of a stored
 * application is a duplicate before any rule (the registry answers first).
 */
class LeaveApplicationHandler : RecordHandler {
    override val types = setOf("leave_application")

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
        if (rec.role != Role.TSO) return RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "leave is applied by a TSO, not a ${rec.role.wire}")
        val type = (rec.payload["leave_type_code"] as? JsonPrimitive)?.content ?: return null
        val from = (rec.payload["from_date"] as? JsonPrimitive)?.content?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val days = (rec.payload["days"] as? JsonPrimitive)?.intOrNull ?: return null
        return LeaveRules.check(h, rec.userId, UUID.fromString(rec.clientUuid), type, from, days, rec.businessDate)?.record()
    }
}

/**
 * A leave decision's side effects (tso-manual B3, D-TSO-MAN-26, 38; LV-22), in the decision's transaction: the
 * `leave.decided` v1 outbox event and the applicant's inbox row `leave_decided`. The caller pushes reason
 * [Inbox.PUSH_REASON] to the applicant after the commit when [record] returns true.
 */
object LeaveDecided {
    data class Leave(val uuid: UUID, val applicant: Long, val typeCode: String, val from: LocalDate, val days: Int, val to: LocalDate)

    /** Writes the event and the inbox row; true when the inbox row is new (push the applicant after the commit). */
    fun record(h: Handle, leave: Leave, status: String, decidedBy: Long, decidedOn: LocalDate): Boolean {
        require(status == "approved" || status == "rejected") { "a decision is approved or rejected" }
        DomainEventProducer.emitService(
            h, "leave.decided", "leave_application", leave.uuid.toString(), decidedOn, leave.uuid.toString(),
            buildJsonObject {
                put("leave_uuid", leave.uuid.toString()); put("user_id", leave.applicant); put("status", status); put("decided_by", decidedBy)
                put("leave_type_code", leave.typeCode); put("from_date", leave.from.toString()); put("to_date", leave.to.toString()); put("days", leave.days)
            },
        )
        val approved = status == "approved"
        val dayWordEn = if (leave.days == 1) "day" else "days"
        val span = if (leave.from == leave.to) "${leave.from}" else "${leave.from} to ${leave.to}"
        val spanBn = if (leave.from == leave.to) bn("${leave.from}") else bn("${leave.from}") + " থেকে " + bn("${leave.to}")
        return Inbox.add(
            h,
            InboxItem(
                userId = leave.applicant, kind = NotificationKind.LEAVE_DECIDED.wire, refType = "leave_application", refId = leave.uuid.toString(),
                titleEn = if (approved) "Leave approved" else "Leave not approved",
                titleBn = if (approved) "ছুটি অনুমোদিত" else "ছুটি অনুমোদিত হয়নি",
                bodyEn = "Your leave of ${leave.days} $dayWordEn ($span) was ${if (approved) "approved" else "not approved"}.",
                bodyBn = "আপনার ${bn(leave.days.toString())} দিনের ছুটি ($spanBn) ${if (approved) "অনুমোদিত হয়েছে" else "অনুমোদিত হয়নি"}।",
                dedupeKey = "leave_decided:${leave.uuid}",
            ),
        )
    }

    private fun bn(s: String): String = s.map { c -> if (c in '0'..'9') '০' + (c - '0') else c }.joinToString("")
}

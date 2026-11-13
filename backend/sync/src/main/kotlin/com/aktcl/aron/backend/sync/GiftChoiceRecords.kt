package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The ingest side of the `gift_choice` record (contract 1.15.0, SRM-B-66; lane portal-loyalty-api, push C, D-LOY-API-19). The generic writer stores the choice in `app.gift_choice`
 * (idempotent by client_uuid); this handler judges it first and keeps `app.gift_assignment` (one row per programme, quarter and outlet) in step:
 *  - who: the roles of `cfg.astha.gift_choice_roles` (default SR and TSO), for an outlet in the uploader's reach (the rule ingest applies to an outlet) - else `scope_out_of_reach`;
 *  - what: an active gift of an active Astha programme (else `unknown_gift` / `programme_inactive`), for an outlet with an active enrolment on the business date, and a gift that is
 *    for the outlet's tier (`gift.tier_codes`, empty = every tier) - else `gift_not_for_tier`;
 *  - the lock: once the hand-over photo locked the assignment (`locked_at`), a choice is refused `gift_locked` (a reason the phone shows, never a silent drop);
 *  - last writer wins by `captured_at` until then: an older choice that arrives after a newer one changes nothing (out-of-order safe), and a replay is a duplicate before this runs.
 */
class GiftChoiceRecords(private val reach: ReachResolver, private val config: ServerConfig) : RecordHandler {
    override val types = setOf("gift_choice")

    private class Target(val programmeId: Long, val routeId: Long, val tier: String?)

    private fun roles(): Set<String> = runCatching {
        (config.value("cfg.astha.gift_choice_roles") as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    }.getOrDefault(setOf("TSO"))   // a malformed list fails closed to the narrowest default

    /**
     * The verdict on one choice. A reviewer's accept ([IngestRecord.released]) waives exactly the check that held the record (scope or programme/enrolment) and nothing else: the open quarter,
     * the gift, the tier and the lock are still judged, in check() and again in afterStored(), so an accepted choice takes effect and can never get around a refusal that is final.
     */
    private fun judge(h: Handle, rec: IngestRecord): Pair<RecordRefusal?, Target?> {
        val waived = rec.released
        val outletId = rec.payload["outlet_id"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: return RecordRefusal(RecordOutcomeCode.UNKNOWN_OUTLET, "outlet_id") to null
        val giftId = rec.payload["gift_id"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: return RecordRefusal(RecordOutcomeCode.UNKNOWN_GIFT, "gift_id") to null
        val quarter = (rec.payload["quarter"] as? JsonPrimitive)?.contentOrNull ?: return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "quarter") to null
        if (rec.role.name !in roles() && waived != RecordOutcomeCode.SCOPE_OUT_OF_REACH) return RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "this role does not choose Astha gifts (cfg.astha.gift_choice_roles)") to null
        val startMonth = runCatching { (config.value("cfg.astha.quarter_start_month") as JsonPrimitive).intOrNull }.getOrNull() ?: 1
        if (AsthaQuarter.parse(quarter, startMonth) == null) return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "quarter $quarter") to null
        // Only the open quarter on the record's Dhaka business date can be chosen for (final and kept in sync_rejected): a stale or future label would otherwise make a lock-free assignment nobody reads.
        val open = AsthaQuarter.of(rec.businessDate, startMonth).label
        if (quarter != open) return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "quarter $quarter is not the open quarter $open on ${rec.businessDate}") to null
        val outlet = h.createQuery("SELECT route_id, zone_id FROM app.outlet WHERE id = :o AND status = 'active'").bind("o", outletId)
            .map { rs, _ -> (rs.getObject("route_id") as Number?)?.toLong() to rs.getLong("zone_id") }.findOne().orElse(null) ?: return RecordRefusal(RecordOutcomeCode.UNKNOWN_OUTLET, "outlet $outletId") to null
        val routeId = outlet.first ?: return RecordRefusal(RecordOutcomeCode.UNKNOWN_OUTLET, "outlet $outletId has no route") to null
        val r = reach.reach(rec.userId, rec.role, 0, rec.businessDate)
        val inReach = if (r.ownRecordsOnly) routeId in r.routeIds else r.coversZone(outlet.second) || routeId in r.routeIds
        if (!inReach && waived != RecordOutcomeCode.SCOPE_OUT_OF_REACH) return RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "outlet $outletId outside the uploader's reach") to null
        val gift = h.createQuery(
            "SELECT g.programme_id, g.tier_codes, p.status AS pstatus, p.kind FROM app.gift g JOIN app.programme p ON p.id = g.programme_id WHERE g.id = :g AND g.status = 'active'",
        ).bind("g", giftId).map { rs, _ -> arrayOf<Any?>(rs.getLong("programme_id"), (rs.getArray("tier_codes").array as Array<*>).map { it.toString() }, rs.getString("pstatus"), rs.getString("kind")) }.findOne().orElse(null)
            ?: return RecordRefusal(RecordOutcomeCode.UNKNOWN_GIFT, "gift $giftId") to null
        val programmeId = gift[0] as Long
        @Suppress("UNCHECKED_CAST") val tiers = gift[1] as List<String>
        val inactiveWaived = waived == RecordOutcomeCode.PROGRAMME_INACTIVE
        if ((gift[2] != "active" || gift[3] != "astha") && !inactiveWaived) return RecordRefusal(RecordOutcomeCode.PROGRAMME_INACTIVE, "gift $giftId is not of an active Astha programme") to null
        val tier = h.createQuery(
            "SELECT tier_code FROM app.programme_enrolment WHERE programme_id = :p AND outlet_id = :o AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d) ORDER BY valid_from DESC LIMIT 1",
        ).bind("p", programmeId).bind("o", outletId).bind("d", rec.businessDate).map { rs, _ -> rs.getString(1) ?: "" }.findOne().orElse(null)
            ?: if (inactiveWaived) null else return RecordRefusal(RecordOutcomeCode.PROGRAMME_INACTIVE, "outlet $outletId is not enrolled in the programme") to null
        // A reviewer who accepted an outlet without an enrolment has no tier to judge the gift against.
        if (tier != null && tiers.isNotEmpty() && tier !in tiers) return RecordRefusal(RecordOutcomeCode.GIFT_NOT_FOR_TIER, "gift $giftId is not for tier '$tier'") to null
        // FOR UPDATE: the hand-over photo's lock UPDATE of this row waits for this transaction, so the lock cannot close between check() and afterStored() (both run in the record's transaction).
        val locked = h.createQuery("SELECT locked_at IS NOT NULL FROM app.gift_assignment WHERE programme_id = :p AND quarter = :q AND outlet_id = :o FOR UPDATE")
            .bind("p", programmeId).bind("q", quarter).bind("o", outletId).mapTo(Boolean::class.java).findOne().orElse(false)
        if (locked) return RecordRefusal(RecordOutcomeCode.GIFT_LOCKED, "the outlet's gift for $quarter is locked by its hand-over photo") to null
        return null to Target(programmeId, routeId, tier?.ifEmpty { null })
    }

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? = judge(h, rec).first

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        val (refusal, target) = judge(h, rec)
        // check() already passed in this transaction (with the reviewer's waiver), so a refusal here means the world moved between the two (a programme or gift edit committed in between):
        // storing the choice and acking it accepted without applying it would be silent, so the record rolls back (acked server_error, retryable) and its resend is judged afresh by check() and
        // gets the final answer (gift_locked included) - deterministic, and the phone always learns. The photo lock itself cannot close here: judge() holds the assignment row FOR UPDATE.
        if (refusal != null || target == null) throw IllegalStateException("gift_choice ${rec.clientUuid} no longer passes its checks (${refusal?.code?.wire}); rolled back for a re-judged resend")
        val at = OffsetDateTime.ofInstant(java.time.Instant.parse((rec.envelope["captured_at"] as JsonPrimitive).content), ZoneOffset.UTC)
        h.createUpdate(
            """
            INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id, tier_code, chosen_by_user_id, chosen_at)
            VALUES (:p, :q, :o, :r, :g, :t, :u, :at)
            ON CONFLICT (programme_id, quarter, outlet_id) DO UPDATE
               SET gift_id = EXCLUDED.gift_id, tier_code = EXCLUDED.tier_code, route_id = EXCLUDED.route_id, chosen_by_user_id = EXCLUDED.chosen_by_user_id, chosen_at = EXCLUDED.chosen_at
             WHERE app.gift_assignment.locked_at IS NULL AND (app.gift_assignment.chosen_at IS NULL OR app.gift_assignment.chosen_at <= EXCLUDED.chosen_at)
            """.trimIndent(),
        ).bind("p", target.programmeId).bind("q", (rec.payload["quarter"] as JsonPrimitive).content).bind("o", (rec.payload["outlet_id"] as JsonPrimitive).content.toLong())
            .bind("r", target.routeId).bind("g", (rec.payload["gift_id"] as JsonPrimitive).content.toLong()).bind("t", target.tier).bind("u", rec.userId).bind("at", at).execute()
    }
}

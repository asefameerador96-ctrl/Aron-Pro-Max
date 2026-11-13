package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.ProgrammeScopeParams
import com.aktcl.aron.backend.platform.ProgrammeScopeSql
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The ingest side of the `gift_photo` record (contract 1.16.0, SRM-B-77 to 80; lane portal-loyalty-api, push C, D-LOY-API-20). The generic writer stores the photo row (idempotent by client_uuid);
 * V0185's BEFORE INSERT trigger decides which photo of an assignment or unit is the FIRST by captured_at (the winner) and stores any other as a flagged duplicate: never deleted, never refused.
 * The winner is the earliest by (captured_at, client_uuid) in the trigger and in app.gift_photo_elect (after an admin void of the winner, or after a unit photo fails its checks), so arrival never decides.
 * This handler adds what a trigger cannot:
 *  - check: an Astha photo names an existing assignment whose outlet is in the uploader's reach (else `schema_invalid` / `scope_out_of_reach`);
 *  - a campaign-unit photo (no assignment) is judged against its redemption: unit_no within the redeemed qty, the gift is a line of it, the outlet is the redemption's, the outlet is in the uploader's
 *    reach and in the programme's scope ([ProgrammeScopeParams]). One that fails is STORED and flagged (`check_flag`, never the unit's photo), never refused; one whose redemption lines have not all
 *    arrived yet is parked (`parent_missing`, retryable) because it cannot be judged yet;
 *  - the server geofence re-check of the photo fix with the same function as the visit ([GeoRecheckHandler.recheck]); stored beside the phone's data, never a reason to refuse;
 *  - the lock: with `cfg.astha.gift_choice_lock` = `on_sr_photo` (default) the assignment gets `locked_at` (kept once set) and `photo_media_uuid` = the winner's photo, recomputed from the table on
 *    every arrival, so a late arrival of an earlier photo moves the winner and the order of arrival never changes the outcome.
 */
class GiftPhotoRecords(private val reach: ReachResolver, private val config: ServerConfig) : RecordHandler {
    override val types = setOf("gift_photo")
    private val geoCheck = GiftPhotoGeoRecheck()
    private val log = LoggerFactory.getLogger(GiftPhotoRecords::class.java)

    private fun long(rec: IngestRecord, k: String) = (rec.payload[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /** The verdict on a campaign-unit photo: a `check_flag` reason, or null when it passes; [pending] when the redemption's lines are not all stored yet. */
    private class UnitVerdict(val flag: String?, val pending: Boolean)

    private fun unitVerdict(h: Handle, rec: IngestRecord): UnitVerdict {
        val red = (rec.payload["redemption_client_uuid"] as? JsonPrimitive)?.contentOrNull ?: return UnitVerdict("gift_not_in_redemption", false)
        val head = h.createQuery("SELECT outlet_id, programme_id, line_count FROM app.redemption WHERE client_uuid = CAST(:r AS uuid)").bind("r", red)
            .map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getInt(3)) }.findOne().orElse(null) ?: return UnitVerdict(null, true)
        val giftId = long(rec, "gift_id")
        val stored = h.createQuery("SELECT count(*) FROM app.redemption_line WHERE redemption_client_uuid = CAST(:r AS uuid) AND voided_at IS NULL").bind("r", red).mapTo(Int::class.java).one()
        if (stored < head.third) return UnitVerdict(null, true)   // the lines are children of the same family and still on their way
        if (long(rec, "outlet_id") != head.first) return UnitVerdict("outlet_mismatch", false)
        val qty = h.createQuery("SELECT sum(qty) FROM app.redemption_line WHERE redemption_client_uuid = CAST(:r AS uuid) AND gift_id = :g AND voided_at IS NULL")
            .bind("r", red).bind("g", giftId).mapTo(Long::class.javaObjectType).findOne().orElse(null) ?: return UnitVerdict("gift_not_in_redemption", false)
        val unit = long(rec, "unit_no")
        if (unit == null || unit < 1 || unit > qty) return UnitVerdict("unit_out_of_range", false)
        val o = h.createQuery("SELECT route_id, zone_id FROM app.outlet WHERE id = :o").bind("o", head.first).map { rs, _ -> (rs.getObject("route_id") as Number?)?.toLong() to rs.getLong("zone_id") }.findOne().orElse(null)
            ?: return UnitVerdict("outlet_mismatch", false)
        val r = reach.reach(rec.userId, rec.role, 0, rec.businessDate)
        val inReach = if (r.ownRecordsOnly) o.first in r.routeIds else r.coversZone(o.second) || o.first in r.routeIds
        if (!inReach) return UnitVerdict("out_of_reach", false)
        // the programme's geography, through the one predicate every loyalty read uses
        val scope = ProgrammeScopeParams.of(h, r, listOfNotNull(o.first))
        val inScope = h.createQuery(
            "SELECT EXISTS (SELECT 1 FROM app.programme p LEFT JOIN app.programme_version v ON v.id = p.current_version_id JOIN app.outlet o ON o.id = :o WHERE p.id = :p AND ${ProgrammeScopeSql.geo("v")} AND ${ProgrammeScopeSql.outlet("v", "o")})",
        ).bind("o", head.first).bind("p", head.second).also { scope.bindTo(it) }.mapTo(Boolean::class.java).one()
        return UnitVerdict(if (inScope) null else "out_of_reach", false)
    }

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
        val assignment = long(rec, "gift_assignment_id") ?: return if (unitVerdict(h, rec).pending) RecordRefusal(RecordOutcomeCode.PARENT_MISSING, "the redemption or its lines are not stored yet") else null
        val a = h.createQuery("SELECT a.outlet_id, a.route_id, o.zone_id FROM app.gift_assignment a JOIN app.outlet o ON o.id = a.outlet_id WHERE a.id = :a").bind("a", assignment)
            .map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.findOne().orElse(null)
            ?: return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "gift_assignment_id $assignment")
        if (long(rec, "outlet_id") != a.first) return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "outlet_id does not match assignment $assignment")
        val r = reach.reach(rec.userId, rec.role, 0, rec.businessDate)
        val inReach = if (r.ownRecordsOnly) a.second in r.routeIds else r.coversZone(a.third) || a.second in r.routeIds
        return if (inReach) null else RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "assignment $assignment outside the uploader's reach")
    }

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        geoCheck.recheckOne(h, rec.clientUuid, rec.receivedAt)
        val assignment = long(rec, "gift_assignment_id")
        if (assignment == null) { flagUnit(h, rec); return }
        val lock = runCatching { (config.value("cfg.astha.gift_choice_lock") as JsonPrimitive).content }.getOrDefault("on_sr_photo") == "on_sr_photo"
        h.createUpdate(
            """
            UPDATE app.gift_assignment a
               SET photo_media_uuid = w.photo_uuid, locked_at = CASE WHEN :lock THEN COALESCE(a.locked_at, :now) ELSE a.locked_at END
              FROM (SELECT photo_uuid FROM app.gift_photo WHERE gift_assignment_id = :a AND voided_at IS NULL AND NOT is_duplicate ORDER BY captured_at, client_uuid LIMIT 1) w
             WHERE a.id = :a AND (a.photo_media_uuid IS DISTINCT FROM w.photo_uuid OR (:lock AND a.locked_at IS NULL))
            """.trimIndent(),
        ).bind("a", assignment).bind("lock", lock).bind("now", OffsetDateTime.ofInstant(rec.receivedAt, ZoneOffset.UTC)).execute()
    }

    /** A campaign-unit photo that fails its checks stays stored, flagged and out of the unit's count; the earliest eligible photo of the unit is elected again (it may have been demoted by this one). */
    private fun flagUnit(h: Handle, rec: IngestRecord) {
        val flag = unitVerdict(h, rec).flag ?: return
        h.createUpdate("UPDATE app.gift_photo SET check_flag = :f, is_duplicate = true, flagged_at = COALESCE(flagged_at, now()) WHERE client_uuid = CAST(:u AS uuid) AND check_flag IS NULL")
            .bind("f", flag).bind("u", rec.clientUuid).execute()
        h.createQuery("SELECT app.gift_photo_elect(NULL, CAST(:r AS uuid), :g, CAST(:n AS smallint))")
            .bind("r", (rec.payload["redemption_client_uuid"] as JsonPrimitive).content).bind("g", long(rec, "gift_id")).bind("n", long(rec, "unit_no")?.toInt()).mapTo(Long::class.javaObjectType).findOne()
        log.info("gift photo {} stored and flagged {}", rec.clientUuid, flag)
    }
}

/**
 * The server geofence re-check of gift photo fixes: once on ingest (inside its own savepoint) and again by [sweep] for a photo whose re-check failed there (the worker's
 * [GeoRecheckSweepJob], same style as the visits'). The photo is evidence, never refused for it: a failure leaves `server_verdict` null.
 */
class GiftPhotoGeoRecheck(private val geo: GeoRecheckHandler = GeoRecheckHandler()) {
    private val log = LoggerFactory.getLogger(GiftPhotoGeoRecheck::class.java)

    /** Re-checks one stored, not yet checked photo; true when a verdict was stored. */
    fun recheckOne(h: Handle, clientUuid: String, receivedAt: Instant, checkedAt: Instant = receivedAt): Boolean {
        val p = h.createQuery(
            """
            SELECT g.captured_at, g.outlet_id, g.fix_status, g.fix_lat, g.fix_lng, g.fix_accuracy_m, g.fix_is_mock, u.role
              FROM app.gift_photo g JOIN app.app_user u ON u.id = g.user_id
             WHERE g.client_uuid = CAST(:u AS uuid) AND g.server_checked_at IS NULL
            """.trimIndent(),
        ).bind("u", clientUuid).map { rs, _ ->
            GeoRecheckHandler.VisitFix(
                rs.getObject("captured_at", OffsetDateTime::class.java).toInstant(), rs.getLong("outlet_id"), rs.getString("fix_status"),
                rs.getObject("fix_lat") as Double?, rs.getObject("fix_lng") as Double?, rs.getObject("fix_accuracy_m") as Double?, rs.getObject("fix_is_mock") as Boolean?, rs.getString("role"),
            )
        }.findOne().orElse(null) ?: return false
        h.savepoint(SAVEPOINT)
        return try {
            val r = geo.recheck(h, p, receivedAt)
            val n = h.createUpdate(
                "UPDATE app.gift_photo SET server_verdict = :v, server_distance_m = :d, server_radius_m = :r, server_checked_at = :at WHERE client_uuid = CAST(:u AS uuid) AND server_checked_at IS NULL",
            ).bind("v", r.verdict).bind("d", r.distanceM).bind("r", r.radiusM).bind("at", OffsetDateTime.ofInstant(checkedAt, ZoneOffset.UTC)).bind("u", clientUuid).execute()
            h.release(SAVEPOINT)
            n > 0
        } catch (e: Exception) {
            h.rollbackToSavepoint(SAVEPOINT)
            log.warn("gift photo geo re-check skipped photo={}: {}", clientUuid, e.javaClass.simpleName)
            false
        }
    }

    /** Fills `server_verdict` of photos of the last [days] business dates up to [today] whose re-check failed on ingest; up to [limit] in random order. Returns the number stored. */
    fun sweep(h: Handle, today: LocalDate, days: Int, limit: Int, now: Instant): Int {
        data class Row(val uuid: String, val receivedAt: Instant)
        val rows = h.createQuery(
            "SELECT client_uuid::text AS u, received_at FROM app.gift_photo WHERE business_date BETWEEN :from AND :to AND server_checked_at IS NULL AND voided_at IS NULL ORDER BY random() LIMIT :n",
        ).bind("from", today.minusDays(days.toLong())).bind("to", today).bind("n", limit)
            .map { rs, _ -> Row(rs.getString("u"), rs.getObject("received_at", OffsetDateTime::class.java).toInstant()) }.list()
        return rows.count { recheckOne(h, it.uuid, it.receivedAt, now) }
    }

    private companion object { const val SAVEPOINT = "gift_photo_geo" }
}

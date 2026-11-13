package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The ingest side of the `gift_photo` record (contract 1.17.0, SRM-B-77 to 80; lane portal-loyalty-api, push C, D-LOY-API-20). The generic writer stores the photo row (idempotent by client_uuid);
 * V0185's BEFORE INSERT trigger decides which photo of an assignment or unit is the FIRST by captured_at (the winner) and stores any other as a flagged duplicate: never deleted, never refused.
 * This handler adds what a trigger cannot:
 *  - check: an Astha photo names an existing assignment whose outlet is in the uploader's reach (else `schema_invalid` / `scope_out_of_reach`);
 *  - the server geofence re-check of the photo fix with the same function as the visit ([GeoRecheckHandler.recheck]); stored beside the phone's data, never a reason to refuse;
 *  - the lock: with `cfg.astha.gift_choice_lock` = `on_sr_photo` (default) the assignment gets `locked_at` (kept once set) and `photo_media_uuid` = the winner's photo, recomputed from the table on
 *    every arrival, so a late arrival of an earlier photo moves the winner and the order of arrival never changes the outcome.
 */
class GiftPhotoRecords(private val reach: ReachResolver, private val config: ServerConfig) : RecordHandler {
    override val types = setOf("gift_photo")
    private val geo = GeoRecheckHandler()
    private val log = LoggerFactory.getLogger(GiftPhotoRecords::class.java)

    private fun long(rec: IngestRecord, k: String) = (rec.payload[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
        val assignment = long(rec, "gift_assignment_id") ?: return null   // a campaign unit is judged by its redemption parent (TypeRule)
        val a = h.createQuery("SELECT a.outlet_id, a.route_id, o.zone_id FROM app.gift_assignment a JOIN app.outlet o ON o.id = a.outlet_id WHERE a.id = :a").bind("a", assignment)
            .map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.findOne().orElse(null)
            ?: return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "gift_assignment_id $assignment")
        if (long(rec, "outlet_id") != a.first) return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "outlet_id does not match assignment $assignment")
        val r = reach.reach(rec.userId, rec.role, 0, rec.businessDate)
        val inReach = if (r.ownRecordsOnly) a.second in r.routeIds else r.coversZone(a.third) || a.second in r.routeIds
        return if (inReach) null else RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "assignment $assignment outside the uploader's reach")
    }

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        recheck(h, rec)
        val assignment = long(rec, "gift_assignment_id") ?: return
        val lock = runCatching { (config.value("cfg.astha.gift_choice_lock") as JsonPrimitive).content }.getOrDefault("on_sr_photo") == "on_sr_photo"
        h.createUpdate(
            """
            UPDATE app.gift_assignment a
               SET photo_media_uuid = w.photo_uuid, locked_at = CASE WHEN :lock THEN COALESCE(a.locked_at, :now) ELSE a.locked_at END
              FROM (SELECT photo_uuid FROM app.gift_photo WHERE gift_assignment_id = :a AND voided_at IS NULL AND NOT is_duplicate) w
             WHERE a.id = :a AND (a.photo_media_uuid IS DISTINCT FROM w.photo_uuid OR (:lock AND a.locked_at IS NULL))
            """.trimIndent(),
        ).bind("a", assignment).bind("lock", lock).bind("now", OffsetDateTime.ofInstant(rec.receivedAt, ZoneOffset.UTC)).execute()
    }

    /** Server geofence re-check of the photo fix, once, inside its own savepoint; a failure leaves `server_verdict` null (the photo is evidence, never refused for it). */
    private fun recheck(h: Handle, rec: IngestRecord) {
        val p = h.createQuery(
            "SELECT captured_at, outlet_id, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock FROM app.gift_photo WHERE client_uuid = CAST(:u AS uuid) AND server_checked_at IS NULL",
        ).bind("u", rec.clientUuid).map { rs, _ ->
            GeoRecheckHandler.VisitFix(
                rs.getObject("captured_at", OffsetDateTime::class.java).toInstant(), rs.getLong("outlet_id"), rs.getString("fix_status"),
                rs.getObject("fix_lat") as Double?, rs.getObject("fix_lng") as Double?, rs.getObject("fix_accuracy_m") as Double?, rs.getObject("fix_is_mock") as Boolean?, rec.role.name,
            )
        }.findOne().orElse(null) ?: return
        h.savepoint(SAVEPOINT)
        try {
            val r = geo.recheck(h, p, rec.receivedAt)
            h.createUpdate(
                "UPDATE app.gift_photo SET server_verdict = :v, server_distance_m = :d, server_radius_m = :r, server_checked_at = :at WHERE client_uuid = CAST(:u AS uuid) AND server_checked_at IS NULL",
            ).bind("v", r.verdict).bind("d", r.distanceM).bind("r", r.radiusM).bind("at", OffsetDateTime.ofInstant(rec.receivedAt, ZoneOffset.UTC)).bind("u", rec.clientUuid).execute()
            h.release(SAVEPOINT)
        } catch (e: Exception) {
            h.rollbackToSavepoint(SAVEPOINT)
            log.warn("gift photo geo re-check skipped photo={}: {}", rec.clientUuid, e.javaClass.simpleName)
        }
    }

    private companion object { const val SAVEPOINT = "gift_photo_geo" }
}

package com.aktcl.aron.sr

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.programmes.LocalGiftChoice
import com.aktcl.aron.feature.programmes.LocalGiftPhoto
import com.aktcl.aron.feature.programmes.LocalMemo
import com.aktcl.aron.feature.programmes.LocalMemoLine
import com.aktcl.aron.feature.programmes.LocalRedemption
import com.aktcl.aron.feature.programmes.PlannedRecord
import com.aktcl.aron.feature.programmes.SaveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The SR programme records (lane android-sr-programmes): reads of what this phone already holds and the one write path of the programme screens.
 * Every write is `CaptureRepository.recordProgramme`: one transaction, idempotent by client UUID, with the same open-route-day guard as every
 * operational write. Kept free of Compose so a test drives it over an in-memory database.
 */
class ProgrammeStore(
    private val db: AronDatabase,
    private val capture: CaptureRepository,
    private val meta: CaptureMetaProvider,
    /** Claims the compressed photo for its record before the record commits (F-SYS-030), with the shutter fix; a no-op where there is no camera queue (tests). */
    private val claimPhoto: suspend (photoUuid: String, recordUuid: String, fix: com.aktcl.aron.core.database.entity.GeoFixEntity?) -> Unit = { _, _, _ -> },
    private val requestSync: () -> Unit,
) {
    /** The redemptions on this phone with their outbox state (a row with no outbox state yet counts as pending). */
    suspend fun redemptions(): List<LocalRedemption> = withContext(Dispatchers.IO) {
        db.captureDao().amoRecordsOfType("redemption").mapNotNull { r ->
            val o = runCatching { Json.parseToJsonElement(r.payloadJson) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            fun n(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull()
            val ob = db.outboxDao().byClientUuid(r.clientUuid)
            LocalRedemption(n("outlet_id") ?: return@mapNotNull null, n("programme_id") ?: return@mapNotNull null, n("points_total") ?: return@mapNotNull null,
                (o["confirmed_at"] as? JsonPrimitive)?.content ?: return@mapNotNull null, ob?.state ?: "pending", ob?.ackedAt)
        }
    }

    /** The gift choices on this phone with their outbox state, oldest first (the last one of an outlet and quarter is its current one). */
    suspend fun giftChoices(): List<LocalGiftChoice> = withContext(Dispatchers.IO) {
        val reasons = runCatching { Json.parseToJsonElement(ReferenceRepository(db).section("reason_texts") ?: "{}") as? JsonObject }.getOrNull()
        fun text(code: String?, lang: String): String? = code?.let { ((reasons?.get(it) as? JsonObject)?.get(lang) as? JsonPrimitive)?.content }
        db.captureDao().amoRecordsOfType("gift_choice").mapNotNull { r ->
            val o = runCatching { Json.parseToJsonElement(r.payloadJson) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            val ob = db.outboxDao().byClientUuid(r.clientUuid)
            val code = listOf(ob?.lastMessageKey, ob?.lastCode).firstOrNull { it != null && (text(it, "en") != null || text(it, "bn") != null) }
            LocalGiftChoice(
                (o["outlet_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null, (o["quarter"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                (o["gift_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null, ob?.state ?: "pending", ob?.ackedAt,
                if (ob?.state == "rejected") text(code, "bn") else null, if (ob?.state == "rejected") text(code, "en") else null,
            )
        }
    }

    /** The gift photos on this phone with their outbox state (the Astha "already captured" read and, for a campaign, the unit slots already photographed). */
    suspend fun giftPhotos(): List<LocalGiftPhoto> = withContext(Dispatchers.IO) {
        db.captureDao().amoRecordsOfType("gift_photo").mapNotNull { r ->
            val o = runCatching { Json.parseToJsonElement(r.payloadJson) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            fun t(k: String) = (o[k] as? JsonPrimitive)?.content
            LocalGiftPhoto(
                t("programme_kind") ?: return@mapNotNull null, t("outlet_id")?.toLongOrNull() ?: return@mapNotNull null, t("gift_assignment_id")?.toLongOrNull(),
                t("redemption_client_uuid"), t("unit_no")?.toIntOrNull(), db.outboxDao().byClientUuid(r.clientUuid)?.state ?: "pending",
            )
        }
    }

    /** The contract GeoFix object of a stored fix row, as the gift photo record carries it. */
    fun fixJson(fix: com.aktcl.aron.core.database.entity.GeoFixEntity): JsonObject = com.aktcl.aron.core.database.record.RecordMapping.json.encodeToJsonElement(
        com.aktcl.aron.contract.GeoFix.serializer(), com.aktcl.aron.core.database.record.RecordMapping.fix(fix),
    ) as JsonObject

    /**
     * Saves one gift photo record: the photo is claimed for the record FIRST (so the media worker uploads it after the record's ack), then the record is saved
     * through the one programme write path (check-in gate, open route-day, idempotent by uuid). A claim that fails leaves the record unsaved and the result REFUSED.
     */
    suspend fun saveGiftPhoto(meta: CaptureMeta, plan: PlannedRecord, photoUuid: String, fix: com.aktcl.aron.core.database.entity.GeoFixEntity): SaveOutcome {
        try { claimPhoto(photoUuid, plan.clientUuid, fix) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return SaveOutcome.REFUSED }
        return saveProgramme(meta, listOf(plan))
    }

    /** The memos of a Dhaka month (`YYYY-MM`) on this phone with their lines and outbox state, for the Astha achievement (the phone's own memos on top of the server figure). */
    suspend fun memosOfMonth(month: String): List<LocalMemo> = withContext(Dispatchers.IO) {
        db.captureDao().memosBetween("$month-01", "$month-31").map { m ->
            val ob = db.outboxDao().byClientUuid(m.clientUuid)
            // A row put back to pending by a restore or digest resync keeps its first acknowledgement time: the server already held it once, so it counts as acknowledged
            // then (inside the figure when that was before the request), never as a memo the phone alone holds.
            val state = if (ob?.state == "pending" && ob.ackedAt != null) "acked" else ob?.state ?: "pending"
            LocalMemo(
                m.clientUuid, m.outletId, m.meta.businessDate, m.supersedesClientUuid, state, ob?.ackedAt,
                db.captureDao().linesOf(m.clientUuid).map { LocalMemoLine(it.skuId, it.lineKind, it.qtyBase, it.packFactor, it.grossMtk) },
                m.meta.routeId, m.memoKind,
            )
        }
    }

    /**
     * The trusted-clock instant the held full snapshot was REQUESTED at: the cut-off of the Astha figure's memos (a memo acknowledged after it is not in the
     * server figure). After a promoted prefetch it is the snapshot's server time (the same source as the balances); null when neither is held.
     */
    suspend fun bundleRequestedAt(): String? = withContext(Dispatchers.IO) {
        db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_REQUESTED_AT) ?: db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_SERVER_TIME)
    }


    /**
     * Saves planned records (a redemption and its lines, or one gift choice) for one outlet's route, all with the ONE [meta] of the basket (one instant,
     * one business date). A replay of the same record uuid with the SAME content is [SaveOutcome.ALREADY_SAVED]; the same uuid with DIFFERENT content
     * (another basket under an old uuid) is [SaveOutcome.CONFLICT] and writes nothing: the host then mints a new uuid, so a changed basket is never reported
     * as saved because an earlier one held the uuid. A route-day already submitted is [SaveOutcome.DAY_SUBMITTED]; any other refusal is [SaveOutcome.REFUSED]. Nothing is written
     * unless the result is SAVED. No exception leaves this function.
     */
    suspend fun saveProgramme(meta: CaptureMeta, planned: List<PlannedRecord>): SaveOutcome = withContext(Dispatchers.IO) {
        val records = planned.map { AmoRecordEntity(it.clientUuid, meta, it.type, it.familyUuid, it.rank, it.payload.toString()) }
        try {
            if (capture.recordProgramme(records)) { runCatching { requestSync() }; SaveOutcome.SAVED }
            else if (sameContent(records)) SaveOutcome.ALREADY_SAVED else SaveOutcome.CONFLICT
        } catch (e: com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException) { SaveOutcome.DAY_SUBMITTED }
        catch (e: com.aktcl.aron.core.database.repo.RouteDaySubmittedException) { SaveOutcome.DAY_SUBMITTED }
        catch (e: com.aktcl.aron.core.database.repo.AttendanceRefusedException) { SaveOutcome.CHECK_IN_FIRST }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { SaveOutcome.REFUSED }
    }

    /**
     * True when the phone already holds this same basket under the same uuid: the parent's members (the time it was confirmed at excluded: a replay is
     * a later tap) and the lines' contents (their own uuids excluded: a replay mints new ones) are equal. A different basket is not a replay.
     */
    private suspend fun sameContent(records: List<AmoRecordEntity>): Boolean {
        val held = db.captureDao().amoRecordsOfFamily(records.first().clientUuid)
        return signature(held) != null && signature(held) == signature(records)
    }

    private fun signature(rs: List<AmoRecordEntity>): Pair<JsonObject, List<String>>? {
        val parent = rs.firstOrNull { it.rank == 0 }?.let { runCatching { Json.parseToJsonElement(it.payloadJson) as? JsonObject }.getOrNull() } ?: return null
        val lines = rs.filter { it.rank != 0 }.map { it.payloadJson.let { j -> runCatching { (Json.parseToJsonElement(j) as JsonObject).toString() }.getOrDefault(j) } }.sorted()
        // derived figures are not part of the basket: the time it was confirmed and the balance the phone showed (after a kill and return the reloaded balance already holds the first redemption)
        return JsonObject(parent - "confirmed_at" - "balance_before_points") to lines
    }

    /** The trusted capture meta of one outlet's route; take it ONCE per basket and use it for the plan's time and the save. */
    fun metaFor(routeId: Long): CaptureMeta = meta.meta(routeId)

    fun newUuid(): String = ClientIds.newUuid()
}

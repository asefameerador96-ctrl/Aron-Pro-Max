package com.aktcl.aron.sr

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.programmes.LocalGiftChoice
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
        db.captureDao().amoRecordsOfType("gift_choice").mapNotNull { r ->
            val o = runCatching { Json.parseToJsonElement(r.payloadJson) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            LocalGiftChoice((o["outlet_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null, (o["quarter"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                (o["gift_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null, db.outboxDao().byClientUuid(r.clientUuid)?.state ?: "pending")
        }
    }

    /** When the bundle that holds the balances was built (the server's clock), or null. */
    suspend fun bundleBuiltAt(): String? = withContext(Dispatchers.IO) { db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_SERVER_TIME) }

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
        return JsonObject(parent - "confirmed_at") to lines
    }

    /** The trusted capture meta of one outlet's route; take it ONCE per basket and use it for the plan's time and the save. */
    fun metaFor(routeId: Long): CaptureMeta = meta.meta(routeId)

    fun newUuid(): String = ClientIds.newUuid()
}

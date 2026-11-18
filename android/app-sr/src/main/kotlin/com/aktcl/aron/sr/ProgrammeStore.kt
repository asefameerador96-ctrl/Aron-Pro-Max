package com.aktcl.aron.sr

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.programmes.LocalGiftChoice
import com.aktcl.aron.feature.programmes.LocalRedemption
import com.aktcl.aron.feature.programmes.PlannedRecord
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
            LocalRedemption(n("outlet_id") ?: return@mapNotNull null, n("programme_id") ?: return@mapNotNull null, n("points_total") ?: return@mapNotNull null,
                (o["confirmed_at"] as? JsonPrimitive)?.content ?: return@mapNotNull null, db.outboxDao().byClientUuid(r.clientUuid)?.state ?: "pending")
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
     * Saves a planned redemption (the redemption and its lines) for an outlet of [routeId]. True when it is saved or was already saved (a replay of the
     * same redemption uuid), false when it could not be (the open-day guard refused, a payload problem); nothing is written then, and
     * the screen says so. A saved record asks for a sync soon (debounced, Wi-Fi/network rules are the engine's).
     */
    suspend fun saveProgramme(routeId: Long, planned: List<PlannedRecord>): Boolean = withContext(Dispatchers.IO) {
        val records = planned.map { AmoRecordEntity(it.clientUuid, meta.meta(routeId), it.type, it.familyUuid, it.rank, it.payload.toString()) }
        val ok = runCatching { capture.recordProgramme(records); true }.getOrDefault(false)
        if (ok) runCatching { requestSync() }
        ok
    }

    fun newUuid(): String = ClientIds.newUuid()
}

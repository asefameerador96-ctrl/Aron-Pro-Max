package com.aktcl.aron.tso

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.contract.SyncTrigger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Queues a TSO write (leave, feedback, plan, ...) as a sync record (docs/24 s4.3): the outbox row and an optional local
 * mirror row (`sync_meta`, so a screen can show "not sent yet") are written in ONE transaction, then a debounced sync is
 * asked for. Idempotent by the client uuid: a second call with the same uuid changes nothing and returns false. The
 * payload is built by the screen's own pure code; the envelope comes from trusted time exactly as the SR app's does.
 */
class TsoOutbox(
    private val context: Context,
    private val components: SessionComponents,
    private val databases: UserDatabases,
    private val scheduler: SyncScheduler,
) {
    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    suspend fun enqueue(
        userId: Long, type: RecordType, clientUuid: String, payload: JsonObject,
        familyUuid: String = clientUuid, rank: Int = 0, mirrorKey: String? = null, mirrorValue: String? = null,
    ): Boolean {
        val db = databases.of(userId)
        val clock = components.trustedClock
        val added = db.withTransaction {
            val outbox = db.outboxDao()
            if (outbox.byClientUuid(clientUuid) != null) return@withTransaction false
            val dao = db.referenceDao()
            val now = clock.nowMs()
            val meta = CaptureMeta(
                businessDate = clock.businessDate().toString(), capturedAt = iso(now), capturedElapsedMs = clock.elapsedRealtimeMs(),
                bootCount = clock.bootCountNow(), clockOffsetMs = clock.clockOffsetMs(), capturedOffline = !online(),
                routeId = null, bundleVersion = dao.meta(ReferenceRepository.KEY_BUNDLE_VERSION),
                configVersion = dao.meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L,
            )
            val text = RecordMapping.json.encodeToString(
                JsonElement.serializer(), RecordMapping.record(type, clientUuid, familyUuid, rank, meta, JsonObject.serializer(), payload),
            )
            outbox.insert(listOf(OutboxEntity(
                clientUuid = clientUuid, recordType = type.wire, familyUuid = familyUuid, rank = rank, businessDate = meta.businessDate,
                payloadJson = text, payloadSha256 = RecordMapping.sha256Hex(text), createdAt = iso(now),
            )))
            if (mirrorKey != null && mirrorValue != null) dao.putMeta(SyncMetaEntity(mirrorKey, mirrorValue))
            true
        }
        if (added) runCatching { scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE) }
        return added
    }
}

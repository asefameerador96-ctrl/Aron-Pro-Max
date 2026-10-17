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
private val ISO_MS: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

class TsoOutbox(
    private val env: TsoEnv,
    private val databases: UserDatabases,
    private val scheduler: SyncScheduler,
    private val online: () -> Boolean,
) {
    constructor(context: Context, components: SessionComponents, databases: UserDatabases, scheduler: SyncScheduler) :
        this(SessionTsoEnv(components), databases, scheduler, {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        })

    // the contract's Timestamp: millisecond precision and a Z suffix (Instant.toString drops ".000" on whole seconds)
    private fun iso(ms: Long) = ISO_MS.format(java.time.Instant.ofEpochMilli(ms))

    /** One record of a batch (a plan and its outlets are one family and must commit together). */
    class Rec(val type: RecordType, val clientUuid: String, val payload: JsonObject, val familyUuid: String = clientUuid, val rank: Int = 0, val routeId: Long? = null)

    suspend fun enqueue(
        userId: Long, type: RecordType, clientUuid: String, payload: JsonObject,
        familyUuid: String = clientUuid, rank: Int = 0, mirrorKey: String? = null, mirrorValue: String? = null, routeId: Long? = null,
    ): Boolean = enqueueAll(userId, listOf(Rec(type, clientUuid, payload, familyUuid, rank, routeId)), listOfNotNull(if (mirrorKey != null && mirrorValue != null) mirrorKey to mirrorValue else null)) > 0

    /**
     * All [recs] and all [mirrors] in ONE transaction; a record whose uuid is already queued is skipped (so a repeated save of a
     * union adds only what is new). Returns how many records were added; a sync is asked for when at least one was.
     */
    suspend fun enqueueAll(
        userId: Long, recs: List<Rec>, mirrors: List<Pair<String, String>> = emptyList(),
        /** Mirror rows whose new value is computed FROM the stored one inside the transaction (a union cannot lose an update to a concurrent save). */
        merges: List<Pair<String, (existing: String?) -> String>> = emptyList(),
        /** Mirror rows removed in the same transaction (a pending flag ends with the record that settles it). */
        deletes: List<String> = emptyList(),
    ): Int {
        val db = databases.of(userId)
        val added = db.withTransaction {
            val outbox = db.outboxDao()
            val dao = db.referenceDao()
            var n = 0
            for (r in recs) {
                if (outbox.byClientUuid(r.clientUuid) != null) continue
                val now = env.nowMs()
                val meta = CaptureMeta(
                    businessDate = env.businessDate(), capturedAt = iso(now), capturedElapsedMs = env.elapsedRealtimeMs(),
                    bootCount = env.bootCount(), clockOffsetMs = env.clockOffsetMs(), capturedOffline = !online(),
                    routeId = r.routeId, bundleVersion = dao.meta(ReferenceRepository.KEY_BUNDLE_VERSION),
                    configVersion = dao.meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L,
                )
                val text = RecordMapping.json.encodeToString(
                    JsonElement.serializer(), RecordMapping.record(r.type, r.clientUuid, r.familyUuid, r.rank, meta, JsonObject.serializer(), r.payload),
                )
                outbox.insert(listOf(OutboxEntity(
                    clientUuid = r.clientUuid, recordType = r.type.wire, familyUuid = r.familyUuid, rank = r.rank, businessDate = meta.businessDate,
                    payloadJson = text, payloadSha256 = RecordMapping.sha256Hex(text), createdAt = iso(now),
                )))
                n++
            }
            if (n > 0) mirrors.forEach { (k, v) -> dao.putMeta(SyncMetaEntity(k, v)) }
            for ((k, f) in merges) { val cur = dao.meta(k); val v = f(cur); if (v != cur) dao.putMeta(SyncMetaEntity(k, v)) }
            deletes.forEach { dao.deleteMeta(it) }
            n
        }
        if (added > 0) runCatching { scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE) }
        return added
    }
}

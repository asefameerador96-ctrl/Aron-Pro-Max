package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The outbox (docs/24 s5.2): one row per sync record, written in the same transaction as its domain row.
 * [seq] is commit order and therefore send order (s4.2 rule 1); [payloadJson] is the full record, envelope included,
 * exactly as it will be sent; it is never edited after commit.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["client_uuid"], unique = true),
        Index(value = ["state", "seq"]),
        Index("batch_uuid"),
        Index(value = ["business_date", "record_type"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @ColumnInfo(name = "record_type") val recordType: String,
    @ColumnInfo(name = "family_uuid") val familyUuid: String,
    val rank: Int,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "payload_sha256") val payloadSha256: String,
    val state: String = OutboxState.PENDING,
    @ColumnInfo(name = "batch_uuid") val batchUuid: String? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_code") val lastCode: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "acked_at") val ackedAt: String? = null,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    /** F-SYS-072: the record's ES256 `sig`, made once when its batch is first assembled and sent unchanged on every retry. */
    @ColumnInfo(name = "sig") val sig: String? = null,
)

/** Outbox row states (docs/24 s4.5, s4.6). */
object OutboxState {
    const val PENDING = "pending"
    const val IN_FLIGHT = "in_flight"
    const val ACKED = "acked"
    const val REJECTED = "rejected"
    const val QUARANTINED = "quarantined"
    val ALL = setOf(PENDING, IN_FLIGHT, ACKED, REJECTED, QUARANTINED)
}

/** Small key-value state of the sync engine (bundle version, delta cursor, config version, last digest). */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A client UUID whose record was acked and then purged from this phone (F-SYS-028). A replay of the same UUID (a retried tap, a
 * duplicated upload) finds it here and writes nothing: without it the purged record would be captured again and sent a second time
 * (the server dedupes by UUID, but the phone must not create a second record either). Kept [com.aktcl.aron.core.database.repo.LocalPurge.TOMBSTONE_DAYS] days.
 */
@Entity(tableName = "purged_uuid")
data class PurgedUuidEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @ColumnInfo(name = "purged_at") val purgedAt: String,
)

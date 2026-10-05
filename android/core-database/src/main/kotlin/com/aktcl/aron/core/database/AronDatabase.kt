package com.aktcl.aron.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Day-1 seed of the outbox (docs/24 s4.1). The core agent replaces it with the full schema of s5.1; the columns
 * here already follow the contract: client_uuid is the identity, seq is the send order, state is the s4.6 machine.
 */
@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val clientUuid: String,
    val recordType: String,
    val familyUuid: String,
    val rank: Int,
    val businessDate: String,
    val payloadJson: String,
    val payloadSha256: String,
    val state: String = "pending",
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(row: OutboxRow): Long

    @Query("SELECT COUNT(*) FROM outbox WHERE state = :state")
    suspend fun countInState(state: String): Int
}

@Database(entities = [OutboxRow::class], version = 1, exportSchema = true)
abstract class AronDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
}

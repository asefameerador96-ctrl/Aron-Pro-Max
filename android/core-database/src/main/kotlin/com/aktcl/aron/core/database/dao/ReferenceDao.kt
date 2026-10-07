package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.entity.MemoCounterEntity
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.TaskEntity
import com.aktcl.aron.core.database.entity.PriceEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity

@Dao
interface ReferenceDao {
    @Query("DELETE FROM route") suspend fun clearRoutes()
    @Query("DELETE FROM outlet") suspend fun clearOutlets()
    @Query("DELETE FROM sku") suspend fun clearSkus()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRoutes(rows: List<RouteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOutlets(rows: List<OutletEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSkus(rows: List<SkuEntity>)

    @Query("SELECT * FROM route WHERE business_date = :businessDate ORDER BY planned_today DESC, sequence_no, code")
    suspend fun routesFor(businessDate: String): List<RouteEntity>

    /** Outlets in visit order: the route's visit sequence, then the Bangla-aware sort key the server sends. */
    @Query("SELECT * FROM outlet WHERE route_id = :routeId ORDER BY visit_sequence IS NULL, visit_sequence, name_sort_key")
    suspend fun outletsOf(routeId: Long): List<OutletEntity>

    @Query("SELECT * FROM outlet WHERE outlet_id = :outletId")
    suspend fun outlet(outletId: Long): OutletEntity?

    @Query("SELECT * FROM sku WHERE status = 'active' ORDER BY sort, code")
    suspend fun activeSkus(): List<SkuEntity>

    @Query("SELECT COUNT(*) FROM outlet") suspend fun outletCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(row: SyncMetaEntity)
    @Query("SELECT value FROM sync_meta WHERE `key` = :key") suspend fun meta(key: String): String?
    @Query("DELETE FROM sync_meta WHERE `key` = :key") suspend fun deleteMeta(key: String)
    @Query("SELECT * FROM sync_meta WHERE substr(`key`, 1, length(:prefix)) = :prefix ORDER BY `key`") suspend fun metaWithPrefix(prefix: String): List<SyncMetaEntity>

    @Query("DELETE FROM price") suspend fun clearPrices()
    @Query("DELETE FROM config_value") suspend fun clearConfig()
    @Query("DELETE FROM bundle_section") suspend fun clearSections()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPrices(rows: List<PriceEntity>)
    @Insert suspend fun insertConfig(rows: List<ConfigValueEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSections(rows: List<BundleSectionEntity>)

    /** The price of [skuId] and [priceType] in force on [businessDate] (`valid_to` exclusive, as the contract says); the latest start wins. */
    @Query(
        """SELECT * FROM price WHERE sku_id = :skuId AND price_type = :priceType AND valid_from <= :businessDate
           AND (valid_to IS NULL OR valid_to > :businessDate) ORDER BY valid_from DESC, price_id DESC LIMIT 1""",
    )
    suspend fun priceOn(skuId: Long, priceType: String, businessDate: String): PriceEntity?

    @Query("SELECT COUNT(*) FROM price") suspend fun priceCount(): Int

    @Query("SELECT * FROM config_value WHERE key = :key ORDER BY scheduled, effective_from")
    suspend fun configRows(key: String): List<ConfigValueEntity>

    @Query("SELECT json FROM bundle_section WHERE name = :name") suspend fun section(name: String): String?

    @Query("SELECT last_n FROM memo_counter WHERE business_date = :businessDate") suspend fun memoCounter(businessDate: String): Int?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemoCounter(row: MemoCounterEntity)

    @Query("DELETE FROM task") suspend fun clearTasks()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTasks(rows: List<TaskEntity>)
    @Query("UPDATE task SET status = 'completed', resolved_at = :resolvedAt WHERE task_uuid = :taskUuid") suspend fun completeTask(taskUuid: String, resolvedAt: String): Int
    @Query("SELECT * FROM task ORDER BY due_date IS NULL, due_date, title") suspend fun tasks(): List<TaskEntity>
    @Query("SELECT * FROM task WHERE task_uuid = :taskUuid") suspend fun task(taskUuid: String): TaskEntity?

    /** A bundle replaces tasks; the phone's own resolutions not yet acked by the server are kept (after the ack the server's status wins). */
    @Query(
        """UPDATE task SET status = 'completed',
           resolved_at = COALESCE(resolved_at, (SELECT MAX(e.captured_at) FROM task_event e WHERE e.task_uuid = task.task_uuid AND e.event = 'resolved'))
           WHERE task_uuid IN (SELECT e.task_uuid FROM task_event e JOIN outbox o ON o.client_uuid = e.client_uuid
                               WHERE e.event = 'resolved' AND o.state != 'acked')""",
    )
    suspend fun reapplyLocalResolutions()
}

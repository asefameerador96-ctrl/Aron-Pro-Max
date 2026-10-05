package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aktcl.aron.core.database.entity.OutletEntity
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
}

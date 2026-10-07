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

    // Row deletes of a bundle delta (F-SYS-007).
    @Query("DELETE FROM outlet WHERE outlet_id IN (:ids)") suspend fun deleteOutlets(ids: List<Long>)
    @Query("DELETE FROM outlet WHERE route_id = :routeId") suspend fun deleteOutletsOfRoute(routeId: Long)
    @Query("DELETE FROM route WHERE route_id = :routeId") suspend fun deleteRoute(routeId: Long)
    @Query("DELETE FROM price WHERE price_id IN (:ids)") suspend fun deletePrices(ids: List<Long>)
    @Query("DELETE FROM sku WHERE sku_id IN (:ids)") suspend fun deleteSkus(ids: List<Long>)
    @Query("DELETE FROM task WHERE task_uuid IN (:uuids)") suspend fun deleteTasks(uuids: List<String>)
    @Query("DELETE FROM bundle_section WHERE name = :name OR substr(name, 1, length(:name) + 1) = :name || '#'") suspend fun deleteSection(name: String)
    @Query("SELECT * FROM route WHERE route_id = :routeId") suspend fun route(routeId: Long): RouteEntity?
    @Query("SELECT name FROM bundle_section WHERE name LIKE 'route.%' AND name NOT LIKE '%#%'") suspend fun routeSectionNames(): List<String>

    @Query("SELECT * FROM route WHERE business_date = :businessDate ORDER BY planned_today DESC, sequence_no, code")
    suspend fun routesFor(businessDate: String): List<RouteEntity>

    /** Outlets in visit order: the route's visit sequence, then the Bangla-aware sort key the server sends. */
    @Query("SELECT * FROM outlet WHERE route_id = :routeId ORDER BY visit_sequence IS NULL, visit_sequence, name_sort_key")
    suspend fun outletsOf(routeId: Long): List<OutletEntity>

    @Query("SELECT * FROM outlet WHERE outlet_id = :outletId")
    suspend fun outlet(outletId: Long): OutletEntity?

    /** Every SKU of the bundle, inactive ones too (a memo line of an SKU since withdrawn still has a category). */
    @Query("SELECT sku_id AS skuId, category_code AS categoryCode FROM sku") suspend fun skuCategories(): List<SkuCategory>
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

    // ---- v5: AV/KV content and surveys (F-SR-020/021), replaced by every snapshot ----
    @Query("DELETE FROM content_item") suspend fun clearContentItems()
    @Query("DELETE FROM outlet_content_assignment") suspend fun clearContentAssignments()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertContentItems(rows: List<com.aktcl.aron.core.database.entity.ContentItemEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertContentAssignments(rows: List<com.aktcl.aron.core.database.entity.OutletContentAssignmentEntity>)
    @Query("DELETE FROM survey") suspend fun clearSurveys()
    @Query("DELETE FROM survey_question") suspend fun clearSurveyQuestions()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSurveys(rows: List<com.aktcl.aron.core.database.entity.SurveyEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSurveyQuestions(rows: List<com.aktcl.aron.core.database.entity.SurveyQuestionEntity>)

    /** The items to show at [outletId] on [businessDate], in play order (sequence; AV before KV on a tie). */
    @Query(
        """SELECT * FROM content_item WHERE valid_from <= :businessDate AND valid_to >= :businessDate
           AND (all_outlets = 1 OR content_id IN (SELECT content_id FROM outlet_content_assignment WHERE outlet_id = :outletId))
           ORDER BY sequence, kind, content_id""",
    )
    suspend fun contentForOutlet(outletId: Long, businessDate: String): List<com.aktcl.aron.core.database.entity.ContentItemEntity>

    /** Every item valid on [businessDate] or later (the asset prefetch). */
    @Query("SELECT * FROM content_item WHERE valid_to >= :businessDate ORDER BY valid_from, sequence, kind, content_id")
    suspend fun contentFrom(businessDate: String): List<com.aktcl.aron.core.database.entity.ContentItemEntity>

    @Query("SELECT * FROM survey ORDER BY survey_id") suspend fun surveys(): List<com.aktcl.aron.core.database.entity.SurveyEntity>
    @Query("SELECT * FROM survey_question WHERE survey_id = :surveyId ORDER BY ordinal")
    suspend fun surveyQuestions(surveyId: Long): List<com.aktcl.aron.core.database.entity.SurveyQuestionEntity>

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

    @Query("DELETE FROM config_value WHERE key = :key AND scheduled = :scheduled") suspend fun deleteConfig(key: String, scheduled: Boolean)
    @Query("DELETE FROM config_value WHERE key = :key") suspend fun deleteConfigKey(key: String)
    @Query("UPDATE outlet SET radius_m = :radiusM, max_accuracy_m = :maxAccuracyM WHERE outlet_id = :outletId")
    suspend fun updateOutletRadius(outletId: Long, radiusM: Int, maxAccuracyM: Int): Int
}

data class SkuCategory(val skuId: Long, val categoryCode: String)

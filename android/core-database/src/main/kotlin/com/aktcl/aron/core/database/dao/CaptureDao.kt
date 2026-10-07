package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.TaskEventEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity

/**
 * Inserts of device-originated rows. Every insert ABORTs on a duplicate client UUID (or memo number): a capture is
 * committed once; a retry of the same capture is a caller bug and must fail loudly instead of doubling a number.
 */
@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFix(row: GeoFixEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttendance(row: AttendanceEventEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertStock(rows: List<StockMovementEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertVisit(row: VisitEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertVisitClose(row: VisitCloseEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMemo(row: MemoEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMemoLines(rows: List<MemoLineEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMemoDiscounts(rows: List<MemoDiscountEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertQcLines(rows: List<QcLineEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertDueCollection(row: DueCollectionEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertVisitSkip(row: VisitSkipEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertDaySubmit(row: DaySubmitEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOutletRequest(row: OutletChangeRequestEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTaskEvent(row: TaskEventEntity)

    @Query("SELECT * FROM memo_discount WHERE memo_client_uuid = :memoClientUuid ORDER BY client_uuid")
    suspend fun discountsOf(memoClientUuid: String): List<MemoDiscountEntity>

    @Query("SELECT * FROM qc_line WHERE memo_client_uuid = :memoClientUuid ORDER BY client_uuid")
    suspend fun qcLinesOf(memoClientUuid: String): List<QcLineEntity>

    @Query("SELECT * FROM qc_line WHERE business_date = :businessDate ORDER BY captured_at")
    suspend fun qcLinesOn(businessDate: String): List<QcLineEntity>

    /** Memos of a date range (Sale History, 7 days), newest first; superseded ones included, see [supersededIn]. */
    @Query("SELECT * FROM memo WHERE business_date BETWEEN :fromDate AND :toDate ORDER BY business_date DESC, committed_at DESC")
    suspend fun memosBetween(fromDate: String, toDate: String): List<MemoEntity>

    /** Client uuids of memos in the range that a later edit superseded. */
    @Query(
        """SELECT DISTINCT supersedes_client_uuid FROM memo WHERE supersedes_client_uuid IS NOT NULL
           AND business_date BETWEEN :fromDate AND :toDate""",
    )
    suspend fun supersededIn(fromDate: String, toDate: String): List<String>

    @Query("SELECT * FROM visit_close WHERE business_date = :businessDate ORDER BY captured_at")
    suspend fun visitClosesOn(businessDate: String): List<VisitCloseEntity>

    @Query("SELECT * FROM due_collection WHERE against_memo_client_uuid = :memoClientUuid ORDER BY captured_at")
    suspend fun dueCollectionsOf(memoClientUuid: String): List<DueCollectionEntity>

    @Query("SELECT * FROM due_collection WHERE business_date = :businessDate ORDER BY captured_at")
    suspend fun dueCollectionsOn(businessDate: String): List<DueCollectionEntity>

    @Query("SELECT * FROM visit_skip WHERE business_date = :businessDate ORDER BY captured_at")
    suspend fun visitSkipsOn(businessDate: String): List<VisitSkipEntity>

    @Query("SELECT * FROM day_submit WHERE business_date = :businessDate ORDER BY submit_cycle")
    suspend fun daySubmitsOn(businessDate: String): List<DaySubmitEntity>

    @Query("SELECT * FROM outlet_change_request ORDER BY captured_at DESC")
    suspend fun outletRequests(): List<OutletChangeRequestEntity>

    @Query("SELECT * FROM task_event WHERE task_uuid = :taskUuid ORDER BY captured_at")
    suspend fun taskEventsOf(taskUuid: String): List<TaskEventEntity>

    @Query("SELECT * FROM geo_fix WHERE client_uuid = :clientUuid")
    suspend fun fix(clientUuid: String): GeoFixEntity?

    @Query("SELECT * FROM visit WHERE client_uuid = :clientUuid")
    suspend fun visit(clientUuid: String): VisitEntity?

    @Query("SELECT * FROM visit WHERE business_date = :businessDate ORDER BY sequence_no")
    suspend fun visitsOn(businessDate: String): List<VisitEntity>

    @Query("SELECT * FROM memo WHERE client_uuid = :clientUuid")
    suspend fun memo(clientUuid: String): MemoEntity?

    @Query("SELECT * FROM memo WHERE business_date = :businessDate ORDER BY committed_at")
    suspend fun memosOn(businessDate: String): List<MemoEntity>

    @Query("SELECT * FROM memo_line WHERE memo_client_uuid = :memoClientUuid ORDER BY line_no")
    suspend fun linesOf(memoClientUuid: String): List<MemoLineEntity>

    @Query("SELECT * FROM attendance_event WHERE business_date = :businessDate ORDER BY captured_at")
    suspend fun attendanceOn(businessDate: String): List<AttendanceEventEntity>

    @Query("SELECT * FROM stock_movement WHERE business_date = :businessDate ORDER BY captured_at")
    suspend fun stockOn(businessDate: String): List<StockMovementEntity>

    /** Issued minus returned and adjusted stock in base units per SKU for a day (issue and adjustment add; others, `qc_return` included, subtract). */
    @Query(
        """SELECT sku_id AS skuId, SUM(CASE WHEN kind IN ('issue','adjustment') THEN qty_base ELSE -qty_base END) AS qtyBase
           FROM stock_movement WHERE business_date = :businessDate GROUP BY sku_id ORDER BY sku_id""",
    )
    suspend fun stockBalanceOn(businessDate: String): List<SkuQty>
}

data class SkuQty(val skuId: Long, val qtyBase: Long)

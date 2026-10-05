package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    /** Issued minus returned and adjusted stock in base units per SKU for a day (issue and adjustment add; others subtract). */
    @Query(
        """SELECT sku_id AS skuId, SUM(CASE WHEN kind IN ('issue','adjustment') THEN qty_base ELSE -qty_base END) AS qtyBase
           FROM stock_movement WHERE business_date = :businessDate GROUP BY sku_id ORDER BY sku_id""",
    )
    suspend fun stockBalanceOn(businessDate: String): List<SkuQty>
}

data class SkuQty(val skuId: Long, val qtyBase: Long)

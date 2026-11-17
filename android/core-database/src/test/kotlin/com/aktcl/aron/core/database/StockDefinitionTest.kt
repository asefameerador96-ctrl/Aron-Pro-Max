package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.repo.StockHeld
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-AGATE-07 (lead's round 2, item 4): ONE definition of "held". The SQL behind `stockBalanceOn` (day-close digest, the Home figure's
 * loaded part) and the Kotlin [StockHeld.held] (the gate, the sale catalog) must agree for EVERY stock_movement kind, not only issue.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StockDefinitionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private fun move(kind: String, sku: Long, qty: Long, date: String = "2026-10-05") = StockMovementEntity(
        clientUuid = ClientIds.newUuid(), meta = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, null, null, "b", false, 1),
        kind = kind, skuId = sku, qtyEntered = qty, unitEntered = "stick", packFactor = 1, qtyBase = qty, slipPrinted = false,
    )

    @Test fun theSqlAndTheKotlinFormulaAgreeForEveryKind() = runTest {
        // every kind the table allows (V0007: issue, return, adjustment, damaged, short, qc_return), on two SKUs and two days
        val kinds = listOf("issue", "return", "adjustment", "damaged", "short", "qc_return")
        val rows = kinds.mapIndexed { i, k -> move(k, 1, 100L + i) } + kinds.mapIndexed { i, k -> move(k, 2, 7L * (i + 1)) } + move("issue", 1, 9_999, date = "2026-10-04")
        db.captureDao().insertStock(rows)
        val sql = db.captureDao().stockBalanceOn("2026-10-05").associate { it.skuId to it.qtyBase }
        val kotlin = StockHeld.held(db.captureDao().stockOn("2026-10-05"), emptyList(), emptyList())
        assertEquals("the SQL and the formula are one definition", kotlin, sql)
        // spelled out: issue + adjustment - qc_return; return, damaged and short count nothing
        assertEquals(100L + 102L - 105L, sql.getValue(1L))
        assertEquals(7L + 21L - 42L, sql.getValue(2L))
    }
}

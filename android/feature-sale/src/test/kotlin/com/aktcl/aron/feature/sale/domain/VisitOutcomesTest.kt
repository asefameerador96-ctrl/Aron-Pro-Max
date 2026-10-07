package com.aktcl.aron.feature.sale.domain

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VisitOutcomesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    @Test fun everyContractOutcomeIsCoveredAndCloseWritesOneRecordWithOutbox() = runTest {
        assertEquals(
            setOf("sold", "zero_sale_stock_ok", "closed", "owner_absent", "refused", "competitor_exclusive", "not_reached", "abandoned"),
            VisitOutcomeCode.entries.map { it.wire }.toSet(),
        )
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val uuid = ClientIds.newUuid()
        val (v, f) = Fx.visit(uuid); repo.recordVisitOpen(v, f)
        val closer = VisitCloser(repo, { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:40:00.000Z" })
        closer.close(VisitToClose(uuid, 10231, null), VisitOutcomeCode.CLOSED, Fx.DATE)
        assertEquals(listOf("visit", "visit_close"), db.outboxDao().nextPending(10).map { it.recordType })
        // a second close of the same visit cannot double the record
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { closer.close(VisitToClose(uuid, 10231, null), VisitOutcomeCode.SOLD, Fx.DATE) } }
        assertEquals(2, db.outboxDao().nextPending(10).size)
    }

    @Test fun abandonedIsNotVisitedAndNoSaleExcludesSoldOutlets() {
        val vs = listOf(
            ClosedVisit(1, "d", "sold"), ClosedVisit(2, "d", "closed"), ClosedVisit(3, "d", "abandoned"),
            ClosedVisit(4, "d", "zero_sale_stock_ok"), ClosedVisit(1, "d", "refused"),
        )
        assertEquals(3, VisitStats.visitedOutlets(vs))
        assertEquals(2, VisitStats.noSaleOutlets(vs)) // outlets 2 and 4; outlet 1 sold
    }

    @Test fun threeConsecutiveClosedRaisesAnAmoTaskAbandonedIsSkippedAndASaleBreaksIt() {
        fun h(o: Long, d: String, x: String) = ClosedVisit(o, d, x)
        val history = listOf(h(7, "2026-10-01", "closed"), h(7, "2026-10-02", "closed"), h(7, "2026-10-03", "abandoned"), h(7, "2026-10-04", "closed"),
            h(8, "2026-10-01", "closed"), h(8, "2026-10-02", "sold"), h(8, "2026-10-03", "closed"),
            h(9, "2026-10-01", "closed"), h(9, "2026-10-02", "closed"))
        assertEquals(setOf(7L), VisitStats.closedStreakOutlets(history))
        assertTrue(VisitStats.closedStreakOutlets(emptyList()).isEmpty())
    }
}

package com.aktcl.aron.feature.memo.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.SaleCapture
import com.aktcl.aron.feature.memo.domain.DueLedger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomMemoAdaptersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private fun meta() = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)

    @Test fun storedMemoReadsLinesAndMarkPaidWritesOneCollectionAndTheDueGoes() = runTest {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        repo.recordSale(SaleCapture(
            MemoEntity(memo, meta(), visit, 1, "sr334001-261005-001", "sale", "2026-10-05T04:35:00.000Z", "2026-10-05", "outlet", 84_000, 0, 0, 0, 0, 84_000, 34_000, 50_000, true, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), meta(), memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
        val store = RoomMemoStore(db)
        val stored = store.memos("2026-10-05").single()
        assertEquals(84_000, stored.lines.single().grossMtk); assertEquals(50_000, stored.dueMtk)

        val writer = RoomDueCollectionWriter(repo, { meta() }, { visit })
        val vm = DueLedger.markPaid(stored, store.collections("2026-10-05"), 50_000)!!
        writer.write(vm)
        assertEquals(0, DueLedger.remaining(stored, store.collections("2026-10-05")))
        assertEquals(1, db.captureDao().dueCollectionsOn("2026-10-05").size)
        assertEquals(1, store.history("2026-10-05").size)
    }
}

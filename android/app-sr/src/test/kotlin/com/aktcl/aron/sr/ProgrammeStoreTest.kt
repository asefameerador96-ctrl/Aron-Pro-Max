package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.programmes.Basket
import com.aktcl.aron.feature.programmes.LocalRedeemed
import com.aktcl.aron.feature.programmes.LoyaltyGift
import com.aktcl.aron.feature.programmes.SaveOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lane android-sr-programmes: a confirmed redemption is saved once, a replay writes nothing, and the next balance reads it back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProgrammeStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-11-18T05:00:00.000Z" }
    private var syncs = 0
    private val meta = CaptureMetaProvider { rid ->
        CaptureMeta(businessDate = "2026-11-18", capturedAt = "2026-11-18T05:00:00.000Z", capturedElapsedMs = 1_000, bootCount = 1, clockOffsetMs = 0, capturedOffline = true,
            routeId = rid, bundleVersion = "2026-11-18:1", configVersion = 1)
    }
    private val store = ProgrammeStore(db, repo, meta) { syncs++ }
    private val chair = LoyaltyGift(1, 3, "Chair", null, 200, emptyList(), null, true)

    @After fun tearDown() = db.close()

    private fun plan(uuid: String, qty: Int) = Basket(10, 3, 500).let { b -> (1..qty).fold(b) { acc, _ -> acc.plus(chair) } }.plan(listOf(chair), uuid, ClientIds::newUuid, "2026-11-18T05:00:00.000Z")

    @Test fun savedOnceAReplayWritesNothingAndTheBalanceReadsItBack() = runTest {
        val uuid = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), plan(uuid, 2)))
        assertEquals(SaveOutcome.ALREADY_SAVED, store.saveProgramme(meta.meta(7), plan(uuid, 2))) // a double tap or a retry after a kill and relaunch: the same redemption, not a second one
        val r = store.redemptions()
        assertEquals(1, r.size); assertEquals(400L, r[0].points); assertEquals("pending", r[0].state)
        assertEquals(2, db.outboxDao().countInState("pending")) // the redemption and its one line
        // the bundle was built before the redemption: the phone holds it back from the balance (500 - 400)
        assertEquals(400L, LocalRedeemed.points(r, 10, 3, "2026-11-18T01:00:00.000Z"))
        assertTrue(syncs >= 1)
    }

    @Test fun aSaveTheGuardRefusesWritesNothingAndSaysSo() = runTest {
        val uuid = ClientIds.newUuid()
        val bad = plan(uuid, 1).let { it.dropLast(1) } // the redemption without its line: line_count says 1
        assertEquals(SaveOutcome.REFUSED, store.saveProgramme(meta.meta(7), bad))
        assertEquals(0, store.redemptions().size)
        assertEquals(0, db.outboxDao().countInState("pending"))
    }

    @Test fun aGiftChoiceIsSavedOnceAndReadBackWithItsState() = runTest {
        val uuid = ClientIds.newUuid()
        val rec = com.aktcl.aron.feature.programmes.GiftChoiceModel.plan(10, 4, "2026-Q4", uuid)
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), listOf(rec)))
        assertEquals(SaveOutcome.ALREADY_SAVED, store.saveProgramme(meta.meta(7), listOf(rec))) // a double tap: the same record
        val c = store.giftChoices()
        assertEquals(1, c.size); assertEquals(4L, c[0].giftId); assertEquals("2026-Q4", c[0].quarter); assertEquals("pending", c[0].state)
        assertEquals(1, db.outboxDao().countInState("pending"))
    }

    @Test fun aDifferentBasketUnderTheSameUuidIsAConflictNotSaved() = runTest {
        val uuid = ClientIds.newUuid()
        assertEquals(SaveOutcome.SAVED, store.saveProgramme(meta.meta(7), plan(uuid, 1)))
        assertEquals(SaveOutcome.CONFLICT, store.saveProgramme(meta.meta(7), plan(uuid, 2))) // two chairs under the uuid that held one
        val r = store.redemptions()
        assertEquals(1, r.size); assertEquals(200L, r[0].points) // the first basket stands, nothing was changed
        assertEquals(2, db.outboxDao().countInState("pending"))
    }
}

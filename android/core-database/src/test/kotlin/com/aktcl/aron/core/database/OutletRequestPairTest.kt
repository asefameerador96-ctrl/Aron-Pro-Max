package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.ClusterCreateEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SRM-B-40 and SRM-B-44: a change-information request and its cluster change are ONE transaction; a created cluster is local, idempotent and has its fix. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OutletRequestPairTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val repo = CaptureRepository(db) { "2026-11-12T04:00:00.000Z" }
    private val meta = CaptureMeta("2026-11-12", "2026-11-12T04:00:00.000Z", 1, 1, 0, true, 1, null, "b", false, 1)

    private fun fix(id: String, owner: String) = GeoFixEntity(id, owner, "outlet_capture", "ok", 23.78, 90.40, 10.0, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
    private fun request(id: String, fixId: String, type: String) = OutletChangeRequestEntity(
        clientUuid = id, meta = meta, requestType = type, outletId = 10, proposedJson = "{}", fixClientUuid = fixId, photoUuidsJson = "[]", originVisitClientUuid = null, note = "n",
    )
    private val a = "00000000-0000-4000-8000-00000000000a"; private val fa = "00000000-0000-4000-8000-0000000000fa"
    private val b = "00000000-0000-4000-8000-00000000000b"; private val fb = "00000000-0000-4000-8000-0000000000fb"

    @Test fun bothRequestsAreWrittenTogetherAndAReplayWritesNothing() = runTest {
        repo.recordOutletRequests(listOf(request(a, fa, "info") to fix(fa, a), request(b, fb, "cluster") to fix(fb, b)))
        assertEquals(1, db.captureDao().outletRequestCount(a)); assertEquals(1, db.captureDao().outletRequestCount(b))
        repo.recordOutletRequests(listOf(request(a, fa, "info") to fix(fa, a), request(b, fb, "cluster") to fix(fb, b))) // a replay
        assertEquals(1, db.captureDao().outletRequestCount(a)); assertEquals(1, db.captureDao().outletRequestCount(b))
    }

    @Test fun aFailureOfTheSecondRowLeavesNeitherRowBehind() = runTest {
        val broken = request(b, fb, "not_a_contract_type") to fix(fb, b) // refused: the type is not in the contract
        val failed = runCatching { repo.recordOutletRequests(listOf(request(a, fa, "info") to fix(fa, a), broken)) }
        assertTrue(failed.isFailure)
        assertEquals("the first request was rolled back with the second", 0, db.captureDao().outletRequestCount(a))
        assertEquals(0, db.outboxDao().countInState(com.aktcl.aron.core.database.entity.OutboxState.PENDING))
    }

    @Test fun aCreatedClusterIsStoredWithItsFixOnceAndNeverQueuedForSync() = runTest {
        val c = ClusterCreateEntity(a, "Lakepar", 5, "Agora GoliDaily", "2026-11-12T04:00:00.000Z", "2026-11-12", fa)
        assertTrue(repo.recordClusterCreate(c, fix(fa, a)))
        assertFalse("a replay of the same client uuid writes nothing", repo.recordClusterCreate(c, fix(fa, a)))
        assertEquals(1, db.captureDao().clusterCreateCount())
        assertEquals("Lakepar", db.captureDao().clusterCreates().single().name)
        assertEquals("local only: no outbox row until the contract record exists", 0, db.outboxDao().countInState(com.aktcl.aron.core.database.entity.OutboxState.PENDING))
    }
}

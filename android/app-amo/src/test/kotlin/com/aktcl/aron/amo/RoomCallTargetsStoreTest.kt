package com.aktcl.aron.amo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.feature.amo.CachedCallTargets
import com.aktcl.aron.feature.amo.CallTarget
import com.aktcl.aron.feature.amo.CallTargetsFetch
import com.aktcl.aron.feature.amo.CallTargetsStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-AMO-SHELL-10: the kept copy per month lives in Room (`sync_meta`), so it survives a kill and serves the cards offline. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomCallTargetsStoreTest {
    private val answer = """{"as_of":"2026-11-10T09:30:00.000Z","business_date":"2026-11-10","period":"2026-11",
        "today":{"done":3,"target":5},"month":{"done":42,"target":130},"control":{"done":12,"target":null},"joint":{"done":4,"target":20}}"""

    private fun db(): AronDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AronDatabase::class.java).allowMainThreadQueries().build()

    @Test fun writeReadClearRoundTripPerMonth() = runBlocking {
        val db = db()
        val store = RoomCallTargetsStore(db.referenceDao())
        assertNull(store.read("2026-11"))
        store.write("2026-11", answer)
        assertEquals(answer, store.read("2026-11"))
        assertNull(store.read("2026-10"))
        store.write("2026-11", "{}")  // a later answer replaces the earlier one
        assertEquals("{}", store.read("2026-11"))
        store.clear("2026-11")
        assertNull(store.read("2026-11"))
        db.close()
    }

    @Test fun aNewSourceOnTheSameDatabaseServesTheKeptCopyWhileOffline() = runBlocking {
        val db = db()
        val online = CachedCallTargets({ CallTargetsFetch.Fresh(answer) }, RoomCallTargetsStore(db.referenceDao()))
        assertEquals(CallTargetsStatus.Fresh, online.load("2026-11").status)
        // the app was killed and relaunched offline: a fresh source, same Room
        val offline = CachedCallTargets({ CallTargetsFetch.Unavailable }, RoomCallTargetsStore(db.referenceDao()))
        val r = offline.load("2026-11")
        assertEquals(CallTargetsStatus.Stale, r.status)
        assertEquals(CallTarget(42, 130), r.targets!!.month)
        db.close()
    }
}

package com.aktcl.aron.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Proves Room + KSP + Robolectric work in this build (docs/24 s13.3 android gate). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OutboxDaoTest {
    @Test
    fun insertsAndCountsPendingRows() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AronDatabase::class.java)
            .allowMainThreadQueries().build()
        db.outboxDao().insert(
            OutboxRow(
                clientUuid = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", recordType = "visit",
                familyUuid = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", rank = 1, businessDate = "2026-10-05",
                payloadJson = "{}", payloadSha256 = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            ),
        )
        assertEquals(1, db.outboxDao().countInState("pending"))
        db.close()
    }
}

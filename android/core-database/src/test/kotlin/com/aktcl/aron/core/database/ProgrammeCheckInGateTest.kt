package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.record.ProgrammePayloads
import com.aktcl.aron.core.database.repo.AttendanceRefusedException
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The SR programme records are operational writes: behind the check-in gate (train 118), refused without a check-in or after the check-out, nothing written. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProgrammeCheckInGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var gated: CaptureRepository
    private val now = "2026-10-05T04:36:00.000Z"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        gated = CaptureRepository(db, requireCheckIn = true) { now }
    }
    @After fun tearDown() = db.close()

    private suspend fun checkIn() { val (e, f) = TestRows.attendance(); gated.recordAttendance(e, f) }
    private suspend fun checkOut() {
        val u = ClientIds.newUuid(); val f = TestRows.fix(u, "attendance_out")
        gated.recordAttendance(AttendanceEventEntity(u, TestRows.meta(routeId = null).copy(capturedAt = "2026-10-05T11:30:00.000Z"), "check_out", f.clientUuid, null), f)
    }
    private fun choice(uuid: String = ClientIds.newUuid()) = AmoRecordEntity(uuid, TestRows.meta(), "gift_choice", uuid, 0, ProgrammePayloads.giftChoice(10, 4, "2026-Q4").toString())
    private fun redemption(): List<AmoRecordEntity> {
        val p = ClientIds.newUuid()
        return listOf(
            AmoRecordEntity(p, TestRows.meta(), "redemption", p, 0, ProgrammePayloads.redemption(10, 3, null, 500, 200, 0, 0, 1, "2026-10-05T04:35:00.000Z").toString()),
            AmoRecordEntity(ClientIds.newUuid(), TestRows.meta(), "redemption_line", p, 1, ProgrammePayloads.redemptionLine(p, 7, 1, 200).toString()),
        )
    }
    private fun nothingWritten() { assertEquals(0, db.query("SELECT COUNT(*) FROM amo_record", null).use { it.moveToFirst(); it.getInt(0) }); assertEquals(0, db.query("SELECT COUNT(*) FROM outbox WHERE record_type IN ('redemption','redemption_line','gift_choice')", null).use { it.moveToFirst(); it.getInt(0) }) }

    @Test fun aRedemptionWithoutACheckInIsRefusedAndNothingIsWritten() = runTest {
        assertThrows(AttendanceRefusedException::class.java) { runBlocking { gated.recordProgramme(redemption()) } }
        nothingWritten()
    }

    @Test fun aGiftChoiceWithoutACheckInIsRefusedAndNothingIsWritten() = runTest {
        assertThrows(AttendanceRefusedException::class.java) { runBlocking { gated.recordProgramme(listOf(choice())) } }
        nothingWritten()
    }

    @Test fun afterCheckInTheWritesGoThrough() = runTest {
        checkIn()
        assertTrue(gated.recordProgramme(redemption()))
        assertTrue(gated.recordProgramme(listOf(choice())))
    }

    @Test fun afterCheckOutARedemptionIsRefusedAgain() = runTest {
        checkIn(); checkOut()
        assertThrows(AttendanceRefusedException::class.java) { runBlocking { gated.recordProgramme(redemption()) } }
        nothingWritten()
    }

    @Test fun aReplayOfASavedRedemptionStillAnswersAlreadySavedNotRefused() = runTest {
        checkIn()
        val r = redemption()
        assertTrue(gated.recordProgramme(r))
        checkOut() // the SR checks out; a late double tap of the saved redemption is a replay, not a new write
        assertEquals(false, gated.recordProgramme(r))
    }
}

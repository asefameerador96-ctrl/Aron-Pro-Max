package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.record.ProgrammePayloads
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lane android-sr-programmes: the redemption and gift_choice records are idempotent by client UUID, all-or-nothing and shaped as the contract. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProgrammeRecordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    @After fun tearDown() = db.close()

    private fun parent(uuid: String, lines: Int, cash: Long = 0, rate: Long = 2_000, total: Long = cash + 200L * lines, balance: Long = 500) =
        AmoRecordEntity(uuid, TestRows.meta(), "redemption", uuid, 0,
            ProgrammePayloads.redemption(10, 3, null, balance, total, cash, rate, lines, "2026-10-05T04:35:00.000Z").toString())

    private fun line(parentUuid: String, qty: Int = 1, each: Long = 200, uuid: String = ClientIds.newUuid()) =
        AmoRecordEntity(uuid, TestRows.meta(), "redemption_line", parentUuid, 1, ProgrammePayloads.redemptionLine(parentUuid, 7, qty, each).toString())

    private suspend fun outboxCount(): Int = db.outboxDao().countInState("pending")

    @Test fun aBasketIsWrittenOnceWithFamilyAndRank() = runTest {
        val p = ClientIds.newUuid()
        assertTrue(repo.recordProgramme(listOf(parent(p, 2), line(p), line(p))))
        assertEquals(1, db.captureDao().amoRecordsOfType("redemption").size)
        assertEquals(2, db.captureDao().amoRecordsOfType("redemption_line").size)
        val o = db.outboxDao().byClientUuid(p)!!
        assertEquals("redemption", o.recordType); assertEquals(0, o.rank); assertEquals(p, o.familyUuid)
        assertEquals(3, outboxCount())
    }

    @Test fun aReplayOrDuplicateUploadOfTheSameRedemptionWritesNothingTwice() = runTest {
        val p = ClientIds.newUuid()
        val l = line(p)
        assertTrue(repo.recordProgramme(listOf(parent(p, 1), l)))
        assertFalse(repo.recordProgramme(listOf(parent(p, 1), l))) // a double tap, a retry after kill and relaunch
        assertFalse(repo.recordProgramme(listOf(parent(p, 1), line(p)))) // even with fresh line uuids: the parent uuid is the key
        assertEquals(1, db.captureDao().amoRecordsOfType("redemption").size)
        assertEquals(1, db.captureDao().amoRecordsOfType("redemption_line").size)
        assertEquals(2, outboxCount())
    }

    @Test fun anInvalidLineWritesNothingNotEvenTheParent() = runTest {
        val p = ClientIds.newUuid()
        val bad = line(p).copy(payloadJson = """{"redemption_client_uuid":"$p","gift_id":7,"qty":51,"points_each":200,"points_total":10200}""")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordProgramme(listOf(parent(p, 2, total = 10_400), line(p), bad)) } }
        assertEquals(0, db.captureDao().amoRecordsOfType("redemption").size)
        assertEquals(0, outboxCount())
    }

    @Test fun theTotalsMustAddUp() = runTest {
        val p = ClientIds.newUuid()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordProgramme(listOf(parent(p, 1, total = 999), line(p))) } } // points_total != cash + lines
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordProgramme(listOf(parent(p, 2), line(p))) } } // line_count 2 but one line
        assertEquals(0, outboxCount())
    }

    @Test fun cashBackIsIntegerMilliTakaAtTheRate() = runTest {
        val p = ClientIds.newUuid()
        val pay = ProgrammePayloads.redemption(10, 3, null, 500, 50, 50, 2_000, 0, "2026-10-05T04:35:00.000Z")
        assertEquals("100000", pay["cash_mtk"].toString()) // 50 points x 2000 mtk
        assertTrue(repo.recordProgramme(listOf(AmoRecordEntity(p, TestRows.meta(), "redemption", p, 0, pay.toString()))))
    }

    @Test fun anOverdrawnPhoneBalanceGoesOutAsZeroNeverNegative() {
        val pay = ProgrammePayloads.redemption(10, 3, null, -40, 200, 0, 2_000, 1, "2026-10-05T04:35:00.000Z")
        assertEquals("0", pay["balance_before_points"].toString())
        assertNull(ProgrammePayloads.problem("redemption", pay))
    }

    @Test fun aGiftChoiceIsIdempotentAndItsQuarterIsChecked() = runTest {
        val u = ClientIds.newUuid()
        val rec = AmoRecordEntity(u, TestRows.meta(), "gift_choice", u, 0, ProgrammePayloads.giftChoice(10, 4, "2026-Q4").toString())
        assertTrue(repo.recordProgramme(listOf(rec)))
        assertFalse(repo.recordProgramme(listOf(rec)))
        assertEquals(1, outboxCount())
        val u2 = ClientIds.newUuid()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordProgramme(listOf(AmoRecordEntity(u2, TestRows.meta(), "gift_choice", u2, 0, ProgrammePayloads.giftChoice(10, 4, "2026-Q5").toString()))) } }
    }

    @Test fun aMemberTheContractLacksIsRefused() = runTest {
        val u = ClientIds.newUuid()
        val extra = ProgrammePayloads.giftChoice(10, 4, "2026-Q4").toString().replace("}", ""","tier":"gold"}""")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordProgramme(listOf(AmoRecordEntity(u, TestRows.meta(), "gift_choice", u, 0, extra))) } }
    }

    @Test fun aChildWithoutItsParentOrWithAWrongFamilyIsRefused() = runTest {
        val p = ClientIds.newUuid()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordProgramme(listOf(line(p))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordProgramme(listOf(parent(p, 1), line(ClientIds.newUuid()))) } }
    }
}

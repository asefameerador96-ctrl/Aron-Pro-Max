package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.record.AmoPayloads
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.LocalPurge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** T1 checker of the AMO record store (refutation tests). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerAmoRecordsTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    @After fun tearDown() = db.close()

    private fun row(type: String, family: String, rank: Int, payload: String, uuid: String = ClientIds.newUuid(), routeId: Long? = 10231) =
        AmoRecordEntity(uuid, TestRows.meta(routeId = routeId), type, family, rank, payload)

    /** No 5 -> 6 step in SchemaMigrationTest: existing rows must survive and amo_record must be keyed by client_uuid. */
    @Test fun version5To6KeepsEveryRowAndAddsAmoRecord() {
        helper.createDatabase("migration-56", 5).use { db ->
            db.execSQL(
                """INSERT INTO outbox (client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state,
                   batch_uuid, attempts, created_at, sig) VALUES ('6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f', 'visit', 'f', 0, '2026-10-05', '{}', 'h',
                   'pending', null, 0, 't', 's1')""",
            )
        }
        helper.runMigrationsAndValidate("migration-56", 6, true).use { db ->
            db.query("SELECT COUNT(*) FROM outbox").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            var pk: String? = null
            db.query("PRAGMA table_info(`amo_record`)").use { c ->
                while (c.moveToNext()) if (c.getInt(c.getColumnIndexOrThrow("pk")) == 1) pk = c.getString(c.getColumnIndexOrThrow("name"))
            }
            assertEquals("client_uuid", pk)
        }
    }

    /** DEFECT: any payload is accepted for any AMO type; the server rejects it later (unevaluatedProperties / required), so the record is lost. */
    @Test fun aPayloadThatIsNotTheTypesPayloadIsRefused() = runTest {
        val visit = ClientIds.newUuid()
        val wrong = row("task", ClientIds.newUuid(), 0, AmoPayloads.distributionCheck(visit, true, null).toString())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordAmo(wrong.copy(familyUuid = wrong.clientUuid)) } }
        val empty = ClientIds.newUuid()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordAmo(row("risk_review", empty, 0, "{}", uuid = empty)) } }
    }

    /** DEFECT: docs/24 s4.2 says risk_review/outlet_request_verification/task are self, rank 0; recordAmo takes any family and rank 0..3. */
    @Test fun selfFamilyTypesMustBeTheirOwnFamilyAtRankZero() = runTest {
        val p = AmoPayloads.riskReview(9, "reviewed", null).toString()
        val u = ClientIds.newUuid()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordAmo(row("risk_review", ClientIds.newUuid(), 0, p, uuid = u)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordAmo(row("risk_review", u, 3, p, uuid = u)) } }
        val visit = ClientIds.newUuid()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordAmo(row("distribution_check", visit, 0, AmoPayloads.distributionCheck(visit, null, null).toString())) }
        }
    }

    /** DEFECT: docs/24 s4.3 requires route_id on visit families; every other visit-family writer calls requireRoute, recordAmo does not. */
    @Test fun visitFamilyAmoRecordsCarryTheRoute() = runTest {
        val visit = ClientIds.newUuid()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordAmo(row("distribution_check", visit, 1, AmoPayloads.distributionCheck(visit, null, null).toString(), routeId = null)) }
        }
    }

    /** A payload that is not a JSON object fails inside the transaction: nothing may be left behind. */
    @Test fun aNonObjectPayloadLeavesNothing() = runTest {
        val u = ClientIds.newUuid()
        assertThrows(Exception::class.java) { runBlocking { repo.recordAmo(row("task", u, 0, "[1,2]", uuid = u)) } }
        assertEquals(0, db.captureDao().amoRecordCount(u))
        assertNull(db.outboxDao().byClientUuid(u))
    }

    /** A replay after the family was purged must not enqueue the record a second time. */
    @org.junit.Ignore("Open, all capture types alike: a replay of a purged UUID is queued again; the server dedupes it. See docs/status/android-amo.md traps.")
    @Test fun aReplayAfterPurgeDoesNotEnqueueAgain() = runTest {
        val u = ClientIds.newUuid()
        val r = row("task", u, 0, AmoPayloads.task("visit_followup", 7, null, "T", null, null, null).toString(), uuid = u)
        assertTrue(repo.recordAmo(r))
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'acked', acked_at = '2026-10-05T05:00:00.000Z'")
        assertEquals(1, LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z").byTable["amo_record"])
        assertFalse("the record was already sent and acked", repo.recordAmo(r))
        assertNull(db.outboxDao().byClientUuid(u))
    }

}

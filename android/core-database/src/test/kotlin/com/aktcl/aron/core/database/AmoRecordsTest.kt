package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.record.AmoPayloads
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The AMO's own records: payloads equal the contract's members, one row and one outbox record per client UUID. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoRecordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    @After fun tearDown() = db.close()

    private val u = ClientIds.newUuid()
    private val keys = { o: JsonObject -> o.keys }

    private fun check(schema: String, full: JsonObject, minimal: JsonObject) {
        val props = ContractYaml.propertyNames(schema)
        assertEquals("$schema: every member the contract has", props, keys(full))
        assertTrue("$schema: required members in the minimal object: ${ContractYaml.requiredNames(schema) - keys(minimal)}", keys(minimal).containsAll(ContractYaml.requiredNames(schema)))
        assertTrue(props.containsAll(keys(minimal)))
    }

    @Test fun payloadsMatchTheContract() {
        check("DistributionCheckPayload", AmoPayloads.distributionCheck(u, true, "n"), AmoPayloads.distributionCheck(u, null, null))
        check("DistributionCheckLinePayload", AmoPayloads.distributionLine(u, 3, true, true), AmoPayloads.distributionLine(u, 3, false, false))
        check("CallAssessmentPayload", AmoPayloads.callAssessment("joint_call", u, 1, 1, 5, 12, 25, true, u), AmoPayloads.callAssessment("joint_call", null, 1, 1, null, 0, 0))
        check("CallAssessmentAnswerPayload", AmoPayloads.callAssessmentAnswer(u, 2, 4, "t", true), AmoPayloads.callAssessmentAnswer(u, 2, null, null, null))
        check("PriceComplianceCheckPayload", AmoPayloads.priceCompliance(u, 1, "retail", 100, 90, false, "n"), AmoPayloads.priceCompliance(u, 1, "retail", 100, 100, true, null))
        check("RiskReviewPayload", AmoPayloads.riskReview(9, "confirmed", "n"), AmoPayloads.riskReview(9, "dismissed", null))
        val fix = Json.parseToJsonElement("""{"fix_status":"ok"}""").jsonObject
        check("OutletRequestVerificationPayload", AmoPayloads.outletRequestVerification(u, "verified", 4, "Urban", fix, u, "n"), AmoPayloads.outletRequestVerification(u, "discarded", null, null, fix, null, null))
        check("TaskPayload", AmoPayloads.task("visit_followup", 7, 50001, "Title", "d", "2026-10-06", u), AmoPayloads.task("visit_followup", 7, null, "Title", null, null, null))
    }

    @Test fun builtInRulesRefuseWhatTheServerWouldReject() {
        assertThrows(IllegalArgumentException::class.java) { AmoPayloads.distributionLine(u, 3, present = false, oos = true) }
        assertThrows(IllegalArgumentException::class.java) { AmoPayloads.callAssessmentAnswer(u, 1, 6, null, null) }
        assertThrows(IllegalArgumentException::class.java) { AmoPayloads.riskReview(1, "deleted", null) }
        assertThrows(IllegalArgumentException::class.java) { AmoPayloads.priceCompliance(u, 1, "retail", -1, 0, true, null) }
        assertThrows(IllegalArgumentException::class.java) { AmoPayloads.task("Bad Type", 1, null, "t", null, null, null) }
    }

    private fun row(type: String, family: String, rank: Int, payload: JsonObject, uuid: String = ClientIds.newUuid()) =
        AmoRecordEntity(uuid, TestRows.meta(routeId = null), type, family, rank, payload.toString())

    @Test fun oneRowAndOneOutboxRecordPerClientUuidWhateverTheReplay() = runTest {
        val visit = ClientIds.newUuid()
        val r = row("distribution_check", visit, 1, AmoPayloads.distributionCheck(visit, true, null))
        assertTrue(repo.recordAmo(r))
        assertFalse(repo.recordAmo(r)) // a retried tap or replay changes nothing
        assertFalse(repo.recordAmo(r.copy(payloadJson = AmoPayloads.distributionCheck(visit, false, null).toString())))
        assertEquals(1, db.captureDao().amoRecordsOn("distribution_check", "2026-10-05").size)
        val ob = db.outboxDao().byClientUuid(r.clientUuid)!!
        assertEquals("distribution_check", ob.recordType); assertEquals(visit, ob.familyUuid); assertEquals(1, ob.rank)
        val env = Json.parseToJsonElement(ob.payloadJson).jsonObject
        assertEquals("distribution_check", env["type"]!!.jsonPrimitive.content)
        assertEquals("true", env["payload"]!!.jsonObject["posm_present"]!!.jsonPrimitive.content) // the first write won
    }

    @Test fun otherRecordTypesAndBadIdsAreRefused() = runTest {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.recordAmo(row("memo", u, 1, JsonObject(emptyMap()))) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.recordAmo(row("task", "not-a-uuid", 0, JsonObject(emptyMap()))) } }
        assertEquals(0, db.captureDao().amoRecordsOn("task", "2026-10-05").size)
    }

    @Test fun familyReadReturnsParentsFirst() = runTest {
        val visit = ClientIds.newUuid()
        val check = ClientIds.newUuid()
        repo.recordAmo(row("distribution_check_line", visit, 2, AmoPayloads.distributionLine(check, 1, true, false)))
        repo.recordAmo(row("distribution_check", visit, 1, AmoPayloads.distributionCheck(visit, null, null), uuid = check))
        assertEquals(listOf(1, 2), db.captureDao().amoRecordsOfFamily(visit).map { it.rank })
    }
}

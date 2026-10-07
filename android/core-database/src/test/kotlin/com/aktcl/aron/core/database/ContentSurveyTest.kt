package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.ContentViewEntity
import com.aktcl.aron.core.database.entity.SurveyResponseEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.LocalPurge
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Room v5 (docs/requests/android-sr-a-av-kv-survey-data.md): AV/KV content, surveys and their two records (F-SR-020/021). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ContentSurveyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
    private val ref = ReferenceRepository(db)

    @After fun tearDown() = db.close()

    private suspend fun outboxOf(uuid: String) = db.outboxDao().byClientUuid(uuid)!!
    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject["payload"]!!.jsonObject

    private fun bundle(content: String, surveys: String, version: String = "2026-10-05:3"): JsonObject {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val meta = JsonObject(base["meta"]!!.jsonObject + ("bundle_version" to JsonPrimitive(version)))
        return JsonObject(base + ("meta" to meta) + ("content" to Json.parseToJsonElement(content)) + ("surveys" to Json.parseToJsonElement(surveys)))
    }

    private suspend fun apply(raw: JsonObject) = ref.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)

    private fun item(id: Long, kind: String, seq: Int, outlets: String = "[]", from: String = "2026-10-01", to: String = "2026-10-31", sha: String = "a".repeat(64)) =
        """{"content_id":$id,"version":2,"kind":"$kind","title_en":"Item $id","title_bn":"আইটেম","asset_url":"https://cdn.example/c/$id.bin?sig=x",
           "sha256":"$sha","bytes":1048576,"duration_s":30,"valid_from":"$from","valid_to":"$to","sequence":$seq,"outlet_ids":$outlets}"""

    private val survey = """[{"survey_id":7,"version":3,"title_en":"POSM","title_bn":"পসম","questions":[
        {"question_id":71,"answer_type":"bool","label_en":"POSM present?","label_bn":"পসম আছে?","requires_photo":false,"key":"posm_present"},
        {"question_id":72,"answer_type":"photo_only","label_en":"Photo","label_bn":"ছবি","show_if_key":"posm_present","show_if_bool":true},
        {"question_id":73,"answer_type":"option","label_en":"Where","option_codes":["counter","wall"]}]}]"""

    @Test fun theBundleFillsContentAndSurveysAndEverySnapshotReplacesThem() = runTest {
        apply(bundle("[${item(1, "kv", 2)},${item(2, "av", 1, "[50001]")},${item(3, "av", 2, "[50002]")},${item(4, "kv", 1, to = "2026-10-04")}," +
            """{"content_id":5,"kind":"av"},""" +
            item(6, "kv", 1).replace("\"outlet_ids\":[]", "\"outlet_ids\":[\"x\"]") + "," + item(8, "kv", 1).replace(",\"outlet_ids\":[]", "") + "]", survey))
        // Outlet 50001: the assigned AV (sequence 1), then the all-outlet KV; the expired item and the other outlet's AV are not shown.
        assertEquals(listOf(2L, 1L), db.referenceDao().contentForOutlet(50001, "2026-10-05").map { it.contentId })
        assertEquals(listOf(3L, 1L), db.referenceDao().contentForOutlet(50002, "2026-10-05").map { it.contentId })
        assertEquals("a malformed item is left out", 3, db.referenceDao().contentFrom("2026-10-05").size)
        val q = db.referenceDao().surveyQuestions(7)
        assertEquals(listOf(71L, 72L, 73L), q.map { it.questionId })
        assertTrue("photo_only requires a photo", q[1].requiresPhoto)
        assertEquals("posm_present", q[1].showIfKey)
        assertEquals("""["counter","wall"]""", q[2].optionCodesJson)
        assertEquals(3, db.referenceDao().surveys().single().version)

        apply(bundle("[${item(1, "kv", 1)}]", "[]", "2026-10-05:4"))
        assertEquals(listOf(1L), db.referenceDao().contentForOutlet(50001, "2026-10-05").map { it.contentId })
        assertTrue(db.referenceDao().surveys().isEmpty())
        assertTrue(db.referenceDao().surveyQuestions(7).isEmpty())
    }

    @Test fun aContentViewJoinsItsVisitFamilyOncePerItem() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val view = ContentViewEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 2, 2, "av", "viewed", 1, "2026-10-05T04:32:00.000Z", 30_000)
        assertTrue(repo.recordContentView(view))
        val row = outboxOf(view.clientUuid)
        assertEquals("content_view", row.recordType)
        assertEquals(visit.clientUuid, row.familyUuid)
        assertEquals(1, row.rank)
        assertEquals(
            setOf("visit_client_uuid", "content_id", "content_version", "kind", "outcome", "sequence_no", "started_at", "duration_ms"),
            payload(row.payloadJson).keys,
        )
        // The same item again in this visit (a replay) is not recorded twice; a missing asset is logged as skipped.
        assertFalse(repo.recordContentView(view.copy(clientUuid = ClientIds.newUuid())))
        assertTrue(repo.recordContentView(ContentViewEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 1, 2, "kv", "skipped_missing", 2)))
        assertEquals(setOf("visit_client_uuid", "content_id", "content_version", "kind", "outcome", "sequence_no"),
            payload(db.outboxDao().nextPending(100).last().payloadJson).keys)
        assertEquals(2, db.captureDao().contentViewsOf(visit.clientUuid).size)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordContentView(view.copy(clientUuid = ClientIds.newUuid(), contentId = 9, outcome = "liked")) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.recordContentView(view.copy(clientUuid = ClientIds.newUuid(), contentId = 9, visitClientUuid = ClientIds.newUuid())) }
        }
    }

    @Test fun surveyAnswersCarryExactlyTheirTypeOncePerQuestion() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        fun answer(q: Long, type: String) = SurveyResponseEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 7, 3, q, type)
        val yes = answer(71, "bool").copy(answerBool = true)
        repo.recordSurveyResponse(yes)
        val photo = answer(72, "photo_only").copy(photoUuid = ClientIds.newUuid())
        repo.recordSurveyResponse(photo)
        val row = outboxOf(photo.clientUuid)
        assertEquals("survey_response", row.recordType)
        assertEquals(visit.clientUuid, row.familyUuid)
        assertEquals(setOf("visit_client_uuid", "survey_id", "survey_version", "question_id", "answer_type", "photo_uuid"), payload(row.payloadJson).keys)
        assertEquals("true", payload(outboxOf(yes.clientUuid).payloadJson)["answer_bool"].toString())
        // A second answer to the same question in the same visit is refused.
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.recordSurveyResponse(answer(71, "bool").copy(answerBool = false)) } }
        // The member must match the type.
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordSurveyResponse(answer(73, "option").copy(answerBool = true)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordSurveyResponse(answer(73, "option").copy(answerOptionCode = "Wall!")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordSurveyResponse(answer(72, "photo_only")) } }
        repo.recordSurveyResponse(answer(73, "option").copy(answerOptionCode = "wall"))
        assertEquals(3, db.captureDao().surveyResponsesOf(visit.clientUuid).size)
    }

    @Test fun contentAndSurveysAreForSrCallsOnly() = runTest {
        val (v, fix) = TestRows.visit()
        val visit = v.copy(visitKind = "amo_control_call")
        repo.recordVisitOpen(visit, fix)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordContentView(ContentViewEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 1, 1, "kv", "viewed", 1)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordSurveyResponse(SurveyResponseEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 7, 3, 71, "bool", answerBool = true)) }
        }
        assertEquals(0, db.captureDao().contentViewsOf(visit.clientUuid).size)
    }

    @Test fun thePurgeTakesTheRecordsWithTheirVisitFamily() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        repo.recordContentView(ContentViewEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 1, 1, "kv", "viewed", 1))
        repo.recordSurveyResponse(SurveyResponseEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 7, 3, 71, "bool", answerBool = true))
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'acked', acked_at = '2026-10-05T05:00:00.000Z'")
        val r = LocalPurge(db).purge("2026-10-20", "2026-10-20T05:00:00.000Z")
        assertEquals(1, r.byTable["content_view"])
        assertEquals(1, r.byTable["survey_response"])
        assertEquals(0, db.outboxDao().countInState("acked"))
    }
}

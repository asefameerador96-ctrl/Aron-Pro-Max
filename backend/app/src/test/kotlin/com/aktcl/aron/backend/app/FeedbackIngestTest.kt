package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * tso-manual B3, D-TSO-MAN-36 (FB-09, FB-11; R-F-06; s8 row 43 "FeedbackIngestTest"): the `feedback` sync record and
 * `POST /v1/feedback` run the same precheck (FeedbackChecks / FeedbackRules), through the production wiring on the seed
 * (tso1001 with a TSO phone, sr1001):
 * 1. Both categories of screenshot 43 (suggestion, feedback) are stored on both paths, with and without the one
 *    gallery image; the list carries business_date, captured_at, the author's username and zone (FB-09).
 * 2. An unknown category is refused on both paths (400 unknown_code / rejected schema_invalid, final).
 * 3. With cfg.feedback.max_images 0 an image is refused on both paths (400 not_allowed / rejected schema_invalid); a
 *    feedback without one is still stored.
 * 4. Not a TSO: 403 / quarantined scope_out_of_reach.
 * 5. A category retired after the capture never refuses a resend of the stored record (replay first), but refuses a
 *    new record.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FeedbackIngestTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var tso: TsoPhone
    private lateinit var sr: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        tso = world.phone("tso1001", "tso")
        sr = world.phone("sr1001", "sr")
        // A category of the test's own, retired in test 5.
        world.exec("INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs, valid_from) VALUES ('feedback_category', 'praise', 'Praise', NULL, 9, '{}', DATE '2026-01-01')")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun uuid() = UUID.randomUUID().toString()
    private fun payload(category: String, photo: String?) =
        """{"category_code":"$category","title":"Shelf space","description":"The retailer asks for a second rack.","photo_uuid":${photo?.let { "\"$it\"" } ?: "null"}}"""

    private suspend fun HttpClient.viaSync(p: TsoPhone, category: String, photo: String? = null, cu: String = uuid()): JsonObject =
        with(world) { ack(sync(p, listOf(record("feedback", payload(category, photo), cu = cu))), cu) }

    private suspend fun HttpClient.viaPost(p: TsoPhone, category: String, photo: String? = null, id: String = uuid()): HttpResponse =
        with(world) {
            postAs(p, "/v1/feedback", """{"feedback_uuid":"$id","category_code":"$category","title":"Shelf space","description":"The retailer asks for a second rack.","photo_uuid":${photo?.let { "\"$it\"" } ?: "null"}}""")
        }

    private suspend fun HttpResponse.reason(): String? = json(bodyAsText())["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("code")?.jsonPrimitive?.content
    private fun stored(cu: String) = world.one("SELECT count(*) FROM app.feedback WHERE client_uuid = '$cu'")

    @Test
    @Order(1)
    fun bothCategoriesAreStoredOnBothPathsAndTheListNamesTheAuthor() = world.withApi {
        val a = uuid(); val b = uuid()
        assertEquals("accepted", viaSync(tso, "feedback", cu = a).status())
        assertEquals("accepted", viaSync(tso, "suggestion", photo = uuid(), cu = b).status())
        assertEquals(HttpStatusCode.Created, viaPost(tso, "suggestion").status)
        assertEquals(HttpStatusCode.Created, viaPost(tso, "feedback", photo = uuid()).status)
        val items = with(world) { json(getAs(tso, "/v1/feedback").bodyAsText()) }["items"]!!.jsonArray.map { it.jsonObject }
        val row = items.single { it["feedback_uuid"]!!.jsonPrimitive.content == a }
        assertEquals("2027-01-03", row["business_date"]!!.jsonPrimitive.content)
        assertEquals("tso1001", row["username"]!!.jsonPrimitive.content)
        assertEquals(world.one("SELECT home_zone_id FROM app.app_user WHERE username = 'tso1001'").toString(), row["zone_id"]!!.jsonPrimitive.content)
        assertEquals(world.scalar("SELECT to_char(captured_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM app.feedback WHERE client_uuid = '$a'"), row["captured_at"]!!.jsonPrimitive.content.take(19))
    }

    @Test
    @Order(2)
    fun anUnknownCategoryIsRefusedOnBothPaths() = world.withApi {
        val cu = uuid()
        val a = viaSync(tso, "complaint", cu = cu)
        assertEquals("rejected" to "schema_invalid", a.status() to a.code()); assertEquals("false", a["retryable"]!!.jsonPrimitive.content)
        assertEquals(0L, stored(cu))
        val id = uuid()
        val r = viaPost(tso, "complaint", id = id)
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("unknown_code", r.reason()); assertEquals(0L, stored(id))
    }

    @Test
    @Order(3)
    fun noImageWhileTheConfigAllowsNone() = world.withApi {
        world.cfg("cfg.feedback.max_images", "0")
        val cu = uuid()
        val a = viaSync(tso, "feedback", photo = uuid(), cu = cu)
        assertEquals("rejected" to "schema_invalid", a.status() to a.code(), a.toString())
        assertEquals(0L, stored(cu))
        val r = viaPost(tso, "feedback", photo = uuid())
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("not_allowed", r.reason())
        assertEquals("accepted", viaSync(tso, "feedback").status(), "without an image it is stored")
        assertEquals(HttpStatusCode.Created, viaPost(tso, "suggestion").status)
        world.cfg("cfg.feedback.max_images", "1")
    }

    @Test
    @Order(4)
    fun onlyATsoSendsFeedback() = world.withApi {
        val cu = uuid()
        val a = viaSync(sr, "feedback", cu = cu)
        assertEquals("quarantined" to "scope_out_of_reach", a.status() to a.code())
        assertEquals(0L, stored(cu))
        assertEquals(HttpStatusCode.Forbidden, viaPost(sr, "feedback").status)
    }

    @Test
    @Order(5)
    fun aRetiredCategoryNeverRefusesAResendButRefusesANewRecord() = world.withApi {
        val cu = uuid()
        assertEquals("accepted", viaSync(tso, "praise", cu = cu).status())
        world.exec("UPDATE app.code_list_item SET valid_to = DATE '2027-01-02' WHERE list_key = 'feedback_category' AND code = 'praise'")
        assertEquals("duplicate", viaSync(tso, "praise", cu = cu).status(), "replay first")
        val again = viaSync(tso, "praise")
        assertEquals("rejected" to "schema_invalid", again.status() to again.code())
        assertEquals(HttpStatusCode.BadRequest, viaPost(tso, "praise").status)
    }
}

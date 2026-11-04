package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.FreshDb
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/requests/android-tso-record-mapping.md: the AMO and TSO bundles carry the active rubrics (contract RubricDef). */
class BundleRubricsTest {
    private lateinit var fresh: FreshDb

    /** The Visit Query rubric V0139 seeds into every migrated database (lead ruling R-F-10). */
    private var seeded = 0L

    @BeforeEach fun setUp() {
        fresh = FreshDb.create()
        seeded = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT id FROM app.rubric WHERE kind = 'retailer_questionnaire'").mapTo(Long::class.java).one()
        }
    }
    @AfterEach fun tearDown() = fresh.close()

    /**
     * The admin's own rubrics. The seeded Visit Query is set inactive first (an admin replaced it), so it is one more
     * inactive rubric the bundle must leave out.
     */
    private fun adminRubrics() {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.rubric SET status = 'inactive' WHERE id = ?", seeded)
            fun rubric(kind: String, status: String, versions: List<String>) {
                val id = h.createQuery("INSERT INTO app.rubric (kind, version, status) VALUES (?, ?, ?) RETURNING id").bind(0, kind).bind(1, versions.size).bind(2, status).mapTo(Long::class.java).one()
                versions.forEachIndexed { i, c -> h.execute("INSERT INTO app.rubric_version (rubric_id, version, criteria) VALUES (?, ?, CAST(? AS jsonb))", id, i + 1, c) }
            }
            rubric("retailer_questionnaire", "active", listOf(
                """[{"criterion_id":1,"key":"old","label_en":"Old","label_bn":null,"answer_type":"bool"}]""",
                """[{"criterion_id":2,"key":"clean","label_en":"Shop clean?","label_bn":"দোকান পরিষ্কার?","answer_type":"bool"},
                    {"criterion_id":3,"key":"note","label_en":"Note","answer_type":"text"},
                    {"criterion_id":6,"key":"stars","label_en":"Stars","answer_type":"stars_1_5"},
                    {"criterion_id":7,"key":"odd","label_en":"Odd","answer_type":"photo"},
                    {"key":"broken","answer_type":"bool"}]""",
            ))
            rubric("joint_call", "active", listOf("""[{"criterion_id":4,"key":"greet","label_en":"Greeting","label_bn":null,"answer_type":"score_1_5"}]"""))
            rubric("joint_call", "inactive", listOf("""[{"criterion_id":5,"key":"x","label_en":"X","label_bn":null,"answer_type":"bool"}]"""))
        }
    }

    private fun rubrics(role: String) = fresh.db.jdbi.withHandle<List<kotlinx.serialization.json.JsonObject>, Exception> { BundleContent.rubrics(it, role) }

    @Test
    fun aFreshDatabaseSendsTheSeededVisitQueryToTheSupervisors() {
        for (role in listOf("TSO", "AMO")) {
            val rs = rubrics(role)
            assertEquals(listOf(seeded), rs.map { it["rubric_id"]!!.jsonPrimitive.long }, "V0139's rubric is the only one")
            val q = rs[0]
            assertEquals("retailer_questionnaire", q["kind"]!!.jsonPrimitive.content)
            assertEquals(1, q["version"]!!.jsonPrimitive.int)
            val cs = q["criteria"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(1L, 2L), cs.map { it["criterion_id"]!!.jsonPrimitive.long })
            assertEquals(listOf("sr_visits_regularly", "sr_prints_memo"), cs.map { it["key"]!!.jsonPrimitive.content })
            assertEquals(listOf("text", "text"), cs.map { it["answer_type"]!!.jsonPrimitive.content })
            assertEquals("Does the SR visit your shop regularly?", cs[0]["label_en"]!!.jsonPrimitive.content)
            assertEquals("SR আপনার দোকান নিয়মিত ভিজিট করে?", cs[0]["label_bn"]!!.jsonPrimitive.content)
            assertTrue(cs.all { it["enabled"]!!.jsonPrimitive.boolean })
        }
        assertEquals(emptyList(), rubrics("SR"))
    }

    @Test
    fun supervisorsGetTheActiveRubricsAtTheirCurrentVersionInTheRubricDefShape() {
        adminRubrics()
        for (role in listOf("TSO", "AMO")) {
            val rs = rubrics(role)
            assertEquals(listOf("retailer_questionnaire", "joint_call"), rs.map { it["kind"]!!.jsonPrimitive.content }, "inactive rubrics are left out")
            assertTrue(rs.none { it["rubric_id"]!!.jsonPrimitive.long == seeded }, "the inactive seeded rubric too")
            val q = rs[0]
            assertEquals(2, q["version"]!!.jsonPrimitive.int)
            val cs = q["criteria"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(2L, 3L, 6L), cs.map { it["criterion_id"]!!.jsonPrimitive.long }, "the current version only; a criterion without id or label is skipped")
            assertEquals("দোকান পরিষ্কার?", cs[0]["label_bn"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, cs[1]["label_bn"])
            assertTrue(cs.all { it["enabled"]!!.jsonPrimitive.boolean })
            assertEquals("score_1_5", cs[2]["answer_type"]!!.jsonPrimitive.content, "the older spelling is sent as the bundle enum")
            val allowed = setOf("criterion_id", "label_bn", "label_en", "answer_type", "enabled", "key")
            assertTrue(cs.all { allowed.containsAll(it.keys) }, "RubricDef criteria are closed")
            assertEquals(setOf("rubric_id", "version", "kind", "criteria"), q.keys)
        }
        assertEquals(emptyList(), rubrics("SR"))
    }
}

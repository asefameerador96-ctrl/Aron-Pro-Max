package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tso-manual B3, MC-T-01, XA-01..03, SP-03 (D-TSO-MAN-35; s8 rows 33 to 38 "TsoMyCallEndToEndTest (task reaches the SR
 * bundle with route_id)"): the TSO's My Call end to end through `POST /v1/sync/batch` and the bundle, on the seed
 * (tso1001 with a TSO phone; sr1001, the primary SR of the Mirpur routes, with an SR phone; amo1001 with an AMO phone):
 * 1. The TSO bundle carries the code lists the TSO screens read (leave types, both feedback categories, the task types,
 *    the 31 ticket issue types with their attrs, the resolution code) and the seeded Visit Query rubric; a code limited
 *    to the AMO by `attrs.roles` reaches the AMO only.
 * 2. Set Plan (plan + two outlets of MIR-SR-D), Visit Query for one outlet (retailer_questionnaire, both answers,
 *    delegate yes) and Assign Task to the route's SR (envelope route) are all accepted in one batch, family order.
 * 3. My Visit Plan lists the route, the visited outlet completed and the other pending.
 * 4. The SR is nudged `task_assigned`; the SR's bundle carries the task with its route_id, outlet, type and creator.
 * 5. The SR resolves it from the phone; the TSO's task list shows it completed; the SR's next bundle no longer carries it.
 * 6. The TSO Visit Log and the Task Planner reports show the plan, both answers by key, the delegated task and its
 *    status, and the planner's new columns (label, assigned by, route, comment).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TsoMyCallEndToEndTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var tso: TsoPhone
    private lateinit var sr: TsoPhone
    private lateinit var amo: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        tso = world.phone("tso1001", "tso")
        sr = world.phone("sr1001", "sr")
        amo = world.phone("amo1001", "amo")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun uuid() = UUID.randomUUID().toString()
    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonObject }
    private fun codes(bundle: JsonObject, list: String): List<String> =
        bundle.list("code_lists").singleOrNull { it["list_key"]!!.jsonPrimitive.content == list }?.list("items")?.map { it["code"]!!.jsonPrimitive.content }.orEmpty()

    @Test
    fun aTaskTheTsoAssignsInMyCallReachesTheSrWithItsRouteAndComesBackResolved() = world.withApi {
        with(world) {
            // 1. The TSO's code lists and rubric; attrs.roles filters (V0027 rule).
            val tb = getAs(tso, "/v1/sync/bundle").let { r -> assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText()); json(r.bodyAsText()) }
            assertEquals(listOf("casual", "sick", "earn"), codes(tb, "leave_type"))
            assertEquals(listOf("suggestion", "feedback"), codes(tb, "feedback_category"))
            assertEquals(listOf("oos", "general", "irregular_visit"), codes(tb, "task_type"))
            assertEquals(31, codes(tb, "ticket_issue_type").size, "the 31 Annex A issue types")
            val issue = tb.list("code_lists").single { it["list_key"]!!.jsonPrimitive.content == "ticket_issue_type" }.list("items").first()
            assertEquals("p1", issue["attrs"]!!.jsonObject["priority"]!!.jsonPrimitive.content)
            assertEquals(listOf("resolved"), codes(tb, "ticket_resolution_code"))
            assertTrue("manual_override" !in codes(tb, "force_reason"), "an AMO-only code never reaches the TSO")
            val rubric = tb.list("rubrics").single { it["kind"]!!.jsonPrimitive.content == "retailer_questionnaire" }
            val rubricId = rubric["rubric_id"]!!.jsonPrimitive.long
            assertTrue("manual_override" in codes(json(getAs(amo, "/v1/sync/bundle").bodyAsText()), "force_reason"), "the AMO gets its own code")
            assertTrue("manual_override" !in codes(json(getAs(sr, "/v1/sync/bundle").bodyAsText()), "force_reason"))

            // 2. Set Plan, Visit Query and Assign Task, one batch (the phone's outbox order).
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val (o1, o2) = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 2")
            val srFcm = registerPush(sr)
            pushes.clear()
            val plan = uuid(); val po1 = uuid(); val po2 = uuid(); val q = uuid(); val task = uuid()
            val records = listOf(
                record("visit_plan", """{"plan_date":"2027-01-03","note":null}""", route, cu = plan),
                record("visit_plan_outlet", """{"plan_client_uuid":"$plan","outlet_id":$o1}""", route, cu = po1, family = plan, rank = 1),
                record("visit_plan_outlet", """{"plan_client_uuid":"$plan","outlet_id":$o2}""", route, cu = po2, family = plan, rank = 1),
                record("call_assessment", """{"kind":"retailer_questionnaire","visit_client_uuid":null,"visit_plan_outlet_client_uuid":"$po1","rubric_id":$rubricId,"rubric_version":1,
                    "total_score":0,"max_score":0,"delegate_task":true}""", route, cu = q, secondsAgo = 30),
                record("call_assessment_answer", """{"assessment_client_uuid":"$q","criterion_id":1,"answer_text":"হ্যাঁ, প্রতিদিন আসে"}""", route, family = q, rank = 1, secondsAgo = 30),
                record("call_assessment_answer", """{"assessment_client_uuid":"$q","criterion_id":2,"answer_text":"No, only sometimes"}""", route, family = q, rank = 1, secondsAgo = 30),
                record("task", """{"task_type_code":"oos","assignee_user_id":${sr.userId},"outlet_id":$o1,"title":"OOS","description":"Refill the rack","due_date":"2027-01-03","source_visit_client_uuid":null}""",
                    route, cu = task, secondsAgo = 20),
            )
            val answer = sync(tso, records)
            assertEquals(List(records.size) { "accepted" }, answer["acks"]!!.jsonArray.map { it.jsonObject.status() }, answer.toString())

            // 3. My Visit Plan.
            val plans = json(getAs(tso, "/v1/visit-plans?from=2027-01-03&to=2027-01-03").bodyAsText()).list("items")
            val mine = plans.single { it["plan_uuid"]!!.jsonPrimitive.content == plan }
            assertEquals(route, mine["route_id"]!!.jsonPrimitive.long)
            val byOutlet = mine.list("outlets").associateBy { it["outlet_id"]!!.jsonPrimitive.long }
            assertEquals("completed", byOutlet.getValue(o1)["status"]!!.jsonPrimitive.content); assertNotNull(byOutlet.getValue(o1)["completed_at"])
            assertEquals("pending", byOutlet.getValue(o2)["status"]!!.jsonPrimitive.content)

            // 4. The SR is nudged and its bundle carries the task with the route.
            val until = System.nanoTime() + 30_000_000_000L
            while (pushes.none { it.first == srFcm && it.second["reason"] == "task_assigned" } && System.nanoTime() < until) Thread.sleep(20)
            assertTrue(pushes.any { it.first == srFcm && it.second["reason"] == "task_assigned" }, "the SR's phone is nudged: $pushes")
            val sb = json(getAs(sr, "/v1/sync/bundle").bodyAsText())
            val t = sb.list("tasks").single { it["task_uuid"]!!.jsonPrimitive.content == task }
            assertEquals(route, t["route_id"]!!.jsonPrimitive.long, "XA-01: TaskDto.route_id")
            assertEquals(o1, t["outlet_id"]!!.jsonPrimitive.long)
            assertEquals("oos", t["task_type_code"]!!.jsonPrimitive.content)
            assertEquals(tso.userId, t["assigned_by_user_id"]!!.jsonPrimitive.long)
            assertEquals("Refill the rack", t["description"]!!.jsonPrimitive.content)
            assertTrue("oos" in codes(sb, "task_type"), "the SR card's label comes from the task_type list")

            // 5. The SR resolves it; the TSO sees it completed; the next SR bundle no longer carries it.
            val done = sync(sr, listOf(record("task_event", """{"task_uuid":"$task","event":"resolved","note":"Rack refilled"}""", route, secondsAgo = 10)))
            assertEquals("accepted", done["acks"]!!.jsonArray.single().jsonObject.status(), done.toString())
            val tasks = json(getAs(tso, "/v1/tasks").bodyAsText()).list("items")
            assertEquals("completed", tasks.single { it["task_uuid"]!!.jsonPrimitive.content == task }["status"]!!.jsonPrimitive.content)
            assertTrue(json(getAs(sr, "/v1/sync/bundle").bodyAsText()).list("tasks").none { it["task_uuid"]!!.jsonPrimitive.content == task })

            // 6. The reports (the worker keeps the dw dimensions the task planner joins; here once by hand).
            fresh.db.jdbi.useHandle<Exception> { h -> com.aktcl.aron.backend.analytics.Aggregator.refreshDimensions(h) }
            val body = """{"period":{"date":"2027-01-03"},"output":{"format":"json","page_size":500}}"""
            val log = postWeb("tso1001", "/v1/reports/tso-visit-log/query", body)
            assertEquals(HttpStatusCode.OK, log.status, log.bodyAsText())
            val rows = json(log.bodyAsText()).list("rows")
            val code1 = scalar("SELECT code FROM app.outlet WHERE id = $o1"); val code2 = scalar("SELECT code FROM app.outlet WHERE id = $o2")
            val r1 = rows.single { it["outlet_code"]!!.jsonPrimitive.content == code1 }
            assertEquals(
                listOf("2027-01-03", "tso1001", "Mirpur", "Mirpur 10 Daily", "completed", "হ্যাঁ, প্রতিদিন আসে", "No, only sometimes", "true", "OOS", "completed"),
                listOf("plan_date", "tso", "zone", "route", "status", "sr_visits_regularly", "sr_prints_memo", "delegate_task", "task", "task_status").map { r1[it]!!.jsonPrimitive.content },
            )
            val r2 = rows.single { it["outlet_code"]!!.jsonPrimitive.content == code2 }
            assertEquals("pending", r2["status"]!!.jsonPrimitive.content)
            assertEquals("null", r2["task"].toString())
            val planner = postWeb("tso1001", "/v1/reports/task-planner/query", body)
            assertEquals(HttpStatusCode.OK, planner.status, planner.bodyAsText())
            val pr = json(planner.bodyAsText()).list("rows").single { it["outlet_code"]!!.jsonPrimitive.content == code1 }
            assertEquals(listOf("OOS", "tso1001", "sr1001", "Mirpur 10 Daily", "Refill the rack", "completed"), listOf("label", "assigned_by", "assignee", "route", "comment", "status").map { pr[it]!!.jsonPrimitive.content })
        }
    }
}

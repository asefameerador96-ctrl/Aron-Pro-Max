package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun ckUuid() = UUID.randomUUID().toString()
private fun ckCursor(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckerBackendAdminTest {
    private lateinit var env: BackendAdminEnv
    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()
    private val device = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"

    private fun leave(id: String, from: String, days: Int) = """{"leave_uuid":"$id","leave_type_code":"casual","from_date":"$from","days":$days,"reason":"Family"}"""

    @Test
    fun overlappingLeaveOfTheSameTsoIsRefused() = env.app {
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), leave(ckUuid(), "2027-08-01", 5)).status)
        val r = sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), leave(ckUuid(), "2027-08-03", 5))
        assertTrue(r.status.value in 400..409, "overlapping leave accepted: ${r.status}")
    }

    @Test
    fun unknownAndOutOfReachLeaveDecisionLookTheSame() = env.app {
        val id = ckUuid()
        sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), leave(id, "2027-09-01", 1))
        val out = sendA3(HttpMethod.Post, "/v1/leave/$id/decision", env.tok("dmo3001"), """{"decision":"approve"}""")
        val unknown = sendA3(HttpMethod.Post, "/v1/leave/${ckUuid()}/decision", env.tok("dmo3001"), """{"decision":"approve"}""")
        assertEquals(out.status, unknown.status, "existence oracle for a caller outside reach")
    }

    @Test
    fun tamperedDateCursorIsNever500() = env.app {
        for (path in listOf("/v1/leave", "/v1/visit-plans")) for (raw in listOf("+999999999-01-01|1", "-999999999-01-01|1", "2027-01-01|99999999999999999999")) {
            val r = sendA3(HttpMethod.Get, "$path?cursor=${ckCursor(raw)}", env.tok("admin1001"))
            assertTrue(r.status.value < 500, "$path $raw -> ${r.status}")
        }
    }

    @Test
    fun visitPlanSameUuidOtherRouteStaysOnOneRoute() = env.app {
        val a = env.outletIds("MIR-SR-D", 1)
        val other = env.scalar("SELECT r.code FROM app.route r JOIN app.zone z ON z.id=r.zone_id JOIN app.app_user u ON u.home_zone_id=z.id WHERE u.username='tso1001' AND r.code <> 'MIR-SR-D' AND EXISTS (SELECT 1 FROM app.outlet o WHERE o.route_id=r.id) LIMIT 1")
        val plan = ckUuid()
        fun body(route: String, o: List<Long>) = """{"plan_uuid":"$plan","plan_date":"2027-05-05","route_id":${env.routeIds.getValue(route)},"outlet_ids":[${o.joinToString(",")}]}"""
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body("MIR-SR-D", a)).status)
        println("CK other route in zone: $other")
        if (other != null) {
            val o2 = env.outletIds(other, 1)
            val r = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body(other, o2))
            assertTrue(r.status.value >= 400, "same plan_uuid grew a second route: ${r.status} ${r.bodyAsText()}")
        }
    }

    @Test
    fun visitPlanBackdatedDate() = env.app {
        val o = env.outletIds("MIR-SR-D", 1)
        val r = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), """{"plan_uuid":"${ckUuid()}","plan_date":"2020-01-01","route_id":${env.routeIds.getValue("MIR-SR-D")},"outlet_ids":[${o[0]}]}""")
        assertTrue(r.status.value >= 400, "past-dated plan accepted: ${r.status}")
    }

    @Test
    fun concurrentSameUuidCreatesNever500() = env.app {
        val lid = ckUuid(); val fid = ckUuid(); val sid = ckUuid(); val pid = ckUuid()
        val o = env.outletIds("MIR-SR-D", 2)
        val codes = coroutineScope {
            (1..8).flatMap {
                listOf(
                    async { sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), leave(lid, "2027-10-01", 1)).status.value },
                    async { sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), """{"feedback_uuid":"$fid","category_code":"idea","title":"t","description":"d"}""").status.value },
                    async { sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), """{"upload_uuid":"$sid","bytes":10,"sha256":"${"a".repeat(64)}","app_version":"1"}""", mapOf("X-Device-Id" to device)).status.value },
                    async { sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), """{"plan_uuid":"$pid","plan_date":"2027-10-02","route_id":${env.routeIds.getValue("MIR-SR-D")},"outlet_ids":[${o.joinToString(",")}]}""").status.value },
                )
            }.awaitAll()
        }
        assertTrue(codes.none { it >= 500 }, "codes=$codes")
        assertEquals(1, env.count("SELECT count(*) FROM app.leave_application WHERE client_uuid='$lid'"))
        assertEquals(1, env.count("SELECT count(*) FROM app.feedback WHERE client_uuid='$fid'"))
        assertEquals(1, env.count("SELECT count(*) FROM app.support_upload WHERE upload_uuid='$sid'"))
        assertEquals(o.size, env.count("SELECT count(*) FROM app.visit_plan_outlet WHERE plan_client_uuid='$pid'"))
    }

    @Test
    fun oddInputIsNever500() = env.app {
        val t = env.tok("tso1001")
        val calls = listOf(
            Triple(HttpMethod.Post, "/v1/leave", """{"leave_uuid":"${ckUuid()}","leave_type_code":"casual","from_date":"2100-12-31","days":365,"reason":"x"}"""),
            Triple(HttpMethod.Post, "/v1/leave", """{"leave_uuid":"${ckUuid()}","leave_type_code":"casual","from_date":"2027-01-01","days":99999999999,"reason":"x"}"""),
            Triple(HttpMethod.Post, "/v1/leave", "[]"), Triple(HttpMethod.Post, "/v1/leave", "null"), Triple(HttpMethod.Post, "/v1/leave", "{"),
            Triple(HttpMethod.Post, "/v1/leave", """{"leave_uuid":"${ckUuid()}","leave_type_code":"casual","from_date":"2027-01-01","days":1,"reason":"\u0000bad"}"""),
            Triple(HttpMethod.Post, "/v1/feedback", """{"feedback_uuid":"${ckUuid()}","category_code":"idea","title":"a\u0000b","description":"d"}"""),
            Triple(HttpMethod.Post, "/v1/feedback", """{"feedback_uuid":"${ckUuid()}","category_code":"idea","title":"t","description":"d","photo_uuid":"zzz"}"""),
            Triple(HttpMethod.Post, "/v1/visit-plans", """{"plan_uuid":"${ckUuid()}","plan_date":"2027-01-01","route_id":9223372036854775807,"outlet_ids":[9223372036854775807]}"""),
            Triple(HttpMethod.Get, "/v1/routes/99999999999999999999/assignments", ""), Triple(HttpMethod.Get, "/v1/routes/0/assignments", ""),
            Triple(HttpMethod.Get, "/v1/routes/1/assignments?valid_on=0001-01-01", ""), Triple(HttpMethod.Get, "/v1/leave?limit=0", ""), Triple(HttpMethod.Get, "/v1/feedback?cursor=!!", ""),
            Triple(HttpMethod.Get, "/v1/visit-plans?planner_user_id=99999999999", ""),
        )
        val bad = mutableListOf<String>()
        for ((m, p, b) in calls) {
            val r = sendA3(m, p, t, b.ifEmpty { null })
            if (r.status.value >= 500) bad += "$m $p $b -> ${r.status} ${r.bodyAsText().take(120)}"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun scopeLeakAcrossTerritoriesOnGets() = env.app {
        val o = env.outletIds("MIR-SR-D", 1)
        val plan = ckUuid(); val fid = ckUuid()
        sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), """{"plan_uuid":"$plan","plan_date":"2027-12-01","route_id":${env.routeIds.getValue("MIR-SR-D")},"outlet_ids":[${o[0]}]}""")
        sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), """{"feedback_uuid":"$fid","category_code":"idea","title":"t","description":"d"}""")
        for (u in listOf("tso2001", "tso3001", "dmo3001")) {
            assertTrue(sendA3(HttpMethod.Get, "/v1/visit-plans?planner_user_id=${env.ids.getValue("tso1001")}", env.tok(u)).status.value in 400..403, u)
            assertTrue(sendA3(HttpMethod.Get, "/v1/visit-plans", env.tok(u)).objA3().itemsA3().none { it.strA3("plan_uuid") == plan }, u)
            assertTrue(sendA3(HttpMethod.Get, "/v1/feedback", env.tok(u)).objA3().itemsA3().none { it.strA3("feedback_uuid") == fid }, u)
            assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/routes/${env.routeIds.getValue("MIR-SR-D")}/assignments", env.tok(u)).status, u)
        }
        val far = env.outletIds("FAR-SR-1", 1)
        val r = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), """{"plan_uuid":"${ckUuid()}","plan_date":"2027-12-02","route_id":${env.routeIds.getValue("FAR-SR-1")},"outlet_ids":[${far[0]}]}""")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        val sid = ckUuid()
        val body = """{"upload_uuid":"$sid","bytes":10,"sha256":"${"a".repeat(64)}","app_version":"1"}"""
        sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), body, mapOf("X-Device-Id" to device))
        assertNotEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("amo1001", device), body, mapOf("X-Device-Id" to device)).status)
    }
}

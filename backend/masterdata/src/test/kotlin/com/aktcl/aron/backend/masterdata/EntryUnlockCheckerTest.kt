package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.toJavaLocalDate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** T1 checker (docs/29) for F-API-049 (EntryUnlocks.kt). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntryUnlockCheckerTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    // wall-clock-ok: the app under test runs on AronClock.SYSTEM, so its Dhaka "today" is the real one
    private val today = com.aktcl.aron.rules.BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate()

    /**
     * Contract EntryUnlockWrite: "`to` is at most cfg.web.entry_unlock_max_days after `from`" (and the V0039 column comment
     * says the same). EntryUnlocks.kt:129 counts inclusive dates, so `to = from + 7` (7 days after) is refused.
     */
    @Test
    fun toExactlyMaxDaysAfterFromIsAccepted() = env.app {
        val zone = env.scalar("SELECT id FROM app.zone WHERE code = 'Z-MIR'")!!.toLong()
        val from = today.minusDays(8); val to = today.minusDays(1)
        val r = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks", env.tok("admin1001"), """{"scope_type":"zone","scope_id":$zone,"from":"$from","to":"$to","reason":"Seven days after from, per contract"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    }

    @Test
    fun hostileInputNever500() = env.app {
        val zone = env.scalar("SELECT id FROM app.zone WHERE code = 'Z-MIR'")!!.toLong(); val admin = env.tok("admin1001")
        val d = today.minusDays(1)
        val bodies = listOf(
            """{"scope_type":"zone","scope_id":99999999999999999999,"from":"$d","to":"$d","reason":"Hostile scope id test"}""",
            """{"scope_type":"zone","scope_id":$zone,"from":"$d","to":"$d","ttl_h":99999999999,"reason":"Hostile ttl test case"}""",
            """{"scope_type":"zone","scope_id":$zone,"from":"$d","to":"$d","reason":"Hostile \u0000 reason"}""",
            """{"scope_type":"zone","scope_id":$zone,"from":"1999-12-31","to":"$d","reason":"Hostile old date case"}""",
            """{"scope_type":"zone","scope_id":$zone,"from":"$d","to":"$d","reason":"${"x".repeat(495)}          "}""",
        )
        for (b in bodies) {
            val r = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks", admin, b)
            assertNotEquals(HttpStatusCode.InternalServerError, r.status, "$b -> ${r.bodyAsText()}")
        }
        for (q in listOf("active=maybe", "cursor=%00", "cursor=zzzz", "zone_id=99999999999999999999", "limit=-1")) {
            val r = sendA3(HttpMethod.Get, "/v1/admin/entry-unlocks?$q", admin)
            assertNotEquals(HttpStatusCode.InternalServerError, r.status, "$q -> ${r.bodyAsText()}")
        }
        for (id in listOf("99999999999999999999", "0", "abc")) {
            val r = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks/$id/expire", admin, "{}")
            assertNotEquals(HttpStatusCode.InternalServerError, r.status, "$id -> ${r.bodyAsText()}")
        }
    }
}

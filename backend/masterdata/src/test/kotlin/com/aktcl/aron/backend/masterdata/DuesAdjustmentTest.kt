package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** F-ADM-036 (T1, money): maker-checker dues adjustments, idempotent create, one ledger row on approval, write-off bounded by the balance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuesAdjustmentTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private fun body(u: UUID, outlet: Long, kind: String = "correction", amount: Long = 5_000, reason: String = "Retailer paid in cash at the depot", memo: String? = null) =
        """{"client_uuid":"$u","outlet_id":$outlet,"kind":"$kind","amount_mtk":$amount,"reason":"$reason"${if (memo != null) ""","memo_client_uuid":"$memo"""" else ""}}"""

    private fun balance(outlet: Long) = env.scalar("SELECT COALESCE(SUM(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outlet")!!.toLong()

    @Test
    fun makerProposesCheckerApprovesAndTheLedgerMovesOnce() = env.app {
        val outlet = env.outletIds("MIR-SR-D", 1).single()
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        val u = UUID.randomUUID()
        val before = balance(outlet)
        val c = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlet, amount = 7_000))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText())
        val id = c.objA3().strA3("adjustment_id"); assertEquals("pending", c.objA3().strA3("status"))
        // Replay: same content answers the stored row (200), nothing doubles; other content under the same uuid is a conflict.
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlet, amount = 7_000)).status)
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlet, amount = 8_000)).status)
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", sup, body(u, outlet, amount = 7_000)).status, "another maker cannot adopt the uuid")
        assertEquals(1, env.count("SELECT count(*) FROM app.dues_adjustment WHERE client_uuid = '$u'"))
        assertEquals(before, balance(outlet), "pending moves nothing")
        // Only SUPERADMIN decides.
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", admin, """{"decision":"approve"}""").status)
        val d = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", sup, """{"decision":"approve","note":"checked with the depot"}""")
        assertEquals(HttpStatusCode.OK, d.status, d.bodyAsText())
        assertEquals("approved", d.objA3().strA3("status")); assertEquals(env.ids.getValue("superadmin1001").toString(), d.objA3().getValue("decided_by_user_id").jsonPrimitive.content)
        assertEquals(before + 7_000, balance(outlet))
        assertEquals(1, env.count("SELECT count(*) FROM app.due_ledger WHERE source_client_uuid = '$u' AND entry_kind = 'adjustment' AND user_id = ${env.ids.getValue("superadmin1001")} AND business_date = app.dhaka_date(now())"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'dues_adjustment' AND entity_id = '$id' AND action = 'approve' AND reason = 'checked with the depot'"))
        // Decided once.
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", sup, """{"decision":"approve"}""").status, "same decision by the same checker is a replay")
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", sup, """{"decision":"reject"}""").status)
        assertEquals(before + 7_000, balance(outlet))
        // A replay of the create after the decision still answers the stored (approved) row, no new pending row.
        assertEquals("approved", sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlet, amount = 7_000)).objA3().strA3("status"))
    }

    @Test
    fun theMakerCannotDecideAndRejectionMovesNothing() = env.app {
        val outlet = env.outletIds("MIR-SR-D", 1).single()
        val sup = env.tok("superadmin1001"); val admin = env.tok("admin1001")
        val own = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", sup, body(UUID.randomUUID(), outlet)).objA3().strA3("adjustment_id")
        val r = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$own/decision", sup, """{"decision":"approve"}""")
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("ERR_SEPARATION_OF_DUTIES", r.bodyAsText().substringAfter("\"code\":\"").substringBefore('"'))
        assertEquals(0, env.count("SELECT count(*) FROM app.due_ledger WHERE source_client_uuid = (SELECT client_uuid FROM app.dues_adjustment WHERE id = $own)"))
        val u = UUID.randomUUID(); val before = balance(outlet)
        val other = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlet)).objA3().strA3("adjustment_id")
        val rej = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$other/decision", sup, """{"decision":"reject","note":"not supported by the receipt"}""")
        assertEquals("rejected", rej.objA3().strA3("status")); assertEquals(before, balance(outlet))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$other/decision", sup, """{"decision":"approve"}""").status)
        assertEquals(HttpStatusCode.NotFound, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/987654/decision", sup, """{"decision":"approve"}""").status)
    }

    @Test
    fun writeOffsAreNegativeAndBoundedByTheBalance() = env.app {
        val outlet = env.outletIds("MIR-SR-D", 1).single()
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        suspend fun post(b: String, t: String = admin) = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", t, b)
        assertEquals(HttpStatusCode.BadRequest, post(body(UUID.randomUUID(), outlet, "write_off", 1_000)).status, "a write-off lowers the balance")
        assertEquals(HttpStatusCode.BadRequest, post(body(UUID.randomUUID(), outlet, "correction", 0)).status)
        // Open balance X (a +X correction), then a write-off of X + 1 is refused at approval, X is allowed.
        val up = post(body(UUID.randomUUID(), outlet, amount = 20_000)).objA3().strA3("adjustment_id")
        sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$up/decision", sup, """{"decision":"approve"}""")
        val x = balance(outlet)
        val tooBig = post(body(UUID.randomUUID(), outlet, "write_off", -(x + 1))).objA3().strA3("adjustment_id")
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$tooBig/decision", sup, """{"decision":"approve"}""").status)
        assertEquals(x, balance(outlet))
        val exact = post(body(UUID.randomUUID(), outlet, "write_off", -x)).objA3().strA3("adjustment_id")
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$exact/decision", sup, """{"decision":"approve"}""").status)
        assertEquals(0L, balance(outlet))
    }

    @Test
    fun validationRolesScopeAndListing() = env.app {
        val outlet = env.outletIds("MIR-SR-D", 1).single()
        val admin = env.tok("admin1001")
        suspend fun post(b: String, t: String = admin) = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", t, b)
        assertEquals(HttpStatusCode.BadRequest, post(body(UUID.randomUUID(), outlet, reason = "short")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body(UUID.randomUUID(), outlet, kind = "gift")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body(UUID.randomUUID(), outlet, memo = UUID.randomUUID().toString())).status, "memo of another outlet or unknown")
        assertEquals(HttpStatusCode.BadRequest, post("""{"client_uuid":"nope","outlet_id":$outlet,"kind":"correction","amount_mtk":1,"reason":"long enough reason"}""").status)
        assertEquals(HttpStatusCode.Forbidden, post(body(UUID.randomUUID(), 987654)).status, "unknown outlet answers like an out-of-reach one")
        for (u in listOf("tso1001", "dmo1001", "sr1001", "support1001")) {
            assertEquals(HttpStatusCode.Forbidden, post(body(UUID.randomUUID(), outlet), env.tok(u)).status, u)
            assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/admin/dues-adjustments", env.tok(u)).status, u)
        }
        val id = post(body(UUID.randomUUID(), outlet)).objA3().strA3("adjustment_id")
        val l = sendA3(HttpMethod.Get, "/v1/admin/dues-adjustments?outlet_id=$outlet&status=pending&limit=1", admin)
        assertEquals(HttpStatusCode.OK, l.status, l.bodyAsText())
        assertEquals(1, l.objA3().itemsA3().size)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/admin/dues-adjustments?status=zzz", admin).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/admin/dues-adjustments?cursor=!!", admin).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", env.tok("superadmin1001"), """{"decision":"maybe"}""").status)
    }

    @Test
    fun theAmountCapIsInclusiveAndAnswers400BeforeAnyInsert() = env.app {
        val outlet = env.outletIds("MIR-SR-D", 1).single()
        val admin = env.tok("admin1001")
        val cap = 10_000_000_000_000L
        suspend fun post(a: Long, kind: String = "correction") = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(UUID.randomUUID(), outlet, kind, a))
        assertEquals(HttpStatusCode.Created, post(cap).status)
        assertEquals(HttpStatusCode.Created, post(-cap).status)
        assertEquals(HttpStatusCode.Created, post(-cap, "write_off").status)
        for (a in listOf(cap + 1, -cap - 1, Long.MAX_VALUE, Long.MIN_VALUE)) assertEquals(HttpStatusCode.BadRequest, post(a).status, "$a")
        assertEquals(HttpStatusCode.BadRequest, post(-cap - 1, "write_off").status)
    }
}

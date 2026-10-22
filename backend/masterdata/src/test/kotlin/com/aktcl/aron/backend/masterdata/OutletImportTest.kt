package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `POST /v1/admin/outlets/import` (docs/34 s2): channel mandatory, preview and commit, idempotent by batch_uuid, audited with the reason. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutletImportTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)
    private val reason = "Imported from the Mirpur survey sheet"
    private val clusterName get() = env.sql("SELECT c.name FROM app.cluster c JOIN app.zone z ON z.id = c.zone_id WHERE z.code = 'Z-MIR' ORDER BY c.id LIMIT 1")!!
    private val routeCode get() = env.sql("SELECT r.code FROM app.route r JOIN app.zone z ON z.id = r.zone_id WHERE z.code = 'Z-MIR' AND r.status = 'active' ORDER BY r.id LIMIT 1")!!

    private fun row(name: String, extra: String = "", channel: String? = "\"GT\"", zone: String = "Z-MIR", cluster: String = clusterName) =
        """{"name":"$name","owner_name":"Owner of $name","zone_code":"$zone","cluster":"$cluster","outlet_kind":"retail"${channel?.let { ""","channel":$it""" } ?: ""}$extra}"""

    private fun batch(rows: List<String>, dry: Boolean, id: UUID = UUID.randomUUID(), reasonText: String? = reason) =
        """{"batch_uuid":"$id","rows":[${rows.joinToString(",")}],"dry_run":$dry${if (reasonText != null && !dry) ""","change_reason":"$reasonText"""" else ""}}"""

    private fun count(sql: String) = env.count(sql)

    @Test
    fun channelIsRequiredOnEveryRowEvenOnAPreview() = env.app {
        for (dry in listOf(true, false)) {
            val missing = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Ok Row"), row("No Channel", channel = null)), dry))
            assertEquals(HttpStatusCode.BadRequest, missing.status)
            val t = missing.bodyAsText()
            assertTrue(t.contains("body.rows/1/channel") && t.contains("required") && t.contains("GT, DCC, Astha, RCC, MT, HoReCa"), t)
            for (bad in listOf("null", "\"\"", "\"gt\"", "\"Retail\"", "5")) {
                val r = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Bad Channel", channel = bad)), dry))
                assertEquals(HttpStatusCode.BadRequest, r.status, bad)
                assertTrue(r.bodyAsText().contains("body.rows/0/channel"), r.bodyAsText())
            }
        }
        assertEquals(0, count("SELECT count(*) FROM app.outlet WHERE name IN ('Ok Row','No Channel','Bad Channel')"))
    }

    @Test
    fun previewWritesNothingAndReportsEveryRowProblem() = env.app {
        val before = count("SELECT count(*) FROM app.outlet")
        val rows = listOf(
            row("Preview One"), row("Preview Two", channel = "\"Astha\""),
            row("Zone Unknown", zone = "Z-NOPE"), row("Cluster Unknown", cluster = "No Such Cluster"),
            row("Route Unknown", ""","route_code":"NO-ROUTE""""), row("Short Phone", ""","contact_number":"123""""), row("Lat Only", ""","lat":23.8"""),
        )
        val res = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(rows, true))
        assertEquals(HttpStatusCode.OK, res.status)
        val o = res.objA1()
        assertEquals(2, o.lng("creates")); assertEquals(0, o.lng("updates")); assertEquals(7, o.lng("rows"))
        assertEquals(1, o.getValue("by_channel").let { (it as kotlinx.serialization.json.JsonObject).lng("Astha") })
        val errs = o.arr("errors").map { it as kotlinx.serialization.json.JsonObject }.map { "${it.lng("row")}:${it.strA1("column")}:${it.strA1("code")}" }
        assertEquals(listOf("3:zone_code:unknown_zone", "4:cluster:unknown_cluster", "5:route_code:unknown_route", "6:contact_number:invalid", "7:lng:required"), errs)
        assertEquals(before, count("SELECT count(*) FROM app.outlet"))
        assertEquals(0, count("SELECT count(*) FROM app.audit_log WHERE entity = 'outlet_import'"))
    }

    @Test
    fun commitRefusesTheWholeBatchOnAnyRowError() = env.app {
        val before = count("SELECT count(*) FROM app.outlet")
        val res = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Fine Row"), row("Bad Zone", zone = "Z-NOPE")), false))
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("body.rows/1/zone_code") && res.bodyAsText().contains("unknown_zone"), res.bodyAsText())
        assertEquals(before, count("SELECT count(*) FROM app.outlet"))
    }

    @Test
    fun commitNeedsAReasonAndRoleAndCreatesWithHistoryAndAudit() = env.app {
        val noReason = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Reasonless")), false, reasonText = null))
        assertEquals(HttpStatusCode.BadRequest, noReason.status); assertTrue(noReason.bodyAsText().contains("change_reason"))
        val short = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Reasonless")), false, reasonText = "short"))
        assertEquals(HttpStatusCode.BadRequest, short.status)
        for ((user, role) in listOf("tso1001" to Role.TSO, "dmo1001" to Role.DMO, "support1001" to Role.SUPPORT)) {
            assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/outlets/import", env.token(user, role), batch(listOf(row("Nope")), true)).status, user)
        }
        val id = UUID.randomUUID()
        val res = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(
            row("Import Created", ""","lat":23.81,"lng":90.41,"contact_number":"01712345678","route_code":"$routeCode","address":"Road 1"""", channel = "\"MT\""),
            row("Import Coded", ""","code":"IMP/1""""),
        ), false, id))
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("false", res.objA1().getValue("replayed").toString())
        val oid = count("SELECT id FROM app.outlet WHERE name = 'Import Created'")
        assertEquals("AD-$oid", env.sql("SELECT code FROM app.outlet WHERE id = $oid"))
        assertEquals("MT|master|retail", env.sql("SELECT concat_ws('|', channel, location_basis, outlet_kind) FROM app.outlet WHERE id = $oid"))
        assertEquals("IMP/1", env.sql("SELECT code FROM app.outlet WHERE name = 'Import Coded'"))
        assertEquals(1, count("SELECT count(*) FROM app.outlet_placement_history WHERE outlet_id = $oid AND valid_to IS NULL"))
        assertEquals("master", env.sql("SELECT basis FROM app.outlet_location_history WHERE outlet_id = $oid"))
        assertEquals(1, env.audit("outlet_import", id, "import"))
        assertEquals(reason, env.sql("SELECT reason FROM app.audit_log WHERE entity = 'outlet_import' AND entity_id = '$id'"))
        assertEquals(1, env.audit("outlet", oid, "import_create"))
        assertEquals("[redacted]", env.sql("SELECT after->>'contact_number' FROM app.audit_log WHERE entity = 'outlet' AND entity_id = '$oid'"))
    }

    @Test
    fun replayIsIdempotentAndOtherContentUnderTheSameUuidIs409() = env.app {
        val id = UUID.randomUUID()
        val rows = listOf(row("Replay A"), row("Replay B", channel = "\"RCC\""))
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(rows, false, id)).status)
        val n = count("SELECT count(*) FROM app.outlet"); val audits = count("SELECT count(*) FROM app.audit_log")
        val again = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(rows.reversed(), false, id))
        assertEquals(HttpStatusCode.OK, again.status); assertEquals("true", again.objA1().getValue("replayed").toString())
        assertEquals(n, count("SELECT count(*) FROM app.outlet")); assertEquals(audits, count("SELECT count(*) FROM app.audit_log"))
        val other = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Replay A"), row("Replay C")), false, id))
        assertEquals(HttpStatusCode.Conflict, other.status); assertEquals("ERR_SYNC_BATCH_UUID_REUSED", other.code())
        assertEquals(n, count("SELECT count(*) FROM app.outlet"))
    }

    @Test
    fun aRowWithTheCodeOfAnOutletOfTheSameZoneUpdatesItAndNeverTouchesItsStatus() = env.app {
        val first = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Update Me", ""","code":"UPD/1","lat":23.8,"lng":90.4""")), false))
        assertEquals(HttpStatusCode.OK, first.status)
        env.sql("UPDATE app.outlet SET status = 'closed', closed_at = now() WHERE code = 'UPD/1' RETURNING 1")
        val prev = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Update Me 2", ""","code":"UPD/1"""", channel = "\"DCC\"")), true)).objA1()
        assertEquals(0, prev.lng("creates")); assertEquals(1, prev.lng("updates"))
        val n = count("SELECT count(*) FROM app.outlet")
        val id = UUID.randomUUID()
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Update Me 2", ""","code":"UPD/1"""", channel = "\"DCC\"")), false, id)).status)
        assertEquals(n, count("SELECT count(*) FROM app.outlet"))
        assertEquals("Update Me 2|DCC|closed|23.8", env.sql("SELECT concat_ws('|', name, channel, status, lat) FROM app.outlet WHERE code = 'UPD/1'"), "a blank pin keeps the pin, the status stays")
        val oid = count("SELECT id FROM app.outlet WHERE code = 'UPD/1'")
        assertEquals(1, env.audit("outlet", oid, "import_update"))
    }

    @Test
    fun codeOfAnotherZoneAndDuplicateCodesInTheSheetAreRowErrors() = env.app {
        val other = "Z-OTHER"
        env.sql("INSERT INTO app.cluster (zone_id, name) SELECT id, 'Other Cluster' FROM app.zone WHERE code = 'Z-OTHER' ON CONFLICT DO NOTHING RETURNING 1")
        val otherCluster = env.sql("SELECT c.name FROM app.cluster c JOIN app.zone z ON z.id = c.zone_id WHERE z.code = '$other' ORDER BY c.id LIMIT 1")!!
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Zone Owner", ""","code":"ZONE/1"""", zone = other, cluster = otherCluster)), false)).status)
        val res = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(
            row("Taken", ""","code":"ZONE/1""""), row("Dup One", ""","code":"DUP/1""""), row("Dup Two", ""","code":"DUP/1""""), row("Bad Code", ""","code":"bad code!""""),
        ), true))
        val errs = res.objA1().arr("errors").map { it as kotlinx.serialization.json.JsonObject }.map { "${it.lng("row")}:${it.strA1("column")}:${it.strA1("code")}" }
        assertEquals(listOf("1:code:code_taken", "3:code:duplicate", "4:code:invalid"), errs)
    }

    @Test
    fun malformedBodiesAreRefusedNotCrashed() = env.app {
        suspend fun post(b: String) = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, b).status
        val u = UUID.randomUUID()
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"$u","rows":[],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, post("""{"rows":[${row("X Row")}],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"$u","rows":[${row("X Row")}]}"""))
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"not-a-uuid","rows":[${row("X Row")}],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"$u","rows":[7],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"$u","rows":[${row("X Row", ""","status":"closed"""")}],"dry_run":true}"""))
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"$u","rows":[${row("X Row")}],"dry_run":"yes"}"""))
        val nul = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Nul\\u0000Name")), true))
        assertEquals(HttpStatusCode.BadRequest, nul.status)
    }
}

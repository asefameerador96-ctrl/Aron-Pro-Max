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

    private fun warnings(o: kotlinx.serialization.json.JsonObject) = o.arr("warnings").map { it as kotlinx.serialization.json.JsonObject }.map { "${it.lng("row")}:${it.strA1("column")}:${it.strA1("code")}" }
    private fun errors(o: kotlinx.serialization.json.JsonObject) = o.arr("errors").map { it as kotlinx.serialization.json.JsonObject }.map { "${it.lng("row")}:${it.strA1("column")}:${it.strA1("code")}" }

    /** D-MD-05 (M05): the field's channel labels load, an empty channel comes from the sub-channel, a channel we cannot name is a ROW error (never a 400 for the batch). */
    @Test
    fun channelLabelsAreAcceptedAndABadChannelIsARowErrorNotABatchRefusal() = env.app {
        val rows = listOf(
            row("Label Gt", channel = "\"GT Channel\""), row("Label Astha", channel = "\"Astha Channel\"", extra = ""","sub_channel":"Gold""""),
            row("Label Horeca", channel = "\"HoReCa\""), row("Label Dcc", channel = "\" dcc channel \""),
            row("Derived From Tier", channel = null, extra = ""","sub_channel":"Diamond""""), row("Derived From Gt", channel = "\"\"", extra = ""","sub_channel":"GT""""),
            row("Nothing To Derive From", channel = null), row("Bad Channel", channel = "\"Retail\""), row("Number Channel", channel = "5"),
        )
        val res = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(rows, true))
        assertEquals(HttpStatusCode.OK, res.status)
        val o = res.objA1()
        assertEquals(7, o.lng("creates"))
        assertEquals(listOf("8:channel:unknown_channel", "9:channel:invalid"), errors(o))
        assertEquals(listOf("7:channel:channel_defaulted"), warnings(o))
        val by = o.getValue("by_channel") as kotlinx.serialization.json.JsonObject
        assertEquals(listOf(3L, 1L, 2L, 0L, 0L, 1L), listOf("GT", "DCC", "Astha", "RCC", "MT", "HoReCa").map { by.lng(it) })
        val wbc = o.getValue("warnings_by_code") as kotlinx.serialization.json.JsonObject
        assertEquals(2L, wbc.lng("channel_derived")); assertEquals(1L, wbc.lng("channel_defaulted"))
        // commit with skip_rows loads the good rows and reports the skipped ones; the default refuses the batch on a row error
        val refused = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(rows, false))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(0, count("SELECT count(*) FROM app.outlet WHERE name LIKE 'Label %' OR name IN ('Derived From Tier','Derived From Gt','Nothing To Derive From')"))
        val skip = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(rows, false).replace("\"dry_run\":false", "\"dry_run\":false,\"on_row_error\":\"skip_rows\""))
        assertEquals(HttpStatusCode.OK, skip.status)
        val c = skip.objA1()
        assertEquals(7L, c.lng("creates")); assertEquals(2L, c.lng("skipped"))
        assertEquals("GT", env.sql("SELECT channel FROM app.outlet WHERE name = 'Label Gt'"))
        assertEquals("Astha|Gold", env.sql("SELECT o.channel || '|' || s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.name = 'Label Astha'"))
        assertEquals("Astha|Diamond", env.sql("SELECT o.channel || '|' || s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.name = 'Derived From Tier'"))
        assertEquals("GT", env.sql("SELECT channel FROM app.outlet WHERE name = 'Nothing To Derive From'"))
        assertEquals(0, count("SELECT count(*) FROM app.outlet WHERE name IN ('Bad Channel','Number Channel')"))
        assertTrue(env.sql("SELECT after::text FROM app.audit_log WHERE entity = 'outlet' AND action = 'import_create' AND after->>'channel_defaulted' = 'true' LIMIT 1") != null, "the defaulted channel is traceable in the audit row")
    }

    /** D-MD-06..08, 01, 16, 20 (M06 M07 M08 M01 M15 M16 M20): the field's rows load as they are, the report names what was repaired, left out or not trusted. */
    @Test
    fun fieldRowsLoadAsTheyAreAndAReRunChangesNothing() = env.app {
        val ok = ""","lat":23.7806123456789,"lng":90.4,"contact_number":"1711000001","geo_class":"Semi Urban","created_at":"2024-03-05 10:15:00","updated_at":"2025-01-02","sub_channel":"Gold""""
        val rows = listOf(
            row("Fld Gold", ok, channel = "\"Astha Channel\""),
            row("Fld Mismatch", ""","sub_channel":"Gold","contact_number":"+880 1711-000002"""", channel = "\"GT Channel\""),
            row("Fld Astha Dash", ""","sub_channel":"--","contact_number":"123","lat":51.5,"lng":-0.12""", channel = "\"Astha Channel\""),
            row("Fld Silver", ""","sub_channel":"Silver"""", channel = "\"Astha\""),
            row("Fld Astha Gt", ""","sub_channel":"GT"""", channel = "\"Astha Channel\""),
        )
        val code = "FLD${System.nanoTime() % 100000}"
        val withCode = rows.mapIndexed { i, r -> r.replace("{\"name\"", "{\"code\":\"$code$i\",\"name\"") }
        suspend fun send(dry: Boolean, conf: Boolean, id: UUID = UUID.randomUUID()) =
            sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(withCode, dry, id).replaceFirst("\"dry_run\":$dry", "\"dry_run\":$dry,\"location_confirmed\":$conf"))
        val dry = send(true, true).objA1()
        assertEquals(5L, dry.lng("creates")); assertEquals(emptyList(), errors(dry))
        val w = warnings(dry)
        assertTrue("2:sub_channel:sub_channel_mismatch" in w && "3:contact_number:phone_unusable" in w && "3:lat:coordinates_outside_bangladesh" in w && "4:sub_channel:sub_channel_retired" in w && "5:sub_channel:sub_channel_mismatch" in w, w.toString())
        val wbc = dry.getValue("warnings_by_code") as kotlinx.serialization.json.JsonObject
        assertEquals(2L, wbc.lng("phone_repaired")); assertEquals(1L, wbc.lng("coordinates_rounded"))
        // location_confirmed=false: the pins load as master but unconfirmed
        val batchId = UUID.randomUUID()
        assertEquals(HttpStatusCode.OK, send(false, false, batchId).status)
        assertEquals("master|false|01711000001|SemiUrban|Gold", env.sql("SELECT location_basis || '|' || location_confirmed || '|' || contact_number || '|' || geo_class || '|' || s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.code = '${code}0'"))
        assertEquals("23.78061235", env.sql("SELECT lat::numeric(10,8)::text FROM app.outlet WHERE code = '${code}0'"))
        assertEquals("2024-03-05 04:15:00+00", env.sql("SELECT created_at::text FROM app.outlet WHERE code = '${code}0'"))
        assertEquals("01711000002|", env.sql("SELECT contact_number || '|' || coalesce(sub_channel_id::text, '') FROM app.outlet WHERE code = '${code}1'"))
        assertEquals("|true", env.sql("SELECT coalesce(contact_number, '') || '|' || (sub_channel_id IS NULL) FROM app.outlet WHERE code = '${code}2'"))
        assertEquals("true", env.sql("SELECT (sub_channel_id IS NULL AND channel = 'Astha')::text FROM app.outlet WHERE code = '${code}3'"))
        assertEquals("false", env.sql("SELECT location_confirmed::text FROM app.outlet WHERE code = '${code}2'"))
        // the same batch replays; a re-run with new uuid and the switch ON confirms only pins inside Bangladesh and changes nothing else (no second row)
        assertEquals(true, send(false, false, batchId).objA1().getValue("replayed").toString() == "true")
        assertEquals(HttpStatusCode.OK, send(false, true).status)
        assertEquals(5, count("SELECT count(*) FROM app.outlet WHERE code LIKE '$code%'"))
        assertEquals("true", env.sql("SELECT location_confirmed::text FROM app.outlet WHERE code = '${code}0'"))
        assertEquals("false", env.sql("SELECT location_confirmed::text FROM app.outlet WHERE code = '${code}2'"))
        assertEquals(1, count("SELECT count(*) FROM app.outlet WHERE code = '${code}0' AND version >= 2"))
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
        assertEquals(3, o.lng("creates")); assertEquals(0, o.lng("updates")); assertEquals(7, o.lng("rows"))
        assertEquals(1, o.getValue("by_channel").let { (it as kotlinx.serialization.json.JsonObject).lng("Astha") })
        val errs = o.arr("errors").map { it as kotlinx.serialization.json.JsonObject }.map { "${it.lng("row")}:${it.strA1("column")}:${it.strA1("code")}" }
        assertEquals(listOf("3:zone_code:unknown_zone", "4:cluster:unknown_cluster", "5:route_code:unknown_route", "7:lng:required"), errs)
        assertEquals(listOf("6:contact_number:phone_unusable"), warnings(o))
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
        assertEquals("gen.$oid", env.sql("SELECT code FROM app.outlet WHERE id = $oid"))
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

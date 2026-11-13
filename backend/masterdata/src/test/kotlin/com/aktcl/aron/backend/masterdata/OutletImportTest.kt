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
    fun fieldRowsLoadAsTheyAreAndAnIdenticalReRunChangesNothing() = env.app {
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
        assertEquals(1L, wbc.lng("phone_source_kept")); assertEquals(3L, wbc.lng("tier_source_kept"))
        // location_confirmed=false: the pins load as master but unconfirmed
        val batchId = UUID.randomUUID()
        assertEquals(HttpStatusCode.OK, send(false, false, batchId).status)
        assertEquals("master|false|01711000001|SemiUrban|Gold", env.sql("SELECT location_basis || '|' || location_confirmed || '|' || contact_number || '|' || geo_class || '|' || s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.code = '${code}0'"))
        assertEquals("23.78061235", env.sql("SELECT lat::numeric(10,8)::text FROM app.outlet WHERE code = '${code}0'"))
        // (1) the sheet's dates go to the source columns; our created_at/updated_at are the time of the write (delta readers see the outlet)
        assertEquals("2024-03-05 04:15:00+00|2025-01-01 18:00:00+00", env.sql("SELECT source_created_at::text || '|' || source_updated_at::text FROM app.outlet WHERE code = '${code}0'"))
        assertEquals("true", env.sql("SELECT (updated_at > now() - interval '1 hour' AND created_at > now() - interval '1 hour')::text FROM app.outlet WHERE code = '${code}0'"))
        // B1: the phone that cannot be repaired is kept in the source column, never lost; (4) a dropped tier keeps the field's text the same way
        assertEquals("123", env.sql("SELECT source_contact_number FROM app.outlet WHERE code = '${code}2'"))
        assertEquals("Gold|Silver|GT", env.sql("SELECT string_agg(coalesce(source_sub_channel, '-'), '|' ORDER BY code) FROM app.outlet WHERE code IN ('${code}1', '${code}3', '${code}4')"))
        assertEquals(null, env.sql("SELECT source_sub_channel FROM app.outlet WHERE code = '${code}0'"))
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
        // (2) the run with the switch ON confirmed one pin (a real change); the SAME rows once more change NOTHING: no version bump, no audit row, all unchanged
        val versions = env.sql("SELECT string_agg(version::text, ',' ORDER BY code) FROM app.outlet WHERE code LIKE '$code%'")
        val audits = count("SELECT count(*) FROM app.audit_log WHERE entity = 'outlet'")
        val again = send(false, true).objA1()
        assertEquals(listOf(0L, 0L, 5L), listOf(again.lng("creates"), again.lng("updates"), again.lng("unchanged")))
        assertEquals(versions, env.sql("SELECT string_agg(version::text, ',' ORDER BY code) FROM app.outlet WHERE code LIKE '$code%'"))
        assertEquals(audits, count("SELECT count(*) FROM app.audit_log WHERE entity = 'outlet'"))
        val dryAgain = send(true, true).objA1()
        assertEquals(5L, dryAgain.lng("unchanged"), "the dry run says the same")
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
        assertEquals(4, o.lng("creates")); assertEquals(0, o.lng("updates")); assertEquals(7, o.lng("rows"))
        assertEquals(1, o.getValue("by_channel").let { (it as kotlinx.serialization.json.JsonObject).lng("Astha") })
        val errs = o.arr("errors").map { it as kotlinx.serialization.json.JsonObject }.map { "${it.lng("row")}:${it.strA1("column")}:${it.strA1("code")}" }
        assertEquals(listOf("3:zone_code:unknown_zone", "4:cluster:unknown_cluster", "5:route_code:unknown_route"), errs)
        // (6) a half pin never costs the row: it loads without a pin, with a warning
        assertEquals(listOf("6:contact_number:phone_unusable", "7:lng:coordinates_incomplete"), warnings(o))
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
        // (3) a codeless row gets a stable code from its identifying fields
        assertEquals(ImportRules.generatedOutletCode("Z-MIR", routeCode, clusterName, "Import Created", "Owner of Import Created"), env.sql("SELECT code FROM app.outlet WHERE id = $oid"))
        assertEquals("MT|master|retail", env.sql("SELECT concat_ws('|', channel, location_basis, outlet_kind) FROM app.outlet WHERE id = $oid"))
        assertEquals("IMP/1", env.sql("SELECT code FROM app.outlet WHERE name = 'Import Coded'"))
        assertEquals(1, count("SELECT count(*) FROM app.outlet_placement_history WHERE outlet_id = $oid AND valid_to IS NULL"))
        assertEquals("master|migration", env.sql("SELECT basis || '|' || source FROM app.outlet_location_history WHERE outlet_id = $oid"))
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

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.commit(rows: List<String>, conf: Boolean = false, skip: Boolean = false) =
        sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(rows, false).replaceFirst("\"dry_run\":false", "\"dry_run\":false,\"location_confirmed\":$conf" + if (skip) ",\"on_row_error\":\"skip_rows\"" else "")).objA1()

    private fun tier(code: String) = env.sql("SELECT o.channel || '|' || coalesce(s.code, '-') FROM app.outlet o LEFT JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.code = '$code'")

    /**
     * B3 (push 1b): the GT default applies on CREATE only. On an update a blank channel or sub-channel keeps what is stored; a row that changes the channel
     * must carry a tier of the new channel (or none, which clears a stored tier of the old channel with a warning); a tier of another channel is a row error.
     */
    @Test
    fun aBlankChannelOnAnUpdateKeepsTheStoredChannelAndTier() = env.app {
        val c = "B3X${System.nanoTime() % 100000}"
        fun r(channel: String?, sub: String?) = row("Gold Shop", ""","code":"$c"""" + (sub?.let { ""","sub_channel":"$it"""" } ?: ""), channel = channel)
        assertEquals(1L, commit(listOf(r("\"Astha Channel\"", "Gold"))).lng("creates"))
        assertEquals("Astha|Gold", tier(c))
        // blank channel and blank tier: nothing changes (before the fix: GT with Gold)
        val keep = commit(listOf(r(null, null)))
        assertEquals(1L, keep.lng("unchanged")); assertEquals("Astha|Gold", tier(c))
        assertEquals(1L, commit(listOf(r("\"\"", "--"))).lng("unchanged")); assertEquals("Astha|Gold", tier(c))
        // blank channel, a tier of the stored channel: the tier changes, the channel stays
        assertEquals(1L, commit(listOf(r(null, "Diamond"))).lng("updates")); assertEquals("Astha|Diamond", tier(c))
        // blank channel, a tier of another channel: a mismatch warning, nothing changes except the kept source text
        val mis = commit(listOf(r(null, "GT")))
        assertTrue(warnings(mis).contains("1:sub_channel:sub_channel_mismatch"), warnings(mis).toString()); assertEquals("Astha|Diamond", tier(c))
        // a channel change with a tier of a third channel is a row error
        val dry = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(r("\"GT Channel\"", "Gold")), true)).objA1()
        assertEquals(listOf("1:sub_channel:sub_channel_inconsistent"), errors(dry))
        // a channel change with no tier clears the old channel's tier, with a warning
        val gt = commit(listOf(r("\"GT Channel\"", null)))
        assertTrue(warnings(gt).contains("1:sub_channel:tier_cleared"), warnings(gt).toString()); assertEquals("GT|-", tier(c))
        // and a channel change with a consistent tier writes both
        assertEquals(1L, commit(listOf(r("\"Astha\"", "Platinum"))).lng("updates")); assertEquals("Astha|Platinum", tier(c))
    }

    /** (3) push 1b: a codeless row imported twice is the same outlet (stable generated code); two identical codeless rows in one batch are a duplicate. */
    @Test
    fun aCodelessRowImportedTwiceIsNotDuplicated() = env.app {
        val first = commit(listOf(row("Codeless Twice Shop")))
        assertEquals(1L, first.lng("creates"))
        val again = commit(listOf(row("Codeless Twice Shop")))
        assertEquals(0L, again.lng("creates")); assertEquals(1L, again.lng("unchanged"))
        assertEquals(1, count("SELECT count(*) FROM app.outlet WHERE name = 'Codeless Twice Shop'"))
        assertTrue(env.sql("SELECT code FROM app.outlet WHERE name = 'Codeless Twice Shop'")!!.matches(Regex("^gen\\.[0-9a-f]{20}$")))
        val dup = sendA1(HttpMethod.Post, "/admin/outlets/import", admin, batch(listOf(row("Codeless Pair"), row("Codeless Pair")), true)).objA1()
        assertEquals(listOf("2:code:duplicate"), errors(dup))
    }

    /**
     * (5) push 1b: a pin the FIELD confirmed is never overwritten or un-confirmed by a master pin when the switch is off; the master pin is recorded as a
     * candidate (once). With the switch on (the owner says master coordinates are confirmed) the master pin replaces it, confirmed.
     */
    @Test
    fun aFieldConfirmedPinIsKeptAndTheMasterPinBecomesACandidate() = env.app {
        val c = "PIN${System.nanoTime() % 100000}"
        fun r(lat: Double, lng: Double) = row("Pin Shop", ""","code":"$c","lat":$lat,"lng":$lng""")
        commit(listOf(r(23.80, 90.40)))
        env.sql("UPDATE app.outlet SET lat = 23.801, lng = 90.401, location_confirmed = true WHERE code = '$c' RETURNING 1") // the field verified another spot
        val id = count("SELECT id FROM app.outlet WHERE code = '$c'")
        val v = env.sql("SELECT version FROM app.outlet WHERE id = $id")
        val res = commit(listOf(r(23.80, 90.40)))
        assertTrue(warnings(res).contains("1:lat:pin_kept_as_candidate"), warnings(res).toString())
        assertEquals("23.801|90.401|true", env.sql("SELECT lat || '|' || lng || '|' || location_confirmed FROM app.outlet WHERE id = $id"))
        assertEquals(v, env.sql("SELECT version FROM app.outlet WHERE id = $id"), "the outlet row itself is not touched")
        assertEquals(1, count("SELECT count(*) FROM app.outlet_location_history WHERE outlet_id = $id AND basis = 'candidate' AND lat = 23.8"))
        assertEquals(1L, commit(listOf(r(23.80, 90.40))).lng("unchanged"), "the same candidate is not recorded twice")
        assertEquals(1, count("SELECT count(*) FROM app.outlet_location_history WHERE outlet_id = $id AND basis = 'candidate'"))
        // the owner says the master coordinates are confirmed: the master pin replaces the field pin, confirmed
        commit(listOf(r(23.80, 90.40)), conf = true)
        assertEquals("23.8|90.4|true|master", env.sql("SELECT lat || '|' || lng || '|' || location_confirmed || '|' || location_basis FROM app.outlet WHERE id = $id"))
    }

    /** (6) push 1b: border and island pins of Bangladesh confirm with the switch on; a pin outside the box or out of range never costs the row. */
    @Test
    fun borderAndIslandPinsLoadAndABadPinNeverCostsTheRow() = env.app {
        val p = "BRD${System.nanoTime() % 100000}"
        val pins = listOf(
            "stmartin" to (20.6237 to 92.3236), "banglabandha" to (26.6290 to 88.4220), "west" to (24.2500 to 88.0300), "teknaf" to (20.8600 to 92.3000),
            "north" to (26.8000 to 88.5000), "india" to (22.5726 to 88.3639 - 1.0),
        )
        val rows = pins.mapIndexed { i, (n, ll) -> row("Border $n", ""","code":"$p$i","lat":${ll.first},"lng":${ll.second}""") } +
            row("Border range", ""","code":"${p}r","lat":95.5,"lng":90.4""") + row("Border text", ""","code":"${p}t","lat":"23.8","lng":90.4""")
        val res = commit(rows, conf = true)
        assertEquals(8L, res.lng("creates")); assertEquals(emptyList(), errors(res))
        assertEquals("true,true,true,true,false,false", env.sql("SELECT string_agg(location_confirmed::text, ',' ORDER BY code) FROM app.outlet WHERE code IN ('${p}0','${p}1','${p}2','${p}3','${p}4','${p}5')"))
        val w = warnings(res)
        assertTrue("5:lat:coordinates_outside_bangladesh" in w && "6:lat:coordinates_outside_bangladesh" in w && "7:lat:coordinates_invalid" in w && "8:lat:coordinates_invalid" in w, w.toString())
        assertEquals("none|none", env.sql("SELECT string_agg(location_basis, '|' ORDER BY code) FROM app.outlet WHERE code IN ('${p}r', '${p}t')"))
    }

    /** (1) push 1b: the sheet's own dates are compared like any other column: the same row changes nothing, a row whose date really changed is stored. */
    @Test
    fun aChangedSheetDateIsStoredAndTheSameRowStillChangesNothing() = env.app {
        val c = "DTE${System.nanoTime() % 100000}"
        fun r(updated: String) = row("Date Shop", ""","code":"$c","created_at":"2024-03-05 10:15:00","updated_at":"$updated"""")
        assertEquals(1L, commit(listOf(r("2025-01-02"))).lng("creates"))
        assertEquals("2025-01-01 18:00:00+00", env.sql("SELECT source_updated_at::text FROM app.outlet WHERE code = '$c'"))
        assertEquals(1L, commit(listOf(r("2025-01-02"))).lng("unchanged"))
        val changed = commit(listOf(r("2025-02-03")))
        assertEquals(1L, changed.lng("updates"))
        assertEquals("2025-02-02 18:00:00+00", env.sql("SELECT source_updated_at::text FROM app.outlet WHERE code = '$c'"))
        // our own updated_at is the time the SERVER wrote the row, never the sheet's
        assertEquals("true", env.sql("SELECT (updated_at > now() - interval '1 hour')::text FROM app.outlet WHERE code = '$c'"))
    }
}

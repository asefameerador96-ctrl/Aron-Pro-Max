package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.Aggregator
import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.IngestService
import com.aktcl.aron.backend.sync.SyncBatchRequest
import com.aktcl.aron.backend.sync.Uploader
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.Money
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * getFlaggedMemos (contract 1.8.0, D-BC-FLAGGED-MEMOS-01) and `MemoView.server_flags`: flagged memos from
 * `dw.v_memo_flags` under the caller's reach. Six flagged copies of one stored sale (sr1001, 2027-01-03): four in
 * Z-MIR (one plain, one voided by its user, one superseded, one tombstoned by an admin data void), one in a second
 * territory of D-DHK (the DMO sees it, the TSO does not) and one in another division (neither sees it).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlaggedMemosApiTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: ApiWorld
    private val day = ApiWorld.BUSINESS_DATE
    /** memo_no to (zone code, flags, raw status), oldest stored first. */
    private val memos = linkedMapOf(
        "sr1001-270103-901" to Triple("Z-MIR", "price_mismatch", "active"),
        "sr1001-270103-902" to Triple("Z-SOU", "zero_price_line", "active"),
        "sr1001-270103-903" to Triple("Z-XFM", "arithmetic_mismatch", "active"),
        "sr1001-270103-904" to Triple("Z-MIR", "price_mismatch", "voided"),
        "sr1001-270103-905" to Triple("Z-MIR", "pack_factor_mismatch", "superseded"),
        "sr1001-270103-906" to Triple("Z-MIR", "zero_price_line,price_mismatch", "data_void"),
    )

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot)
        world.fresh.db.jdbi.useHandle<Exception> { h -> seed(h) }
    }

    @AfterAll
    fun tearDown() { world.close() }

    private fun seed(h: Handle) {
        h.execute(
            """
            INSERT INTO app.territory (code, name, name_bn, division_id) SELECT 'T-DHK-S', 'Dhaka South', 'ঢাকা দক্ষিণ', id FROM app.division WHERE code = 'D-DHK';
            INSERT INTO app.house (code, name, territory_id) SELECT 'H-SOU', 'South House', id FROM app.territory WHERE code = 'T-DHK-S';
            INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
              SELECT 'Z-SOU', 'South', 'দক্ষিণ', t.id, hs.id, 'South DEP' FROM app.territory t, app.house hs WHERE t.code = 'T-DHK-S' AND hs.code = 'H-SOU';
            INSERT INTO app.division (code, name, name_bn, wing_id) SELECT 'D-XFM', 'Far Division', 'দূর বিভাগ', id FROM app.wing WHERE code = 'W-CEN';
            INSERT INTO app.territory (code, name, name_bn, division_id) SELECT 'T-XFM', 'Far', 'দূর', id FROM app.division WHERE code = 'D-XFM';
            INSERT INTO app.house (code, name, territory_id) SELECT 'H-XFM', 'Far House', id FROM app.territory WHERE code = 'T-XFM';
            INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
              SELECT 'Z-XFM', 'Far', 'দূর', t.id, hs.id, 'Far DEP' FROM app.territory t, app.house hs WHERE t.code = 'T-XFM' AND hs.code = 'H-XFM';
            INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no)
              SELECT r.code, r.code, 'Daily', z.id, 'sr', 'daily', 127, 1 FROM (VALUES ('SOU-SR-D', 'Z-SOU'), ('XFM-SR-D', 'Z-XFM')) AS r(code, zone) JOIN app.zone z ON z.code = r.zone;
            """.trimIndent(),
        )
        // A BRAND user and an ADMIN whose only grant is users:r (no reports:r).
        for ((u, role) in listOf("qabrandfm1" to "BRAND", "qaadminnr1" to "ADMIN")) {
            h.execute(
                "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) " +
                    "SELECT '$u', 'QA $role', '$role', 'QA', upper('$u'), 'en', home_zone_id, true, false, password_hash FROM app.app_user WHERE username = 'admin1001'",
            )
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT id, 'national', 0, DATE '2026-01-01', 'qa' FROM app.app_user WHERE username = '$u'")
        }
        h.execute("INSERT INTO app.user_module_grant (user_id, module, access) SELECT id, 'users', 'r' FROM app.app_user WHERE username = 'qaadminnr1'")
        val base = storeBaseMemo(h)
        val cols = h.createQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'memo' AND column_name <> 'id' AND is_generated = 'NEVER' ORDER BY ordinal_position").mapTo(String::class.java).list()
        memos.entries.forEachIndexed { i, (no, m) ->
            val (zone, flags, status) = m
            val repl = mapOf(
                "client_uuid" to "gen_random_uuid()", "family_uuid" to "gen_random_uuid()", "visit_client_uuid" to "gen_random_uuid()", "memo_no" to "'$no'",
                // The insert trigger stamps zone_id from the route (V0042): a memo of another zone is on that zone's route.
                "route_id" to "(SELECT id FROM app.route WHERE zone_id = (SELECT id FROM app.zone WHERE code = '$zone') ORDER BY id LIMIT 1)", "device_id" to "NULL", "sig" to "NULL", "external_ref" to "NULL",
                "server_flags" to "'{$flags}'::text[]", "received_at" to "TIMESTAMPTZ '${day}T05:00:00Z' + interval '1 minute' * $i",
                "status" to "'${if (status == "data_void") "active" else status}'",
                "voided_by_client_uuid" to if (status == "voided") "gen_random_uuid()" else "NULL",
                "superseded_by_client_uuid" to if (status == "superseded") "gen_random_uuid()" else "NULL",
                "voided_at" to if (status == "data_void") "TIMESTAMPTZ '${day}T06:00:00Z'" else "NULL",
            )
            h.execute("INSERT INTO app.memo (${cols.joinToString()}) SELECT ${cols.joinToString { repl[it] ?: it }} FROM app.memo WHERE memo_no = '$base'")
        }
        // Checker note: two memos of yesterday stored in the same microsecond, so the keyset needs memo_id and full precision.
        for (no in listOf("sr1001-270102-951", "sr1001-270102-952")) {
            val repl = mapOf(
                "client_uuid" to "gen_random_uuid()", "family_uuid" to "gen_random_uuid()", "visit_client_uuid" to "gen_random_uuid()", "memo_no" to "'$no'",
                "business_date" to "DATE '${ApiWorld.YESTERDAY}'", "device_id" to "NULL", "sig" to "NULL", "external_ref" to "NULL",
                "server_flags" to "'{price_mismatch}'::text[]", "received_at" to "TIMESTAMPTZ '${ApiWorld.YESTERDAY}T05:00:00.123456Z'",
            )
            h.execute("INSERT INTO app.memo (${cols.joinToString()}) SELECT ${cols.joinToString { repl[it] ?: it }} FROM app.memo WHERE memo_no = '$base'")
        }
        Aggregator.refreshDimensions(h)
    }

    /** One priced sale of sr1001 on MIR-SR-D stored through the real ingest path (the harness stores no memo); its memo_no. */
    private fun storeBaseMemo(h: Handle): String {
        fun one(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
        val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
        val outlet = one("SELECT min(id) FROM app.outlet WHERE route_id = $route AND status = 'active'")
        val sr = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val (sku, price, per) = h.createQuery(
            "SELECT s.id, p.amount_mtk, p.per_base_qty FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' " +
                "WHERE p.amount_mtk > 0 AND p.valid_from <= DATE '$day' AND (p.valid_to IS NULL OR p.valid_to > DATE '$day') ORDER BY s.id LIMIT 1",
        ).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.one()
        val device = h.createQuery("SELECT id, device_uuid::text FROM app.device WHERE device_uuid = '${ApiWorld.SR_PHONE}'").map { rs, _ -> rs.getLong(1) to rs.getString(2) }.one()
        val gross = Money.lineGrossMtk(20, price, per)
        val memoNo = "sr1001-270103-101"
        val visit = UUID.randomUUID().toString(); val memo = UUID.randomUUID().toString()
        fun env(type: String, cu: String, rank: Int, payload: String) = Json.parseToJsonElement(
            """{"type":"$type","client_uuid":"$cu","family_uuid":"$visit","rank":$rank,"schema_version":1,"business_date":"$day","captured_at":"${day}T03:4$rank:00Z",
               "captured_elapsed_ms":18330000,"boot_count":1,"clock_offset_ms":0,"captured_offline":false,"route_id":$route,"bundle_version":"$day:1","bundle_stale":false,
               "config_version":1,"payload":$payload}""",
        ).jsonObject
        val fix = """{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
            "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""
        val records = listOf(
            env("visit", visit, 0, """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${day}T03:40:00Z","sequence_no":1,"planned":true,"fix":$fix,
                "geo":{"verdict":"in_range","distance_m":10.0,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}"""),
            env("memo", memo, 1, """{"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"${day}T03:41:00Z",
                "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"round_adj_mtk":0,
                "net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":1,"discount_line_count":0,"qc_line_count":0,"offer_version_ids":[],
                "rounding_mode":"half_up_paisa"}"""),
            env("memo_line", UUID.randomUUID().toString(), 2, """{"memo_client_uuid":"$memo","line_no":1,"sku_id":$sku,"line_kind":"sale","qty_entered":20,
                "unit_entered":"stick","pack_factor":1,"qty_base":20,"price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":$per,"gross_mtk":$gross}"""),
        )
        val ingest = IngestService(world.fresh.db, DbServerConfig(world.fresh.db, RegistryDefaults(), world.clock), SqlReachResolver(world.fresh.db, GeoRepository(world.fresh.db, world.clock), world.clock), world.clock)
        val res = ingest.ingest(
            Uploader(sr, Role.SR, 1, device.first, device.second, null),
            SyncBatchRequest(
                batch_uuid = UUID.randomUUID().toString(), device_uuid = device.second, schema_version = 1, app_version = ApiWorld.APP_VERSION, trigger = "manual",
                sent_at_device = "${day}T04:00:00Z", pending_rows = 0, time_anchors = emptyList(), device_counts = emptyMap(), records = records,
            ),
        )
        check(res.acks.all { it.status == "accepted" }) { "base memo not stored: ${res.acks}" }
        return memoNo
    }

    private suspend fun HttpClient.token(user: String): String = world.login(this, ApiWorld.Caller(user, "web", null)) ?: error("no token for $user")
    private suspend fun HttpClient.read(t: String, path: String): HttpResponse = get("/v1$path") { bearerAuth(t); header("X-App-Version", ApiWorld.APP_VERSION) }
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.items() = getValue("items").jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.contentOrNull
    private fun id(sql: String): Long = world.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    /** The memo_nos [user] sees for the business date, in answer order. */
    private suspend fun HttpClient.seen(user: String, query: String = ""): List<String> {
        val r = read(token(user), "/dashboards/flagged-memos?from=$day&to=$day$query")
        assertEquals(HttpStatusCode.OK, r.status, "$user: ${r.bodyAsText()}")
        return r.obj().items().map { it.str("memo_no")!! }
    }

    @Test
    fun aNationalCallerSeesEveryFlaggedMemoNewestFirstWithNamesAndMappedStatus() = testApplication {
        application { aronApi(world.wiring) }
        val c = createClient {}
        val page = c.read(c.token("superadmin1001"), "/dashboards/flagged-memos?from=$day&to=$day").obj()
        val items = page.items()
        assertEquals(memos.keys.reversed(), items.map { it.str("memo_no") }, "newest stored first; the unflagged paper memo is absent")
        assertEquals(JsonNull, page["next_cursor"])
        val by = items.associateBy { it.str("memo_no")!! }
        assertEquals(listOf("active", "active", "active", "void", "superseded", "void"), memos.keys.map { by.getValue(it).str("status") }, "voided and an admin data void are void")
        assertEquals(JsonNull, by.getValue("sr1001-270103-904")["voided_at"], "a memo voided by its user shows only in status")
        assertEquals("${day}T06:00:00Z", by.getValue("sr1001-270103-906").str("voided_at")?.replace(".000Z", "Z"))
        val qa = world.fresh.db.jdbi.withHandle<Pair<String, String>, Exception> { h -> h.createQuery("SELECT username, full_name FROM app.app_user WHERE username = 'sr1001'").map { rs, _ -> rs.getString(1) to rs.getString(2) }.one() }
        assertTrue(items.all { it.str("user_code") == qa.first && it.str("user_name") == qa.second }, "names joined from app_user")
        assertEquals(listOf("zero_price_line", "price_mismatch"), by.getValue("sr1001-270103-906").getValue("flags").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(id("SELECT id FROM app.zone WHERE code = 'Z-SOU'"), by.getValue("sr1001-270103-902").str("zone_id")!!.toLong())
        assertEquals(id("SELECT id FROM app.division WHERE code = 'D-XFM'"), by.getValue("sr1001-270103-903").str("division_id")!!.toLong())
    }

    @Test
    fun aTsoSeesOnlyItsTerritoryAndADmoOnlyItsDivision() = testApplication {
        application { aronApi(world.wiring) }
        val c = createClient {}
        assertEquals(listOf("sr1001-270103-906", "sr1001-270103-905", "sr1001-270103-904", "sr1001-270103-901"), c.seen("tso1001"))
        assertEquals(listOf("sr1001-270103-906", "sr1001-270103-905", "sr1001-270103-904", "sr1001-270103-902", "sr1001-270103-901"), c.seen("dmo1001"))
        // A node outside the reach is 403 ERR_OUT_OF_SCOPE, never an empty page.
        for ((user, q) in listOf("tso1001" to "level=territory&node_id=${id("SELECT id FROM app.territory WHERE code = 'T-DHK-S'")}", "dmo1001" to "level=division&node_id=${id("SELECT id FROM app.division WHERE code = 'D-XFM'")}")) {
            val r = c.read(c.token(user), "/dashboards/flagged-memos?from=$day&to=$day&$q")
            assertEquals(HttpStatusCode.Forbidden, r.status, "$user $q")
            assertEquals("ERR_OUT_OF_SCOPE", r.obj().str("code"))
        }
        // Inside the reach, a node narrows: the DMO at the second territory.
        assertEquals(listOf("sr1001-270103-902"), c.seen("dmo1001", "&level=territory&node_id=${id("SELECT id FROM app.territory WHERE code = 'T-DHK-S'")}"))
    }

    @Test
    fun brandAndAnAdminWithoutReportsAreRefused() = testApplication {
        application { aronApi(world.wiring) }
        val c = createClient {}
        for (u in listOf("qabrandfm1", "qaadminnr1")) {
            val r = c.read(c.token(u), "/dashboards/flagged-memos?from=$day&to=$day")
            assertEquals(HttpStatusCode.Forbidden, r.status, u)
            assertEquals("ERR_FORBIDDEN", r.obj().str("code"), u)
        }
        assertEquals(memos.size, c.seen("admin1001").size, "an ADMIN with reports:r reads it")
    }

    @Test
    fun pagingTheFlagFilterAndTheDateRules() = testApplication {
        application { aronApi(world.wiring) }
        val c = createClient {}
        val t = c.token("superadmin1001")
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = c.read(t, "/dashboards/flagged-memos?from=$day&to=$day&limit=2" + (cursor?.let { "&cursor=$it" } ?: "")).obj()
            seen += page.items().map { it.str("memo_no")!! }
            cursor = page["next_cursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null && seen.size <= memos.size)
        assertEquals(memos.keys.reversed(), seen, "keyset pages cover every row once, in order")
        assertEquals(listOf("sr1001-270103-906", "sr1001-270103-902"), c.seen("superadmin1001", "&flag=zero_price_line"))
        assertEquals(emptyList(), c.seen("superadmin1001", "&flag=not_a_flag_yet"), "an unknown flag is an empty page")
        assertEquals(memos.size, c.read(t, "/dashboards/flagged-memos").obj().items().size, "from and to default to today")
        for (q in listOf("from=2026-10-01&to=2027-01-03", "from=$day&to=${ApiWorld.YESTERDAY}", "from=$day&to=$day&cursor=x", "from=$day&to=$day&limit=0")) {
            assertEquals(HttpStatusCode.BadRequest, c.read(t, "/dashboards/flagged-memos?$q").status, q)
        }
        assertEquals(HttpStatusCode.OK, c.read(t, "/dashboards/flagged-memos?from=2026-10-03&to=2027-01-03").status, "92 days is allowed")
    }

    @Test
    fun pagesSplitTwoMemosStoredInTheSameMicrosecond() = testApplication {
        application { aronApi(world.wiring) }
        val c = createClient {}
        val t = c.token("superadmin1001")
        val day0 = ApiWorld.YESTERDAY
        val first = c.read(t, "/dashboards/flagged-memos?from=$day0&to=$day0&limit=1").obj()
        val second = c.read(t, "/dashboards/flagged-memos?from=$day0&to=$day0&limit=1&cursor=${first.str("next_cursor")}").obj()
        assertEquals(listOf("sr1001-270102-952", "sr1001-270102-951"), (first.items() + second.items()).map { it.str("memo_no") }, "tie broken by memo_id, newest first")
        assertEquals(JsonNull, second["next_cursor"])
    }

    @Test
    fun theMemoReadCarriesServerFlags() = testApplication {
        application { aronApi(world.wiring) }
        val c = createClient {}
        val t = c.token("superadmin1001")
        val flagged = c.read(t, "/memos?memo_no=sr1001-270103-901").obj().items().single()
        assertEquals(listOf("price_mismatch"), flagged.getValue("server_flags").jsonArray.map { it.jsonPrimitive.content })
        val plain = c.read(t, "/memos?memo_no=sr1001-270103-101").obj().items().single()
        assertEquals(0, plain.getValue("server_flags").jsonArray.size, "empty when the memo has none")
    }
}

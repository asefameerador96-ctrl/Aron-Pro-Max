package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Loyalty-programme portal API (lane portal-loyalty-api, train 2; D-LOY-API-05): /v1/admin/programmes and /v1/admin/gifts, the twelve operations of
 * contract 1.13.0. Each write has a happy path, 400, 403, 409 and replay case (update also 412 and 404, status also 404); the reads have a happy path,
 * 400 on a bad query or path, 403 and 404; the scope matrix runs the seven portal levels (SUPERADMIN, ADMIN, NATIONAL, BRAND, WM, DMO, TSO) and the
 * refused roles through the real guard (PortalLevels and AdminModules run exactly as in production). The field stays deferred (docs/27).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminLoyaltyTest {
    private lateinit var env: SeededAdminDb
    private val reaches = ConcurrentHashMap<Long, Reach>()
    private val base = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(50000)))
    private lateinit var ids: Map<String, Long>
    private val fx = HashMap<String, Long>()

    private fun sql(q: String) = env.fresh.db.jdbi.useHandle<Exception> { it.execute(q) }
    private fun scalar(q: String): String = env.scalar(q) ?: error("no row for: $q")
    private fun count(q: String) = scalar(q).toInt()

    private val from get() = scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date + 1)::text")
    private val to get() = scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date + 30)::text")
    private val past get() = scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date - 5)::text")
    private val longAgo get() = scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date - 40)::text")

    @BeforeAll
    fun setUp() {
        env = SeededAdminDb()
        listOf("lnat1" to "NATIONAL", "lbrand1" to "BRAND", "lbrand2" to "BRAND", "lwm1" to "WM", "ldmo1" to "DMO", "ltso1" to "TSO", "ltso2" to "TSO", "ltso3" to "TSO",
            "lamo1" to "AMO", "lsup1" to "SUPPORT", "lana1" to "ANALYST", "ladm1" to "ADMIN", "ladm2" to "ADMIN", "ladm3" to "ADMIN").forEach { (u, r) ->
            sql("INSERT INTO app.app_user (username, full_name, role, locale, pilot, must_change_password) VALUES ('$u', '$u', '$r', 'en', true, false)")
        }
        ids = env.fresh.db.jdbi.withHandle<Map<String, Long>, Exception> { h -> h.createQuery("SELECT username, id FROM app.app_user").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap() }
        // Geography: A is the seed (Z-MIR); O is a second territory of the same division; B is a separate wing, division, territory, zone and route.
        sql("INSERT INTO app.wing (code, name) VALUES ('W-B', 'Wing B')")
        sql("INSERT INTO app.division (code, name, wing_id) SELECT 'D-B', 'Division B', id FROM app.wing WHERE code = 'W-B'")
        sql("INSERT INTO app.territory (code, name, division_id) SELECT 'T-B', 'Territory B', id FROM app.division WHERE code = 'D-B'")
        sql("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-B', 'Zone B', id FROM app.territory WHERE code = 'T-B'")
        sql("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-B', 'Route B', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-B'")
        fx["zoneA"] = scalar("SELECT id FROM app.zone WHERE code = 'Z-MIR'").toLong()
        fx["zoneO"] = scalar("SELECT id FROM app.zone WHERE code = 'Z-OTHER'").toLong()
        fx["zoneB"] = scalar("SELECT id FROM app.zone WHERE code = 'Z-B'").toLong()
        fx["terO"] = scalar("SELECT id FROM app.territory WHERE code = 'T-OTHER'").toLong()
        fx["divB"] = scalar("SELECT id FROM app.division WHERE code = 'D-B'").toLong()
        fx["wingB"] = scalar("SELECT id FROM app.wing WHERE code = 'W-B'").toLong()
        fx["wingA"] = scalar("SELECT d.wing_id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id WHERE z.code = 'Z-MIR'").toLong()
        fx["routeA"] = scalar("SELECT min(id) FROM app.route WHERE zone_id = ${fx["zoneA"]}").toLong()
        fx["routeB"] = scalar("SELECT id FROM app.route WHERE code = 'R-B'").toLong()
        sql("INSERT INTO app.sub_channel (channel, code, name) VALUES ('GT', 'SC-T', 'Sub channel T')")
        fx["subCh"] = scalar("SELECT id FROM app.sub_channel WHERE code = 'SC-T'").toLong()
        // Products: category > segment > brands b1 and b2 > variants v1, v2 > SKUs s1, s2.
        sql("INSERT INTO app.product_node (level, code, name) VALUES ('category', 'C-LOY', 'Loyalty category')")
        sql("INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'segment', id, 'S-LOY', 'Loyalty segment' FROM app.product_node WHERE code = 'C-LOY'")
        for (b in listOf("1", "2")) {
            sql("INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'brand', id, 'B-LOY$b', 'Loyalty brand $b' FROM app.product_node WHERE code = 'S-LOY'")
            sql("INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'variant', id, 'V-LOY$b', 'Loyalty variant $b' FROM app.product_node WHERE code = 'B-LOY$b'")
            sql("INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default, report_unit) SELECT 'SKU-LOY$b', id, 'cigarette', 'Sku $b', 'S$b', 'stick', ${if (b == "1") 20 else 10}, 'stick', 'stick' FROM app.product_node WHERE code = 'V-LOY$b'")
            fx["b$b"] = scalar("SELECT id FROM app.product_node WHERE code = 'B-LOY$b'").toLong()
            fx["v$b"] = scalar("SELECT id FROM app.product_node WHERE code = 'V-LOY$b'").toLong()
            fx["s$b"] = scalar("SELECT id FROM app.sku WHERE code = 'SKU-LOY$b'").toLong()
        }
        fx["cat"] = scalar("SELECT id FROM app.product_node WHERE code = 'C-LOY'").toLong()
        sql("INSERT INTO app.user_brand_scope (user_id, brand_id) VALUES (${ids.getValue("lbrand1")}, ${fx["b1"]})")
        val date = java.time.LocalDate.EPOCH
        fun reach(user: String, role: Role, zones: Set<Long>, routes: Set<Long>) {
            reaches[ids.getValue(user)] = Reach(ids.getValue(user), role, date, false, zones, routes, false, emptyList())
        }
        reach("lwm1", Role.WM, setOf(fx["zoneA"]!!, fx["zoneO"]!!), emptySet())
        reach("ldmo1", Role.DMO, setOf(fx["zoneB"]!!), emptySet())
        reach("ltso1", Role.TSO, setOf(fx["zoneA"]!!), emptySet())
        reach("ltso2", Role.TSO, emptySet(), setOf(fx["routeB"]!!))
        reach("ltso3", Role.TSO, emptySet(), emptySet())
        // Outlets for the enrolments: two in zone A (one on route A), one in zone B (route B).
        for ((code, zone, route) in listOf(Triple("L-O1", "zoneA", "routeA"), Triple("L-O2", "zoneA", "routeA"), Triple("L-O3", "zoneB", "routeB")))
            sql("INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel) VALUES ('$code', 'Outlet $code', 'Owner', ${fx[zone]}, ${fx[route]}, (SELECT min(id) FROM app.cluster), 'GT')")
        for (c in listOf("L-O1", "L-O2", "L-O3")) fx[c] = scalar("SELECT id FROM app.outlet WHERE code = '$c'").toLong()
    }

    @AfterAll
    fun tearDown() = env.close()



    // ------------------------------------------------------------------------------------------------ plumbing

    private val phoneDevice = UUID.randomUUID().toString()

    private fun tok(user: String, role: Role, modules: Map<String, String>? = null, flavour: String = "web"): String {
        // wall-clock-ok: token validity window against the app guard, which verifies with the system clock
        val now = Instant.now()
        val b = JWTClaimsSet.Builder().issuer("aron").audience("aron-api").subject(ids.getValue(user).toString()).claim("uname", user)
            .claim("role", role.wire).claim("sv", 1L).claim("flv", flavour).claim("perm", emptyList<String>()).claim("pii", false)
            .claim("amr", if (role.wire in listOf("ADMIN", "SUPERADMIN", "SUPPORT")) listOf("pwd", "mfa") else listOf("pwd"))
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900))).jwtID(UUID.randomUUID().toString())
        if (modules != null) b.claim("modules", modules)
        if (flavour != "web") b.claim("dvu", phoneDevice).claim("did", 1L)
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("t").build(), b.build()).apply { sign(TestTokens.keys.signer) }.serialize()
    }

    private val super1 get() = tok("superadmin1001", Role.SUPERADMIN)
    private val adminW get() = tok("ladm1", Role.ADMIN, mapOf("loyalty" to "w"))
    private val adminR get() = tok("ladm2", Role.ADMIN, mapOf("loyalty" to "r"))
    private val adminNone get() = tok("ladm3", Role.ADMIN, mapOf("prices" to "w", "targets" to "w", "discounts" to "w"))
    private val brand1 get() = tok("lbrand1", Role.BRAND)
    private val nat get() = tok("lnat1", Role.NATIONAL)

    /** A stand-in blob store: the read URL of a stored asset is derived from its path. */
    private val blob = object : BlobSasIssuer {
        override fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant) = "https://blob.test/w/$blobPath"
        // Signature-shaped, as the real issuer returns: the test below proves no such URL is ever stored in a journal or an audit row.
        override fun readUrl(blobPath: String) = "https://blob.test/r/$blobPath?sv=2024-11-04&se=2099-01-01T00%3A00%3A00Z&sig=SECRETSIG%2Babc"
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            installAronPlatform(PlatformContext(AronClock.SYSTEM, base, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), object : ScopeVersionLookup {
                override fun current(userId: Long): Long = 1
                override fun rolePermissions(role: String): Set<String> = emptySet()
                override fun portalGrantsLive(): Boolean = true
            }, base)
            val resolver = ReachResolver { userId, role, _, date -> reaches[userId] ?: Reach(userId, role, date, true, emptySet(), emptySet(), false, emptyList()) }
            routing { route("/v1") { adminLoyaltyRoutes(AdminLoyaltyDeps(env.fresh.db, resolver, guard, blob)) } }
        }
        block()
    }

    private fun uuid() = UUID.randomUUID().toString()
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private fun code() = "LOY-" + seq.incrementAndGet() + "-" + uuid().take(4)
    private fun gcode() = "gift_" + seq.incrementAndGet() + "_" + uuid().take(4).replace(Regex("[^a-f0-9]"), "a")
    private val why = "Approved by the loyalty committee"
    private val tiers2 = """[{"tier_code":"silver","sort_order":1,"label_en":"Silver","label_bn":"রৌপ্য","threshold":"100"},{"tier_code":"gold","sort_order":2,"label_en":"Gold","threshold":"1500.5"}]"""

    private fun createBody(code: String = code(), request: String = uuid(), kind: String = "astha", tiers: String = "[]", qualifying: String = "[]", scope: String = "[]",
                           activeFrom: String = from, activeTo: String = to, extra: String = "") =
        """{"request_uuid":"$request","kind":"$kind","code":"$code","name_en":"Programme $code","active_from":"$activeFrom","active_to":"$activeTo","tiers":$tiers,"qualifying":$qualifying,"scope":$scope,"change_reason":"$why"$extra}"""

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, token: String, body: String? = null, ifMatch: Int? = null) = send(method, path, token, body, ifMatch)
    private suspend fun ApplicationTestBuilder.create(token: String, body: String = createBody()): HttpResponse = call(HttpMethod.Post, "/v1/admin/programmes", token, body)
    private suspend fun ApplicationTestBuilder.createId(body: String): Long {
        val r = create(super1, body)
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.obj().getValue("programme_id").jsonPrimitive.content.toLong()
    }

    private fun q(level: String, id: Long) = """{"product_level":"$level","product_id":$id}"""
    private fun s(type: String, id: Long) = """{"node_type":"$type","node_id":$id}"""
    private fun rowVersion(id: Long) = scalar("SELECT version FROM app.programme WHERE id = $id").toInt()

    private suspend fun HttpResponse.problem(): Pair<String, String> = obj().let { it.getValue("code").jsonPrimitive.content to (it["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("pointer")?.jsonPrimitive?.content ?: "") }
    private suspend fun HttpResponse.expect(status: HttpStatusCode, code: String? = null, pointer: String? = null) {
        val text = bodyAsText()
        assertEquals(status, this.status, text)
        if (code != null || pointer != null) { val (c, p) = problem(); if (code != null) assertEquals(code, c, text); if (pointer != null) assertEquals(pointer, p, text) }
    }

    private suspend fun ApplicationTestBuilder.activate(id: Long, token: String = super1, request: String = uuid()) =
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", token, """{"request_uuid":"$request","to_status":"active","reason":"$why"}""")

    private fun patchBody(members: String, request: String = uuid()) = """{"request_uuid":"$request",$members"change_reason":"$why"}"""

    // ------------------------------------------------------------------------------------------------ createProgramme

    @Test
    fun createMakesADraftVersionOneWithTiersScopeAuditAndTheWriterRule() = app {
        val code = code()
        val r = create(adminW, createBody(code, tiers = tiers2, qualifying = "[${q("brand", fx["b1"]!!)}]", scope = "[${s("zone", fx["zoneA"]!!)}]", extra = ""","period_label":"2026-Q4","name_bn":"বাংলা""""))
        r.expect(HttpStatusCode.Created)
        assertEquals("\"2\"", r.headers["ETag"], "insert (1) + the link to the first version (2)")
        val o = r.obj()
        assertEquals("draft", o.getValue("status").jsonPrimitive.content)
        assertEquals(1, o.getValue("version_no").jsonPrimitive.content.toInt())
        assertEquals(code, o.getValue("code").jsonPrimitive.content)
        assertEquals(listOf("silver", "gold"), o.getValue("tiers").jsonArray.map { it.jsonObject.getValue("tier_code").jsonPrimitive.content })
        assertEquals(listOf("100.000", "1500.500"), o.getValue("tiers").jsonArray.map { it.jsonObject.getValue("threshold").jsonPrimitive.content }, "STD as stored, numeric(16,3), never a float")
        assertEquals("std", o.getValue("tiers").jsonArray[0].jsonObject.getValue("threshold_basis").jsonPrimitive.content)
        val id = o.getValue("programme_id").jsonPrimitive.content.toLong()
        // The writer rule: app.programme carries the version's definition, the pointer is set, and one version row exists.
        assertEquals("Programme $code|2026-Q4|1", scalar("SELECT p.name_en || '|' || p.period_label || '|' || v.version_no FROM app.programme p JOIN app.programme_version v ON v.id = p.current_version_id WHERE p.id = $id"))
        assertEquals(1, count("SELECT count(*) FROM app.programme_version WHERE programme_id = $id AND change_reason = '$why' AND result_row_version = 2"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'programme' AND entity_id = '$id' AND action = 'create' AND reason = '$why'"))
        assertEquals("true", scalar("SELECT (jsonb_array_length(tiers) = 2)::text FROM dw.v_programme WHERE programme_id = $id"))
        // GET shows the same programme with the same ETag; the version list has one row.
        call(HttpMethod.Get, "/v1/admin/programmes/$id", adminR).also { it.expect(HttpStatusCode.OK); assertEquals("\"2\"", it.headers["ETag"]) }
        call(HttpMethod.Get, "/v1/admin/programmes/$id/versions", adminR).obj().getValue("items").jsonArray.also { assertEquals(1, it.size); assertTrue(it[0].jsonObject.getValue("is_current").jsonPrimitive.content == "true") }
    }

    @Test
    fun createRefusalsAreFieldPrecise() = app {
        fun with(vararg kv: Pair<String, String>): String {
            var b = createBody()
            for ((k, v) in kv) b = if (v.isEmpty()) b.replace(Regex("\"$k\":\"[^\"]*\",?"), "") else b.replace(Regex("\"$k\":\"[^\"]*\""), "\"$k\":\"$v\"")
            return b
        }
        create(super1, with("request_uuid" to "")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.request_uuid")
        create(super1, with("request_uuid" to "not-a-uuid")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.request_uuid")
        create(super1, with("kind" to "loyalty")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.kind")
        create(super1, with("code" to "bad code!")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.code")
        create(super1, with("name_en" to "")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.name_en")
        create(super1, with("change_reason" to "short")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.change_reason")
        create(super1, createBody(extra = ""","status":"active"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.status")
        create(super1, createBody(extra = ""","bogus":1""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.bogus")
        create(super1, createBody(activeFrom = to, activeTo = from)).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.active_to")
        create(super1, createBody(extra = ""","points_expire_on":"$from"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.points_expire_on")
        create(super1, createBody(extra = ""","points_expire_on":"not-a-date"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.points_expire_on")
        fun tier(code: String, order: Int, th: String?) = """{"tier_code":"$code","sort_order":$order,"label_en":"T","threshold":${th?.let { "\"$it\"" } ?: "null"}}"""
        create(super1, createBody(tiers = "[${tier("a1", 1, "10")},${tier("a1", 2, "20")}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers")
        create(super1, createBody(tiers = "[${tier("a1", 1, "10")},${tier("a2", 1, "20")}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers")
        create(super1, createBody(tiers = "[${tier("a1", 1, "20")},${tier("a2", 2, "20")}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers")   // thresholds must rise
        create(super1, createBody(tiers = "[${tier("a1", 1, "-5")}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers[0].threshold")      // never negative
        create(super1, createBody(tiers = "[${tier("a1", 1, "1.2345")}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers[0].threshold")  // 3 fraction digits at most
        create(super1, createBody(tiers = "[${tier("A1", 1, "1")}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers[0].tier_code")
        create(super1, createBody(tiers = """[{"tier_code":"a1","sort_order":1,"label_en":"T","threshold_basis":"stick"}]""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers[0].threshold_basis")
        create(super1, createBody(tiers = """[{"tier_code":"a1","label_en":"T"}]""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers[0].sort_order")
        create(super1, createBody(tiers = "[" + (1..10).joinToString(",") { tier("t$it", it, "${it * 10}") } + "," + tier("t11", 10, "999") + "]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tiers")
        create(super1, createBody(tiers = "[${tier("a1", 1, null)},${tier("a2", 2, null)}]")).expect(HttpStatusCode.Created)    // no threshold supplied yet: nothing is invented
        create(super1, createBody(scope = "[${s("channel", 1)}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.scope")
        create(super1, createBody(scope = "[${s("zone", 999999)}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.scope[0]")
        create(super1, createBody(qualifying = "[${q("brand", 999999)}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.qualifying[0]")
        create(super1, createBody(qualifying = "[${q("brand", fx["b1"]!!)},${q("brand", fx["b1"]!!)}]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.qualifying[1]")
        create(super1, createBody(extra = ""","rules":{"slab":{"x":1}}""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rules/slab")
        create(super1, createBody(extra = ""","rules":{"slab_pct":5,"note":"owner data"}""")).expect(HttpStatusCode.Created)
    }

    @Test
    fun createIsRefusedToEveryRoleButSuperadminAndAdminWithTheWriteGrant() = app {
        create(adminR).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        create(adminNone).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        for (t in listOf(nat, brand1, tok("lwm1", Role.WM), tok("ldmo1", Role.DMO), tok("ltso1", Role.TSO), tok("lamo1", Role.AMO), tok("lsup1", Role.SUPPORT), tok("lana1", Role.ANALYST)))
            create(t).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        create(tok("ltso1", Role.TSO, flavour = "tso")).expect(HttpStatusCode.Unauthorized, "ERR_DEVICE_PROOF_INVALID")    // a phone token without its device proof stops at the guard
        assertEquals(0, count("SELECT count(*) FROM app.programme WHERE code LIKE 'LOY-%' AND created_by IN (${ids.getValue("lwm1")}, ${ids.getValue("lbrand1")}, ${ids.getValue("lnat1")})"))
    }

    @Test
    fun createReplaysTheFirstResultAndRefusesAReusedRequestUuidOrCode() = app {
        val request = uuid(); val code = code()
        val body = createBody(code, request, tiers = tiers2)
        val first = create(super1, body); first.expect(HttpStatusCode.Created)
        val id = first.obj().getValue("programme_id").jsonPrimitive.content
        val again = create(super1, body); again.expect(HttpStatusCode.Created)
        assertEquals(id, again.obj().getValue("programme_id").jsonPrimitive.content)
        assertEquals("\"2\"", again.headers["ETag"])
        assertEquals(1, count("SELECT count(*) FROM app.programme WHERE code = '$code'"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'programme' AND entity_id = '$id' AND action = 'create'"))
        // The same uuid with other content, a taken code with a new uuid, and a uuid already used by a status call are conflicts.
        create(super1, createBody(code, request, tiers = "[]")).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        create(super1, createBody(code)).expect(HttpStatusCode.Conflict, "ERR_MASTER_DUPLICATE_CODE", "body.code")
        val usedByStatus = uuid()
        activate(id.toLong(), super1, usedByStatus).expect(HttpStatusCode.OK)
        create(super1, createBody(request = usedByStatus)).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        // After the programme became active the replay still answers the FIRST result: draft, at the first ETag.
        val replay = create(super1, body); replay.expect(HttpStatusCode.Created)
        assertEquals("draft", replay.obj().getValue("status").jsonPrimitive.content)
        assertEquals("\"2\"", replay.headers["ETag"])
    }

    // ------------------------------------------------------------------------------------------------ updateProgramme

    @Test
    fun updateAppendsAVersionWhileDraftCopiesItIntoTheProgrammeAndReplays() = app {
        val id = createId(createBody(tiers = tiers2))
        val etag = rowVersion(id)
        val req = uuid()
        val r = call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, patchBody(""""name_en":"Renamed","tiers":[{"tier_code":"silver","sort_order":1,"label_en":"Silver","threshold":"150"}],""", req), etag)
        r.expect(HttpStatusCode.OK)
        assertEquals("\"${etag + 1}\"", r.headers["ETag"])
        val o = r.obj()
        assertEquals(2, o.getValue("version_no").jsonPrimitive.content.toInt())
        assertEquals("Renamed", o.getValue("name_en").jsonPrimitive.content)
        assertEquals(1, o.getValue("tiers").jsonArray.size, "the tiers list replaces the old one")
        assertEquals("Renamed", scalar("SELECT name_en FROM app.programme WHERE id = $id"), "the writer rule: copied into app.programme in the same transaction")
        assertEquals(2, count("SELECT count(*) FROM app.programme_version WHERE programme_id = $id"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'programme' AND entity_id = '$id' AND action = 'new_version'"))
        // Old version stays as it was (immutable); the list is newest first.
        val versions = call(HttpMethod.Get, "/v1/admin/programmes/$id/versions", adminR).obj().getValue("items").jsonArray
        assertEquals(listOf(2, 1), versions.map { it.jsonObject.getValue("version_no").jsonPrimitive.content.toInt() })
        assertEquals(2, versions[1].jsonObject.getValue("tiers").jsonArray.size)
        // Replay of the same call (now with a stale ETag) answers the first result before If-Match is judged.
        val again = call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, patchBody(""""name_en":"Renamed","tiers":[{"tier_code":"silver","sort_order":1,"label_en":"Silver","threshold":"150"}],""", req), etag)
        again.expect(HttpStatusCode.OK)
        assertEquals(2, count("SELECT count(*) FROM app.programme_version WHERE programme_id = $id"))
        assertEquals("\"${etag + 1}\"", again.headers["ETag"])
        // The same uuid with other content is a conflict.
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, patchBody(""""name_en":"Different",""", req), etag + 1).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
    }

    @Test
    fun updateRefusalsStaleEtagNoChangeImmutableMembersFinalProgrammesAndMissingProgramme() = app {
        val id = createId(createBody(tiers = tiers2)); val etag = rowVersion(id)
        fun body(m: String) = patchBody(m)
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""name_en":"A","""), etag + 5).expect(HttpStatusCode.PreconditionFailed, "ERR_PRECONDITION_FAILED")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""name_en":"A",""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "header.If-Match")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""kind":"campaign","name_en":"A","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.kind")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""code":"NEW","name_en":"A","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.code")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""status":"active","name_en":"A","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.status")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body")
        val cur = scalar("SELECT name_en FROM app.programme WHERE id = $id")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""name_en":"$cur","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""active_to":"${scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date - 1)::text")}","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.active_to")
        call(HttpMethod.Patch, "/v1/admin/programmes/999999", adminW, body(""""name_en":"A","""), 1).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminR, body(""""name_en":"A","""), etag).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminNone, body(""""name_en":"A","""), etag).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", brand1, body(""""name_en":"A","""), etag).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        assertEquals(1, count("SELECT count(*) FROM app.programme_version WHERE programme_id = $id"), "nothing was written")
        // Once active, a programme is final.
        activate(id).expect(HttpStatusCode.OK)
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, body(""""name_en":"A","""), rowVersion(id)).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
    }

    // ------------------------------------------------------------------------------------------------ setProgrammeStatus

    @Test
    fun statusMovesDraftToActiveBySuperadminAndActiveToEndedByAdminWithTheTrailAndReplay() = app {
        val id = createId(createBody())
        activate(id, adminW).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        val row = rowVersion(id)
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", super1, """{"request_uuid":"${uuid()}","to_status":"active","reason":"$why"}""", row + 3).expect(HttpStatusCode.PreconditionFailed, "ERR_PRECONDITION_FAILED")
        assertEquals("draft", scalar("SELECT status FROM app.programme WHERE id = $id"))
        val request = uuid()
        val r = activate(id, super1, request); r.expect(HttpStatusCode.OK)
        assertEquals("active", r.obj().getValue("status").jsonPrimitive.content)
        assertEquals("\"${row + 1}\"", r.headers["ETag"])
        assertEquals("true", scalar("SELECT is_effective::text FROM dw.v_programme WHERE programme_id = $id"))
        assertEquals(1, count("SELECT count(*) FROM app.programme_status_change WHERE programme_id = $id AND from_status = 'draft' AND to_status = 'active' AND request_uuid = '$request' AND reason = '$why'"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'programme' AND entity_id = '$id' AND action = 'activate'"))
        activate(id, super1, request).expect(HttpStatusCode.OK)
        assertEquals(1, count("SELECT count(*) FROM app.programme_status_change WHERE programme_id = $id"))
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", super1, """{"request_uuid":"$request","to_status":"inactive","reason":"$why"}""").expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        activate(id, super1).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        val end = """{"request_uuid":"${uuid()}","to_status":"inactive","reason":"Programme finished early"}"""
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", adminR, end).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", adminW, end).expect(HttpStatusCode.OK)
        assertEquals("inactive", scalar("SELECT status FROM app.programme WHERE id = $id"))
        // The activation replayed after the programme ended still answers the first result: active, at the first ETag.
        val replayed = activate(id, super1, request); replayed.expect(HttpStatusCode.OK)
        assertEquals("active", replayed.obj().getValue("status").jsonPrimitive.content)
        assertEquals("\"${row + 1}\"", replayed.headers["ETag"])
        // An ended programme cannot move again; a draft cannot be ended; nothing moves back to draft.
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", super1, end.replace(Regex("\"request_uuid\":\"[^\"]+\""), "\"request_uuid\":\"${uuid()}\"")).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        val draft = createId(createBody())
        call(HttpMethod.Post, "/v1/admin/programmes/$draft/status", super1, end.replace(Regex("\"request_uuid\":\"[^\"]+\""), "\"request_uuid\":\"${uuid()}\"")).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        call(HttpMethod.Post, "/v1/admin/programmes/$draft/status", super1, """{"request_uuid":"${uuid()}","to_status":"draft","reason":"$why"}""").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.to_status")
    }

    @Test
    fun statusRefusalsBadBodyUnknownProgrammeAndAWindowThatHasPassed() = app {
        val id = createId(createBody())
        fun body(extra: String) = """{"request_uuid":"${uuid()}",$extra}"""
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", super1, body(""""to_status":"active","reason":"short"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.reason")
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", super1, body(""""to_status":"active"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.reason")
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", super1, """{"to_status":"active","reason":"$why"}""").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.request_uuid")
        call(HttpMethod.Post, "/v1/admin/programmes/$id/status", super1, body(""""to_status":"active","reason":"$why","x":1""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.x")
        call(HttpMethod.Post, "/v1/admin/programmes/999999/status", super1, body(""""to_status":"active","reason":"$why"""")).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        val old = createId(createBody(activeFrom = longAgo, activeTo = past))
        activate(old).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.active_to")
        assertEquals("draft", scalar("SELECT status FROM app.programme WHERE id = $old"))
        assertEquals(0, count("SELECT count(*) FROM app.programme_status_change WHERE programme_id = $old"))
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Test
    fun listFiltersAndShowsTheCurrentDefinitionAndBadQueriesAre400() = app {
        val a = createId(createBody(kind = "diamond_league")); val b = createId(createBody(kind = "astha"))
        activate(a).expect(HttpStatusCode.OK)
        val all = call(HttpMethod.Get, "/v1/admin/programmes", adminR).also { it.expect(HttpStatusCode.OK) }.obj().getValue("items").jsonArray.map { it.jsonObject.getValue("programme_id").jsonPrimitive.content.toLong() }
        assertTrue(all.containsAll(listOf(a, b)) && all.indexOf(b) < all.indexOf(a), "newest first")
        val active = call(HttpMethod.Get, "/v1/admin/programmes?status=active", adminR).obj().getValue("items").jsonArray.map { it.jsonObject.getValue("status").jsonPrimitive.content }.toSet()
        assertEquals(setOf("active"), active)
        val drafts = call(HttpMethod.Get, "/v1/admin/programmes?status=draft&kind=astha", adminR).obj().getValue("items").jsonArray
        assertTrue(drafts.all { it.jsonObject.getValue("status").jsonPrimitive.content == "draft" && it.jsonObject.getValue("kind").jsonPrimitive.content == "astha" } && drafts.isNotEmpty())
        call(HttpMethod.Get, "/v1/admin/programmes?status=bogus", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.status")
        call(HttpMethod.Get, "/v1/admin/programmes?kind=bogus", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.kind")
        call(HttpMethod.Get, "/v1/admin/programmes/abc", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "path.id")
        call(HttpMethod.Get, "/v1/admin/programmes/abc/versions", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "path.id")
        call(HttpMethod.Get, "/v1/admin/programmes/$a/versions?limit=0", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.limit")
        call(HttpMethod.Get, "/v1/admin/programmes/999999", adminR).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/programmes/999999/versions", adminR).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/programmes", adminNone).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Get, "/v1/admin/programmes", tok("lamo1", Role.AMO)).expect(HttpStatusCode.Forbidden)
    }

    @Test
    fun versionsPageNewestFirstByCursor() = app {
        val id = createId(createBody())
        var etag = rowVersion(id)
        for (n in 1..3) { call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, patchBody(""""name_en":"N$n","""), etag).expect(HttpStatusCode.OK); etag = rowVersion(id) }
        val p1 = call(HttpMethod.Get, "/v1/admin/programmes/$id/versions?limit=2", adminR).obj()
        assertEquals(listOf(4, 3), p1.getValue("items").jsonArray.map { it.jsonObject.getValue("version_no").jsonPrimitive.content.toInt() })
        val p2 = call(HttpMethod.Get, "/v1/admin/programmes/$id/versions?limit=2&cursor=${p1.getValue("next_cursor").jsonPrimitive.content}", adminR).obj()
        assertEquals(listOf(2, 1), p2.getValue("items").jsonArray.map { it.jsonObject.getValue("version_no").jsonPrimitive.content.toInt() })
        assertTrue(p2.getValue("next_cursor") is kotlinx.serialization.json.JsonNull)
    }

    // ------------------------------------------------------------------------------------------------ gifts

    private fun giftBody(programme: Long, code: String = gcode(), request: String = uuid(), extra: String = "") =
        """{"request_uuid":"$request","programme_id":$programme,"code":"$code","name_en":"Gift $code","points_cost":200,"tier_codes":[],"change_reason":"$why"$extra}"""

    private suspend fun ApplicationTestBuilder.createGift(token: String, body: String): HttpResponse = call(HttpMethod.Post, "/v1/admin/gifts", token, body)

    @Test
    fun giftCreateIsSuperadminOnlyWithReplayDuplicateAndRefusals() = app {
        val prog = createId(createBody(tiers = tiers2))
        val code = gcode(); val request = uuid()
        val body = giftBody(prog, code, request)
        createGift(adminW, body).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")      // gift-catalogue changes: Super Admin only (brief Q3)
        createGift(adminR, body).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        val r = createGift(super1, body); r.expect(HttpStatusCode.Created)
        assertEquals("\"1\"", r.headers["ETag"])
        val g = r.obj()
        assertEquals(200, g.getValue("points_cost").jsonPrimitive.content.toInt())
        assertEquals(code, g.getValue("code").jsonPrimitive.content)
        val id = g.getValue("gift_id").jsonPrimitive.content.toLong()
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'gift' AND entity_id = '$id' AND action = 'create' AND reason = '$why'"))
        assertEquals("true", scalar("SELECT (status = 'active')::text FROM dw.v_gift WHERE gift_id = $id"))
        assertEquals("false", scalar("SELECT is_effective::text FROM dw.v_gift WHERE gift_id = $id"), "a gift of a draft programme is not effective")
        // Replay: the first result, no second row; the same uuid with other content, and a taken code, are conflicts.
        createGift(super1, body).also { it.expect(HttpStatusCode.Created); assertEquals(id.toString(), it.obj().getValue("gift_id").jsonPrimitive.content) }
        assertEquals(1, count("SELECT count(*) FROM app.gift WHERE programme_id = $prog AND code = '$code'"))
        createGift(super1, giftBody(prog, code, request, ""","status":"inactive"""")).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        createGift(super1, giftBody(prog, code)).expect(HttpStatusCode.Conflict, "ERR_MASTER_DUPLICATE_CODE", "body.code")
        // Refusals.
        createGift(super1, giftBody(prog).replace("\"points_cost\":200", "\"points_cost\":0")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.points_cost")
        createGift(super1, giftBody(prog).replace("\"points_cost\":200", "\"points_cost\":100001")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.points_cost")
        createGift(super1, giftBody(prog).replace("\"points_cost\":200", "\"points_cost\":1.5")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.points_cost")   // integer points, no float
        createGift(super1, giftBody(prog).replace("\"tier_codes\":[]", "\"tier_codes\":[\"platinum\"]")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.tier_codes[0]")
        createGift(super1, giftBody(prog).replace("\"tier_codes\":[]", "\"tier_codes\":[\"gold\"]")).expect(HttpStatusCode.Created)
        createGift(super1, giftBody(prog, "Bad Code")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.code")
        createGift(super1, giftBody(prog, extra = ""","image_asset_id":"${uuid()}"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.image_asset_id")
        createGift(super1, giftBody(prog, extra = ""","bogus":1""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.bogus")
        createGift(super1, giftBody(999999)).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.programme_id")
        createGift(super1, giftBody(prog).replace("\"change_reason\":\"$why\"", "\"change_reason\":\"short\"")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.change_reason")
        // An ended programme takes no new gifts.
        activate(prog).expect(HttpStatusCode.OK)
        call(HttpMethod.Post, "/v1/admin/programmes/$prog/status", super1, """{"request_uuid":"${uuid()}","to_status":"inactive","reason":"$why"}""").expect(HttpStatusCode.OK)
        createGift(super1, giftBody(prog)).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
    }

    @Test
    fun giftUpdateGetListWithPreconditionNoChangeAndFirstResultReplay() = app {
        val prog = createId(createBody())
        val gid = createGift(super1, giftBody(prog)).obj().getValue("gift_id").jsonPrimitive.content.toLong()
        val v = scalar("SELECT version FROM app.gift WHERE id = $gid").toInt()
        fun patch(m: String, req: String = uuid()) = """{"request_uuid":"$req",$m"change_reason":"$why"}"""
        val req = uuid()
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", adminW, patch(""""points_cost":300,""", req), v).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""points_cost":300,""", req), v + 4).expect(HttpStatusCode.PreconditionFailed, "ERR_PRECONDITION_FAILED")
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""points_cost":300,""", req)).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "header.If-Match")
        val ok = call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""points_cost":300,"name_en":"New name",""", req), v); ok.expect(HttpStatusCode.OK)
        assertEquals("\"${v + 1}\"", ok.headers["ETag"])
        assertEquals(300, ok.obj().getValue("points_cost").jsonPrimitive.content.toInt())
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'gift' AND entity_id = '$gid' AND action = 'update'"))
        // A later change, then the first call replayed (stale ETag): the FIRST result again, no new journal row.
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""status":"inactive","""), v + 1).expect(HttpStatusCode.OK)
        val replay = call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""points_cost":300,"name_en":"New name",""", req), v)
        replay.expect(HttpStatusCode.OK)
        assertEquals("active", replay.obj().getValue("status").jsonPrimitive.content)
        assertEquals("\"${v + 1}\"", replay.headers["ETag"])
        assertEquals(2, count("SELECT count(*) FROM app.gift_change WHERE gift_id = $gid AND change_kind = 'update'"))
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""points_cost":999,""", req), v + 2).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        // No change, immutable members, empty patch, missing gift.
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""points_cost":300,"""), v + 2).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body")
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""code":"other_code","points_cost":5,"""), v + 2).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.code")
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""""programme_id":$prog,"points_cost":5,"""), v + 2).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.programme_id")
        call(HttpMethod.Patch, "/v1/admin/gifts/$gid", super1, patch(""), v + 2).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body")
        call(HttpMethod.Patch, "/v1/admin/gifts/999999", super1, patch(""""points_cost":5,"""), 1).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        // Reads.
        call(HttpMethod.Get, "/v1/admin/gifts/$gid", adminR).also { it.expect(HttpStatusCode.OK); assertEquals("\"${v + 2}\"", it.headers["ETag"]) }
        call(HttpMethod.Get, "/v1/admin/gifts/abc", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "path.id")
        call(HttpMethod.Get, "/v1/admin/gifts/999999", adminR).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        assertEquals(listOf(gid), call(HttpMethod.Get, "/v1/admin/gifts?programme_id=$prog&status=inactive", adminR).obj().getValue("items").jsonArray.map { it.jsonObject.getValue("gift_id").jsonPrimitive.content.toLong() })
        call(HttpMethod.Get, "/v1/admin/gifts?status=draft", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.status")
        call(HttpMethod.Get, "/v1/admin/gifts?programme_id=0", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.programme_id")
        call(HttpMethod.Get, "/v1/admin/gifts", adminNone).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
    }

    @Test
    fun aGiftPictureIsAnUploadedGiftImageAssetResolvedToAReadUrl() = app {
        val prog = createId(createBody())
        val img = uuid(); val other = uuid()
        sql("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES ('$img', 'gift_image', 'image/png', 1000, decode(repeat('ab', 32), 'hex'), 'gifts/$img.png')")
        sql("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES ('$other', 'sku_image', 'image/png', 1000, decode(repeat('cd', 32), 'hex'), 'skus/$other.png')")
        createGift(super1, giftBody(prog, extra = ",\"image_asset_id\":\"$other\"")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.image_asset_id")   // wrong purpose
        val g = createGift(super1, giftBody(prog, extra = ",\"image_asset_id\":\"$img\"")); g.expect(HttpStatusCode.Created)
        assertTrue(g.obj().getValue("image_url").jsonPrimitive.content.startsWith("https://blob.test/r/gifts/$img.png?"), "the answer carries a freshly resolved URL")
        val id = g.obj().getValue("gift_id").jsonPrimitive.content.toLong()
        val v = scalar("SELECT version FROM app.gift WHERE id = $id").toInt()
        val cleared = call(HttpMethod.Patch, "/v1/admin/gifts/$id", super1, "{\"request_uuid\":\"${uuid()}\",\"image_asset_id\":null,\"change_reason\":\"$why\"}", v)
        cleared.expect(HttpStatusCode.OK)
        assertTrue(cleared.obj().getValue("image_url") is kotlinx.serialization.json.JsonNull)
        call(HttpMethod.Patch, "/v1/admin/gifts/$id", super1, "{\"request_uuid\":\"${uuid()}\",\"image_asset_id\":\"$img\",\"change_reason\":\"$why\"}", v + 1).expect(HttpStatusCode.OK)
        assertEquals(img, scalar("SELECT image_asset_id::text FROM app.gift WHERE id = $id"))
        // The house rule: a signed read URL (a time-limited secret) is never stored in an append-only row. The journal keeps the asset id; the URL is resolved on every answer, replays included.
        val leaked = count("SELECT count(*) FROM app.gift_change WHERE gift_id = $id AND (snapshot::text LIKE '%SECRETSIG%' OR snapshot::text LIKE '%sig=%' OR snapshot::text LIKE '%blob.test%')")
        assertEquals(0, leaked, "app.gift_change.snapshot keeps a signed URL")
        assertEquals(0, count("SELECT count(*) FROM app.audit_log WHERE entity = 'gift' AND entity_id = '$id' AND (after::text LIKE '%SECRETSIG%' OR before::text LIKE '%SECRETSIG%')"), "audit_log keeps a signed URL")
        assertEquals(3, count("SELECT count(*) FROM app.gift_change WHERE gift_id = $id AND snapshot ->> 'image_asset_id' = '$img'") + count("SELECT count(*) FROM app.gift_change WHERE gift_id = $id AND snapshot -> 'image_asset_id' = 'null'::jsonb"), "the snapshot keeps image_asset_id")
        val againReq = uuid()
        val first = call(HttpMethod.Patch, "/v1/admin/gifts/$id", super1, "{\"request_uuid\":\"$againReq\",\"points_cost\":321,\"change_reason\":\"$why\"}", v + 2); first.expect(HttpStatusCode.OK)
        val replay = call(HttpMethod.Patch, "/v1/admin/gifts/$id", super1, "{\"request_uuid\":\"$againReq\",\"points_cost\":321,\"change_reason\":\"$why\"}", v + 2); replay.expect(HttpStatusCode.OK)
        assertTrue(replay.obj().getValue("image_url").jsonPrimitive.content.contains("sig=SECRETSIG"), "a replay resolves the URL again from the asset")
        assertEquals(0, count("SELECT count(*) FROM app.gift_change WHERE gift_id = $id AND snapshot::text LIKE '%SECRETSIG%'"))
    }

    // ------------------------------------------------------------------------------------------------ review fixes (Opus pass on 17be785bd)

    @Test
    fun versionsAreCutByTheirOwnScopeAndTheProgrammeBySeenThroughItsCurrentVersion() = app {
        // P1: v1 = brand 2 / zone B, v2 (current) = brand 1 / zone A.  P2: v1 = brand 1 / zone A, v2 (current) = brand 2 / zone B.
        suspend fun twoVersions(first: Pair<Long, Long>, second: Pair<Long, Long>): Long {
            val id = createId(createBody(qualifying = "[${q("brand", first.first)}]", scope = "[${s("zone", first.second)}]"))
            call(HttpMethod.Patch, "/v1/admin/programmes/$id", super1, patchBody(""""qualifying":[${q("brand", second.first)}],"scope":[${s("zone", second.second)}],"""), rowVersion(id)).expect(HttpStatusCode.OK)
            return id
        }
        val p1 = twoVersions(fx["b2"]!! to fx["zoneB"]!!, fx["b1"]!! to fx["zoneA"]!!)
        val p2 = twoVersions(fx["b1"]!! to fx["zoneA"]!!, fx["b2"]!! to fx["zoneB"]!!)
        suspend fun versionsOf(token: String, id: Long): List<Int>? = call(HttpMethod.Get, "/v1/admin/programmes/$id/versions", token).let {
            if (it.status == HttpStatusCode.NotFound) null else { it.expect(HttpStatusCode.OK); it.obj().getValue("items").jsonArray.map { v -> v.jsonObject.getValue("version_no").jsonPrimitive.content.toInt() } }
        }
        // SUPERADMIN, ADMIN and NATIONAL see every version of both.
        for (t in listOf(super1, adminR, adminW, nat)) { assertEquals(listOf(2, 1), versionsOf(t, p1)); assertEquals(listOf(2, 1), versionsOf(t, p2)) }
        // BRAND 1 and WM (zones A and O) and TSO 1 (zone A): P1 shows only its in-reach v2, P2 is invisible (its current version is out of reach: 404).
        for (t in listOf(brand1, tok("lwm1", Role.WM), tok("ltso1", Role.TSO))) { assertEquals(listOf(2), versionsOf(t, p1), "P1 v1 is out of reach"); assertEquals(null, versionsOf(t, p2)) }
        // DMO (zone B): the mirror image.
        val dmo = tok("ldmo1", Role.DMO)
        assertEquals(null, versionsOf(dmo, p1)); assertEquals(listOf(2), versionsOf(dmo, p2))
        // A TSO whose reach is route B only sees neither; one with no reach neither (but brand-free place rows narrow only geography, so both are 404 here).
        for (u in listOf("ltso2", "ltso3")) { assertEquals(null, versionsOf(tok(u, Role.TSO), p1)); assertEquals(null, versionsOf(tok(u, Role.TSO), p2)) }
        // The cursor pages what the caller may see only.
        val page = call(HttpMethod.Get, "/v1/admin/programmes/$p1/versions?limit=1", brand1).obj()
        assertEquals(listOf(2), page.getValue("items").jsonArray.map { it.jsonObject.getValue("version_no").jsonPrimitive.content.toInt() })
        assertTrue(page.getValue("next_cursor") is kotlinx.serialization.json.JsonNull, "no hidden version is hinted at by a cursor")
    }

    @Test
    fun theSameRequestUuidRacedByTwoThreadsMakesOneRowAndTheSecondIsAReplay() = app {
        val request = uuid(); val code = code()
        val body = createBody(code, request)
        val both = kotlinx.coroutines.coroutineScope {
            listOf(async(kotlinx.coroutines.Dispatchers.IO) { create(super1, body) }, async(kotlinx.coroutines.Dispatchers.IO) { create(super1, body) }).awaitAll()
        }
        both.forEach { it.expect(HttpStatusCode.Created) }
        assertEquals(1, both.map { it.obj().getValue("programme_id").jsonPrimitive.content }.toSet().size, "one programme answered twice")
        assertEquals(1, count("SELECT count(*) FROM app.programme WHERE code = '$code'"))
        assertEquals(1, count("SELECT count(*) FROM app.programme_version WHERE request_uuid = '$request'"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log a JOIN app.programme p ON p.id::text = a.entity_id WHERE a.entity = 'programme' AND a.action = 'create' AND p.code = '$code'"))
        // The same race on a status move: one trail row.
        val id = both[0].obj().getValue("programme_id").jsonPrimitive.content.toLong(); val move = uuid()
        val moves = kotlinx.coroutines.coroutineScope { listOf(async(kotlinx.coroutines.Dispatchers.IO) { activate(id, super1, move) }, async(kotlinx.coroutines.Dispatchers.IO) { activate(id, super1, move) }).awaitAll() }
        moves.forEach { it.expect(HttpStatusCode.OK) }
        assertEquals(1, count("SELECT count(*) FROM app.programme_status_change WHERE request_uuid = '$move'"))
    }

    @Test
    fun theSameBatchUuidRacedByTwoThreadsEnrolsOnceAndTheSecondIsAReplay() = app {
        val prog = createId(createBody())
        val batch = uuid()
        val body = enrolBody(prog, batch, enrolRow(prog, fx["L-O1"]!!, null, "100") + "," + enrolRow(prog, fx["L-O2"]!!))
        val both = kotlinx.coroutines.coroutineScope {
            listOf(async(kotlinx.coroutines.Dispatchers.IO) { call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, body) },
                async(kotlinx.coroutines.Dispatchers.IO) { call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, body) }).awaitAll()
        }
        both.forEach { it.expect(HttpStatusCode.OK) }
        assertEquals(listOf(2, 2), both.map { it.obj().getValue("inserted").jsonPrimitive.content.toInt() }, "both answers carry the first counts")
        assertEquals(2, count("SELECT count(*) FROM app.programme_enrolment WHERE programme_id = $prog"))
        assertEquals(1, count("SELECT count(*) FROM app.programme_enrolment_batch WHERE batch_uuid = '$batch'"))
    }

    @Test
    fun aProgrammeMadeBeforeThePortalHasNoVersionAndCannotBeChangedOrActivatedThroughIt() = app {
        sql("INSERT INTO app.programme (kind, code, name_en, active_from, active_to) VALUES ('astha', 'LEGACY-NOVER', 'Legacy', CURRENT_DATE, CURRENT_DATE + 29)")
        val id = scalar("SELECT id FROM app.programme WHERE code = 'LEGACY-NOVER'").toLong()
        val read = call(HttpMethod.Get, "/v1/admin/programmes/$id", adminR); read.expect(HttpStatusCode.OK)
        assertTrue("programme_version_id" !in read.obj() && "tiers" !in read.obj(), "the optional version members are absent, not null")
        assertEquals(emptyList(), call(HttpMethod.Get, "/v1/admin/programmes/$id/versions", adminR).obj().getValue("items").jsonArray.toList())
        call(HttpMethod.Patch, "/v1/admin/programmes/$id", adminW, patchBody(""""name_en":"Renamed","""), rowVersion(id)).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        activate(id).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        assertEquals("draft", scalar("SELECT status FROM app.programme WHERE id = $id"))
        assertEquals(0, count("SELECT count(*) FROM app.programme_version WHERE programme_id = $id"))
    }

    @Test
    fun aPhoneTokenIsRefusedWithTheExactCodeOnEveryOperation() = app {
        val prog = createId(createBody())
        val phone = tok("ltso1", Role.TSO, flavour = "tso")
        val calls = listOf(HttpMethod.Get to "/v1/admin/programmes", HttpMethod.Get to "/v1/admin/programmes/$prog", HttpMethod.Post to "/v1/admin/programmes", HttpMethod.Get to "/v1/admin/gifts", HttpMethod.Get to "/v1/admin/programmes/$prog/enrolments")
        for ((method, path) in calls) {
            // Without the device proof the guard stops it: 401 ERR_DEVICE_PROOF_INVALID.
            call(method, path, phone, if (method == HttpMethod.Post) createBody() else null).expect(HttpStatusCode.Unauthorized, "ERR_DEVICE_PROOF_INVALID")
            // With a matching device proof the portal handler itself refuses it: 403 ERR_FORBIDDEN (a phone token is never a portal caller).
            val withProof = client.request(path) {
                this.method = method; bearerAuth(phone); header("X-Device-Id", phoneDevice)
                if (method == HttpMethod.Post) { contentType(io.ktor.http.ContentType.Application.Json); setBody(createBody()) }
            }
            withProof.expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        }
    }

    // ------------------------------------------------------------------------------------------------ enrolments

    private fun enrolBody(programme: Long, batch: String = uuid(), rows: String) = """{"batch_uuid":"$batch","rows":[$rows],"change_reason":"$why"}"""
    private fun enrolRow(programme: Long, outlet: Long, tier: String? = null, target: String? = null, from: String = this.from, to: String? = null) =
        """{"programme_id":$programme,"outlet_id":$outlet,"tier_code":${tier?.let { "\"$it\"" } ?: "null"},"base_target":${target?.let { "\"$it\"" } ?: "null"},"valid_from":"$from","valid_to":${to?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun enrolmentsUpsertIdempotentlyByBatchWithCountsAndRefusals() = app {
        val prog = createId(createBody(tiers = tiers2))
        val o1 = fx["L-O1"]!!; val o2 = fx["L-O2"]!!; val o3 = fx["L-O3"]!!
        val batch = uuid()
        val body = enrolBody(prog, batch, enrolRow(prog, o1, "silver", "500") + "," + enrolRow(prog, o2, "gold", null))
        call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminR, body).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        val r = call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, body); r.expect(HttpStatusCode.OK)
        assertEquals(listOf(2, 0, 0), listOf("inserted", "updated", "unchanged").map { r.obj().getValue(it).jsonPrimitive.content.toInt() })
        assertEquals("500.000", scalar("SELECT base_target::text FROM app.programme_enrolment WHERE programme_id = $prog AND outlet_id = $o1"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'programme' AND entity_id = '$prog' AND action = 'enrol'"))
        // Replay: the first counts, nothing written twice.
        call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, body).also { it.expect(HttpStatusCode.OK); assertEquals(2, it.obj().getValue("inserted").jsonPrimitive.content.toInt()) }
        assertEquals(2, count("SELECT count(*) FROM app.programme_enrolment WHERE programme_id = $prog"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'programme' AND entity_id = '$prog' AND action = 'enrol'"))
        // The same batch uuid with other rows is a conflict.
        call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, enrolBody(prog, batch, enrolRow(prog, o3, "silver"))).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        // A new batch: one row unchanged, one updated (tier and target), one inserted.
        val r2 = call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, enrolBody(prog, rows = enrolRow(prog, o1, "silver", "500") + "," + enrolRow(prog, o2, "silver", "250.5") + "," + enrolRow(prog, o3, "gold")))
        r2.expect(HttpStatusCode.OK)
        assertEquals(listOf(1, 1, 1), listOf("inserted", "updated", "unchanged").map { r2.obj().getValue(it).jsonPrimitive.content.toInt() }.let { listOf(it[0], it[1], it[2]) })
        assertEquals("silver|250.500", scalar("SELECT tier_code || '|' || base_target FROM app.programme_enrolment WHERE programme_id = $prog AND outlet_id = $o2"))
        // Overlap with another enrolment of the same programme and outlet is a conflict and the whole batch rolls back.
        val before = count("SELECT count(*) FROM app.programme_enrolment WHERE programme_id = $prog")
        call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, enrolBody(prog, rows = enrolRow(prog, o3, "gold", from = scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date + 3)::text")) + "," + enrolRow(prog, fx["L-O1"]!!, "gold", from = scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date + 5)::text"))))
            .expect(HttpStatusCode.Conflict, "ERR_MASTER_OVERLAP")
        assertEquals(before, count("SELECT count(*) FROM app.programme_enrolment WHERE programme_id = $prog"), "all or nothing")
        // Refusals.
        suspend fun put(rows: String) = call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, enrolBody(prog, rows = rows))
        put(enrolRow(prog, o1, "platinum")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows[0].tier_code")
        put(enrolRow(prog, 999999)).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows[0].outlet_id")
        put(enrolRow(prog + 1000, o1)).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows[0].programme_id")
        put(enrolRow(prog, o1, target = "-1")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows[0].base_target")   // never negative
        put(enrolRow(prog, o1, target = "1.2345")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows[0].base_target")
        put(enrolRow(prog, o1, from = to, to = from)).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows[0].valid_to")
        put(enrolRow(prog, o1) + "," + enrolRow(prog, o1)).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows[1]")
        put("").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.rows")
        call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", adminW, """{"batch_uuid":"x","rows":[],"change_reason":"$why"}""").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.batch_uuid")
        call(HttpMethod.Put, "/v1/admin/programmes/999999/enrolments", adminW, enrolBody(999999, rows = enrolRow(999999, o1))).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        // An ended programme takes no new enrolments.
        activate(prog).expect(HttpStatusCode.OK)
        call(HttpMethod.Post, "/v1/admin/programmes/$prog/status", super1, """{"request_uuid":"${uuid()}","to_status":"inactive","reason":"$why"}""").expect(HttpStatusCode.OK)
        put(enrolRow(prog, o1, "gold", from = scalar("SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date + 40)::text"))).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
    }

    @Test
    fun enrolmentReadsAreCutToTheCallersReachAndNeverGoToBrand() = app {
        val prog = createId(createBody(tiers = tiers2))
        val o1 = fx["L-O1"]!!; val o2 = fx["L-O2"]!!; val o3 = fx["L-O3"]!!
        call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", super1, enrolBody(prog, rows = listOf(o1, o2, o3).joinToString(",") { enrolRow(prog, it, "silver") })).expect(HttpStatusCode.OK)
        suspend fun outlets(t: String, qs: String = ""): Set<Long> = call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments$qs", t).also { it.expect(HttpStatusCode.OK) }.obj().getValue("items").jsonArray.map { it.jsonObject.getValue("outlet_id").jsonPrimitive.content.toLong() }.toSet()
        assertEquals(setOf(o1, o2, o3), outlets(super1))
        assertEquals(setOf(o1, o2, o3), outlets(nat))
        assertEquals(setOf(o1, o2), outlets(tok("lwm1", Role.WM)))
        assertEquals(setOf(o3), outlets(tok("ldmo1", Role.DMO)))
        assertEquals(setOf(o1, o2), outlets(tok("ltso1", Role.TSO)))
        assertEquals(setOf(o3), outlets(tok("ltso2", Role.TSO)), "a route-only reach sees the outlets of its route")
        assertEquals(emptySet(), outlets(tok("ltso3", Role.TSO)))
        assertEquals(setOf(o1, o2), outlets(super1, "?zone_id=${fx["zoneA"]}"))
        call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments?zone_id=${fx["zoneB"]}", tok("ltso1", Role.TSO)).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments", brand1).expect(HttpStatusCode.Forbidden)     // outlet data: not for a BRAND user
        call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments", adminNone).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments?zone_id=0", super1).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.zone_id")
        call(HttpMethod.Get, "/v1/admin/programmes/999999/enrolments", super1).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        val p1 = call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments?limit=2", super1).obj()
        assertEquals(2, p1.getValue("items").jsonArray.size)
        val p2 = call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments?limit=2&cursor=${p1.getValue("next_cursor").jsonPrimitive.content}", super1).obj()
        assertEquals(1, p2.getValue("items").jsonArray.size)
        // An enrolment exposes the Decimal3 target as a string, never a float.
        assertEquals("silver", call(HttpMethod.Get, "/v1/admin/programmes/$prog/enrolments", super1).obj().getValue("items").jsonArray[0].jsonObject.getValue("tier_code").jsonPrimitive.content)
    }

    // ------------------------------------------------------------------------------------------------ scope matrix

    @Test
    fun theScopeMatrixOverTheSevenPortalLevelsAndTheRefusedRoles() = app {
        fun m(code: String, qualifying: String = "[]", scope: String = "[]") = code to createBody(code = code, qualifying = qualifying, scope = scope)
        val fixtures = listOf(
            m("X-ALL"), m("X-ZA", scope = "[${s("zone", fx["zoneA"]!!)}]"), m("X-ZB", scope = "[${s("zone", fx["zoneB"]!!)}]"),
            m("X-ZMIX", scope = "[${s("zone", fx["zoneA"]!!)},${s("zone", fx["zoneB"]!!)}]"), m("X-TERO", scope = "[${s("territory", fx["terO"]!!)}]"),
            m("X-DIVB", scope = "[${s("division", fx["divB"]!!)}]"), m("X-WINGA", scope = "[${s("wing", fx["wingA"]!!)}]"), m("X-WINGB", scope = "[${s("wing", fx["wingB"]!!)}]"),
            m("X-ROUTEA", scope = "[${s("route", fx["routeA"]!!)}]"), m("X-ROUTEB", scope = "[${s("route", fx["routeB"]!!)}]"), m("X-SUBCH", scope = "[${s("sub_channel", fx["subCh"]!!)}]"),
            m("X-BR1", qualifying = "[${q("brand", fx["b1"]!!)}]"), m("X-BR2", qualifying = "[${q("sku", fx["s2"]!!)}]"),
            m("X-BRMIX", qualifying = "[${q("brand", fx["b1"]!!)},${q("variant", fx["v2"]!!)}]"), m("X-BRCAT", qualifying = "[${q("category", fx["cat"]!!)}]"),
            m("X-BRV1", qualifying = "[${q("variant", fx["v1"]!!)}]"), m("X-BRS1", qualifying = "[${q("sku", fx["s1"]!!)}]"),
        )
        val idOf = fixtures.associate { (code, body) -> code to createId(body) }
        val everyCode = idOf.keys
        suspend fun seen(token: String): Set<String> = call(HttpMethod.Get, "/v1/admin/programmes", token).also { it.expect(HttpStatusCode.OK) }.obj().getValue("items").jsonArray
            .map { it.jsonObject.getValue("code").jsonPrimitive.content }.filter { it in everyCode }.toSet()
        val noGeo = setOf("X-ALL", "X-SUBCH")
        val brandRows = setOf("X-BR1", "X-BR2", "X-BRMIX", "X-BRCAT", "X-BRV1", "X-BRS1")
        for (t in listOf(super1, adminR, adminW, nat)) assertEquals(everyCode, seen(t))
        val geoRows = everyCode - brandRows
        assertEquals(geoRows + setOf("X-BR1", "X-BRMIX", "X-BRCAT", "X-BRV1", "X-BRS1"), seen(brand1))
        assertEquals(emptySet(), seen(tok("lbrand2", Role.BRAND)), "a brand user with no brand grant sees nothing (fail closed)")
        assertEquals(everyCode - setOf("X-ZB", "X-DIVB", "X-WINGB", "X-ROUTEB"), seen(tok("lwm1", Role.WM)))
        assertEquals(everyCode - setOf("X-ZA", "X-TERO", "X-WINGA", "X-ROUTEA"), seen(tok("ldmo1", Role.DMO)))
        assertEquals(everyCode - setOf("X-ZB", "X-TERO", "X-DIVB", "X-WINGB", "X-ROUTEB"), seen(tok("ltso1", Role.TSO)))
        assertEquals(brandRows + noGeo + setOf("X-ROUTEB"), seen(tok("ltso2", Role.TSO)))
        assertEquals(brandRows + noGeo, seen(tok("ltso3", Role.TSO)))
        // A single read, the versions and the gifts outside the reach are 404 / absent, never 403 (existence is not revealed).
        val wm = tok("lwm1", Role.WM)
        call(HttpMethod.Get, "/v1/admin/programmes/${idOf["X-ZB"]}", wm).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/programmes/${idOf["X-ZB"]}/versions", wm).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/programmes/${idOf["X-ZB"]}/enrolments", wm).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/programmes/${idOf["X-ZA"]}", wm).expect(HttpStatusCode.OK)
        call(HttpMethod.Get, "/v1/admin/programmes/${idOf["X-BR2"]}", brand1).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/programmes/${idOf["X-BR1"]}/versions", brand1).expect(HttpStatusCode.OK)
        val gHidden = createGift(super1, giftBody(idOf.getValue("X-ZB"))).obj().getValue("gift_id").jsonPrimitive.content.toLong()
        val gVisible = createGift(super1, giftBody(idOf.getValue("X-ZA"))).obj().getValue("gift_id").jsonPrimitive.content.toLong()
        call(HttpMethod.Get, "/v1/admin/gifts/$gHidden", wm).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/gifts/$gVisible", wm).expect(HttpStatusCode.OK)
        val wmGifts = call(HttpMethod.Get, "/v1/admin/gifts", wm).obj().getValue("items").jsonArray.map { it.jsonObject.getValue("gift_id").jsonPrimitive.content.toLong() }
        assertTrue(gVisible in wmGifts && gHidden !in wmGifts)
        call(HttpMethod.Get, "/v1/admin/gifts/$gVisible", brand1).expect(HttpStatusCode.OK)    // a zone-only programme names no product: every brand reads it
    }

    @Test
    fun theRefusedRolesAndPhoneTokensNeverReachAnyOperation() = app {
        val prog = createId(createBody())
        val gid = createGift(super1, giftBody(prog)).obj().getValue("gift_id").jsonPrimitive.content
        val refused = listOf(tok("lamo1", Role.AMO), tok("lsup1", Role.SUPPORT), tok("lana1", Role.ANALYST))
        val calls = listOf(
            HttpMethod.Get to "/v1/admin/programmes", HttpMethod.Get to "/v1/admin/programmes/$prog", HttpMethod.Get to "/v1/admin/programmes/$prog/versions",
            HttpMethod.Get to "/v1/admin/programmes/$prog/enrolments", HttpMethod.Get to "/v1/admin/gifts", HttpMethod.Get to "/v1/admin/gifts/$gid",
        )
        for (t in refused) for ((method, path) in calls) call(method, path, t).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        // NATIONAL and the module-less ADMIN read per their rules and never write.
        call(HttpMethod.Post, "/v1/admin/gifts", nat, giftBody(prog)).expect(HttpStatusCode.Forbidden)
        call(HttpMethod.Put, "/v1/admin/programmes/$prog/enrolments", nat, enrolBody(prog, rows = enrolRow(prog, fx["L-O1"]!!))).expect(HttpStatusCode.Forbidden)
        call(HttpMethod.Post, "/v1/admin/programmes/$prog/status", nat, """{"request_uuid":"${uuid()}","to_status":"active","reason":"$why"}""").expect(HttpStatusCode.Forbidden)
    }
}

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Discount-programme portal API (lane portal-discount-api, train 2; D-DISC-API-04): /v1/admin/offers for the six operations of
 * contract 1.12.1. The writes (create, update, status) each have a happy path, 400, 403, 409 and replay case (update also 412 and 404, status
 * also 404); the three reads have a happy path, 400 on a bad query or path, 403 and 404 and no 409 or replay (they change nothing); the scope matrix runs the seven portal
 * levels (SUPERADMIN, ADMIN, NATIONAL, BRAND, WM, DMO, TSO) and the refused roles (SR, AMO, SUPPORT, ANALYST) through the real
 * guard (PortalLevels and AdminModules run exactly as in production).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminOffersTest {
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
        listOf("nat3001" to "NATIONAL", "brand3001" to "BRAND", "brand3002" to "BRAND", "wm3001" to "WM", "dmo3001" to "DMO", "tso3001" to "TSO", "tso3002" to "TSO", "tso3003" to "TSO",
            "amo3001" to "AMO", "support3001" to "SUPPORT", "analyst3001" to "ANALYST", "admin3001" to "ADMIN", "admin3002" to "ADMIN", "admin3003" to "ADMIN").forEach { (u, r) ->
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
        sql("INSERT INTO app.product_node (level, code, name) VALUES ('category', 'C-OFF', 'Offer category')")
        sql("INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'segment', id, 'S-OFF', 'Offer segment' FROM app.product_node WHERE code = 'C-OFF'")
        for (b in listOf("1", "2")) {
            sql("INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'brand', id, 'B-OFF$b', 'Offer brand $b' FROM app.product_node WHERE code = 'S-OFF'")
            sql("INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'variant', id, 'V-OFF$b', 'Offer variant $b' FROM app.product_node WHERE code = 'B-OFF$b'")
            sql("INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default, report_unit) SELECT 'SKU-OFF$b', id, 'cigarette', 'Sku $b', 'S$b', 'stick', ${if (b == "1") 20 else 10}, 'stick', 'stick' FROM app.product_node WHERE code = 'V-OFF$b'")
            fx["b$b"] = scalar("SELECT id FROM app.product_node WHERE code = 'B-OFF$b'").toLong()
            fx["v$b"] = scalar("SELECT id FROM app.product_node WHERE code = 'V-OFF$b'").toLong()
            fx["s$b"] = scalar("SELECT id FROM app.sku WHERE code = 'SKU-OFF$b'").toLong()
        }
        fx["cat"] = scalar("SELECT id FROM app.product_node WHERE code = 'C-OFF'").toLong()
        sql("INSERT INTO app.user_brand_scope (user_id, brand_id) VALUES (${ids.getValue("brand3001")}, ${fx["b1"]})")
        val date = java.time.LocalDate.of(2000, 1, 1)
        fun reach(user: String, role: Role, zones: Set<Long>, routes: Set<Long>) {
            reaches[ids.getValue(user)] = Reach(ids.getValue(user), role, date, false, zones, routes, false, emptyList())
        }
        reach("wm3001", Role.WM, setOf(fx["zoneA"]!!, fx["zoneO"]!!), emptySet())
        reach("dmo3001", Role.DMO, setOf(fx["zoneB"]!!), emptySet())
        reach("tso3001", Role.TSO, setOf(fx["zoneA"]!!), emptySet())
        reach("tso3002", Role.TSO, emptySet(), setOf(fx["routeB"]!!))
        reach("tso3003", Role.TSO, emptySet(), emptySet())
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
    private val adminW get() = tok("admin3001", Role.ADMIN, mapOf("discounts" to "w"))
    private val adminR get() = tok("admin3002", Role.ADMIN, mapOf("discounts" to "r"))
    private val adminNone get() = tok("admin3003", Role.ADMIN, mapOf("prices" to "w", "targets" to "w"))

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            installAronPlatform(PlatformContext(AronClock.SYSTEM, base, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), object : ScopeVersionLookup {
                override fun current(userId: Long): Long = 1
                override fun rolePermissions(role: String): Set<String> = emptySet()
                override fun portalGrantsLive(): Boolean = true
            }, base)
            val resolver = ReachResolver { userId, role, _, date -> reaches[userId] ?: Reach(userId, role, date, true, emptySet(), emptySet(), false, emptyList()) }
            routing { route("/v1") { adminOffersRoutes(AdminOffersDeps(env.fresh.db, resolver, guard)) } }
        }
        block()
    }

    private fun uuid() = UUID.randomUUID().toString()
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private fun code() = "OFF-" + seq.incrementAndGet() + "-" + uuid().take(4)
    private val why = "Approved in the discount committee"

    /** A create body; [extra] members are appended raw (JSON fragments). */
    private fun createBody(code: String = code(), request: String = uuid(), type: String = "pct_discount", discount: String = "\"discount_bp\":500", qualifying: String = "[]", scope: String = "[]",
                           validFrom: String = from, validTo: String = to, extra: String = "") =
        """{"request_uuid":"$request","code":"$code","group_code":"CAMP-1","offer_type":"$type","level":"line","title_en":"Title $code","valid_from":"$validFrom","valid_to":"$validTo",$discount,"qualifying":$qualifying,"scope":$scope,"stacking":"exclusive","change_reason":"$why"$extra}"""

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, token: String, body: String? = null, ifMatch: Int? = null) = send(method, path, token, body, ifMatch)

    private suspend fun ApplicationTestBuilder.create(token: String, body: String = createBody()): HttpResponse = call(HttpMethod.Post, "/v1/admin/offers", token, body)
    private suspend fun ApplicationTestBuilder.createId(body: String): Long {
        val r = create(super1, body)
        assertEquals(HttpStatusCode.Created, r.status, r.status.toString())
        return r.obj().getValue("id").jsonPrimitive.content.toLong()
    }

    private fun q(level: String, id: Long) = """{"product_level":"$level","product_id":$id}"""
    private fun s(type: String, id: Long) = """{"node_type":"$type","node_id":$id}"""

    private suspend fun HttpResponse.problem(): Pair<String, String> = obj().let { it.getValue("code").jsonPrimitive.content to (it["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("pointer")?.jsonPrimitive?.content ?: "") }
    private suspend fun HttpResponse.expect(status: HttpStatusCode, code: String? = null, pointer: String? = null) {
        val text = bodyTextSafe()
        assertEquals(status, this.status, text)
        if (code != null || pointer != null) { val (c, p) = problem(); if (code != null) assertEquals(code, c, text); if (pointer != null) assertEquals(pointer, p, text) }
    }
    private suspend fun HttpResponse.bodyTextSafe() = bodyAsText()

    // ------------------------------------------------------------------------------------------------ createOffer

    @Test
    fun createMakesADraftVersionOneWithItsAuditRowAndSkuUnits() = app {
        val code = code()
        val r = create(adminW, createBody(code, type = "free_qty", discount = """"reward_sku_id":${fx["s2"]},"reward_qty_base":10,"threshold_qty_base":100""", qualifying = "[${q("sku", fx["s1"]!!)}]", scope = "[${s("zone", fx["zoneA"]!!)}]"))
        r.expect(HttpStatusCode.Created)
        assertTrue(r.headers["ETag"]!!.matches(Regex("^\"[0-9]+\"$")))
        val o = r.obj()
        assertEquals("draft", o.getValue("status").jsonPrimitive.content)
        assertEquals(1, o.getValue("version_no").jsonPrimitive.content.toInt())
        assertEquals(code, o.getValue("code").jsonPrimitive.content)
        assertEquals(10, o.getValue("reward_qty_base").jsonPrimitive.content.toInt())
        // The pack size of the reward SKU (10) and of the qualifying SKU (20) travel with the offer for field-unit display.
        assertEquals(listOf(fx["s1"]!! to 20, fx["s2"]!! to 10), o.getValue("sku_units").jsonArray.map { it.jsonObject.getValue("sku_id").jsonPrimitive.content.toLong() to it.jsonObject.getValue("pack_factor").jsonPrimitive.content.toInt() })
        assertEquals(listOf("pack", "pack"), o.getValue("sku_units").jsonArray.map { it.jsonObject.getValue("field_unit").jsonPrimitive.content }, "cigarette SKUs are counted in packs (packets)")
        assertEquals(listOf("pack", "pack", "pack", "dozen", "piece"), listOf("cigarette", "bidi", "lighter", "match", "other").map { fieldUnitOf(it, "piece") })
        assertEquals("stick", fieldUnitOf("other", "stick"), "any other category keeps the SKU's own base unit")
        val id = o.getValue("id").jsonPrimitive.content
        assertEquals(1, count("SELECT count(*) FROM app.offer_version WHERE offer_id = $id"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'offer' AND entity_id = '$id' AND action = 'create' AND reason = '$why'"))
        assertEquals("draft", scalar("SELECT status FROM app.offer WHERE id = $id"))
        // GET shows the same offer; the dw view sees it as not effective (draft).
        call(HttpMethod.Get, "/v1/admin/offers/$id", super1).expect(HttpStatusCode.OK)
        assertEquals("f", scalar("SELECT is_effective FROM dw.v_offer WHERE offer_id = $id"))
    }

    @Test
    fun createRefusesWhatTheV0003ChecksAndTheV1LimitsRefuse() = app {
        val offersBefore = count("SELECT count(*) FROM app.offer")
        val bad = listOf(
            "missing request_uuid" to createBody().replace(Regex("\"request_uuid\":\"[^\"]+\","), "") to "body.request_uuid",
            "request_uuid not v4" to createBody(request = "123e4567-e89b-12d3-a456-426614174000") to "body.request_uuid",
            "code pattern" to createBody(code = "-bad code") to "body.code",
            "valid_to before valid_from" to createBody(validFrom = to, validTo = from) to "body.valid_to",
            "pct without discount_bp" to createBody(discount = """"threshold_qty_base":5""") to "body.discount_bp",
            "pct with a money rate" to createBody(discount = """"discount_bp":500,"discount_mtk_per_unit":100""") to "body.discount_mtk_per_unit",
            "amount without a rate" to createBody(type = "amount_per_unit", discount = """"threshold_qty_base":5""") to "body.discount_mtk_per_unit",
            "amount with bp" to createBody(type = "amount_per_unit", discount = """"discount_mtk_per_unit":100,"discount_bp":5""") to "body.discount_bp",
            "free_qty without a reward" to createBody(type = "free_qty", discount = """"threshold_qty_base":5""") to "body.reward_sku_id",
            "free_qty without a threshold" to createBody(type = "free_qty", discount = """"reward_sku_id":${fx["s1"]},"reward_qty_base":1""") to "body.threshold_qty_base",
            "drp_slide is not offered in v1" to createBody(type = "drp_slide", discount = """"reward_sku_id":${fx["s1"]},"reward_qty_base":1,"threshold_qty_base":5""") to "body.offer_type",
            "bp above 10000" to createBody(discount = """"discount_bp":10001""") to "body.discount_bp",
            "money as a string" to createBody(type = "amount_per_unit", discount = """"discount_mtk_per_unit":"500"""") to "body.discount_mtk_per_unit",
            "money as a float" to createBody(type = "amount_per_unit", discount = """"discount_mtk_per_unit":500.5""") to "body.discount_mtk_per_unit",
            "money above the overflow guard" to createBody(type = "amount_per_unit", discount = """"discount_mtk_per_unit":1000000000001""") to "body.discount_mtk_per_unit",
            "unknown reward sku" to createBody(type = "free_qty", discount = """"reward_sku_id":999999,"reward_qty_base":1,"threshold_qty_base":5""") to "body.reward_sku_id",
            "unknown product" to createBody(qualifying = "[${q("brand", 999999)}]") to "body.qualifying[0]",
            "a variant id given as a brand" to createBody(qualifying = "[${q("brand", fx["v1"]!!)}]") to "body.qualifying[0]",
            "duplicate qualifying row" to createBody(qualifying = "[${q("sku", fx["s1"]!!)},${q("sku", fx["s1"]!!)}]") to "body.qualifying[1]",
            "unknown zone" to createBody(scope = "[${s("zone", 999999)}]") to "body.scope[0]",
            "channel nodes have no identity yet" to createBody(scope = "[${s("channel", 1)}]") to "body.scope",
            "short reason" to createBody().replace(why, "too short") to "body.change_reason",
            "unknown member" to createBody(extra = ",\"status\":\"active\"") to "body.status",
            "title too long" to createBody().replace("\"title_en\":\"Title", "\"title_en\":\"" + "x".repeat(201) + "Title") to "body.title_en",
        )
        for ((labelAndBody, pointer) in bad) {
            val (label, payload) = labelAndBody
            create(super1, payload).also { println("400 case: $label") }.expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", pointer)
        }
        assertEquals(offersBefore, count("SELECT count(*) FROM app.offer"), "no refused create left a row")
    }

    @Test
    fun createReplaysTheFirstResultAndRefusesAReusedUuidOrCode() = app {
        val request = uuid(); val code = code()
        val body = createBody(code, request)
        val first = create(super1, body); first.expect(HttpStatusCode.Created)
        val firstObj = first.obj()
        val offers = count("SELECT count(*) FROM app.offer"); val versions = count("SELECT count(*) FROM app.offer_version"); val audits = count("SELECT count(*) FROM app.audit_log WHERE entity = 'offer'")
        val again = create(super1, body); again.expect(HttpStatusCode.Created)
        assertEquals(firstObj.getValue("id"), again.obj().getValue("id"))
        assertEquals(firstObj.getValue("offer_version_id"), again.obj().getValue("offer_version_id"))
        assertEquals(listOf(offers, versions, audits), listOf(count("SELECT count(*) FROM app.offer"), count("SELECT count(*) FROM app.offer_version"), count("SELECT count(*) FROM app.audit_log WHERE entity = 'offer'")), "a replay writes nothing")
        // The same request_uuid with other content is a conflict; so is a new request on an existing code.
        create(super1, createBody(code, request, discount = """"discount_bp":600""")).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        create(super1, createBody(code(), request)).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        create(super1, createBody(code, uuid())).expect(HttpStatusCode.Conflict, "ERR_MASTER_DUPLICATE_CODE")
        assertEquals(offers, count("SELECT count(*) FROM app.offer"))
        // A request_uuid that made a status move cannot be reused for a create, and the reverse.
        val moved = createId(createBody()); val statusUuid = uuid()
        call(HttpMethod.Post, "/v1/admin/offers/$moved/status", super1, """{"request_uuid":"$statusUuid","to_status":"active","reason":"$why"}""").expect(HttpStatusCode.OK)
        create(super1, createBody(code(), statusUuid)).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        val other = createId(createBody())
        call(HttpMethod.Post, "/v1/admin/offers/$other/status", super1, """{"request_uuid":"$request","to_status":"active","reason":"$why"}""").expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        assertEquals("draft", scalar("SELECT status FROM app.offer WHERE id = $other"))
        // The same for an update: a PATCH with the uuid of a status move is a conflict and writes no version.
        val draft2 = createId(createBody()); val versionsBefore = count("SELECT count(*) FROM app.offer_version WHERE offer_id = $draft2")
        call(HttpMethod.Patch, "/v1/admin/offers/$draft2", super1, """{"request_uuid":"$statusUuid","title_en":"Reused","change_reason":"$why"}""", scalar("SELECT version FROM app.offer WHERE id = $draft2").toInt()).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        assertEquals(versionsBefore, count("SELECT count(*) FROM app.offer_version WHERE offer_id = $draft2"))
    }

    // ------------------------------------------------------------------------------------------------ updateOffer

    @Test
    fun patchAppendsAVersionWhileDraftAndKeepsTheOldVersionIntact() = app {
        val id = createId(createBody(qualifying = "[${q("brand", fx["b1"]!!)}]"))
        val v1 = scalar("SELECT current_version_id FROM app.offer WHERE id = $id")
        val etag = scalar("SELECT version FROM app.offer WHERE id = $id").toInt()
        val request = uuid()
        val patch = """{"request_uuid":"$request","title_en":"Renamed","discount_bp":750,"scope":[${s("zone", fx["zoneA"]!!)}],"change_reason":"Committee raised the rate"}"""
        val r = call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, patch, etag); r.expect(HttpStatusCode.OK)
        val o = r.obj()
        assertEquals(2, o.getValue("version_no").jsonPrimitive.content.toInt())
        assertEquals("Renamed", o.getValue("title_en").jsonPrimitive.content)
        assertEquals(750, o.getValue("discount_bp").jsonPrimitive.content.toInt())
        assertEquals(1, o.getValue("qualifying").jsonArray.size, "members not in the patch keep their value")
        assertNotEquals(etag.toString(), r.headers["ETag"]!!.trim('"'))
        // The first version is exactly as it was.
        assertEquals("500|Title", scalar("SELECT discount_bp || '|' || left(title_en, 5) FROM app.offer_version WHERE id = $v1"))
        assertEquals(2, count("SELECT count(*) FROM app.offer_version WHERE offer_id = $id"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'offer' AND entity_id = '$id' AND action = 'new_version'"))
        // versions: newest first, the current one marked.
        val versions = call(HttpMethod.Get, "/v1/admin/offers/$id/versions", adminW).also { it.expect(HttpStatusCode.OK) }.obj().getValue("items").jsonArray
        assertEquals(listOf(2, 1), versions.map { it.jsonObject.getValue("version_no").jsonPrimitive.content.toInt() })
        assertEquals(listOf(true, false), versions.map { it.jsonObject.getValue("is_current").jsonPrimitive.content.toBoolean() })
        // Replay (the If-Match is stale now: a replay is answered before it is judged) and a reused uuid with other content.
        val replay = call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, patch, etag); replay.expect(HttpStatusCode.OK)
        assertEquals(o.getValue("offer_version_id"), replay.obj().getValue("offer_version_id"))
        assertEquals(2, count("SELECT count(*) FROM app.offer_version WHERE offer_id = $id"))
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, patch.replace("750", "760"), etag).expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        // A type change needs the old rate cleared and the new one given.
        val now = scalar("SELECT version FROM app.offer WHERE id = $id").toInt()
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, """{"request_uuid":"${uuid()}","offer_type":"amount_per_unit","change_reason":"$why"}""", now).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.discount_mtk_per_unit")
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, """{"request_uuid":"${uuid()}","offer_type":"amount_per_unit","discount_bp":null,"discount_mtk_per_unit":4500,"change_reason":"$why"}""", now).expect(HttpStatusCode.OK)
    }

    @Test
    fun patchRefusalsStaleVersionActiveOfferStatusMemberNoChangeAndBadDates() = app {
        val id = createId(createBody())
        val etag = scalar("SELECT version FROM app.offer WHERE id = $id").toInt()
        fun body(members: String) = """{"request_uuid":"${uuid()}",$members"change_reason":"$why"}"""
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""""title_en":"A","""), null).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "header.If-Match")
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""""title_en":"A","""), etag + 5).expect(HttpStatusCode.PreconditionFailed, "ERR_PRECONDITION_FAILED")
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""""status":"active","title_en":"A","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.status")
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body")
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""""discount_bp":500,"""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body")
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""""valid_to":"${past}","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.valid_to")
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""""code":"NEW","title_en":"A","""), etag).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.code")
        call(HttpMethod.Patch, "/v1/admin/offers/999999", adminW, body(""""title_en":"A","""), 1).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        assertEquals(1, count("SELECT count(*) FROM app.offer_version WHERE offer_id = $id"), "nothing was written")
        // Once active, an offer is final.
        activate(id).expect(HttpStatusCode.OK)
        val now = scalar("SELECT version FROM app.offer WHERE id = $id").toInt()
        call(HttpMethod.Patch, "/v1/admin/offers/$id", adminW, body(""""title_en":"A","""), now).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
    }

    // ------------------------------------------------------------------------------------------------ setOfferStatus

    private suspend fun ApplicationTestBuilder.activate(id: Long, token: String = super1, request: String = uuid()) =
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", token, """{"request_uuid":"$request","to_status":"active","reason":"$why"}""")

    @Test
    fun statusMovesDraftToActiveBySuperadminAndActiveToEndedByAdminWithTheTrailAndReplay() = app {
        val id = createId(createBody())
        activate(id, adminW).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        // If-Match on the status call is optional; a stale one is 412 and moves nothing, the current one passes.
        val row = scalar("SELECT version FROM app.offer WHERE id = $id").toInt()
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", super1, """{"request_uuid":"${uuid()}","to_status":"active","reason":"$why"}""", row + 3).expect(HttpStatusCode.PreconditionFailed, "ERR_PRECONDITION_FAILED")
        assertEquals("draft", scalar("SELECT status FROM app.offer WHERE id = $id"))
        assertEquals("draft", scalar("SELECT status FROM app.offer WHERE id = $id"))
        val request = uuid()
        val r = activate(id, super1, request); r.expect(HttpStatusCode.OK)
        assertEquals("active", r.obj().getValue("status").jsonPrimitive.content)
        assertEquals("t", scalar("SELECT is_effective FROM dw.v_offer WHERE offer_id = $id"))
        assertEquals(1, count("SELECT count(*) FROM app.offer_status_change WHERE offer_id = $id AND from_status = 'draft' AND to_status = 'active' AND request_uuid = '$request' AND reason = '$why'"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'offer' AND entity_id = '$id' AND action = 'activate'"))
        // Replay: the same answer, no second row. The same uuid for another move is a conflict.
        activate(id, super1, request).expect(HttpStatusCode.OK)
        assertEquals(1, count("SELECT count(*) FROM app.offer_status_change WHERE offer_id = $id"))
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", super1, """{"request_uuid":"$request","to_status":"inactive","reason":"$why"}""").expect(HttpStatusCode.Conflict, "ERR_CONFLICT")
        // Activating again is a state conflict; ending is allowed to an ADMIN with the w grant, and a read-only ADMIN is refused.
        activate(id, super1).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        val end = """{"request_uuid":"${uuid()}","to_status":"inactive","reason":"Campaign finished early"}"""
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", adminR, end).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", adminW, end).expect(HttpStatusCode.OK)
        assertEquals("inactive", scalar("SELECT status FROM app.offer WHERE id = $id"))
        assertEquals("f", scalar("SELECT is_effective FROM dw.v_offer WHERE offer_id = $id"))
        // An ended offer cannot move again; a draft cannot be ended; nothing moves back to draft.
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", super1, end.replace(Regex("\"request_uuid\":\"[^\"]+\""), "\"request_uuid\":\"${uuid()}\"")).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        activate(id).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        val draft = createId(createBody())
        call(HttpMethod.Post, "/v1/admin/offers/$draft/status", super1, end.replace(Regex("\"request_uuid\":\"[^\"]+\""), "\"request_uuid\":\"${uuid()}\"")).expect(HttpStatusCode.Conflict, "ERR_REQUEST_STATE")
        call(HttpMethod.Post, "/v1/admin/offers/$draft/status", super1, """{"request_uuid":"${uuid()}","to_status":"draft","reason":"$why"}""").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.to_status")
    }

    @Test
    fun statusRefusalsBadBodyUnknownOfferAndAnOfferWhoseWindowHasPassed() = app {
        val id = createId(createBody())
        fun body(extra: String) = """{"request_uuid":"${uuid()}",$extra}"""
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", super1, body(""""to_status":"active","reason":"short"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.reason")
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", super1, body(""""to_status":"active"""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.reason")
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", super1, """{"to_status":"active","reason":"$why"}""").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.request_uuid")
        call(HttpMethod.Post, "/v1/admin/offers/$id/status", super1, body(""""to_status":"active","reason":"$why","x":1""")).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.x")
        call(HttpMethod.Post, "/v1/admin/offers/999999/status", super1, body(""""to_status":"active","reason":"$why"""")).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        val old = createId(createBody(validFrom = longAgo, validTo = past))
        activate(old).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "valid_to")
        assertEquals("draft", scalar("SELECT status FROM app.offer WHERE id = $old"))
        assertEquals(0, count("SELECT count(*) FROM app.offer_status_change WHERE offer_id = $old"))
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Test
    fun listFiltersPagesAndShowsTheCurrentVersion() = app {
        val a = createId(createBody(validFrom = from, validTo = to))
        val b = createId(createBody(validFrom = longAgo, validTo = past))
        activate(a).expect(HttpStatusCode.OK)
        val all = call(HttpMethod.Get, "/v1/admin/offers?limit=500", adminR).also { it.expect(HttpStatusCode.OK) }.obj().getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() }
        assertTrue(all.containsAll(listOf(a, b)) && all.indexOf(b) < all.indexOf(a), "newest first")
        val active = call(HttpMethod.Get, "/v1/admin/offers?status=active&limit=500", adminR).obj().getValue("items").jsonArray.map { it.jsonObject.getValue("status").jsonPrimitive.content }.toSet()
        assertEquals(setOf("active"), active)
        val onDate = call(HttpMethod.Get, "/v1/admin/offers?valid_on=$past&limit=500", adminR).obj().getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() }
        assertTrue(b in onDate && a !in onDate)
        val p1 = call(HttpMethod.Get, "/v1/admin/offers?limit=2", adminR).obj()
        assertEquals(2, p1.getValue("items").jsonArray.size)
        val p2 = call(HttpMethod.Get, "/v1/admin/offers?limit=2&cursor=${p1.getValue("next_cursor").jsonPrimitive.content}", adminR).obj()
        assertTrue(p2.getValue("items").jsonArray.all { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() < p1.getValue("items").jsonArray.last().jsonObject.getValue("id").jsonPrimitive.content.toLong() })
        call(HttpMethod.Get, "/v1/admin/offers?status=bogus", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.status")
        call(HttpMethod.Get, "/v1/admin/offers?limit=0", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.limit")
        call(HttpMethod.Get, "/v1/admin/offers/abc", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "path.id")
        call(HttpMethod.Get, "/v1/admin/offers/abc/versions", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "path.id")
        call(HttpMethod.Get, "/v1/admin/offers/$a/versions?limit=0", adminR).expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.limit")
        call(HttpMethod.Get, "/v1/admin/offers/999999", adminR).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/offers/999999/versions", adminR).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
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
        suspend fun seen(token: String): Set<String> = call(HttpMethod.Get, "/v1/admin/offers?limit=500", token).also { it.expect(HttpStatusCode.OK) }.obj().getValue("items").jsonArray
            .map { it.jsonObject.getValue("code").jsonPrimitive.content }.filter { it in everyCode }.toSet()
        val noGeo = setOf("X-ALL", "X-SUBCH")                       // empty scope, and a channel-only scope, narrow no geography
        val brandRows = setOf("X-BR1", "X-BR2", "X-BRMIX", "X-BRCAT", "X-BRV1", "X-BRS1")

        // SUPERADMIN, ADMIN (r) and NATIONAL read everything.
        for (t in listOf(super1, adminR, adminW, tok("nat3001", Role.NATIONAL))) assertEquals(everyCode, seen(t))
        // BRAND (brand 1): offers naming no product reach every brand; the rest only where a product is, or sits under, brand 1 (a category covers it; a variant or SKU of brand 2 does not).
        val geoRows = everyCode - brandRows
        assertEquals(geoRows + setOf("X-BR1", "X-BRMIX", "X-BRCAT", "X-BRV1", "X-BRS1"), seen(tok("brand3001", Role.BRAND)))
        assertEquals(emptySet(), seen(tok("brand3002", Role.BRAND)), "a brand user with no brand grant sees nothing (fail closed)")
        // WM (zones A and O), DMO (zone B), TSO by zone, by route only, and with no reach.
        assertEquals(everyCode - setOf("X-ZB", "X-DIVB", "X-WINGB", "X-ROUTEB"), seen(tok("wm3001", Role.WM)))
        assertEquals(everyCode - setOf("X-ZA", "X-TERO", "X-WINGA", "X-ROUTEA"), seen(tok("dmo3001", Role.DMO)))
        assertEquals(everyCode - setOf("X-ZB", "X-TERO", "X-DIVB", "X-WINGB", "X-ROUTEB"), seen(tok("tso3001", Role.TSO)))
        assertEquals(brandRows + noGeo + setOf("X-ROUTEB"), seen(tok("tso3002", Role.TSO)))
        assertEquals(brandRows + noGeo, seen(tok("tso3003", Role.TSO)))
        // A single read and the versions outside the reach are 404, never 403 (existence is not revealed).
        val wm = tok("wm3001", Role.WM)
        call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-ZB"]}", wm).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-ZB"]}/versions", wm).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-ZA"]}", wm).expect(HttpStatusCode.OK)
        call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-ZA"]}/versions", wm).expect(HttpStatusCode.OK)
        call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-BR2"]}", tok("brand3001", Role.BRAND)).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-BR1"]}/versions", tok("brand3001", Role.BRAND)).expect(HttpStatusCode.OK)
        // A narrowed later version hides itself from a reader whose reach it left, and the history lists only what is in reach.
        val zaId = idOf.getValue("X-ZA")
        val etag = scalar("SELECT version FROM app.offer WHERE id = $zaId").toInt()
        call(HttpMethod.Patch, "/v1/admin/offers/$zaId", super1, """{"request_uuid":"${uuid()}","scope":[${s("zone", fx["zoneB"]!!)}],"change_reason":"$why"}""", etag).expect(HttpStatusCode.OK)
        call(HttpMethod.Get, "/v1/admin/offers/$zaId", wm).expect(HttpStatusCode.NotFound, "ERR_NOT_FOUND")
        call(HttpMethod.Get, "/v1/admin/offers/$zaId", tok("dmo3001", Role.DMO)).expect(HttpStatusCode.OK)
        assertEquals(1, call(HttpMethod.Get, "/v1/admin/offers/$zaId/versions", tok("dmo3001", Role.DMO)).obj().getValue("items").jsonArray.size, "version 1 (zone A) is outside the DMO's reach")

        // Refused roles: SR, AMO, SUPPORT and ANALYST read nothing; a phone token of the TSO app is not a portal session.
        for ((user, role) in listOf("sr1001" to Role.SR, "amo3001" to Role.AMO, "support3001" to Role.SUPPORT, "analyst3001" to Role.ANALYST)) {
            call(HttpMethod.Get, "/v1/admin/offers", tok(user, role)).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
            call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-ALL"]}", tok(user, role)).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
            call(HttpMethod.Get, "/v1/admin/offers/${idOf["X-ALL"]}/versions", tok(user, role)).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        }
        // (The guard stops a phone token without its device proof before the handler; the handler also refuses any phone principal.)
        call(HttpMethod.Get, "/v1/admin/offers", tok("tso3001", Role.TSO, flavour = "tso")).expect(HttpStatusCode.Unauthorized, "ERR_DEVICE_PROOF_INVALID")
        // With the device header the token binds to, the call reaches the handler, which refuses a phone principal.
        client.request("/v1/admin/offers") { method = HttpMethod.Get; bearerAuth(tok("tso3001", Role.TSO, flavour = "tso")); header("X-Device-Id", phoneDevice) }.expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")

        // Writes: only SUPERADMIN and ADMIN with the w grant. Everyone else, every level, is refused and nothing is written.
        val before = count("SELECT count(*) FROM app.offer")
        val nonWriters = listOf(
            tok("nat3001", Role.NATIONAL), tok("brand3001", Role.BRAND), wm, tok("dmo3001", Role.DMO), tok("tso3001", Role.TSO), adminR, adminNone,
            tok("sr1001", Role.SR), tok("amo3001", Role.AMO), tok("support3001", Role.SUPPORT), tok("analyst3001", Role.ANALYST),
        )
        for (t in nonWriters) {
            create(t, createBody()).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
            call(HttpMethod.Patch, "/v1/admin/offers/${idOf["X-ALL"]}", t, """{"request_uuid":"${uuid()}","title_en":"x","change_reason":"$why"}""", 1).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
            call(HttpMethod.Post, "/v1/admin/offers/${idOf["X-ALL"]}/status", t, """{"request_uuid":"${uuid()}","to_status":"active","reason":"$why"}""").expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        }
        assertEquals(before, count("SELECT count(*) FROM app.offer"))
        assertEquals("draft", scalar("SELECT status FROM app.offer WHERE id = ${idOf["X-ALL"]}"))
        // An ADMIN without the discounts module cannot even read (the module moved off `targets` and `prices`).
        call(HttpMethod.Get, "/v1/admin/offers", adminNone).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        // An ADMIN token minted before the grants carry no `modules` claim is told to refresh.
        call(HttpMethod.Get, "/v1/admin/offers", tok("admin3001", Role.ADMIN)).expect(HttpStatusCode.Unauthorized, "ERR_SCOPE_CHANGED")
    }

    @Test
    fun theFieldIsUntouchedByAnActiveProgramme() = app {
        val id = createId(createBody())
        activate(id).expect(HttpStatusCode.OK)
        // The portal stores programmes; nothing in the memo, price or bundle tables is written by any of the six operations.
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE offer_discount_mtk <> 0"))
        assertEquals(0, count("SELECT count(*) FROM app.memo_discount"))
        assertEquals(0, count("SELECT count(*) FROM app.sku_price WHERE created_at > now() - interval '1 hour' AND publish_batch_uuid IS NOT NULL"))
    }
}

package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.platform.AsthaQuarter
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The field side of loyalty and Astha over HTTP through the production wiring and the db seed with a second division (LKX) beside the Mirpur one (lane
 * portal-loyalty-api, train 3, push B; contract 1.15.0): putAsthaTargets (idempotent by batch_uuid, all-or-nothing, never negative, indexed pointers, role and
 * territory scope, duplicate and out-of-order batches as a property test) and the three reads getLoyaltyBalances, listAsthaTargets and listGiftChoices (SR cut
 * to its own routes, TSO to its territory with an own-division positive control, a national reader must narrow, a route or zone outside the reach is 403).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoyaltyFieldRoutesTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private lateinit var lkxSr: TsoPhone
    private lateinit var amo: TsoPhone
    private lateinit var today: LocalDate
    private lateinit var quarter: AsthaQuarter
    private var routeMir = 0L
    private var routeLkx = 0L
    private var zoneLkx = 0L
    private var outletMir1 = 0L
    private var outletMir2 = 0L
    private var outletLkx = 0L
    private var brand = 0L
    private var category = 0L
    private var programme = 0L
    private val reason = "Quarter targets agreed by the regional heads"

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))
        with(world) {
            today = today()
            quarter = AsthaQuarter.of(today, 1)
            sr = phone("sr1001", "sr"); lkxSr = phone("lkxsr1001", "sr"); amo = phone("amo1001", "amo")
            routeMir = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            routeLkx = one("SELECT id FROM app.route WHERE code = 'LKX-SR-D'")
            zoneLkx = one("SELECT zone_id FROM app.route WHERE id = $routeLkx")
            val m = longs("SELECT id FROM app.outlet WHERE route_id = $routeMir AND status = 'active' ORDER BY id LIMIT 2")
            outletMir1 = m[0]; outletMir2 = m[1]
            outletLkx = one("SELECT id FROM app.outlet WHERE route_id = $routeLkx AND status = 'active' ORDER BY id LIMIT 1")
            val sku = one("SELECT id FROM app.sku WHERE entry_unit_default = 'pack' AND category_code = 'cigarette' ORDER BY id LIMIT 1")
            brand = one("SELECT b.id FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id WHERE s.id = $sku")
            category = one("SELECT id FROM app.product_node WHERE level = 'category' ORDER BY id LIMIT 1")
            programme = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'ASTHA-F', 'Astha field test', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            for (o in listOf(outletMir1, outletMir2, outletLkx))
                exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($programme, $o, 'gold', DATE '${today.minusDays(30)}')")
            // Live balance: +100 two days ago and +50 today for the first Mirpur outlet; the bundle would not show today's 50, the live read does.
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) VALUES ($outletMir1, $programme, 'adjustment', 'f1', 100, DATE '${today.minusDays(2)}', DATE '${today.plusDays(9)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date) VALUES ($outletMir1, $programme, 'adjustment', 'f2', 50, DATE '$today')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date) VALUES ($outletLkx, $programme, 'adjustment', 'f3', 7, DATE '${today.minusDays(1)}')")
            val first = quarter.months[2]   // seeded targets sit in the quarter's third month; the upload tests use the first two, so the two never collide
            for ((route, outlet) in listOf(routeMir to outletMir1, routeMir to outletMir2, routeLkx to outletLkx)) {
                exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target, value_target_mtk) VALUES ($programme, DATE '$first', $route, $outlet, $brand, 100, 0, 250000)")
                exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$first', $route, $outlet, NULL, 0, 6)")
            }
            exec("INSERT INTO app.gift (programme_id, code, name_en, tier_codes) VALUES ($programme, 'gift_f', 'Gift F', '{gold}')")
            for ((route, outlet) in listOf(routeMir to outletMir1, routeLkx to outletLkx))
                exec("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id, tier_code, chosen_at) SELECT $programme, '${quarter.label}', $outlet, $route, id, 'gold', now() FROM app.gift WHERE code = 'gift_f'")
        }
    }

    @AfterAll fun tearDown() = world.close()

    // ------------------------------------------------------------------------------------------------ plumbing

    private suspend fun HttpClient.putTargets(body: String, web: String? = null, phone: TsoPhone? = null): HttpResponse = with(world) {
        put("/v1/programmes/astha/targets") {
            if (phone != null) { bearerAuth(login(phone)); header("X-Device-Id", phone.deviceUuid) } else bearerAuth(web(web!!))
            header("X-App-Version", ApiWorld.APP_VERSION); contentType(ContentType.Application.Json); setBody(body)
        }
    }

    private fun uuid() = UUID.randomUUID().toString()
    private val month0 get() = quarter.months[0].toString().substring(0, 7)
    private val month1 get() = quarter.months[1].toString().substring(0, 7)
    private fun row(outlet: Long, month: String, std: String = "100", memo: Int = 6, value: String? = null, brandId: Long? = brand) =
        """{"outlet_id":$outlet,"brand_id":${brandId ?: "null"},"month":"$month","std_target":"$std","memo_target":$memo${value?.let { ""","value_target_mtk":"$it"""" } ?: ""}}"""
    private fun batch(rows: List<String>, id: String = uuid(), q: String = quarter.label, why: String? = reason) =
        """{"batch_uuid":"$id","quarter":"$q","rows":[${rows.joinToString(",")}]${why?.let { ""","change_reason":"$it"""" } ?: ""}}"""

    private suspend fun HttpResponse.problem(): Pair<String, String> = json(bodyAsText()).let { it["code"]!!.jsonPrimitive.content to (it["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("pointer")?.jsonPrimitive?.content ?: "") }
    private suspend fun HttpResponse.expect(status: HttpStatusCode, code: String? = null, pointer: String? = null) {
        val text = bodyAsText()
        assertEquals(status, this.status, text)
        if (code != null || pointer != null) { val (c, p) = problem(); if (code != null) assertEquals(code, c, text); if (pointer != null) assertEquals(pointer, p, text) }
    }
    private suspend fun HttpResponse.counts(): Triple<Int, Int, Int> = json(bodyAsText()).let { Triple(it["inserted"]!!.jsonPrimitive.content.toInt(), it["updated"]!!.jsonPrimitive.content.toInt(), it["unchanged"]!!.jsonPrimitive.content.toInt()) }

    private fun targetRows() = world.rows("SELECT outlet_id, brand_id, month::text AS month, std_target::text AS std, memo_target, value_target_mtk FROM app.astha_target WHERE batch_uuid IS NOT NULL ORDER BY outlet_id, brand_id NULLS FIRST, month")
    private fun batches() = world.one("SELECT count(*) FROM app.astha_target_batch")

    // ------------------------------------------------------------------------------------------------ putAsthaTargets

    @Test
    fun anUploadIsIdempotentByBatchUuidAnswersTheFirstCountsAndRefusesAReusedUuidWithOtherRows() = world.withApi {
        world.exec("DELETE FROM app.astha_target WHERE batch_uuid IS NOT NULL")
        val id = uuid()
        val rows = listOf(row(outletMir1, month0, "120.5", 4, "500000"), row(outletMir1, month1, "130", 5), row(outletMir2, month0, "0", 0, brandId = null))
        val first = putTargets(batch(rows, id), web = "superadmin1001")
        first.expect(HttpStatusCode.OK); assertEquals(Triple(3, 0, 0), first.counts())
        val saved = targetRows().filter { it["outlet_id"].toString() == outletMir1.toString() && it["month"].toString().startsWith(month0) }.single()
        assertEquals("120.500", saved["std"]); assertEquals(500000L, saved["value_target_mtk"])
        assertEquals(0L, (targetRows().single { it["brand_id"] == brand && it["month"].toString().startsWith(month1) })["value_target_mtk"], "an omitted value target is 0")
        val before = batches()
        val replay = putTargets(batch(rows.reversed(), id), web = "superadmin1001")        // same rows in another order: the same normalised batch
        replay.expect(HttpStatusCode.OK); assertEquals(Triple(3, 0, 0), replay.counts(), "a replay answers the first counts")
        assertEquals(before, batches(), "a replay writes no second ledger row"); assertEquals(3, targetRows().size, "and no second target row")
        putTargets(batch(listOf(row(outletMir1, month0, "999")), id), web = "superadmin1001").expect(HttpStatusCode.Conflict, "ERR_CONFLICT", "body.batch_uuid")
        // A new batch: one changed row, one identical, one new.
        val second = putTargets(batch(listOf(row(outletMir1, month0, "121", 4, "500000"), row(outletMir1, month1, "130", 5), row(outletMir2, month1, "10", 1)), uuid()), web = "superadmin1001")
        second.expect(HttpStatusCode.OK); assertEquals(Triple(1, 1, 1), second.counts())
        assertEquals("121.000", targetRows().single { it["outlet_id"] == outletMir1 && it["brand_id"] == brand && it["month"].toString().startsWith(month0) }["std"])
        assertEquals(1L, world.one("SELECT count(*) FROM app.audit_log WHERE entity = 'astha_target' AND action = 'upload' AND (after::jsonb ->> 'batch_uuid') = '$id'"), "one audit row per batch, replay included")
    }

    @Test
    fun badRowsAreRefusedWithIndexedPointersAndNothingIsStored() = world.withApi {
        val ok = row(outletMir1, month0)
        val tableBefore = world.one("SELECT count(*) FROM app.astha_target"); val ledgerBefore = batches()
        fun refuse(rows: List<String>, pointer: String, q: String = quarter.label, why: String? = reason, code: String = "ERR_VALIDATION") = Triple(batch(rows, q = q, why = why), pointer, code)
        val cases = listOf(
            refuse(listOf(ok, row(outletMir1, month0, std = "-1", brandId = null)), "body.rows[1].std_target"),                       // never negative: the -37,500% bug
            refuse(listOf(ok, ok.replace("\"memo_target\":6", "\"memo_target\":-3").replace("\"month\":\"$month0\"", "\"month\":\"$month1\"")), "body.rows[1].memo_target"),
            refuse(listOf(ok, row(outletMir1, month1, value = "5"), row(outletMir2, month0, value = "-5")), "body.rows[2].value_target_mtk"),
            refuse(listOf(ok, row(outletMir1, month1, value = "1.5")), "body.rows[1].value_target_mtk"),                                 // milli-taka are integers
            refuse(listOf(ok, row(outletMir1, month1, value = "007")), "body.rows[1].value_target_mtk"),
            refuse(listOf(ok, row(outletMir1, quarter.months[2].plusMonths(1).toString().substring(0, 7))), "body.rows[1].month"),     // outside the quarter
            refuse(listOf(ok, ok), "body.rows[1]"),                                                                                       // duplicate key in one batch
            refuse(listOf(ok, row(999_999_999L, month1)), "body.rows[1].outlet_id"),
            refuse(listOf(ok, row(outletMir1, month1, brandId = category)), "body.rows[1].brand_id"),                                    // a category is not a brand
            refuse(listOf(ok), "body.change_reason", why = null),
            refuse(listOf(ok), "body.change_reason", why = "short"),
            refuse(listOf(ok), "body.quarter", q = "2027-Q5"),
            refuse(emptyList(), "body.rows"),
        )
        for ((body, pointer, code) in cases) putTargets(body, web = "superadmin1001").expect(HttpStatusCode.BadRequest, code, pointer)
        putTargets(batch(listOf(ok)).replace("\"rows\":", "\"extra\":1,\"rows\":"), web = "superadmin1001").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.extra")
        putTargets("""{"batch_uuid":"not-a-uuid","quarter":"${quarter.label}","rows":[$ok],"change_reason":"$reason"}""", web = "superadmin1001").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "body.batch_uuid")
        assertEquals(tableBefore, world.one("SELECT count(*) FROM app.astha_target"), "all-or-nothing: the valid first row of a bad batch is not stored")
        assertEquals(ledgerBefore, batches(), "and no batch is recorded")
    }

    @Test
    fun onlyTheConfiguredRolesUploadAndATsoOnlyForItsOwnTerritory() = world.withApi {
        world.exec("DELETE FROM app.astha_target WHERE batch_uuid IS NOT NULL")
        val own = batch(listOf(row(outletMir1, month0, "77")))
        val foreign = batch(listOf(row(outletMir1, month1, "78"), row(outletLkx, month0, "79")))
        putTargets(own, phone = sr).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")      // an SR does not enter targets
        putTargets(own, phone = amo).expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        putTargets(own, web = "dmo1001").expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        putTargets(own, web = "support1001").expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        val before = world.one("SELECT count(*) FROM app.astha_target")
        putTargets(foreign, web = "tso1001").expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN", "body.rows[1].outlet_id")   // cross-territory: the second row is the LKX outlet
        assertEquals(before, world.one("SELECT count(*) FROM app.astha_target"), "the TSO's own-territory first row is not stored either")
        putTargets(own, web = "tso1001").expect(HttpStatusCode.OK)                        // own-territory positive control
        putTargets(batch(listOf(row(outletLkx, month0, "80"))), web = "lkxtso1001").expect(HttpStatusCode.OK)
        putTargets(batch(listOf(row(outletMir1, month0, "81"), row(outletLkx, month1, "82"))), web = "admin1001").expect(HttpStatusCode.OK)   // ADMIN is national
        with(world) {
            cfg("cfg.astha.target_entry_roles", """["ADMIN"]""")
            try {
                putTargets(batch(listOf(row(outletMir1, month0, "83"))), web = "tso1001").expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")   // the list is the config's
                putTargets(batch(listOf(row(outletMir1, month0, "84"))), web = "superadmin1001").expect(HttpStatusCode.OK)                         // SUPERADMIN always may
            } finally { cfg("cfg.astha.target_entry_roles", """["ADMIN","TSO"]""") }
        }
    }

    /**
     * Property: any sequence of batches with duplicates and out-of-order arrivals leaves exactly the state of applying each batch UUID once, at its first arrival
     * (the model), every answer's counts add up to its rows, and a replay never moves the table.
     */
    @Test
    fun duplicateAndOutOfOrderBatchesLeaveTheStateOfTheirFirstArrivalsInOrder() = world.withApi {
        val rnd = Random(20_270_103)
        val keys = listOf(outletMir1 to month0, outletMir1 to month1, outletMir2 to month0, outletMir2 to month1)
        repeat(12) { trial ->
            world.exec("DELETE FROM app.astha_target WHERE batch_uuid IS NOT NULL")
            val bodies = (0 until 4).map { _ ->
                val chosen = keys.filter { rnd.nextBoolean() }.ifEmpty { listOf(keys.first()) }
                val values = chosen.map { (o, m) -> Triple(o, m, rnd.nextInt(0, 500).toString()) }
                uuid() to values
            }
            // A random schedule: every batch at least once, some twice, shuffled.
            val schedule = (bodies + bodies.filter { rnd.nextBoolean() }).shuffled(rnd)
            val model = HashMap<Pair<Long, String>, String>(); val applied = HashSet<String>()
            for ((bid, values) in schedule) {
                val rows = values.map { (o, m, v) -> row(o, m, v) }
                val answer = putTargets(batch(rows, bid), web = "superadmin1001"); answer.expect(HttpStatusCode.OK)
                val (i, u, c) = answer.counts(); assertEquals(rows.size, i + u + c, "trial $trial: the counts add up to the rows")
                val snapshot = targetRows()
                if (applied.add(bid)) values.forEach { (o, m, v) -> model[o to m] = v } else assertEquals(snapshot, targetRows(), "trial $trial: a replay moves nothing")
                val actual = snapshot.associate { (it["outlet_id"] as Long to it["month"].toString().substring(0, 7)) to java.math.BigDecimal(it["std"].toString()).toBigInteger().toString() }
                assertEquals(model, actual, "trial $trial: the state is that of the first arrivals in order")
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the reads

    private fun ids(r: JsonObject, key: String = "outlet_id") = r["items"]!!.jsonArray.map { it.jsonObject[key]!!.jsonPrimitive.content.toLong() }

    @Test
    fun theLiveBalanceIsTheLedgerUpToTodayCutToTheCallersOutlets() = world.withApi {
        with(world) {
            val own = json(getAs(sr, "/v1/programmes/loyalty/balances").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            assertEquals(setOf(outletMir1, outletMir2), ids(own).toSet(), "an SR reads its own route's enrolled outlets only")
            val b = own["items"]!!.jsonArray.map { it.jsonObject }.single { it["outlet_id"]!!.jsonPrimitive.content.toLong() == outletMir1 }
            assertEquals("150", b["balance_points"]!!.jsonPrimitive.content, "live: today's +50 is in the read (the bundle's as-of is the previous day)")
            assertEquals(today.toString(), b["as_of_date"]!!.jsonPrimitive.content); assertEquals("100", b["expiring_points"]!!.jsonPrimitive.content)
            assertEquals(today.plusDays(9).toString(), b["expiry_date"]!!.jsonPrimitive.content)
            assertNotNull(own["as_of"])
            // A named outlet outside the reach is left out, not an error (no way to probe which ids exist).
            val named = json(getAs(sr, "/v1/programmes/loyalty/balances?outlet_id=$outletLkx&outlet_id=$outletMir2").bodyAsText())
            assertEquals(listOf(outletMir2), ids(named))
            assertTrue(ids(json(getAs(sr, "/v1/programmes/loyalty/balances?outlet_id=$outletLkx").bodyAsText())).isEmpty())
            assertEquals(listOf(outletLkx), ids(json(getAs(lkxSr, "/v1/programmes/loyalty/balances").bodyAsText())), "the other division's SR sees only its own outlet (7 points)")
            assertEquals(HttpStatusCode.BadRequest, getAs(sr, "/v1/programmes/loyalty/balances?outlet_id=abc").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(sr, "/v1/programmes/loyalty/balances?programme_id=0").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(sr, "/v1/programmes/loyalty/balances?" + (1..201).joinToString("&") { "outlet_id=$it" }).status)
            assertEquals(HttpStatusCode.Forbidden, getWeb("support1001", "/v1/programmes/loyalty/balances").status)
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/programmes/loyalty/balances").status)
            // A national reader must name outlets; with names, the same cut rules apply (a TSO of Mirpur asking for the LKX outlet gets nothing).
            assertEquals(HttpStatusCode.BadRequest, getWeb("admin1001", "/v1/programmes/loyalty/balances").status)
            assertEquals(listOf(outletLkx), ids(json(getWeb("admin1001", "/v1/programmes/loyalty/balances?outlet_id=$outletLkx").bodyAsText())))
            assertTrue(ids(json(getWeb("tso1001", "/v1/programmes/loyalty/balances?outlet_id=$outletLkx").bodyAsText())).isEmpty())
            assertEquals(setOf(outletMir1, outletMir2), ids(json(getWeb("tso1001", "/v1/programmes/loyalty/balances?outlet_id=$outletMir1&outlet_id=$outletMir2").bodyAsText())).toSet(), "own-territory positive control")
        }
    }

    @Test
    fun theTargetListIsCutToTheReachAndRefusesARouteOrZoneOutsideIt() = world.withApi {
        with(world) {
            val q = quarter.label
            val own = json(getAs(sr, "/v1/programmes/astha/targets?quarter=$q").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            assertEquals(q, own["quarter"]!!.jsonPrimitive.content); assertNotNull(own["as_of"])
            assertEquals(setOf(routeMir), ids(own, "route_id").toSet()); assertTrue(ids(own).any { it == outletMir1 }, "positive control: the SR's own rows are there")
            val row = own["items"]!!.jsonArray.map { it.jsonObject }.first { it["outlet_id"]!!.jsonPrimitive.content.toLong() == outletMir1 && it["brand_id"]!!.toString() != "null" && it["month"]!!.jsonPrimitive.content == quarter.months[2].toString().substring(0, 7) }
            assertEquals("250000", row["value_target_mtk"]!!.jsonPrimitive.content); assertEquals("0", row["value_achieved_mtk"]!!.jsonPrimitive.content); assertEquals("100.000", row["std_target"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, "/v1/programmes/astha/targets?quarter=$q&route_id=$routeLkx").also { it.expect(HttpStatusCode.Forbidden, "ERR_OUT_OF_SCOPE") }.status)
            getAs(sr, "/v1/programmes/astha/targets?quarter=$q&zone_id=$zoneLkx").expect(HttpStatusCode.Forbidden, "ERR_OUT_OF_SCOPE")
            getAs(sr, "/v1/programmes/astha/targets").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.quarter")
            getAs(sr, "/v1/programmes/astha/targets?quarter=2027-Q5").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.quarter")
            getAs(sr, "/v1/programmes/astha/targets?quarter=$q&limit=0").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.limit")
            getAs(sr, "/v1/programmes/astha/targets?quarter=$q&cursor=!!").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.cursor")
            // Paging by cursor visits every row exactly once.
            val seen = mutableListOf<String>(); var cursor: String? = null
            do {
                val page = json(getAs(sr, "/v1/programmes/astha/targets?quarter=$q&limit=1" + (cursor?.let { "&cursor=$it" } ?: "")).bodyAsText())
                seen += page["items"]!!.jsonArray.map { it.toString() }
                cursor = page["next_cursor"]!!.let { if (it.toString() == "null") null else it.jsonPrimitive.content }
            } while (cursor != null)
            assertEquals(own["items"]!!.jsonArray.map { it.toString() }, seen, "pages add up to the whole list, in order")
            // TSOs: own territory only, with positive controls on both sides.
            assertEquals(setOf(routeMir), ids(json(getWeb("tso1001", "/v1/programmes/astha/targets?quarter=$q&route_id=$routeMir").bodyAsText()), "route_id").toSet())
            getWeb("tso1001", "/v1/programmes/astha/targets?quarter=$q&route_id=$routeLkx").expect(HttpStatusCode.Forbidden, "ERR_OUT_OF_SCOPE")
            assertEquals(setOf(routeLkx), ids(json(getWeb("lkxtso1001", "/v1/programmes/astha/targets?quarter=$q").bodyAsText()), "route_id").toSet())
            // National: must narrow; narrowed reads work.
            getWeb("admin1001", "/v1/programmes/astha/targets?quarter=$q").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.route_id")
            assertEquals(setOf(routeLkx), ids(json(getWeb("admin1001", "/v1/programmes/astha/targets?quarter=$q&route_id=$routeLkx").bodyAsText()), "route_id").toSet())
            getWeb("support1001", "/v1/programmes/astha/targets?quarter=$q&route_id=$routeMir").expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
            // A quarter without targets is an empty page, not an error.
            assertTrue(json(getAs(sr, "/v1/programmes/astha/targets?quarter=${quarter.months.first().year + 5}-Q1").bodyAsText())["items"]!!.jsonArray.isEmpty())
        }
    }

    @Test
    fun theGiftChoiceListIsCutToTheReachAndNationalReadersMustNarrow() = world.withApi {
        with(world) {
            val q = quarter.label
            val own = json(getAs(sr, "/v1/programmes/astha/gift-choices?quarter=$q").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            assertEquals(listOf(outletMir1), ids(own)); assertEquals("false", own["items"]!!.jsonArray[0].jsonObject["locked"]!!.jsonPrimitive.content)
            assertNull(own["items"]!!.jsonArray[0].jsonObject["photo_media_uuid"]!!.jsonPrimitive.content.takeIf { it != "null" })
            assertEquals(listOf(outletLkx), ids(json(getWeb("lkxtso1001", "/v1/programmes/astha/gift-choices?quarter=$q").bodyAsText())))
            getWeb("tso1001", "/v1/programmes/astha/gift-choices?quarter=$q&route_id=$routeLkx").expect(HttpStatusCode.Forbidden, "ERR_OUT_OF_SCOPE")
            getWeb("admin1001", "/v1/programmes/astha/gift-choices?quarter=$q").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.route_id")
            assertEquals(listOf(outletLkx), ids(json(getWeb("admin1001", "/v1/programmes/astha/gift-choices?quarter=$q&zone_id=$zoneLkx").bodyAsText())))
            getAs(sr, "/v1/programmes/astha/gift-choices").expect(HttpStatusCode.BadRequest, "ERR_VALIDATION", "query.quarter")
            getWeb("support1001", "/v1/programmes/astha/gift-choices?quarter=$q&route_id=$routeMir").expect(HttpStatusCode.Forbidden, "ERR_FORBIDDEN")
        }
    }
}

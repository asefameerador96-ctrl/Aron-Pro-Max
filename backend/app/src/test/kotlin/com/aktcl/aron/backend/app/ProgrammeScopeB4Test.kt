package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Push B4 (5 and 6 of the lead's re-pass), over HTTP through the production wiring: ONE function builds the programme scope parameters, with two zone sets.
 *  - LEAK: a campaign scoped to route X (zone Z) is in the bundle of the SR of route X and of the zone's TSO, NOT in the bundle of the SR of route Y of the same zone.
 *  - LOSS: an SR fetches the picture of a gift of a zone-scoped programme (200), and a picture of a programme scoped to a zone outside its reach is a bare 404.
 * Route X = MIR-SR-D (sr1001), route Y = MIR-SR-3F (a second SR, sr1002, seeded here); both are planned on the world's Sunday, both in zone Z-MIR.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgrammeScopeB4Test {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var today: LocalDate
    private lateinit var srX: TsoPhone
    private lateinit var srY: TsoPhone
    private lateinit var tso: TsoPhone
    private var routeX = 0L
    private var routeY = 0L
    private var zone = 0L
    private val zoneGift = UUID.randomUUID().toString()
    private val farGift = UUID.randomUUID().toString()

    private fun programme(code: String, nodeType: String?, nodeId: Long?, giftAsset: String? = null) = with(world) {
        val id = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', '$code', 'Scope $code', DATE '${today.minusDays(5)}', DATE '${today.plusDays(30)}', 'active') RETURNING id")
        if (nodeType != null) {
            exec("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) VALUES ($id, 1, 'v', DATE '${today.minusDays(5)}', DATE '${today.plusDays(30)}', 'scope test version', gen_random_uuid(), 'x')")
            exec("UPDATE app.programme SET current_version_id = (SELECT max(id) FROM app.programme_version WHERE programme_id = $id) WHERE id = $id")
            exec("INSERT INTO app.programme_version_scope (programme_version_id, node_type, node_id) SELECT current_version_id, '$nodeType', $nodeId FROM app.programme WHERE id = $id")
        }
        exec("INSERT INTO app.gift (programme_id, code, name_en, points_cost${if (giftAsset != null) ", image_asset_id" else ""}) VALUES ($id, 'g_${code.lowercase().replace("-", "_")}', 'Gift $code', 100${if (giftAsset != null) ", CAST('$giftAsset' AS uuid)" else ""})")
        id
    }

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))   // the LKX mirror is the second zone (another division)
        with(world) {
            today = today()
            routeX = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            routeY = one("SELECT id FROM app.route WHERE code = 'MIR-SR-3F'")
            zone = one("SELECT zone_id FROM app.route WHERE id = $routeX")
            assertEquals(zone, one("SELECT zone_id FROM app.route WHERE id = $routeY"), "fixture: both routes are in one zone")
            // A second SR on route Y only: sr1001 gives route Y up (its assignment ends the day after it starts), sr1002 takes it over from that day.
            exec("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) SELECT 'sr1002', 'Test SR 2 (Mirpur)', role, designation, 'T-SR-1002', locale, home_zone_id, pilot, must_change_password, password_hash FROM app.app_user WHERE username = 'sr1001'")
            exec("UPDATE app.route_assignment SET valid_to = valid_from + 1 WHERE route_id = $routeY AND user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001')")
            exec("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT $routeY, u.id, 'primary', a.valid_to, 'scope test' FROM app.app_user u, app.route_assignment a WHERE u.username = 'sr1002' AND a.route_id = $routeY AND a.valid_to IS NOT NULL")
            srX = phone("sr1001", "sr"); srY = phone("sr1002", "sr"); tso = phone("tso1001", "tso")
            for ((asset, path) in listOf(zoneGift to "assets/sku_image/gift-zone.png", farGift to "assets/sku_image/gift-far.png"))
                exec("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES (CAST('$asset' AS uuid), 'gift_image', 'image/png', 70, decode(repeat('ab', 32), 'hex'), '$path')")
            programme("CMP-ROUTE-X", "route", routeX)
            programme("CMP-ZONE", "zone", zone, giftAsset = zoneGift)
            programme("CMP-FAR", "zone", one("SELECT max(id) FROM app.zone") + 1000, giftAsset = farGift)
        }
    }

    @AfterAll fun tearDown() = world.close()

    private suspend fun io.ktor.client.HttpClient.codes(phone: TsoPhone): Set<String> = with(world) {
        val r = getAs(phone, "/v1/sync/bundle")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val p = json(r.bodyAsText())["programmes"]
        if (p == null || p is JsonNull) emptySet() else p.jsonObject["programmes"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }.toSet()
    }

    /** LEAK: the route node is read zone-wide only for a caller that holds the zone as reach. */
    @Test
    fun aCampaignScopedToRouteXIsAbsentForTheSrOfRouteYInTheSameZoneAndPresentForRouteXsSrAndTheZonesTso() = world.withApi {
        val x = codes(srX); val y = codes(srY); val t = codes(tso)
        assertTrue("CMP-ROUTE-X" in x, "positive control: the SR of route X has it: $x")
        assertTrue("CMP-ROUTE-X" !in y, "the SR of route Y in the same zone must not: $y")
        assertTrue("CMP-ROUTE-X" in t, "no loss: the zone's TSO reads a route node zone-wide: $t")
        assertTrue("CMP-ZONE" in x && "CMP-ZONE" in y && "CMP-ZONE" in t, "a zone-scoped campaign reaches every SR of the zone and its TSO: $x $y $t")
        assertTrue("CMP-FAR" !in x && "CMP-FAR" !in y && "CMP-FAR" !in t, "a campaign of a zone outside the reach reaches nobody here")
    }

    /** LOSS: the SR's reach has no zone ids, yet the zone-scoped programme's gift picture is its to see. */
    @Test
    fun anSrFetchesThePictureOfAZoneScopedProgrammesGiftAndAnotherZonesPictureIsA404() = world.withApi {
        with(world) {
            for (phone in listOf(srX, srY, tso)) {
                val ok = getAs(phone, "/v1/sku-images/$zoneGift")
                assertEquals(HttpStatusCode.OK, ok.status, "${phone.username}: ${ok.bodyAsText()}")
                assertEquals(HttpStatusCode.NotFound, getAs(phone, "/v1/sku-images/$farGift").status, "${phone.username}: a programme scoped to a zone outside the reach")
            }
        }
    }

    /** The programme scope is applied PER OUTLET to the balances: an AMO covering Mirpur and the LKX mirror zone gets no balance for a Mirpur outlet of a league scoped to the LKX zone. */
    @Test
    fun anAmoOfTwoZonesGetsNoBalanceForAnOutletOutsideTheProgrammesScope() {
        with(world) {
            val routeGul = one("SELECT id FROM app.route WHERE code = 'LKX-SR-D'")
            val zoneGul = one("SELECT zone_id FROM app.route WHERE id = $routeGul")
            val outletMir = one("SELECT id FROM app.outlet WHERE route_id = $routeX AND status = 'active' ORDER BY id LIMIT 1")
            val outletGul = one("SELECT id FROM app.outlet WHERE route_id = $routeGul AND status = 'active' ORDER BY id LIMIT 1")
            val id = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('diamond_league', 'DL-LKX-ONLY', 'Scoped league', DATE '${today.minusDays(5)}', DATE '${today.plusDays(30)}', 'active') RETURNING id")
            exec("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) VALUES ($id, 1, 'v', DATE '${today.minusDays(5)}', DATE '${today.plusDays(30)}', 'scope test version', gen_random_uuid(), 'x')")
            exec("UPDATE app.programme SET current_version_id = (SELECT max(id) FROM app.programme_version WHERE programme_id = $id) WHERE id = $id")
            exec("INSERT INTO app.programme_version_scope (programme_version_id, node_type, node_id) SELECT current_version_id, 'zone', $zoneGul FROM app.programme WHERE id = $id")
            for (o in listOf(outletMir, outletGul)) {
                exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($id, $o, 'gold', DATE '${today.minusDays(30)}')")
                exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date) VALUES ($o, $id, 'adjustment', 'dl-$o', 11, DATE '${today.minusDays(2)}')")
            }
            val amo = com.aktcl.aron.backend.platform.Reach(1, com.aktcl.aron.contract.Role.AMO, today, false, setOf(zone, zoneGul), emptySet(), false, emptyList())
            val sec = fresh.db.jdbi.withHandle<JsonObject?, Exception> { h ->
                com.aktcl.aron.backend.sync.BundleProgrammes.section(h, amo, listOf(routeX, routeGul), setOf(outletMir, outletGul), today, com.aktcl.aron.backend.sync.BundleProgrammes.Flags(true, true, true), 1)
            }!!
            val rows = sec["loyalty"]!!.jsonArray.map { it.jsonObject }.filter { it["programme_id"]!!.jsonPrimitive.content == id.toString() }
            assertEquals(listOf(outletGul.toString()), rows.map { it["outlet_id"]!!.jsonPrimitive.content }, "only the outlet inside the programme's scope has a balance")
            assertEquals("11", rows.single()["balance_points"]!!.jsonPrimitive.content)
        }
    }
}

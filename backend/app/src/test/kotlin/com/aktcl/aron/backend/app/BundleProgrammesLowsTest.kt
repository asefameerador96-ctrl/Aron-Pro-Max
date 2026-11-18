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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lows of the lead's review of push (B) (2026-11-18): `user.geography` for AMO and TSO, the no-home-zone fallback, the null case and "never another user's chain"; `loyalty[].tier_code`
 * null; `bundle_version` changes when a tier code, a zone name or a brand status changes; and `cfg.astha.quarter_start_month` is GLOBAL ONLY, so the bundle (which reads it through the
 * user's scope chain) and the gift-choice ingest (which reads the global value) can never disagree.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BundleProgrammesLowsTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld

    @BeforeAll fun setUp() { world = TsoWorld(repoRoot) }
    @AfterAll fun tearDown() = world.close()

    private fun bundle(username: String, flavour: String): JsonObject = world.run {
        var out: JsonObject? = null
        val p = phone(username, flavour)
        withApi { out = json(getAs(p, "/v1/sync/bundle").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText()) }
        out!!
    }

    private fun chain(zoneId: Long): List<Pair<Long, String>> = world.rows(
        "SELECT w.id wi, w.name wn, d.id di, d.name dn, t.id ti, t.name tn, z.id zi, z.name zn FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id WHERE z.id = $zoneId",
    ).singleOrNull()?.let { r -> listOf((r["wi"] as Long) to r["wn"] as String, (r["di"] as Long) to r["dn"] as String, (r["ti"] as Long) to r["tn"] as String, (r["zi"] as Long) to r["zn"] as String) } ?: error("no zone chain for zone $zoneId")

    private fun geoOf(b: JsonObject): List<Pair<Long, String>>? = (b["user"]!!.jsonObject["geography"])?.takeIf { it !is JsonNull }?.jsonObject?.let { g ->
        listOf("wing", "division", "territory", "zone").map { k -> g[k]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content.toLong() to it["name"]!!.jsonPrimitive.content } }
    }

    @Test
    fun anAmoAndATsoGetTheirOwnZoneChainAndNeverAnotherUsers() {
        for ((u, f) in listOf("amo1001" to "amo", "tso1001" to "tso", "sr1001" to "sr")) {
            val b = bundle(u, f)
            val zone = b["user"]!!.jsonObject["zone_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLong()
            val geo = geoOf(b)
            if (zone != null) assertEquals(chain(zone), geo, "$u: the names are the chain of the user's own zone")
            else {
                // no home zone: the zone of the user's first assigned route (ordered by route id), or null when there is none; the oracle judges the DHAKA business date the server uses (world.today()), never the database's UTC CURRENT_DATE
                val firstRouteZone = world.longs("SELECT r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = (SELECT id FROM app.app_user WHERE username = '$u') AND a.valid_from <= DATE '${world.today()}' AND (a.valid_to IS NULL OR a.valid_to >= DATE '${world.today()}') ORDER BY r.id LIMIT 1").firstOrNull()
                assertEquals(firstRouteZone?.let { chain(it) }, geo, "$u: no home zone, so the first route's zone, else null")
            }
        }
        // Another user's chain is never sent: move the SR to another zone and the TSO's answer does not change.
        val other = world.one("INSERT INTO app.zone (code, name, name_bn, territory_id) SELECT 'Z-LOWS', 'Lows zone', 'লোজ জোন', territory_id FROM app.zone WHERE id = (SELECT zone_id FROM app.route WHERE code = 'MIR-SR-D') RETURNING id")
        val tsoBefore = geoOf(bundle("tso1001", "tso"))
        val srOld = world.scalar("SELECT home_zone_id::text FROM app.app_user WHERE username = 'sr1001'")
        try {
            world.exec("UPDATE app.app_user SET home_zone_id = $other WHERE username = 'sr1001'")
            assertEquals(chain(other), geoOf(bundle("sr1001", "sr")))
            assertEquals(tsoBefore, geoOf(bundle("tso1001", "tso")), "the TSO still gets the TSO's own chain")
        } finally { world.exec("UPDATE app.app_user SET home_zone_id = ${srOld ?: "NULL"} WHERE username = 'sr1001'") }
    }

    @Test
    fun withoutAHomeZoneTheChainIsTheFirstRoutesZoneAndWithoutRoutesItIsNull() {
        val old = world.scalar("SELECT home_zone_id::text FROM app.app_user WHERE username = 'sr1001'")
        val routeZone = world.one("SELECT r.zone_id FROM app.route r WHERE r.code = 'MIR-SR-D'")
        try {
            world.exec("UPDATE app.app_user SET home_zone_id = NULL WHERE username = 'sr1001'")
            val b = bundle("sr1001", "sr")
            assertNull(b["user"]!!.jsonObject["zone_id"]?.takeIf { it !is JsonNull }, "no home zone")
            assertEquals(chain(routeZone), geoOf(b), "the zone of the first route of the day")
            // No home zone and no route that day: null (the phone shows nothing; no other user's names are read).
            val saved = world.rows("SELECT route_id, user_id FROM app.route_assignment WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001')")
            assertTrue(saved.isNotEmpty())
            world.exec("UPDATE app.route_assignment SET valid_to = DATE '${world.today().minusDays(1)}' WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001')")
            try { assertNull(geoOf(bundle("sr1001", "sr")), "no zone and no route: null") }
            finally { world.exec("UPDATE app.route_assignment SET valid_to = NULL WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001')") }
        } finally { world.exec("UPDATE app.app_user SET home_zone_id = ${old ?: "NULL"} WHERE username = 'sr1001'") }
    }

    @Test
    fun tierCodeIsNullWithoutATierAndTheBundleVersionMovesWithATierCodeAZoneNameAndABrandStatus() {
        val today = world.today()
        val route = world.one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
        val outlet = world.one("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 1")
        val brand = world.one("SELECT b.id FROM app.product_node b WHERE b.level = 'brand' ORDER BY b.id LIMIT 1")
        val prog = world.one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'LOWS-A', 'Lows programme', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
        world.exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($prog, $outlet, NULL, DATE '${today.minusDays(30)}')")
        val first = today.withDayOfMonth(1)
        world.exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($prog, DATE '$first', $route, $outlet, $brand, 10, 0)")
        fun entry(b: JsonObject) = b["programmes"]!!.jsonObject["loyalty"]!!.jsonArray.map { it.jsonObject }.single { it["outlet_id"]!!.jsonPrimitive.content == outlet.toString() && it["programme_id"]!!.jsonPrimitive.content == prog.toString() }
        fun version(b: JsonObject) = b["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content
        val b0 = bundle("sr1001", "sr")
        val tier0 = entry(b0)["tier_code"]
        assertTrue(tier0 == null || tier0 is JsonNull, "an enrolment without a tier sends a null tier_code, not a made-up one")
        val v0 = version(b0)
        assertEquals(v0, version(bundle("sr1001", "sr")), "unchanged content, same version")
        // a tier code changes -> a new version (the section is part of the digest)
        world.exec("UPDATE app.programme_enrolment SET tier_code = 'gold' WHERE programme_id = $prog AND outlet_id = $outlet")
        val b1 = bundle("sr1001", "sr"); val v1 = version(b1)
        assertEquals("gold", entry(b1)["tier_code"]!!.jsonPrimitive.content); assertNotEquals(v0, v1)
        // a zone name changes -> a new version (user.geography is part of the digest)
        val zone = world.one("SELECT zone_id FROM app.route WHERE id = $route")
        world.exec("UPDATE app.zone SET name = name || ' (renamed)' WHERE id = $zone")
        val v2 = version(bundle("sr1001", "sr")); assertNotEquals(v1, v2)
        // a brand becomes inactive -> its name is sent on the target row and the version changes
        world.exec("UPDATE app.product_node SET status = 'inactive' WHERE id = $brand")
        val b3 = bundle("sr1001", "sr"); val v3 = version(b3)
        assertNotEquals(v2, v3)
        val row = b3["programmes"]!!.jsonObject["astha_targets"]!!.jsonArray.map { it.jsonObject }.first { it["brand_id"]?.takeIf { x -> x !is JsonNull }?.jsonPrimitive?.content == brand.toString() }
        assertTrue(row["brand_name"]!!.jsonPrimitive.content.isNotBlank())
        world.exec("UPDATE app.product_node SET status = 'active' WHERE id = $brand")
    }

    @Test
    fun theQuarterStartMonthIsAGlobalOnlyKeySoTheBundleAndTheGiftChoiceIngestReadOneValue() {
        assertEquals("{global}", world.scalar("SELECT scope_levels::text FROM app.cfg_key WHERE key = 'cfg.astha.quarter_start_month'"),
            "a scoped override cannot exist, so the user-chain read of the bundle and the global read of GiftChoiceRecords and the upload cannot differ")
    }
}

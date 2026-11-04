package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.Aggregator
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tso-manual B3, D-TSO-MAN-32 (screenshots 23 and 24; build contract s8 row 23): the Final Submit cascade Wing > Division
 * > Territory > House > Zone is read from `GET /v1/dashboards/national/filter-options`, whose reach comes from the token,
 * and every zone it offers is one the Final Submit preview (Get Sales Data) accepts.
 * - The chain is closed: every division names a wing of the answer, every territory a division, every house a
 *   territory, every zone its territory and (when it has one) a house of the answer, so the app can filter each level by
 *   the one above it.
 * - The zones are exactly the zones of tso1001's territory with routes (dw.dim_geo), never one of the mirrored division.
 * - A level with one option (wing, division and territory for a territory TSO) is what the app preselects.
 * - Each offered zone previews (200, its own zone id), and a zone outside the cascade is refused (403).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalSubmitCascadeReadTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val tag = "FSC"
    private lateinit var world: ApiWorld

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot, mirrors = listOf(tag))
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            // A second house and zone with a route in tso1001's territory (the test seed has Z-MIR only; the Gulshan zones
            // are the Azure dev database's), so the house and zone levels have more than one option.
            h.execute("INSERT INTO app.house (code, name, territory_id) SELECT 'H-B3C', 'B3 Cascade House', id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id, house_id) SELECT 'Z-B3C', 'B3 Cascade', t.id, hs.id FROM app.territory t JOIN app.house hs ON hs.code = 'H-B3C' WHERE t.code = 'T-DHK-N'")
            h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'B3C-SR-D', 'Cascade Daily', 'Daily', id, 'sr', 'daily', 127, 1 FROM app.zone WHERE code = 'Z-B3C'")
            // dw.dim_geo is the worker's (the filter options read it).
            Aggregator.refreshDimensions(h)
        }
    }

    @AfterAll
    fun tearDown() { world.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun withApi(block: suspend HttpClient.() -> Unit) = runBlocking {
        val app = TestApplication { application { aronApi(world.wiring) } }
        app.start()
        try { app.client.block() } finally { app.stop() }
    }

    private fun ids(sql: String): Set<Long> = world.fresh.db.jdbi.withHandle<Set<Long>, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).list().toSet() }

    @Test
    fun theCascadeComesFromTheTokenReachAndEveryZoneInItPreviews() = withApi {
        val tso = world.login(this, ApiWorld.Caller("tso1001", "web", null)) ?: error("no token for tso1001")
        val r = get("/v1/dashboards/national/filter-options") { bearerAuth(tso); header("X-App-Version", ApiWorld.APP_VERSION) }
        assertEquals(200, r.status.value, r.bodyAsText())
        val o = json(r.bodyAsText())
        fun level(k: String) = o[k]!!.jsonArray.map { it.jsonObject }
        fun JsonObject.id() = this["id"]!!.jsonPrimitive.content.toLong()
        fun JsonObject.ref(k: String) = this[k]?.jsonPrimitive?.longOrNull
        val wings = level("wings"); val divisions = level("divisions"); val territories = level("territories"); val houses = level("houses"); val zones = level("zones")

        // The chain is closed (each level filters by the one above it).
        assertTrue(divisions.all { it.ref("wing_id") in wings.map { w -> w.id() } }, "divisions: $divisions")
        assertTrue(territories.all { it.ref("division_id") in divisions.map { d -> d.id() } }, "territories: $territories")
        assertTrue(houses.all { it.ref("territory_id") in territories.map { t -> t.id() } }, "houses: $houses")
        assertTrue(zones.all { z -> z.ref("territory_id") in territories.map { t -> t.id() } && (z.ref("house_id") == null || z.ref("house_id") in houses.map { hs -> hs.id() }) }, "zones: $zones")

        // Reach from the token: the zones of tso1001's territory with routes, none of the mirrored division.
        val mine = ids("SELECT DISTINCT g.zone_id FROM dw.dim_geo g JOIN app.territory t ON t.id = g.territory_id WHERE t.code = 'T-DHK-N'")
        assertTrue(mine.size >= 2, "the territory has several zones: $mine")
        assertEquals(mine, zones.map { it.id() }.toSet())
        assertTrue(houses.size >= 2, "two houses under the territory: $houses")
        val mirror = ids("SELECT id FROM app.zone WHERE code = 'Z-$tag'").single()
        assertTrue(mirror !in mine)

        // One option at a level is what the app preselects (D-TSO-MAN-32): wing, division and territory for this TSO.
        assertEquals(listOf(1, 1, 1), listOf(wings.size, divisions.size, territories.size), "a territory TSO sees one wing, division and territory")

        // Get Sales Data: every offered zone previews; a zone outside the cascade is refused.
        for (z in zones.map { it.id() }) {
            val p = get("/v1/day/final-submit/preview?zone_id=$z&business_date=${ApiWorld.BUSINESS_DATE}") { bearerAuth(tso); header("X-App-Version", ApiWorld.APP_VERSION) }
            assertEquals(200, p.status.value, "zone $z: ${p.bodyAsText()}")
            assertEquals(z, json(p.bodyAsText())["zone_id"]!!.jsonPrimitive.content.toLong())
        }
        val outside = get("/v1/day/final-submit/preview?zone_id=$mirror&business_date=${ApiWorld.BUSINESS_DATE}") { bearerAuth(tso); header("X-App-Version", ApiWorld.APP_VERSION) }
        assertEquals(403, outside.status.value, outside.bodyAsText())
    }
}

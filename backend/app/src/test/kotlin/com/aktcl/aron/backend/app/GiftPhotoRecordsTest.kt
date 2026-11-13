package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.sync.BundleProgrammes
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
 * Push C: the `gift_photo` sync record (contract 1.16.0, SRM-B-77 to 80) through POST /v1/sync/batch. The first photo by captured_at locks the outlet's gift (`locked_at`, `photo_media_uuid`) in ANY arrival
 * order; every other photo is stored as a flagged duplicate (never deleted, never refused); the server re-checks the photo fix against the outlet; an assignment outside the uploader's reach is refused;
 * `pending_gift_verifications` lists redeemed campaign gifts short of photos.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GiftPhotoRecordsTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private lateinit var today: LocalDate
    private var programme = 0L
    private var campaign = 0L
    private var gift = 0L
    private var cGift = 0L
    private var outlet = 0L
    private var outletLkx = 0L
    private var userId = 0L
    private var route = 0L
    private var lkxAssignment = 0L

    private val lat = 23.8069
    private val lng = 90.3687

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))
        with(world) {
            today = today(); sr = phone("sr1001", "sr"); userId = sr.userId
            route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            outlet = one("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 1")
            outletLkx = one("SELECT o.id FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'LKX-SR-D' AND o.status = 'active' ORDER BY o.id LIMIT 1")
            exec("UPDATE app.outlet SET location_basis = 'master', lat = $lat, lng = $lng WHERE id = $outlet")
            programme = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'ASTHA-GP', 'Astha photo', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            campaign = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'CAMP-GP', 'Campaign photo', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            gift = one("INSERT INTO app.gift (programme_id, code, name_en) VALUES ($programme, 'gp_a', 'Gift') RETURNING id")
            cGift = one("INSERT INTO app.gift (programme_id, code, name_en) VALUES ($campaign, 'gp_c', 'Campaign gift') RETURNING id")
            lkxAssignment = one("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id) SELECT $programme, '2031-Q1', $outletLkx, route_id, $gift FROM app.outlet WHERE id = $outletLkx RETURNING id")
        }
    }

    @AfterAll fun tearDown() = world.close()

    private fun assignment(label: String): Long = world.one("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id) VALUES ($programme, '$label', $outlet, $route, $gift) RETURNING id")

    private fun photo(assignment: Long?, secondsAgo: Long, photoUuid: String = UUID.randomUUID().toString(), cu: String = UUID.randomUUID().toString(), flat: Double = lat, outletId: Long = outlet,
                      unit: Pair<String, Int>? = null): JsonObject {
        val target = if (assignment != null) """"programme_kind":"astha","gift_assignment_id":$assignment""" else """"programme_kind":"campaign","redemption_client_uuid":"${unit!!.first}","unit_no":${unit.second}"""
        val g = if (assignment != null) gift else cGift
        return world.record(
            "gift_photo",
            """{$target,"outlet_id":$outletId,"gift_id":$g,"photo_uuid":"$photoUuid",
                "fix":{"purpose":"gift_photo","fix_status":"ok","lat":$flat,"lng":$lng,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                       "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}""",
            routeId = route, cu = cu, secondsAgo = secondsAgo,
        )
    }

    private fun cu(r: JsonObject) = r["client_uuid"].toString().trim('"')
    private fun photoUuid(r: JsonObject) = r["payload"]!!.jsonObject["photo_uuid"]!!.jsonPrimitive.content

    @Test
    fun theFirstPhotoLocksTheAssignmentAndIsGeoChecked() = world.withApi {
        with(world) {
            val a = assignment("2031-Q2"); val p = photo(a, 120)
            assertEquals("accepted", ack(sync(sr, listOf(p)), cu(p)).status())
            val row = rows("SELECT locked_at IS NOT NULL AS locked, photo_media_uuid::text AS m FROM app.gift_assignment WHERE id = $a").single()
            assertEquals(true, row["locked"]); assertEquals(photoUuid(p), row["m"])
            val ph = rows("SELECT is_duplicate, server_verdict, server_distance_m FROM app.gift_photo WHERE client_uuid = CAST('${cu(p)}' AS uuid)").single()
            assertEquals(false, ph["is_duplicate"]); assertEquals("in_range", ph["server_verdict"])
            assertEquals("duplicate", ack(sync(sr, listOf(p)), cu(p)).status(), "a replay is a duplicate and stores nothing")
            assertEquals(1L, one("SELECT count(*) FROM app.gift_photo WHERE gift_assignment_id = $a"))
        }
    }

    @Test
    fun aPhotoFarFromTheOutletIsStoredWithItsServerVerdictNeverRefused() = world.withApi {
        with(world) {
            val a = assignment("2031-Q3"); val p = photo(a, 60, flat = lat + 0.05)
            assertEquals("accepted", ack(sync(sr, listOf(p)), cu(p)).status())
            assertEquals("out_of_range", rows("SELECT server_verdict FROM app.gift_photo WHERE client_uuid = CAST('${cu(p)}' AS uuid)").single()["server_verdict"])
        }
    }

    @Test
    fun aSecondPhotoIsStoredAsAFlaggedDuplicateAndTheFirstStaysTheLock() = world.withApi {
        with(world) {
            val a = assignment("2031-Q4"); val first = photo(a, 300); val second = photo(a, 100)
            sync(sr, listOf(first)); val ack2 = ack(sync(sr, listOf(second)), cu(second))
            assertEquals("accepted", ack2.status(), ack2.toString())
            assertEquals(false, rows("SELECT is_duplicate FROM app.gift_photo WHERE client_uuid = CAST('${cu(first)}' AS uuid)").single()["is_duplicate"])
            val d = rows("SELECT is_duplicate, flagged_at IS NOT NULL AS f FROM app.gift_photo WHERE client_uuid = CAST('${cu(second)}' AS uuid)").single()
            assertEquals(true, d["is_duplicate"]); assertEquals(true, d["f"])
            assertEquals(photoUuid(first), rows("SELECT photo_media_uuid::text AS m FROM app.gift_assignment WHERE id = $a").single()["m"])
        }
    }

    @Test
    fun propertyAnyArrivalOrderEndsOnTheEarliestCapturedPhoto() = world.withApi {
        with(world) {
            repeat(10) { trial ->
                val rnd = Random(4242L + trial)
                val a = assignment("${2040 + trial}-Q1")
                val photos = (1..4).map { i -> photo(a, 100L * i + trial) }   // photos[3] is the earliest captured
                val earliest = photos.last()
                for (p in photos.shuffled(rnd)) sync(sr, listOf(p))
                val winners = rows("SELECT client_uuid::text AS c FROM app.gift_photo WHERE gift_assignment_id = $a AND NOT is_duplicate")
                assertEquals(listOf(cu(earliest)), winners.map { it["c"] }, "trial $trial: exactly one winner, the earliest captured")
                assertEquals(4L, one("SELECT count(*) FROM app.gift_photo WHERE gift_assignment_id = $a"), "nothing is deleted or refused")
                assertEquals(photoUuid(earliest), rows("SELECT photo_media_uuid::text AS m FROM app.gift_assignment WHERE id = $a").single()["m"], "trial $trial: the assignment points at the winner")
            }
        }
    }

    @Test
    fun anAssignmentOutOfReachOrUnknownIsRefused() = world.withApi {
        with(world) {
            val far = photo(lkxAssignment, 60, outletId = outletLkx)
            val a = ack(sync(sr, listOf(far)), cu(far))
            assertEquals("quarantined", a.status()); assertEquals("scope_out_of_reach", a.code())
            val unknown = photo(9_999_999L, 60)
            assertEquals("schema_invalid", ack(sync(sr, listOf(unknown)), cu(unknown)).code())
            assertEquals(0L, one("SELECT count(*) FROM app.gift_assignment WHERE id = $lkxAssignment AND photo_media_uuid IS NOT NULL"))
        }
    }

    @Test
    fun pendingVerificationsListRedeemedCampaignGiftsShortOfPhotos() = world.withApi {
        with(world) {
            val red = UUID.randomUUID().toString()
            val common = "family_uuid, business_date, user_id, captured_at, config_version"
            val vals = "gen_random_uuid(), DATE '$today', $userId, now(), 0"
            exec("INSERT INTO app.redemption (client_uuid, $common, outlet_id, programme_id, balance_before_points, points_total, cash_points, cash_mtk, cash_rate_mtk_per_point, line_count, confirmed_at) VALUES ('$red', $vals, $outlet, $campaign, 500, 300, 0, 0, 0, 1, now())")
            exec("INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, user_id, first_batch_uuid) VALUES ('$red', 'redemption', decode(repeat('00', 32), 'hex'), 'accepted', $userId, gen_random_uuid())")   // a redemption reaches the server through sync, so the photo's parent is registered
            exec("INSERT INTO app.redemption_line (client_uuid, $common, redemption_client_uuid, gift_id, qty, points_each, points_total) VALUES (gen_random_uuid(), $vals, '$red', $cGift, 3, 100, 300)")
            fun pending(): JsonObject? = fresh.db.jdbi.withHandle<JsonObject?, Exception> { h ->
                BundleProgrammes.section(h, com.aktcl.aron.backend.platform.Reach(userId, com.aktcl.aron.contract.Role.SR, today, false, emptySet(), setOf(route), true, emptyList()), listOf(route), setOf(outlet), today, BundleProgrammes.Flags(true, true, true), 1)
            }?.get("pending_gift_verifications")?.jsonArray?.singleOrNull()?.jsonObject
            val before = assertNotNull(pending())
            assertEquals("3", before["qty"]!!.jsonPrimitive.content); assertEquals("0", before["photos_captured"]!!.jsonPrimitive.content)
            val p1 = photo(null, 90, unit = red to 1); val dup = photo(null, 50, unit = red to 1); val p2 = photo(null, 40, unit = red to 2)
            for (p in listOf(p1, dup, p2)) ack(sync(sr, listOf(p)), cu(p)).let { assertEquals("accepted", it.status(), it.toString()) }
            val mid = assertNotNull(pending(), "a partial submit leaves the gift listed")
            assertEquals("2", mid["photos_captured"]!!.jsonPrimitive.content, "the flagged duplicate of unit 1 is not counted")
            val p3 = photo(null, 30, unit = red to 3); sync(sr, listOf(p3))
            assertNull(pending(), "all three units photographed")
            assertTrue(one("SELECT count(*) FROM app.gift_photo WHERE redemption_client_uuid = '$red'") == 4L)
        }
    }
}

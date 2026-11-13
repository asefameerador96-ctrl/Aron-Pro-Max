package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.sync.BundleProgrammes
import com.aktcl.aron.backend.sync.GeoRecheckSweepJob
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
    private var cGift2 = 0L
    private var outlet2 = 0L
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
            outlet2 = one("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' AND id <> $outlet ORDER BY id LIMIT 1")
            exec("UPDATE app.outlet SET location_basis = 'master', lat = $lat, lng = $lng WHERE id = $outlet")
            programme = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'ASTHA-GP', 'Astha photo', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            campaign = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'CAMP-GP', 'Campaign photo', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            gift = one("INSERT INTO app.gift (programme_id, code, name_en) VALUES ($programme, 'gp_a', 'Gift') RETURNING id")
            cGift = one("INSERT INTO app.gift (programme_id, code, name_en) VALUES ($campaign, 'gp_c', 'Campaign gift') RETURNING id")
            cGift2 = one("INSERT INTO app.gift (programme_id, code, name_en) VALUES ($campaign, 'gp_c2', 'Campaign gift 2') RETURNING id")
            lkxAssignment = one("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id) SELECT $programme, '2031-Q1', $outletLkx, route_id, $gift FROM app.outlet WHERE id = $outletLkx RETURNING id")
        }
    }

    @AfterAll fun tearDown() = world.close()

    private fun assignment(label: String): Long = world.one("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id) VALUES ($programme, '$label', $outlet, $route, $gift) RETURNING id")

    private fun photo(assignment: Long?, secondsAgo: Long, photoUuid: String = UUID.randomUUID().toString(), cu: String = UUID.randomUUID().toString(), flat: Double = lat, outletId: Long = outlet,
                      unit: Pair<String, Int>? = null, giftId: Long? = null): JsonObject {
        val target = if (assignment != null) """"programme_kind":"astha","gift_assignment_id":$assignment""" else """"programme_kind":"campaign","redemption_client_uuid":"${unit!!.first}","unit_no":${unit.second}"""
        val g = giftId ?: if (assignment != null) gift else cGift
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
            }?.get("pending_gift_verifications")?.jsonArray?.map { it.jsonObject }?.singleOrNull { it["redemption_client_uuid"]!!.jsonPrimitive.content == red }
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

    // ---- C-R1: ties, voids, campaign-unit checks, sweep ----

    /** A stored redemption of the campaign with [lines] (gift to qty); line_count is [lineCount] (default the number of lines given). */
    private fun redemption(outletId: Long, lines: List<Pair<Long, Int>>, lineCount: Int = lines.size, programmeId: Long = campaign): String = with(world) {
        val red = UUID.randomUUID().toString()
        val common = "family_uuid, business_date, user_id, captured_at, config_version"
        val vals = "gen_random_uuid(), DATE '$today', $userId, now(), 0"
        exec("INSERT INTO app.redemption (client_uuid, $common, outlet_id, programme_id, balance_before_points, points_total, cash_points, cash_mtk, cash_rate_mtk_per_point, line_count, confirmed_at) VALUES ('$red', $vals, $outletId, $programmeId, 5000, 300, 0, 0, 0, $lineCount, now())")
        exec("INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, user_id, first_batch_uuid) VALUES ('$red', 'redemption', decode(repeat('00', 32), 'hex'), 'accepted', $userId, gen_random_uuid())")
        lines.forEach { (g, q) -> addLine(red, g, q) }
        red
    }

    private fun addLine(red: String, g: Long, q: Int) = with(world) {
        exec("INSERT INTO app.redemption_line (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, redemption_client_uuid, gift_id, qty, points_each, points_total) VALUES (gen_random_uuid(), gen_random_uuid(), DATE '$today', $userId, now(), 0, '$red', $g, $q, 100, ${q * 100})")
    }

    private fun flag(r: JsonObject) = world.rows("SELECT check_flag, is_duplicate, flagged_at IS NOT NULL AS f FROM app.gift_photo WHERE client_uuid = CAST('${cu(r)}' AS uuid)").single()
    private fun winnersOfUnit(red: String, unit: Int, g: Long = cGift) = world.rows("SELECT client_uuid::text AS c FROM app.gift_photo WHERE redemption_client_uuid = '$red' AND gift_id = $g AND unit_no = $unit AND NOT is_duplicate AND voided_at IS NULL").map { it["c"] }

    @Test
    fun photosCapturedAtTheSameInstantAreOrderedByClientUuidInAnyArrivalOrder() = world.withApi {
        with(world) {
            for (i in 1..2) {
                val low = "10000000-0000-4000-8000-00000000000$i"; val high = "f0000000-0000-4000-8000-00000000000$i"
                val a = assignment("${2060 + i}-Q1")
                val order = if (i == 1) listOf(low, high) else listOf(high, low)   // both arrival orders
                val recs = order.map { photo(a, 120, cu = it, flat = if (it == low) lat else lat + 0.00001) }   // same instant, but not the same content (that would be a content duplicate)
                for (r in recs) ack(sync(sr, listOf(r)), cu(r)).let { assertEquals("accepted", it.status(), it.toString()) }
                assertEquals(listOf(low), rows("SELECT client_uuid::text AS c FROM app.gift_photo WHERE gift_assignment_id = $a AND NOT is_duplicate").map { it["c"] }, "arrival order $i: the lower client uuid wins the tie")
                assertEquals(photoUuid(recs[order.indexOf(low)]), rows("SELECT photo_media_uuid::text AS m FROM app.gift_assignment WHERE id = $a").single()["m"])
            }
        }
    }

    @Test
    fun voidingTheWinnerPromotesTheEarliestRemainingAndTheLockFollows() = world.withApi {
        with(world) {
            val a = assignment("2070-Q1")
            val p1 = photo(a, 400); val p2 = photo(a, 300); val p3 = photo(a, 200)
            for (p in listOf(p3, p1, p2)) sync(sr, listOf(p))
            fun winner() = rows("SELECT client_uuid::text AS c FROM app.gift_photo WHERE gift_assignment_id = $a AND NOT is_duplicate AND voided_at IS NULL").map { it["c"] }
            fun assign() = rows("SELECT photo_media_uuid::text AS m, locked_at IS NOT NULL AS l FROM app.gift_assignment WHERE id = $a").single()
            assertEquals(listOf(cu(p1)), winner())
            exec("UPDATE app.gift_photo SET voided_at = now() WHERE client_uuid = CAST('${cu(p1)}' AS uuid)")
            assertEquals(listOf(cu(p2)), winner(), "the earliest remaining photo takes over"); assertEquals(photoUuid(p2), assign()["m"]); assertEquals(true, assign()["l"])
            assertEquals(false, flag(p2)["f"], "and it is no longer flagged")
            exec("UPDATE app.gift_photo SET voided_at = now() WHERE client_uuid = CAST('${cu(p3)}' AS uuid)")   // a duplicate's void changes nothing
            assertEquals(listOf(cu(p2)), winner()); assertEquals(photoUuid(p2), assign()["m"])
            exec("UPDATE app.gift_photo SET voided_at = now() WHERE client_uuid = CAST('${cu(p2)}' AS uuid)")
            assertEquals(emptyList(), winner()); assertNull(assign()["m"]); assertEquals(false, assign()["l"], "with no photo left the assignment is open again")
            val again = photo(a, 100); sync(sr, listOf(again))
            assertEquals(listOf(cu(again)), winner(), "a new photo can lock it again"); assertEquals(true, assign()["l"])
            assertEquals(4L, one("SELECT count(*) FROM app.gift_photo WHERE gift_assignment_id = $a"), "no photo was deleted")
        }
    }

    @Test
    fun aCampaignUnitPhotoThatFailsItsChecksIsStoredAndFlaggedNeverDropped() = world.withApi {
        with(world) {
            val red = redemption(outlet, listOf(cGift to 2))
            suspend fun accepted(p: JsonObject, label: String = "") = ack(sync(sr, listOf(p)), cu(p)).let { assertEquals("accepted", it.status(), label + it.toString()) }
            val tooHigh = photo(null, 900, unit = red to 3); accepted(tooHigh, "tooHigh ")
            assertEquals("unit_out_of_range", flag(tooHigh)["check_flag"]); assertEquals(true, flag(tooHigh)["is_duplicate"]); assertEquals(true, flag(tooHigh)["f"])
            val wrongGift = photo(null, 780, unit = red to 1, giftId = cGift2); accepted(wrongGift, "wrongGift ")
            assertEquals("gift_not_in_redemption", flag(wrongGift)["check_flag"])
            val wrongOutlet = photo(null, 660, unit = red to 1, outletId = outlet2); accepted(wrongOutlet, "wrongOutlet ")
            assertEquals("outlet_mismatch", flag(wrongOutlet)["check_flag"])
            assertEquals(emptyList(), winnersOfUnit(red, 1) + winnersOfUnit(red, 3), "none of them counts as a unit's photo")
            assertEquals(3L, one("SELECT count(*) FROM app.gift_photo WHERE redemption_client_uuid = '$red'"), "all three are kept")
            val good = photo(null, 2000, unit = red to 1); accepted(good, "good ")
            assertNull(flag(good)["check_flag"]); assertEquals(listOf(cu(good)), winnersOfUnit(red, 1))
            // a failing photo captured EARLIER than the good one that arrives LATER must not leave the unit without its photo
            val red2 = redemption(outlet, listOf(cGift to 1))
            val goodLater = photo(null, 2400, unit = red2 to 1); val badEarlier = photo(null, 3000, unit = red2 to 1, outletId = outlet2)
            accepted(goodLater, "goodLater "); accepted(badEarlier, "badEarlier ")
            assertEquals("outlet_mismatch", flag(badEarlier)["check_flag"]); assertEquals(listOf(cu(goodLater)), winnersOfUnit(red2, 1), "the good photo is elected again")
        }
    }

    @Test
    fun aCampaignUnitPhotoOutsideTheProgrammesScopeIsFlagged() = world.withApi {
        with(world) {
            // the redemption is of a campaign whose programme scope is the other division's zone: the outlet is in the rep's reach but outside the programme's scope
            val scoped = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'CAMP-SCOPED', 'Scoped', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            exec("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) VALUES ($scoped, 1, 'v', DATE '${today.minusDays(5)}', DATE '${today.plusDays(30)}', 'scope test version', gen_random_uuid(), 'x')")
            exec("UPDATE app.programme SET current_version_id = (SELECT max(id) FROM app.programme_version WHERE programme_id = $scoped) WHERE id = $scoped")
            exec("INSERT INTO app.programme_version_scope (programme_version_id, node_type, node_id) SELECT current_version_id, 'zone', (SELECT zone_id FROM app.outlet WHERE id = $outletLkx) FROM app.programme WHERE id = $scoped")
            val red = redemption(outlet, listOf(cGift to 1), programmeId = scoped)
            val p = photo(null, 100, unit = red to 1)
            assertEquals("accepted", ack(sync(sr, listOf(p)), cu(p)).status())
            assertEquals("out_of_reach", flag(p)["check_flag"]); assertEquals(emptyList(), winnersOfUnit(red, 1))
        }
    }

    @Test
    fun aUnitPhotoWhoseRedemptionLinesHaveNotAllArrivedWaitsInsteadOfBeingJudgedOnAPartialView() = world.withApi {
        with(world) {
            val red = redemption(outlet, listOf(cGift to 1), lineCount = 2)
            val p = photo(null, 100, unit = red to 1, giftId = cGift2)   // its gift is the second line, not stored yet
            val first = ack(sync(sr, listOf(p)), cu(p))
            assertEquals("rejected" to "parent_missing", first.status() to first.code()); assertEquals(true, first["retryable"]?.jsonPrimitive?.content == "true")
            addLine(red, cGift2, 1)
            assertEquals("accepted", ack(sync(sr, listOf(p)), cu(p)).status(), "the resend is judged once the lines are there")
            assertNull(flag(p)["check_flag"]); assertEquals(listOf(cu(p)), winnersOfUnit(red, 1, cGift2))
        }
    }

    @Test
    fun theGeoRecheckOfAGiftPhotoIsRetriedBySweep() = world.withApi {
        with(world) {
            val a = assignment("2080-Q1"); val p = photo(a, 60)
            sync(sr, listOf(p))
            val sel = "SELECT server_verdict, server_checked_at IS NOT NULL AS c FROM app.gift_photo WHERE client_uuid = CAST('${cu(p)}' AS uuid)"
            assertEquals("in_range", rows(sel).single()["server_verdict"])
            exec("UPDATE app.gift_photo SET server_verdict = NULL, server_distance_m = NULL, server_radius_m = NULL, server_checked_at = NULL WHERE client_uuid = CAST('${cu(p)}' AS uuid)")   // the re-check failed on ingest
            assertEquals(false, rows(sel).single()["c"])
            val stored = GeoRecheckSweepJob(wiring.database!!, clock).tick()
            assertTrue(stored >= 1, "the sweep stored a verdict")
            assertEquals("in_range", rows(sel).single()["server_verdict"]); assertEquals(true, rows(sel).single()["c"])
        }
    }

    // ---- C-R2 finding 4: nothing is retryable for ever ----

    private fun finalFlag(red: String, g: Long = cGift, expect: String) = world.withApi {
        with(world) {
            val p = photo(null, 100, unit = red to 1, giftId = g)
            repeat(2) { n -> val a = ack(sync(sr, listOf(p)), cu(p)); assertEquals(if (n == 0) "accepted" else "duplicate", a.status(), "send $n: stored once, never parked: $a") }
            val row = flag(p)
            assertEquals(expect, row["check_flag"]); assertEquals(true, row["is_duplicate"], "flagged, so never the unit's photo")
            assertEquals(emptyList(), winnersOfUnit(red, 1, g))
        }
    }

    @Test
    fun aPhotoWhoseRedemptionWasVoidedBeforeItSyncedIsStoredAndFlaggedNotParked() {
        val red = redemption(outlet, listOf(cGift to 1)); world.exec("UPDATE app.redemption SET voided_at = now() WHERE client_uuid = CAST('$red' AS uuid)")
        finalFlag(red, expect = "parent_voided")
    }

    @Test
    fun aPhotoOfAVoidedLineIsStoredAndFlaggedAndALineThatNeverArrivesBecauseItWasRefusedToo() {
        val voidedLine = redemption(outlet, listOf(cGift to 1, cGift2 to 1)); world.exec("UPDATE app.redemption_line SET voided_at = now() WHERE redemption_client_uuid = CAST('$voidedLine' AS uuid) AND gift_id = $cGift")
        finalFlag(voidedLine, cGift, "parent_voided")
        val refusedLine = redemption(outlet, listOf(cGift to 1), lineCount = 2)   // the second line was refused: it will never be stored
        world.exec("INSERT INTO app.ingest_registry (client_uuid, record_type, family_uuid, payload_sha256, status, outcome_code, user_id, first_batch_uuid) SELECT gen_random_uuid(), 'redemption_line', family_uuid, decode(repeat('00', 32), 'hex'), 'rejected', 'schema_invalid', $userId, gen_random_uuid() FROM app.redemption WHERE client_uuid = CAST('$refusedLine' AS uuid)")
        finalFlag(refusedLine, expect = "parent_refused")
    }

    @Test
    fun aPhotoWhoseRedemptionWasRefusedIsStoredAndFlaggedAndTheOutOfRangeUnitOfAFullyStoredOneToo() {
        val refused = UUID.randomUUID().toString()   // the redemption itself was refused: its registry row is rejected and no row exists
        world.exec("INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, outcome_code, user_id, first_batch_uuid) VALUES ('$refused', 'redemption', decode(repeat('00', 32), 'hex'), 'rejected', 'schema_invalid', $userId, gen_random_uuid())")
        finalFlag(refused, expect = "parent_refused")
        val full = redemption(outlet, listOf(cGift to 1))   // fully stored: judged by the ordinary checks (unit 2 of a 1-unit gift)
        world.withApi {
            with(world) {
                val p = photo(null, 100, unit = full to 2); assertEquals("accepted", ack(sync(sr, listOf(p)), cu(p)).status()); assertEquals("unit_out_of_range", flag(p)["check_flag"])
            }
        }
    }
}

package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.sync.BundleProgrammes
import com.aktcl.aron.backend.sync.GeoRecheckSweepJob
import com.aktcl.aron.backend.sync.GiftParents
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
 * Push C: the `gift_photo` sync record (contract 1.17.0, SRM-B-77 to 80) through POST /v1/sync/batch. The first photo by captured_at locks the outlet's gift (`locked_at`, `photo_media_uuid`) in ANY arrival
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

    private var finalSeq = 0
    private fun finalFlag(red: String, g: Long = cGift, expect: String) = world.withApi {
        with(world) {
            val p = photo(null, 100 + 7L * (++finalSeq), unit = red to 1, giftId = g, flat = lat + 0.00001 * finalSeq)   // distinct content: not a content_duplicate of an earlier test photo
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
                val p = photo(null, 300, unit = full to 2, flat = lat + 0.0005); assertEquals("accepted", ack(sync(sr, listOf(p)), cu(p)).status()); assertEquals("unit_out_of_range", flag(p)["check_flag"])
            }
        }
    }

    // ---- C-R3 finding 1: ONE parent function (GiftParents), a test per state, and the photo follows the reviewer's decision ----

    private val zeroSha = "decode(repeat('00', 32), 'hex')"
    private fun registry(uuid: String, type: String, status: String, code: String? = null, family: String? = null) = world.exec(
        "INSERT INTO app.ingest_registry (client_uuid, record_type, ${if (family != null) "family_uuid, " else ""}payload_sha256, status, outcome_code, user_id, first_batch_uuid) VALUES ('$uuid', '$type', ${if (family != null) "'$family', " else ""}$zeroSha, '$status', ${code?.let { "'$it'" } ?: "NULL"}, $userId, gen_random_uuid())",
    )
    /** A quarantine item of a held record: open, or resolved as [status] (a note starting `return_to_device` marks a returned one). */
    private fun held(uuid: String, type: String, status: String = "open", code: String = "scope_out_of_reach", note: String? = null, family: String? = null): Unit {
        registry(uuid, type, "quarantined", code, family)
        world.exec(
            "INSERT INTO app.sync_quarantine (client_uuid, record_type, code, status, payload_sha256, payload, user_id, route_id, business_date, resolved_at, resolved_by_user_id, resolution_note) " +
                "VALUES ('$uuid', '$type', '$code', '$status', decode(repeat('01', 32), 'hex'), '{\"type\":\"$type\"}', $userId, $route, DATE '$today', ${if (status == "open") "NULL" else "now()"}, ${if (status == "open") "NULL" else userId}, ${note?.let { "'$it'" } ?: "NULL"})",
        )
    }
    private fun familyOf(red: String) = world.rows("SELECT family_uuid::text AS f FROM app.redemption WHERE client_uuid = CAST('$red' AS uuid)").single()["f"] as String
    private fun state(uuid: String, type: String) = world.fresh.db.jdbi.withHandle<GiftParents.Verdict, Exception> { h -> GiftParents.state(h, uuid, type) }
    private fun line(red: String, g: Long, voided: Boolean = false): String {
        val u = UUID.randomUUID().toString()
        world.exec("INSERT INTO app.redemption_line (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, redemption_client_uuid, gift_id, qty, points_each, points_total${if (voided) ", voided_at" else ""}) VALUES ('$u', gen_random_uuid(), DATE '$today', $userId, now(), 0, '$red', $g, 1, 100, 100${if (voided) ", now()" else ""})")
        return u
    }

    @Test
    fun theParentFunctionAnswersEveryStateOfAHeaderAndOfALine() {
        val S = GiftParents.State.entries
        // header
        val stored = redemption(outlet, listOf(cGift to 1)); assertEquals(GiftParents.State.STORED, state(stored, "redemption").state)
        val voided = redemption(outlet, listOf(cGift to 1)); world.exec("UPDATE app.redemption SET voided_at = now() WHERE client_uuid = CAST('$voided' AS uuid)"); assertEquals(GiftParents.State.VOIDED, state(voided, "redemption").state)
        val rejected = UUID.randomUUID().toString(); registry(rejected, "redemption", "rejected", "schema_invalid"); assertEquals(GiftParents.State.REJECTED, state(rejected, "redemption").state)
        val discarded = UUID.randomUUID().toString(); held(discarded, "redemption", "discarded"); assertEquals(GiftParents.State.DISCARDED, state(discarded, "redemption").state)
        val open = UUID.randomUUID().toString(); held(open, "redemption"); state(open, "redemption").let { assertEquals(GiftParents.State.OPEN, it.state); assertEquals(open, it.uuid); assertEquals("scope_out_of_reach", it.code) }
        assertEquals(GiftParents.State.MISSING, state(UUID.randomUUID().toString(), "redemption").state, "not synced yet")
        val returned = UUID.randomUUID().toString(); held(returned, "redemption", "discarded", note = "return_to_device: fix and send again"); assertEquals(GiftParents.State.MISSING, state(returned, "redemption").state, "a returned item may still arrive: not final")
        val otherType = UUID.randomUUID().toString(); registry(otherType, "memo", "rejected", "schema_invalid"); assertEquals(GiftParents.State.MISSING, state(otherType, "redemption").state, "a refused record of another type is not a refused redemption")
        // line
        val red = redemption(outlet, listOf(cGift to 1), lineCount = 6)
        assertEquals(GiftParents.State.STORED, state(line(red, cGift2), "redemption_line").state)
        assertEquals(GiftParents.State.VOIDED, state(line(red, cGift2, voided = true), "redemption_line").state)
        val rl = UUID.randomUUID().toString(); registry(rl, "redemption_line", "rejected", "schema_invalid", familyOf(red)); assertEquals(GiftParents.State.REJECTED, state(rl, "redemption_line").state)
        val dl = UUID.randomUUID().toString(); held(dl, "redemption_line", "discarded", family = familyOf(red)); assertEquals(GiftParents.State.DISCARDED, state(dl, "redemption_line").state)
        val ol = UUID.randomUUID().toString(); held(ol, "redemption_line", family = familyOf(red)); assertEquals(GiftParents.State.OPEN, state(ol, "redemption_line").state)
        assertEquals(GiftParents.State.MISSING, state(UUID.randomUUID().toString(), "redemption_line").state)
        assertTrue(S.filter { it.isFinal }.toSet() == setOf(GiftParents.State.STORED, GiftParents.State.VOIDED, GiftParents.State.REJECTED, GiftParents.State.DISCARDED), "final = stored, voided, rejected, discarded; open and missing wait")
        // the lines of one redemption together: a refused/discarded line beats a held one, a held one beats nothing, nothing = still on its way
        val r2 = redemption(outlet, listOf(cGift to 1), lineCount = 4); val f2 = familyOf(r2)
        fun lines() = world.fresh.db.jdbi.withHandle<GiftParents.Verdict, Exception> { h -> GiftParents.missingLines(h, f2) }
        assertEquals(GiftParents.State.MISSING, lines().state)
        val o2 = UUID.randomUUID().toString(); held(o2, "redemption_line", family = f2); assertEquals(GiftParents.State.OPEN, lines().state); assertEquals(o2, lines().uuid)
        registry(UUID.randomUUID().toString(), "redemption_line", "rejected", "schema_invalid", f2); assertTrue(lines().state.isRefused, "a refused line makes the redemption incomplete for good")
    }

    private suspend fun io.ktor.client.HttpClient.discard(item: Long): HttpStatusCode =
        with(world) { postWeb("admin1001", "/v1/admin/quarantine/$item/resolve", """{"action":"discard","reason":"not a real redemption, discarded by the reviewer"}""").status }
    private fun itemOf(uuid: String) = world.one("SELECT id FROM app.sync_quarantine WHERE client_uuid = CAST('$uuid' AS uuid) ORDER BY id DESC LIMIT 1")

    @Test
    fun aPhotoHeldWithAnOpenHeaderFollowsTheReviewersDiscardAndIsStoredAndFlagged() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString(); held(head, "redemption")      // the redemption is held in review
            val p = photo(null, 200, unit = head to 1, flat = lat + 0.00003)
            val a = ack(sync(sr, listOf(p)), cu(p))
            assertEquals("quarantined", a.status(), "an OPEN parent holds the photo in review, not in the phone's retry loop: $a"); assertEquals("scope_out_of_reach", a.code())
            assertEquals("parent $head is quarantined (scope_out_of_reach)", rows("SELECT detail FROM app.sync_quarantine WHERE client_uuid = CAST('${cu(p)}' AS uuid)").single()["detail"], "the reason names the parent")
            assertEquals(0L, one("SELECT count(*) FROM app.gift_photo WHERE client_uuid = CAST('${cu(p)}' AS uuid)"))
            assertEquals(HttpStatusCode.OK, discard(itemOf(head)))
            assertEquals("parent_refused", flag(p)["check_flag"], "the photo follows the discard: stored and flagged"); assertEquals(true, flag(p)["is_duplicate"])
            assertEquals("accepted", rows("SELECT status FROM app.sync_quarantine WHERE client_uuid = CAST('${cu(p)}' AS uuid)").single()["status"])
            assertEquals("duplicate", ack(sync(sr, listOf(p)), cu(p)).status())
            // a photo that arrives AFTER the discard is stored and flagged at once
            val late = photo(null, 190, unit = head to 2, flat = lat + 0.00004)
            assertEquals("accepted", ack(sync(sr, listOf(late)), cu(late)).status()); assertEquals("parent_refused", flag(late)["check_flag"])
        }
    }

    @Test
    fun aPhotoWhoseRedemptionLineIsHeldInReviewIsHeldWithItAndFollowsItsDiscard() = world.withApi {
        with(world) {
            val red = redemption(outlet, listOf(cGift to 1), lineCount = 2)   // line 1 stored; line 2 held in review
            val l2 = UUID.randomUUID().toString(); held(l2, "redemption_line", family = familyOf(red))
            val p = photo(null, 210, unit = red to 1, flat = lat + 0.00005)
            val a = ack(sync(sr, listOf(p)), cu(p))
            assertEquals("quarantined", a.status(), a.toString()); assertEquals("scope_out_of_reach", a.code())
            assertEquals("parent $l2 is quarantined (scope_out_of_reach)", rows("SELECT detail FROM app.sync_quarantine WHERE client_uuid = CAST('${cu(p)}' AS uuid)").single()["detail"])
            assertEquals(HttpStatusCode.OK, discard(itemOf(l2)))
            assertEquals("parent_refused", flag(p)["check_flag"], "the line was discarded: the redemption can never be complete, the photo is stored and flagged")
            val after = photo(null, 205, unit = red to 1, flat = lat + 0.00006, cu = UUID.randomUUID().toString())
            assertEquals("accepted", ack(sync(sr, listOf(after)), cu(after)).status()); assertEquals("parent_refused", flag(after)["check_flag"], "and a later photo of it likewise")
        }
    }

    @Test
    fun aRefusedRecordOfAnotherTypeInTheFamilyIsNotAnotherLineAndItsLookupHasAnIndex() = world.withApi {
        with(world) {
            val red = redemption(outlet, listOf(cGift to 1), lineCount = 2)
            registry(UUID.randomUUID().toString(), "visit", "rejected", "schema_invalid", familyOf(red))
            val p = photo(null, 220, unit = red to 1, flat = lat + 0.00007)
            val a = ack(sync(sr, listOf(p)), cu(p)); assertEquals("rejected", a.status()); assertEquals("parent_missing", a.code(), "a line is still on its way: retryable, not parent_refused")
            assertEquals(1L, one("SELECT count(*) FROM pg_indexes WHERE schemaname = 'app' AND tablename = 'ingest_registry' AND indexname = 'ingest_registry_family_unresolved'"))
        }
    }

    // ---- C-R4: the accept loop through the REAL records and the REAL accept endpoint ----
    private val oldSecs = 15L * 86_400   // outside the 7-day backdate window: held as business_date_out_of_window until a reviewer accepts

    private fun headerRec(h: String, lineCount: Int, secondsAgo: Long = oldSecs, outletId: Long = outlet) = world.record(
        "redemption",
        """{"balance_before_points":5000,"cash_mtk":0,"cash_points":0,"cash_rate_mtk_per_point":0,"confirmed_at":"${java.time.Instant.now()}","line_count":$lineCount,"outlet_id":$outletId,"points_total":${lineCount * 100},"programme_id":$campaign}""",
        routeId = route, cu = h, family = h, secondsAgo = secondsAgo,
    )
    private fun lineRec(h: String, secondsAgo: Long, cu: String = UUID.randomUUID().toString()) = world.record(
        "redemption_line", """{"gift_id":$cGift,"points_each":100,"points_total":100,"qty":1,"redemption_client_uuid":"$h"}""", routeId = route, cu = cu, family = h, secondsAgo = secondsAgo,
    )
    private suspend fun io.ktor.client.HttpClient.accept(item: Long): HttpStatusCode =
        with(world) { postWeb("admin1001", "/v1/admin/quarantine/$item/resolve", """{"action":"accept","reason":"the reviewer accepts the late redemption"}""").let { r -> if (r.status != HttpStatusCode.OK) println("ACCEPT-FAILED item=$item ${r.status} ${r.bodyAsText()}"); r.status } }
    private fun itemRow(uuid: String) = world.rows("SELECT id, status, code, detail, resolution_note FROM app.sync_quarantine WHERE client_uuid = CAST('$uuid' AS uuid) ORDER BY id DESC LIMIT 1").single()
    private fun openNaming(parent: String) = world.one("SELECT count(*) FROM app.sync_quarantine WHERE status = 'open' AND detail LIKE 'parent $parent%'")
    private fun storedPhoto(r: JsonObject) = world.one("SELECT count(*) FROM app.gift_photo WHERE client_uuid = CAST('${cu(r)}' AS uuid)")

    /** What the phone is told through the batch answer's `resolutions[]` about [uuid]: the statuses (a phone treats `discarded` as final and never sends the row again). */
    private fun phoneResolutions(answer: JsonObject, uuid: String): List<String> =
        answer["resolutions"]?.jsonArray?.map { it.jsonObject }?.filter { it["client_uuid"]!!.jsonPrimitive.content == uuid }?.map { it["resolution"]!!.jsonPrimitive.content } ?: emptyList()

    @Test
    fun acceptingAHeaderWhileALineHasNotSyncedKeepsThePhotoHeldAndTheReviewerReleasesItLater() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00008)
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status())
            assertEquals("quarantined", ack(sync(sr, listOf(l1)), cu(l1)).status()); assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            assertEquals(1L, one("SELECT count(*) FROM app.redemption WHERE client_uuid = CAST('$head' AS uuid)")); assertEquals(1L, one("SELECT count(*) FROM app.redemption_line WHERE redemption_client_uuid = CAST('$head' AS uuid)"))
            assertEquals("open", itemRow(cu(p))["status"], "C-R5: the photo stays held in review, the phone is told nothing final"); assertEquals(0L, openNaming(head), "no item names the accepted header any more")
            assertEquals(true, (itemRow(cu(p))["detail"] as String).startsWith("waits for another parent"), itemRow(cu(p)).toString())
            assertEquals(0L, storedPhoto(p))
            val lineAgain = sync(sr, listOf(l1))   // the line was released with the header: its resend is a replay (stores nothing) and the phone's feed says accepted
            assertEquals("duplicate", ack(lineAgain, cu(l1)).status()); assertEquals(listOf("accepted"), phoneResolutions(lineAgain, cu(l1)))
            assertEquals(1L, one("SELECT count(*) FROM app.redemption_line WHERE client_uuid = CAST('${cu(l1)}' AS uuid)"), "the line's resend stored nothing more")
            val again = sync(sr, listOf(p))
            assertEquals("quarantined", ack(again, cu(p)).status(), "the phone's view of the photo is still: held in review")
            assertEquals(emptyList<String>(), phoneResolutions(again, cu(p)), "the phone-visible resolution feed carries nothing for it (never 'discarded')")
            // the missing line arrives; the photo is still open, and the reviewer's accept stores it (device count == server count)
            val l2 = lineRec(head, 90); assertEquals("accepted", ack(sync(sr, listOf(l2)), cu(l2)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(cu(p))))
            assertEquals(1L, storedPhoto(p)); assertEquals(null, flag(p)["check_flag"]); assertEquals("accepted", itemRow(cu(p))["status"])
            val feed = sync(sr, listOf(p)); assertEquals("duplicate", ack(feed, cu(p)).status()); assertEquals(listOf("accepted"), phoneResolutions(feed, cu(p)))
            assertEquals(HttpStatusCode.OK, accept(itemOf(head))); assertEquals(1L, storedPhoto(p))
        }
    }

    @Test
    fun aPhotoAndALateLineHeldWithTheHeaderAreReleasedInDependencyOrderByOneAccept() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 1); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00009); val l1 = lineRec(head, 90)
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status())
            assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status())   // the photo is item N ...
            assertEquals("quarantined", ack(sync(sr, listOf(l1)), cu(l1)).status()) // ... and the line item N+1: ids order the photo first
            assertEquals(true, itemOf(cu(p)) < itemOf(cu(l1)))
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            assertEquals(1L, storedPhoto(p), "released after its line, so it is judged on a complete redemption"); assertEquals(null, flag(p)["check_flag"])
            assertEquals("accepted", itemRow(cu(p))["status"]); assertEquals("accepted", itemRow(cu(l1))["status"]); assertEquals(0L, openNaming(head)); assertEquals(0L, openNaming(cu(l1)))
            assertEquals("duplicate", ack(sync(sr, listOf(p)), cu(p)).status()); assertEquals("duplicate", ack(sync(sr, listOf(l1)), cu(l1)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            assertEquals(1L, storedPhoto(p))
        }
    }

    @Test
    fun aPhotoOfAnotherUploaderHeldForItsOwnReasonKeepsItsOwnCodeNextToAnOpenLine() = world.withApi {
        with(world) {
            val tso = phone("tso1001", "tso")
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 2); val l1 = lineRec(head, 120)
            val l2 = lineRec(head, oldSecs)   // held for its own reason (the window) and stays open for its own review: a line that is open next to the photo
            val p = photo(null, 100, unit = head to 1, flat = lat + 0.0001)
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status()); assertEquals("quarantined", ack(sync(sr, listOf(l1)), cu(l1)).status())
            assertEquals("quarantined", ack(sync(sr, listOf(l2)), cu(l2)).status()); assertEquals("quarantined", ack(sync(tso, listOf(p)), cu(p)).status())
            assertEquals("parent $head is quarantined (business_date_out_of_window)", itemRow(cu(p))["detail"])
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            val q = itemRow(cu(p))
            assertEquals("open", q["status"], "it is a real separate hold"); assertEquals("scope_out_of_reach", q["code"], "its OWN code: the header is accepted and the photo is of another user, not the open line's code")
            assertEquals(true, (q["detail"] as String).contains("belongs to another user"), q.toString()); assertEquals(0L, openNaming(head))
            assertEquals("open", itemRow(cu(l2))["status"], "the other line is untouched")
            assertEquals("quarantined", ack(sync(tso, listOf(p)), cu(p)).status(), "a resend is still answered quarantined"); assertEquals(0L, storedPhoto(p))
        }
    }

    @Test
    fun aPhotoAfterAReturnToDeviceOfItsHeaderIsParentMissingNotHeldUnderAClosedItem() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            assertEquals("quarantined", ack(sync(sr, listOf(headerRec(head, 1))), head).status())
            assertEquals(HttpStatusCode.OK, postWeb("admin1001", "/v1/admin/quarantine/${itemOf(head)}/resolve", """{"action":"return_to_device","reason":"send the redemption again please"}""").status)
            val p = photo(null, 100, unit = head to 1, flat = lat + 0.00011)
            val a = ack(sync(sr, listOf(p)), cu(p)); assertEquals("rejected", a.status(), a.toString()); assertEquals("parent_missing", a.code())
            assertEquals(0L, one("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = CAST('${cu(p)}' AS uuid)"), "no item is created for it")
        }
    }

    @Test
    fun aRefusedRecordOfAnotherTypeUnderTheParentUuidIsNotADayFinalRefusalOfThePhoto() = world.withApi {
        with(world) {
            val other = UUID.randomUUID().toString(); registry(other, "visit", "rejected", "day_final_submitted")
            val p = photo(null, 100, unit = other to 1, flat = lat + 0.00012)
            val a = ack(sync(sr, listOf(p)), cu(p)); assertEquals("rejected", a.status(), a.toString()); assertEquals("parent_missing", a.code(), "not day_final_submitted: a visit is not its redemption")
        }
    }

    private suspend fun io.ktor.client.HttpClient.returnToDevice(item: Long): HttpStatusCode =
        with(world) { postWeb("admin1001", "/v1/admin/quarantine/$item/resolve", """{"action":"return_to_device","reason":"send it again please, thank you"}""").status }
    private fun openItems(uuid: String) = world.one("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = CAST('$uuid' AS uuid) AND status = 'open'")
    private fun allItems(uuid: String) = world.one("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = CAST('$uuid' AS uuid)")

    @Test
    fun aRecordReturnedToTheDeviceAndHeldAgainWithTheSamePayloadReopensItsOneItem() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString(); val h = headerRec(head, 1)   // a non-gift-photo record on the generic path
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status()); assertEquals(1L, openItems(head))
            assertEquals(HttpStatusCode.OK, returnToDevice(itemOf(head))); assertEquals(0L, openItems(head))
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status(), "the same payload sent again is judged again and held again")
            assertEquals(1L, openItems(head), "exactly one open item"); assertEquals(1L, allItems(head), "the same row, re-opened"); assertEquals(1L, one("SELECT reopen_count FROM app.sync_quarantine WHERE client_uuid = CAST('$head' AS uuid)"))
            assertEquals(1L, one("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = CAST('$head' AS uuid) AND status = 'open' AND resolved_at IS NULL AND resolution_note IS NULL"))
            val list = getWeb("admin1001", "/v1/admin/quarantine?status=open&limit=200").bodyAsText()
            assertEquals(true, list.contains(head), "visible in the review list")
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status()); assertEquals(1L, openItems(head)); assertEquals(1L, one("SELECT reopen_count FROM app.sync_quarantine WHERE client_uuid = CAST('$head' AS uuid)"), "a replay while open changes nothing")
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            assertEquals(1L, one("SELECT count(*) FROM app.redemption WHERE client_uuid = CAST('$head' AS uuid)"))
            assertEquals("duplicate", ack(sync(sr, listOf(h)), head).status())
        }
    }

    @Test
    fun aDiscardedItemIsFinalAndIsNeverReopenedByAResend() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString(); val h = headerRec(head, 1)
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status())
            assertEquals(HttpStatusCode.OK, discard(itemOf(head)))
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status(), "answered as before"); assertEquals(0L, openItems(head)); assertEquals(0L, one("SELECT reopen_count FROM app.sync_quarantine WHERE client_uuid = CAST('$head' AS uuid)"))
        }
    }

    @Test
    fun aGiftPhotoReturnedToTheDeviceAndHeldAgainReopensItsItemAndTheHeadersAcceptThenReleasesIt() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 1); val l1 = lineRec(head, 90); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00013)
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status()); assertEquals("quarantined", ack(sync(sr, listOf(l1)), cu(l1)).status()); assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status())
            assertEquals(HttpStatusCode.OK, returnToDevice(itemOf(cu(p)))); assertEquals(0L, openItems(cu(p)))
            assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status()); assertEquals(1L, openItems(cu(p))); assertEquals(1L, allItems(cu(p)))
            assertEquals("parent $head is quarantined (business_date_out_of_window)", itemRow(cu(p))["detail"])
            assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status()); assertEquals(1L, openItems(cu(p)))
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            assertEquals(1L, storedPhoto(p)); assertEquals("accepted", itemRow(cu(p))["status"]); assertEquals(0L, openNaming(head))
        }
    }

    private val fixOk = """{"purpose":"PURPOSE","fix_status":"ok","lat":$lat,"lng":$lng,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    @Test
    fun aDueCollectionWhoseOtherParentHasNotSyncedStaysHeldAfterItsMemoIsAcceptedAndIsNeverTurnedIntoAFinalDecision() = world.withApi {
        with(world) {
            // the memo's visit and the memo are old (held for the backdate window); the collection (today) is held with the memo; its OWN visit (visit_client_uuid) has not synced
            val visit = UUID.randomUUID().toString(); val memo = UUID.randomUUID().toString(); val otherVisit = UUID.randomUUID().toString(); val dueCu = UUID.randomUUID().toString()
            val bd = today.minusDays(15)
            val v = record("visit", """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${java.time.Instant.now()}","sequence_no":1,"planned":true,"fix":${fixOk.replace("PURPOSE", "visit_open")},
                "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""", routeId = route, cu = visit, family = visit, secondsAgo = oldSecs)
            val m = record("memo", """{"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"sr1001-${bd.toString().substring(2).replace("-", "")}-5901","memo_kind":"sale","committed_at":"${java.time.Instant.now()}",
                "price_list_date":"$bd","price_type":"outlet","gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"round_adj_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"is_credit":false,
                "line_count":0,"discount_line_count":0,"qc_line_count":0,"offer_version_ids":[],"rounding_mode":"half_up_paisa"}""", routeId = route, cu = memo, family = visit, rank = 1, secondsAgo = oldSecs)
            val d = record("due_collection", """{"outlet_id":$outlet,"against_memo_client_uuid":"$memo","against_memo_no":"x","against_memo_business_date":"$bd","amount_mtk":1000,"is_full_settlement":false,"outstanding_before_mtk":100000,"payment_mode":"cash","visit_client_uuid":"$otherVisit"}""",
                routeId = route, cu = dueCu, family = dueCu, secondsAgo = 100)
            assertEquals("quarantined", ack(sync(sr, listOf(v)), visit).status()); assertEquals("quarantined", ack(sync(sr, listOf(m)), memo).status())
            assertEquals("quarantined", ack(sync(sr, listOf(d)), dueCu).status()); assertEquals("parent $memo is quarantined (business_date_out_of_window)", itemRow(dueCu)["detail"])
            assertEquals(HttpStatusCode.OK, accept(itemOf(visit))); assertEquals(HttpStatusCode.OK, accept(itemOf(memo)))
            assertEquals(1L, one("SELECT count(*) FROM app.memo WHERE client_uuid = CAST('$memo' AS uuid)"))
            assertEquals("open", itemRow(dueCu)["status"], "its own visit has not synced: it stays held, it is NOT closed as a decision the phone would read as final")
            assertEquals(true, (itemRow(dueCu)["detail"] as String).startsWith("waits for another parent")); assertEquals(0L, openNaming(memo))
            val feed = sync(sr, listOf(d)); assertEquals("quarantined", ack(feed, dueCu).status()); assertEquals(emptyList<String>(), phoneResolutions(feed, dueCu))
            assertEquals(0L, one("SELECT count(*) FROM app.due_collection WHERE client_uuid = CAST('$dueCu' AS uuid)"))
            // its visit arrives; the reviewer's accept then stores the collection
            val v2 = record("visit", """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${java.time.Instant.now()}","sequence_no":2,"planned":true,"fix":${fixOk.replace("PURPOSE", "visit_open")},
                "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""", routeId = route, cu = otherVisit, family = otherVisit, secondsAgo = 150)
            assertEquals("accepted", ack(sync(sr, listOf(v2)), otherVisit).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(dueCu)))
            assertEquals(1L, one("SELECT count(*) FROM app.due_collection WHERE client_uuid = CAST('$dueCu' AS uuid)"), "device count == server count")
            assertEquals("duplicate", ack(sync(sr, listOf(d)), dueCu).status())
        }
    }

    @Test
    fun theReopenOfAReturnedItemIsGenericAndWorksForAVisitToo() = world.withApi {
        with(world) {
            val visit = UUID.randomUUID().toString()
            val v = record("visit", """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${java.time.Instant.now()}","sequence_no":3,"planned":true,"fix":${fixOk.replace("PURPOSE", "visit_open")},
                "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""", routeId = route, cu = visit, family = visit, secondsAgo = oldSecs)
            assertEquals("quarantined", ack(sync(sr, listOf(v)), visit).status())
            assertEquals(HttpStatusCode.OK, returnToDevice(itemOf(visit))); assertEquals(0L, openItems(visit))
            assertEquals("quarantined", ack(sync(sr, listOf(v)), visit).status()); assertEquals(1L, openItems(visit)); assertEquals(1L, allItems(visit))
            assertEquals("quarantined", ack(sync(sr, listOf(v)), visit).status()); assertEquals(1L, openItems(visit), "a replay changes nothing")
            assertEquals(HttpStatusCode.OK, accept(itemOf(visit))); assertEquals(1L, one("SELECT count(*) FROM app.visit WHERE client_uuid = CAST('$visit' AS uuid)"))
        }
    }

    // ---- D-LOY-API-28: the worker sweep of WAITING items, the review queue's state and count, and the as-sent hash through every child path ----
    private fun sweep() = waitingSweepJob(world.wiring, world.clock, world.fresh.url).tick()!!
    private suspend fun io.ktor.client.HttpClient.queue(): kotlinx.serialization.json.JsonObject =
        with(world) { kotlinx.serialization.json.Json.parseToJsonElement(getWeb("admin1001", "/v1/admin/quarantine?status=open&limit=200").bodyAsText()).jsonObject }
    private fun queueItem(q: kotlinx.serialization.json.JsonObject, uuid: String) = q["items"]!!.jsonArray.map { it.jsonObject }.single { it["client_uuid"]!!.jsonPrimitive.content == uuid }
    /** The stored copy of a held record differs from what the phone sent (as when a device member is dropped on storage): the registry must keep the as-sent hash on every release path. */
    private fun storedCopyDiffers(vararg uuids: String) = uuids.forEach { world.exec("UPDATE app.sync_quarantine SET payload = jsonb_set(payload, '{captured_elapsed_ms}', '1'::jsonb) WHERE client_uuid = CAST('$it' AS uuid) AND status = 'open'") }

    @Test
    fun aWaitingPhotoIsReleasedByTheWorkerSweepWhenItsLastLineIsStoredAndTheQueueShowsItsStateAndCount() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00021)
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status()); assertEquals("quarantined", ack(sync(sr, listOf(l1)), cu(l1)).status()); assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            // waiting: the queue says so (state, awaited parent, count), and the item's code is NOT presented as its own state
            val q = queue()
            val it1 = queueItem(q, cu(p))
            assertEquals(true, it1["waiting"]!!.jsonPrimitive.content.toBoolean()); assertEquals(head, it1["waits_for_uuid"]!!.jsonPrimitive.content); assertEquals(false, it1["waits_for_refused"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(false, (it1["detail"]!!.jsonPrimitive.content).contains("is quarantined"), "it does not show the first parent's hold as its own")
            assertEquals(true, q["waiting_count"]!!.jsonPrimitive.content.toInt() >= 1)
            val before = q["waiting_count"]!!.jsonPrimitive.content.toInt()
            // the other line has not synced: a pass changes nothing and finalises nothing
            sweep().also { assertEquals(0, it.released, it.toString()) }; assertEquals("open", itemRow(cu(p))["status"]); assertEquals(0L, storedPhoto(p))
            // the line arrives; the sweep releases the photo through the accept path, as the reviewer's accept would
            val l2 = lineRec(head, 90); assertEquals("accepted", ack(sync(sr, listOf(l2)), cu(l2)).status())
            sweep().also { assertEquals(1, it.released, it.toString()) }
            assertEquals("accepted", itemRow(cu(p))["status"]); assertEquals(true, (itemRow(cu(p))["resolution_note"] as String).startsWith("released by the waiting-item sweep (job)")); assertEquals(1L, storedPhoto(p)); assertEquals(null, flag(p)["check_flag"])
            assertEquals("aron.system", rows("SELECT u.username FROM app.sync_quarantine q JOIN app.app_user u ON u.id = q.resolved_by_user_id WHERE q.client_uuid = CAST('${cu(p)}' AS uuid)").single()["username"], "a job did it: the system actor, not the reviewer of the first parent")
            assertEquals(before - 1, queue()["waiting_count"]!!.jsonPrimitive.content.toInt())
            val feed = sync(sr, listOf(p)); assertEquals("duplicate", ack(feed, cu(p)).status()); assertEquals(listOf("accepted"), phoneResolutions(feed, cu(p)))
            sweep().also { assertEquals(0, it.released) }; assertEquals(1L, storedPhoto(p), "a second pass stores nothing more")
        }
    }

    @Test
    fun theSweepLeavesAnItemLockedByAReviewerAloneAndANonOpenOneIsNeverTouched() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00022)
            for (r in listOf(h, l1, p)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            val l2 = lineRec(head, 90); assertEquals("accepted", ack(sync(sr, listOf(l2)), cu(l2)).status())
            // a reviewer is in the middle of resolving the photo's item (its row lock): the sweep skips it, nothing is lost, nothing is stored twice
            fresh.dataSource.connection.use { c ->
                c.autoCommit = false
                c.createStatement().use { it.execute("SELECT 1 FROM app.sync_quarantine WHERE client_uuid = CAST('${cu(p)}' AS uuid) AND status = 'open' FOR UPDATE") }
                sweep().also { assertEquals(0, it.released, it.toString()); assertEquals(0, it.failed) }
                c.rollback()
            }
            assertEquals("open", itemRow(cu(p))["status"]); assertEquals(0L, storedPhoto(p))
            // the reviewer's accept wins; the sweep afterwards finds nothing open and stores nothing again
            assertEquals(HttpStatusCode.OK, accept(itemOf(cu(p)))); assertEquals(1L, storedPhoto(p))
            sweep().also { assertEquals(0, it.released) }; assertEquals(1L, storedPhoto(p)); assertEquals("accepted", itemRow(cu(p))["status"])
            assertEquals(false, (itemRow(cu(p))["resolution_note"] as String).startsWith("released by the waiting-item sweep (job)"), "the reviewer's own accept stands")
        }
    }

    @Test
    fun aWaitingPhotoWhoseOtherParentWasRefusedIsNeverFinalisedByTheSweepAndTheDiscardIsTheWayOut() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00023)
            for (r in listOf(h, l1, p)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            assertEquals("open", itemRow(cu(p))["status"]); assertEquals(false, (itemRow(cu(p))["detail"] as String).contains("refused"))
            // the second line is then refused for good
            registry(UUID.randomUUID().toString(), "redemption_line", "rejected", "schema_invalid", familyOf(head))
            sweep().also { assertEquals(0, it.released, it.toString()); assertEquals(1, it.hinted, it.toString()) }
            assertEquals("open", itemRow(cu(p))["status"], "not finalised: the photo is not stored and not closed"); assertEquals(0L, storedPhoto(p))
            val item = queueItem(queue(), cu(p))
            assertEquals(true, item["waits_for_refused"]!!.jsonPrimitive.content.toBoolean()); assertEquals(true, (item["detail"]!!.jsonPrimitive.content).contains("the other parent was refused or discarded"))
            sweep().also { assertEquals(0, it.hinted, "the hint is written once"); assertEquals(0, it.released) }
            val feed = sync(sr, listOf(p)); assertEquals(emptyList<String>(), phoneResolutions(feed, cu(p)), "the phone is told nothing final")
            assertEquals(HttpStatusCode.OK, discard(itemOf(cu(p)))); assertEquals("discarded", itemRow(cu(p))["status"]); assertEquals(0L, storedPhoto(p))
            assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status(), "the phone's resend after the discard is answered as before; nothing is stored"); assertEquals(0L, storedPhoto(p))
            sweep().also { assertEquals(0, it.released) }; assertEquals(0L, storedPhoto(p))
        }
    }

    @Test
    fun theAsSentHashSurvivesTheReleaseLoopTheFollowedDiscardAndAWaitingChildReleasedByTheSweep() = world.withApi {
        with(world) {
            // (a) the re-release loop: the photo is held before its line (ids order it first), the stored copies differ from what the phone sent
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 1); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00024); val l1 = lineRec(head, 90)
            assertEquals("quarantined", ack(sync(sr, listOf(h)), head).status()); assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status()); assertEquals("quarantined", ack(sync(sr, listOf(l1)), cu(l1)).status())
            storedCopyDiffers(cu(p), cu(l1))
            assertEquals(HttpStatusCode.OK, accept(itemOf(head))); assertEquals(1L, storedPhoto(p))
            for (r in listOf(p, l1)) { val a = ack(sync(sr, listOf(r)), cu(r)); assertEquals("duplicate", a.status(), "loop: the as-sent resend of ${r["type"]}: $a") }
            // (b) followDiscardedParent: the header is discarded, the photo follows (stored, flagged); the as-sent resend is a duplicate
            val head2 = UUID.randomUUID().toString()
            val h2 = headerRec(head2, 1); val l2 = lineRec(head2, 90); val p2 = photo(null, 100, unit = head2 to 1, flat = lat + 0.00025)
            for (r in listOf(h2, l2, p2)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
            storedCopyDiffers(cu(p2))
            assertEquals(HttpStatusCode.OK, discard(itemOf(head2))); assertEquals(1L, storedPhoto(p2)); assertEquals("parent_refused", flag(p2)["check_flag"])
            assertEquals("duplicate", ack(sync(sr, listOf(p2)), cu(p2)).status(), "followed discard: the as-sent resend")
            // (c) a waiting child released by the sweep: the line that completes the redemption arrives later
            val head3 = UUID.randomUUID().toString()
            val h3 = headerRec(head3, 2); val l3 = lineRec(head3, 120); val p3 = photo(null, 100, unit = head3 to 1, flat = lat + 0.00026)
            for (r in listOf(h3, l3, p3)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
            storedCopyDiffers(cu(l3), cu(p3))
            assertEquals(HttpStatusCode.OK, accept(itemOf(head3))); assertEquals("open", itemRow(cu(p3))["status"], "waits")
            assertEquals("duplicate", ack(sync(sr, listOf(l3)), cu(l3)).status(), "loop: the line, as sent")
            val l4 = lineRec(head3, 90); assertEquals("accepted", ack(sync(sr, listOf(l4)), cu(l4)).status())
            sweep().also { assertEquals(1, it.released, it.toString()) }
            assertEquals(1L, storedPhoto(p3)); assertEquals("duplicate", ack(sync(sr, listOf(p3)), cu(p3)).status(), "sweep: the as-sent resend of the waiting photo")
            assertEquals(1L, one("SELECT count(*) FROM app.gift_photo WHERE client_uuid = CAST('${cu(p3)}' AS uuid)"))
        }
    }

    /** A photo that waits: header (2 lines) accepted with the line l1, the second line l2 stored afterwards; returns the photo. */
    private suspend fun io.ktor.client.HttpClient.waitingPhotoWithItsLineStored(flat: Double): JsonObject = with(world) {
        val head = UUID.randomUUID().toString()
        val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = flat)
        for (r in listOf(h, l1, p)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
        assertEquals(HttpStatusCode.OK, accept(itemOf(head))); assertEquals("open", itemRow(cu(p))["status"])
        val l2 = lineRec(head, 90); assertEquals("accepted", ack(sync(sr, listOf(l2)), cu(l2)).status())
        p
    }

    @Test
    fun twoSweepersOnTheSameItemReleaseItOnce() = world.withApi {
        with(world) {
            repeat(3) { round ->
                val p = waitingPhotoWithItsLineStored(lat + 0.0003 + round * 0.00001)
                val a = waitingSweepJob(wiring, clock, fresh.url); val b = waitingSweepJob(wiring, clock, fresh.url)   // two replicas: two jobs, two ingest services
                val go = java.util.concurrent.CountDownLatch(1)
                val results = listOf(a, b).map { job -> java.util.concurrent.CompletableFuture.supplyAsync { go.await(); job.tick() } }
                go.countDown()
                val done = results.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS)!! }
                assertEquals(1, done.sumOf { it.released }, "released exactly once across both sweepers: $done"); assertEquals(0, done.sumOf { it.failed }, done.toString())
                assertEquals(1L, one("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = CAST('${cu(p)}' AS uuid) AND status = 'accepted'"), "the item was closed once")
                assertEquals(1L, storedPhoto(p), "stored once"); assertEquals("accepted", itemRow(cu(p))["status"])
                assertEquals("duplicate", ack(sync(sr, listOf(p)), cu(p)).status())
            }
        }
    }

    @Test
    fun theStartedJobSweepsOnItsOwnAndStopsCleanlyAndTheSwitchesStopIt() = world.withApi {
        with(world) {
            // switched off by the emergency env: nothing is released, tick says so
            val p0 = waitingPhotoWithItsLineStored(lat + 0.00041)
            assertEquals(null, waitingSweepJob(wiring, clock, fresh.url, env = { if (it == "ARON_WAITING_SWEEP") "off" else null }).tick(), "off by env")
            assertEquals("open", itemRow(cu(p0))["status"])
            // started explicitly (no test starts it otherwise): the first pass is at once and releases the item; stop() returns and the job answers nothing afterwards
            val job = waitingSweepJob(wiring, clock, fresh.url, firstDelayS = 0)
            job.start()
            val t0 = System.currentTimeMillis()
            while (itemRow(cu(p0))["status"] == "open" && System.currentTimeMillis() - t0 < 30_000) Thread.sleep(100)
            job.stop()
            assertEquals("accepted", itemRow(cu(p0))["status"], "released by the job's own schedule"); assertEquals(1L, storedPhoto(p0))
            assertEquals(null, job.tick(), "stopped: no further pass")
            // a second pass never overlaps the first: the guard answers null while one is running (same job, concurrent ticks)
            val p1 = waitingPhotoWithItsLineStored(lat + 0.00042)
            val j2 = waitingSweepJob(wiring, clock, fresh.url); val go = java.util.concurrent.CountDownLatch(1)
            val rs = (1..4).map { java.util.concurrent.CompletableFuture.supplyAsync { go.await(); j2.tick() } }; go.countDown()
            val outs = rs.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, outs.filterNotNull().sumOf { it.released }, "one release in all"); assertEquals(1L, storedPhoto(p1))
        }
    }

    @Test
    fun theWorkerRolesStillCannotWriteTheIngestTables() = world.withApi {
        with(world) {
            val tables = listOf("sync_quarantine", "ingest_registry", "memo", "memo_line", "due_collection", "redemption", "redemption_line", "gift_photo", "gift_assignment")
            for (role in listOf("worker_rw", "jobs_rw")) for (t in tables) for (priv in listOf("INSERT", "UPDATE")) {
                assertEquals(false, one("SELECT CASE WHEN has_table_privilege('$role', 'app.$t', '$priv') THEN 1 ELSE 0 END") == 1L, "$role must not hold $priv on app.$t: widening the worker to the money tables is a least-privilege decision (D-LOY-API-28)")
            }
            for (t in tables) assertEquals(true, one("SELECT CASE WHEN has_table_privilege('api_rw', 'app.$t', 'UPDATE') THEN 1 ELSE 0 END") == 1L, "api_rw holds UPDATE on app.$t (the sweep's role)")
            // and a real statement as the worker role is refused
            fresh.dataSource.connection.use { c -> c.createStatement().use { st -> st.execute("SET ROLE jobs_rw"); try { assertTrue(runCatching { st.execute("UPDATE app.sync_quarantine SET detail = detail WHERE false") }.isFailure, "permission denied") } finally { st.execute("RESET ROLE") } } }
        }
    }

    // ---- D-LOY-API-30: the lead's review of the sweep (wrong waiver, audit row, starvation, own-check failure) ----
    @Test
    fun theSweepExaminesTheLeastRecentlyCheckedFirstSoUnreleasableItemsNeverStarveTheReleasableOne() = world.withApi {
        with(world) {
            val stuck = (1..3).map { i ->
                val head = UUID.randomUUID().toString()
                val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = lat + 0.0005 + i * 0.00001)
                for (r in listOf(h, l1, p)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
                assertEquals(HttpStatusCode.OK, accept(itemOf(head))); p   // the second line never syncs: it waits for ever
            }
            val ok = waitingPhotoWithItsLineStored(lat + 0.0006)   // created last: the highest id
            val job = waitingSweepJob(wiring, clock, fresh.url, batch = 2)
            val first = job.tick()!!; assertEquals(0, first.released, first.toString()); assertEquals(true, first.skippedByCap >= 2, "the cap leaves the rest for the next pass: $first")
            assertEquals("open", itemRow(cu(ok))["status"])
            var released = first.released
            repeat(3) { released += job.tick()!!.released }
            assertEquals(1, released, "the releasable item was reached although three stuck items have lower ids"); assertEquals("accepted", itemRow(cu(ok))["status"]); assertEquals(1L, storedPhoto(ok))
            for (p in stuck) assertEquals("open", itemRow(cu(p))["status"])
        }
    }

    @Test
    fun stopCancelsThePendingPassAtOnceInsteadOfWaitingTheGrace() = world.withApi {
        with(world) {
            val job = waitingSweepJob(wiring, clock, fresh.url, firstDelayS = 3600)
            job.start(); val t0 = System.currentTimeMillis(); job.stop(graceS = 20)
            assertEquals(true, System.currentTimeMillis() - t0 < 5_000, "stop returned in ${System.currentTimeMillis() - t0} ms")
        }
    }

    // ---- D-LOY-API-31: the second review (a stale marker after a close or a re-open), the backfill, a reviewer racing the sweep, a non-national count ----
    private fun markerCols(uuid: String) = world.rows("SELECT waiting_since IS NOT NULL AS w, awaited_uuid IS NOT NULL AS a, awaited_field IS NOT NULL AS f, accepted_parent_uuid IS NOT NULL AS p, waiting_release_code AS rc, awaited_refused AS r, waiting_checked_at IS NOT NULL AS c, status FROM app.sync_quarantine WHERE client_uuid = CAST('$uuid' AS uuid) ORDER BY id DESC LIMIT 1").single()
    private suspend fun io.ktor.client.HttpClient.waitingCount(user: String = "admin1001"): Int = with(world) { queueOf(user)["waiting_count"]!!.jsonPrimitive.content.toInt() }
    private suspend fun io.ktor.client.HttpClient.queueOf(user: String): kotlinx.serialization.json.JsonObject =
        with(world) { kotlinx.serialization.json.Json.parseToJsonElement(getWeb(user, "/v1/admin/quarantine?status=open&limit=200").bodyAsText()).jsonObject }

    @Test
    fun aWaitingItemReturnedToTheDeviceAndHeldAgainStartsCleanAndTheSweepNeverReleasesIt() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00061)
            for (r in listOf(h, l1, p)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(head)))
            assertEquals(true, markerCols(cu(p))["w"], "waiting"); assertEquals("redemption_client_uuid", rows("SELECT awaited_field AS f FROM app.sync_quarantine WHERE client_uuid = CAST('${cu(p)}' AS uuid)").single()["f"])
            val countWaiting = waitingCount()
            assertEquals(HttpStatusCode.OK, returnToDevice(itemOf(cu(p))))
            val m = markerCols(cu(p)); assertEquals("discarded", m["status"]); assertEquals(false, m["w"], "the close cleared the marker: $m"); assertEquals(false, m["a"]); assertEquals(null, m["rc"]); assertEquals(false, m["c"])
            assertEquals(countWaiting - 1, waitingCount(), "no longer counted as waiting")
            // the missing line arrives but is itself held (too old); the phone resends the photo: judged again, held again with that line, the SAME row re-opens
            val l2 = lineRec(head, oldSecs); assertEquals("quarantined", ack(sync(sr, listOf(l2)), cu(l2)).status())
            assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status())
            val again = markerCols(cu(p)); assertEquals("open", again["status"]); assertEquals(1L, allItems(cu(p)), "the same row re-opened"); assertEquals(false, again["w"], "a re-opened item starts clean: $again"); assertEquals(null, again["rc"])
            assertEquals(false, queueItem(queue(), cu(p))["waiting"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(countWaiting - 1, waitingCount(), "waiting_count unchanged by the re-opened item")
            sweep().also { assertEquals(0, it.released, it.toString()); assertEquals(0, it.heldOwn, it.toString()) }
            assertEquals(0L, storedPhoto(p)); assertEquals("open", itemRow(cu(p))["status"])
        }
    }

    @Test
    fun aDiscardedWaitingItemCarriesNoMarkerAndANonOpenRowCannotCarryOne() = world.withApi {
        with(world) {
            val head = UUID.randomUUID().toString()
            val h = headerRec(head, 2); val l1 = lineRec(head, 120); val p = photo(null, 100, unit = head to 1, flat = lat + 0.00062)
            for (r in listOf(h, l1, p)) assertEquals("quarantined", ack(sync(sr, listOf(r)), cu(r)).status())
            assertEquals(HttpStatusCode.OK, accept(itemOf(head))); assertEquals(true, markerCols(cu(p))["w"])
            val before = waitingCount()
            assertEquals(HttpStatusCode.OK, discard(itemOf(cu(p))))
            val m = markerCols(cu(p)); assertEquals("discarded", m["status"]); assertEquals(false, m["w"], m.toString()); assertEquals(false, m["a"]); assertEquals(false, m["p"]); assertEquals(false, m["f"])
            assertEquals(before - 1, waitingCount())
            val l2 = lineRec(head, 90); assertEquals("accepted", ack(sync(sr, listOf(l2)), cu(l2)).status())
            sweep().also { assertEquals(0, it.released, it.toString()) }; assertEquals(0L, storedPhoto(p)); assertEquals("discarded", itemRow(cu(p))["status"])
            assertEquals("quarantined", ack(sync(sr, listOf(p)), cu(p)).status(), "answered as before; a discarded item is never re-opened"); assertEquals("discarded", itemRow(cu(p))["status"])
            // the database itself refuses a marker on a row that is not open
            val bad = runCatching { exec("UPDATE app.sync_quarantine SET waiting_since = now() WHERE client_uuid = CAST('${cu(p)}' AS uuid)") }
            assertEquals(true, bad.isFailure, "CHECK sync_quarantine_waiting_open")
        }
    }

    @Test
    fun aReviewersAcceptRacingTheSweepStoresTheRecordOnceAndNeitherGets500() = world.withApi {
        with(world) {
            repeat(3) { round ->
                val p = waitingPhotoWithItsLineStored(lat + 0.0007 + round * 0.00001)
                val item = itemOf(cu(p)); val job = waitingSweepJob(wiring, clock, fresh.url)
                val go = java.util.concurrent.CountDownLatch(1)
                val sweepF = java.util.concurrent.CompletableFuture.supplyAsync { go.await(); job.tick() }
                go.countDown()
                val r = postWeb("admin1001", "/v1/admin/quarantine/$item/resolve", """{"action":"accept","reason":"the reviewer accepts while the sweep runs"}""")
                sweepF.get(60, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(true, r.status == HttpStatusCode.OK || r.status == HttpStatusCode.Conflict, "accept answered ${r.status} ${r.bodyAsText()}")
                assertEquals(1L, storedPhoto(p), "stored once"); assertEquals("accepted", itemRow(cu(p))["status"]); assertEquals("duplicate", ack(sync(sr, listOf(p)), cu(p)).status())
            }
        }
    }

    @Test
    fun theWaitingCountOfANonNationalReviewerStaysInsideItsReach() = world.withApi {
        with(world) {
            val p = waitingPhotoWithItsLineStored(lat + 0.0008); val id = itemOf(cu(p))
            val lkxRoute = one("SELECT id FROM app.route WHERE code = 'LKX-SR-D'")
            val nat0 = waitingCount("admin1001"); val sup0 = waitingCount("support1001")
            assertEquals(true, nat0 >= 1 && sup0 >= 1 && sup0 <= nat0, "the item is on the MIR route: both see it ($nat0, $sup0)")
            exec("UPDATE app.sync_quarantine SET route_id = $lkxRoute WHERE id = $id")   // the same item, now on a route of another zone
            assertEquals(nat0, waitingCount("admin1001"), "national sees it everywhere"); assertEquals(sup0 - 1, waitingCount("support1001"), "the narrower reviewer's count drops by the item that left its reach")
            val q = queueOf("support1001"); assertEquals(false, q["items"]!!.jsonArray.any { it.jsonObject["client_uuid"]!!.jsonPrimitive.content == cu(p) }, "and the list agrees")
            exec("UPDATE app.sync_quarantine SET route_id = $route WHERE id = $id")
        }
    }

    @Test
    fun theV0207BackfillGivesTrain115WaitingItemsTheirMarkerWithNoWaiverAndUnnamedOnesAPlainDetail() = world.withApi {
        with(world) {
            val awaited = UUID.randomUUID().toString(); val accepted = UUID.randomUUID().toString()
            val named = UUID.randomUUID().toString(); val unnamed = UUID.randomUUID().toString()
            for ((u, d) in listOf(named to "waits for another parent: visit_client_uuid $awaited (parent $accepted was accepted)", unnamed to "waits for another parent: the redemption or its lines are not stored yet (parent $accepted was accepted)")) {
                held(u, "due_collection", code = "scope_out_of_reach")
                exec("UPDATE app.sync_quarantine SET detail = '$d' WHERE client_uuid = CAST('$u' AS uuid)")
            }
            val updates = File(repoRoot, "db/migrations/V0207__cfg_waiting_sweep_keys.sql").readText().split(Regex(";\\s*\\n")).map { it.lines().filterNot { l -> l.trimStart().startsWith("--") }.joinToString("\n").trim() }.filter { it.startsWith("UPDATE app.sync_quarantine") }
            assertEquals(2, updates.size, "the migration's two backfill statements")
            for (u in updates) exec(u)
            val n = markerCols(named); assertEquals(true, n["w"], n.toString()); assertEquals(true, n["a"]); assertEquals(true, n["p"]); assertEquals(true, n["f"]); assertEquals(null, n["rc"], "no waiver")
            assertEquals(awaited, rows("SELECT awaited_uuid::text AS a FROM app.sync_quarantine WHERE client_uuid = CAST('$named' AS uuid)").single()["a"]); assertEquals("visit_client_uuid", rows("SELECT awaited_field AS f FROM app.sync_quarantine WHERE client_uuid = CAST('$named' AS uuid)").single()["f"])
            val un = markerCols(unnamed); assertEquals(false, un["w"], un.toString())
            assertEquals(true, (itemRow(unnamed)["detail"] as String).startsWith("held after its first parent was accepted"), itemRow(unnamed).toString())
            exec("UPDATE app.sync_quarantine SET status = 'discarded' WHERE client_uuid IN (CAST('$named' AS uuid), CAST('$unnamed' AS uuid))")
        }
    }
}

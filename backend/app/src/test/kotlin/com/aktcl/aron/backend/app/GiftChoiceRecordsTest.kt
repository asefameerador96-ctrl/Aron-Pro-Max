package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import com.aktcl.aron.backend.platform.AsthaQuarter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Push C: the `gift_choice` sync record (contract 1.15.0, SRM-B-66) through POST /v1/sync/batch: stored once by client_uuid, last writer by captured_at wins in any arrival order until the
 * hand-over photo locks the outlet's gift, refused with a reason after the lock, for a gift of another tier, for a role outside cfg.astha.gift_choice_roles and for an outlet out of reach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GiftChoiceRecordsTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private lateinit var amo: TsoPhone
    private lateinit var tso: TsoPhone
    private lateinit var today: LocalDate
    private lateinit var quarter: String
    private var programme = 0L
    private var gA = 0L
    private var gB = 0L
    private var gSilver = 0L
    private var outletGold = 0L
    private var outletGold2 = 0L
    private var outletSilver = 0L
    private var outletLkx = 0L
    private var route = 0L

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))
        with(world) {
            today = today(); quarter = AsthaQuarter.of(today, 1).label
            sr = phone("sr1001", "sr"); amo = phone("amo1001", "amo"); tso = phone("tso1001", "tso")
            route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val o = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 3")
            outletGold = o[0]; outletGold2 = o[1]; outletSilver = o[2]
            outletLkx = one("SELECT o.id FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'LKX-SR-D' AND o.status = 'active' ORDER BY o.id LIMIT 1")
            programme = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'ASTHA-GC', 'Astha gift choice', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            for ((o2, tier) in listOf(outletGold to "gold", outletGold2 to "gold", outletSilver to "silver", outletLkx to "gold"))
                exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($programme, $o2, '$tier', DATE '${today.minusDays(30)}')")
            gA = one("INSERT INTO app.gift (programme_id, code, name_en, tier_codes) VALUES ($programme, 'gc_a', 'Gift A', '{gold}') RETURNING id")
            gB = one("INSERT INTO app.gift (programme_id, code, name_en, tier_codes) VALUES ($programme, 'gc_b', 'Gift B', '{gold}') RETURNING id")
            gSilver = one("INSERT INTO app.gift (programme_id, code, name_en, tier_codes) VALUES ($programme, 'gc_s', 'Gift S', '{silver}') RETURNING id")
        }
    }

    @AfterAll fun tearDown() = world.close()

    private fun choice(outlet: Long, gift: Long, secondsAgo: Long = 60, cu: String = UUID.randomUUID().toString(), q: String = quarter) =
        world.record("gift_choice", """{"outlet_id":$outlet,"gift_id":$gift,"quarter":"$q"}""", routeId = route, cu = cu, secondsAgo = secondsAgo)

    private fun chosen(outlet: Long): Long? = world.rows("SELECT gift_id FROM app.gift_assignment WHERE programme_id = $programme AND quarter = '$quarter' AND outlet_id = $outlet").singleOrNull()?.get("gift_id") as Long?
    private fun stored(outlet: Long) = world.one("SELECT count(*) FROM app.gift_choice WHERE outlet_id = $outlet")

    @Test
    fun aChoiceIsStoredOnceByClientUuidAndSetsTheAssignment() = world.withApi {
        with(world) {
            val r = choice(outletGold, gA); val cu = r["client_uuid"].toString().trim('"')
            val a = ack(sync(sr, listOf(r)), cu)
            assertEquals("accepted", a.status(), a.toString()); assertEquals(gA, chosen(outletGold))
            assertEquals(sr.userId, rows("SELECT chosen_by_user_id FROM app.gift_assignment WHERE outlet_id = $outletGold").single()["chosen_by_user_id"])
            assertEquals("duplicate", ack(sync(sr, listOf(r)), cu).status(), "a retry of the same record is a duplicate")
            assertEquals(1L, stored(outletGold)); assertEquals(gA, chosen(outletGold))
        }
    }

    @Test
    fun theLatestCapturedChoiceWinsInAnyArrivalOrder() = world.withApi {
        with(world) {
            val older = choice(outletGold2, gA, secondsAgo = 900); val newer = choice(outletGold2, gB, secondsAgo = 100)
            sync(sr, listOf(newer)); sync(sr, listOf(older))   // the older one arrives last
            assertEquals(gB, chosen(outletGold2), "an older choice that arrives late changes nothing")
            assertEquals(2L, stored(outletGold2), "but it is kept as a record")
        }
    }

    @Test
    fun propertyAnyArrivalOrderOfChoicesEndsOnTheLatestCaptured() = world.withApi {
        with(world) {
            // fresh outlets of the route for each trial would exceed the seed: reuse one outlet per trial with a new quarter label instead
            val q0 = AsthaQuarter.of(today, 1)
            repeat(12) { trial ->
                val rnd = Random(77L + trial)
                val label = "${q0.label.take(4).toInt() + 1 + trial}-Q${1 + trial % 4}"
                val recs = (1..5).map { i -> choice(outletGold, if (rnd.nextBoolean()) gA else gB, secondsAgo = 100L * i + trial, q = label) }
                val latest = recs[0]
                for (r in recs.shuffled(rnd)) sync(sr, listOf(r))
                val want = latest["payload"]!!.toString().substringAfter("\"gift_id\":").substringBefore(",").toLong()
                val got = rows("SELECT gift_id FROM app.gift_assignment WHERE programme_id = $programme AND quarter = '$label' AND outlet_id = $outletGold").single()["gift_id"] as Long
                assertEquals(want, got, "trial $trial")
            }
        }
    }

    @Test
    fun refusalsCarryTheirReason() = world.withApi {
        with(world) {
            suspend fun outcome(p: TsoPhone, r: kotlinx.serialization.json.JsonObject) = ack(sync(p, listOf(r)), r["client_uuid"].toString().trim('"')).let { it.status() to it.code() }
            assertEquals("rejected" to "gift_not_for_tier", outcome(sr, choice(outletGold, gSilver)), "a silver gift for a gold outlet")
            assertEquals("rejected" to "unknown_gift", outcome(sr, choice(outletGold, 9_999_999)))
            assertEquals("quarantined" to "scope_out_of_reach", outcome(sr, choice(outletLkx, gA)), "an outlet of another division")
            assertEquals("quarantined" to "scope_out_of_reach", outcome(amo, choice(outletGold, gA)), "the AMO is not in cfg.astha.gift_choice_roles (default SR and TSO)")
            assertEquals("accepted", outcome(tso, choice(outletSilver, gSilver)).first, "the zone's TSO may choose")
            assertEquals(gSilver, chosen(outletSilver))
        }
    }

    @Test
    fun aChoiceAfterTheHandOverPhotoLockIsRefusedAndTheLockedGiftStays() = world.withApi {
        with(world) {
            val first = choice(outletGold, gB, secondsAgo = 30)
            sync(sr, listOf(first))
            exec("UPDATE app.gift_assignment SET locked_at = now() WHERE programme_id = $programme AND quarter = '$quarter' AND outlet_id = $outletGold")
            val late = choice(outletGold, gA, secondsAgo = 5)
            val a = ack(sync(sr, listOf(late)), late["client_uuid"].toString().trim('"'))
            assertEquals("rejected", a.status()); assertEquals("gift_locked", a.code())
            assertEquals(gB, chosen(outletGold), "the locked gift is untouched")
            assertTrue(world.one("SELECT count(*) FROM app.gift_choice WHERE client_uuid = CAST('${late["client_uuid"].toString().trim('"')}' AS uuid)") == 0L, "a refused choice stores nothing")
        }
    }
}

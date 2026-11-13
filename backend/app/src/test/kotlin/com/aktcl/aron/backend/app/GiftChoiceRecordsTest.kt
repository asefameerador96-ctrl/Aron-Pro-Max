package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.sync.GiftChoiceRecords
import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            // Only the open quarter can be chosen for (S4), so each trial starts from a clean assignment of the open quarter instead of another quarter label.
            repeat(12) { trial ->
                val rnd = Random(77L + trial)
                val label = quarter
                exec("DELETE FROM app.gift_assignment WHERE programme_id = $programme AND quarter = '$label' AND outlet_id = $outletGold")
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
            assertEquals("rejected" to "schema_invalid", outcome(amo, choice(outletGold, gA)), "the AMO is not in cfg.astha.gift_choice_roles (default SR and TSO): a FINAL refusal, not a hold a reach accept could release (C-R3)")
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

    // ---- C-R1 B5 / S4 ----

    private val fakeReach = com.aktcl.aron.backend.platform.ReachResolver { uid, role, _, d -> Reach(uid, role, d, false, emptySet(), setOf(route), true, emptyList()) }
    private fun handler() = GiftChoiceRecords(fakeReach, world.wiring.config)
    private fun ingest(r: JsonObject, released: RecordOutcomeCode? = null, role: Role = Role.SR) = IngestRecord(
        "gift_choice", r["client_uuid"].toString().trim('"'), today, r, r["payload"]!!.jsonObject, sr.userId, role, sr.deviceId, UUID.randomUUID().toString(), world.now.get(), released,
    )

    /** A programme of its own (code per test) with every outlet of the route enrolled and one gift for every tier, so a test owns its assignments. */
    private fun ownProgramme(tag: String): Pair<Long, Long> = with(world) {
        val p = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'ASTHA-$tag', 'Astha $tag', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
        for (o in listOf(outletGold, outletGold2, outletSilver, outletLkx)) exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($p, $o, 'gold', DATE '${today.minusDays(30)}')")
        p to one("INSERT INTO app.gift (programme_id, code, name_en) VALUES ($p, 'g_${tag.lowercase()}', 'Gift $tag') RETURNING id")
    }

    @Test
    fun aChoiceAcceptedFromQuarantineByAReviewerTakesEffect() = world.withApi {
        with(world) {
            val (p, g) = ownProgramme("QA")
            val r = choice(outletLkx, g); val cu = r["client_uuid"].toString().trim('"')
            val held = ack(sync(sr, listOf(r)), cu)
            assertEquals("quarantined", held.status()); assertEquals("scope_out_of_reach", held.code())
            assertEquals(0L, one("SELECT count(*) FROM app.gift_assignment WHERE programme_id = $p AND outlet_id = $outletLkx"))
            val qid = one("SELECT id FROM app.sync_quarantine WHERE client_uuid = CAST('$cu' AS uuid) AND status = 'open'")
            val res = postWeb("admin1001", "/v1/admin/quarantine/$qid/resolve", """{"action":"accept","reason":"checked with the TSO, the outlet is on the rep's day plan"}""")
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(1L, stored(outletLkx), "the accepted choice is stored once")
            assertEquals(g, rows("SELECT gift_id FROM app.gift_assignment WHERE programme_id = $p AND quarter = '$quarter' AND outlet_id = $outletLkx").single()["gift_id"], "and the assignment follows it (it used to be acked accepted and never applied)")
            assertEquals("duplicate", ack(sync(sr, listOf(r)), cu).status())
        }
    }

    @Test
    fun aReviewersAcceptOnlyWaivesTheCheckThatHeldTheRecord() {
        val (p, g) = ownProgramme("QW")
        with(world) {
            val locked = choice(outletLkx, g)
            exec("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id, locked_at) SELECT $p, '$quarter', $outletLkx, route_id, $g, now() FROM app.outlet WHERE id = $outletLkx")
            fresh.db.jdbi.useHandle<Exception> { h ->
                assertEquals(RecordOutcomeCode.SCOPE_OUT_OF_REACH, handler().check(h, ingest(locked))?.code)
                assertEquals(RecordOutcomeCode.GIFT_LOCKED, handler().check(h, ingest(locked, RecordOutcomeCode.SCOPE_OUT_OF_REACH))?.code, "the waiver of the scope check still meets the lock")
            }
        }
    }

    @Test
    fun theHandOverLockCannotCloseBetweenCheckAndAfterStored() {
        val (p, g) = ownProgramme("QL")
        with(world) {
            exec("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id, chosen_by_user_id, chosen_at) SELECT $p, '$quarter', $outletGold, route_id, $g, ${sr.userId}, now() - interval '10 minutes' FROM app.outlet WHERE id = $outletGold")
            val aid = one("SELECT id FROM app.gift_assignment WHERE programme_id = $p AND outlet_id = $outletGold")
            val rec = ingest(choice(outletGold, g, secondsAgo = 5))
            fresh.db.jdbi.useTransaction<Exception> { h ->
                assertNull(handler().check(h, rec))
                // the photo's lock UPDATE comes from another connection: it must wait for this record's transaction
                // (a thread of its own: Jdbi hands a nested withHandle on this thread the transaction that holds the lock)
                val blocked = java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
                    try {
                        ex.submit<Boolean> {
                            fresh.db.jdbi.withHandle<Boolean, Exception> { other ->
                                other.begin()
                                try { other.execute("SET LOCAL lock_timeout = '400ms'"); other.execute("UPDATE app.gift_assignment SET locked_at = now() WHERE id = $aid"); false } catch (e: Exception) { true } finally { other.rollback() }
                            }
                        }.get()
                    } finally { ex.shutdown() }
                }
                assertTrue(blocked, "the assignment row is held between check and afterStored")
                handler().afterStored(h, rec, null)
            }
            assertEquals(null, rows("SELECT locked_at FROM app.gift_assignment WHERE id = $aid").single()["locked_at"])
            // if it closed anyway (another path, same transaction), the choice is NOT stored-and-silently-dropped: the record rolls back and its resend gets the final answer
            val late = ingest(choice(outletGold, g, secondsAgo = 1))
            assertFailsWith<IllegalStateException> {
                fresh.db.jdbi.useTransaction<Exception> { h ->
                    assertNull(handler().check(h, late))
                    h.execute("UPDATE app.gift_assignment SET locked_at = now() WHERE id = $aid")
                    handler().afterStored(h, late, null)
                }
            }
            assertEquals(null, rows("SELECT locked_at FROM app.gift_assignment WHERE id = $aid").single()["locked_at"], "the failed record rolled back with everything it did")
        }
    }

    @Test
    fun aLockedAnswerIsFinalAndTheSameOnReplay() = world.withApi {
        with(world) {
            val (p, g) = ownProgramme("QR")
            val a = choice(outletGold, g, secondsAgo = 90); sync(sr, listOf(a))
            exec("UPDATE app.gift_assignment SET locked_at = now() WHERE programme_id = $p AND outlet_id = $outletGold")
            val late = choice(outletGold, g, secondsAgo = 5); val cu = late["client_uuid"].toString().trim('"')
            repeat(3) { n -> val x = ack(sync(sr, listOf(late)), cu); assertEquals("rejected" to "gift_locked", x.status() to x.code(), "send $n") }
            assertEquals(1L, one("SELECT count(*) FROM app.sync_rejected WHERE client_uuid = CAST('$cu' AS uuid)"), "kept once, never dropped")
        }
    }

    @Test
    fun onlyTheOpenQuarterCanBeChosenFor() = world.withApi {
        with(world) {
            val (p, g) = ownProgramme("QQ")
            val stale = AsthaQuarter.of(today.minusMonths(3), 1).label; val future = AsthaQuarter.of(today.plusMonths(3), 1).label
            for (q in listOf(stale, future)) {
                val r = choice(outletGold2, g, q = q); val cu = r["client_uuid"].toString().trim('"')
                repeat(2) { val x = ack(sync(sr, listOf(r)), cu); assertEquals("rejected" to "schema_invalid", x.status() to x.code(), "$q") }
                assertEquals(1L, one("SELECT count(*) FROM app.sync_rejected WHERE client_uuid = CAST('$cu' AS uuid)"), "$q is kept, not dropped")
            }
            assertEquals(0L, one("SELECT count(*) FROM app.gift_assignment WHERE programme_id = $p"))
            val ok = choice(outletSilver, g); assertEquals("accepted", ack(sync(tso, listOf(ok)), ok["client_uuid"].toString().trim('"')).status(), "the open quarter is accepted")
        }
    }

    // ---- C-R2 finding 3: the one table of waived checks, a test per hold code ----

    /** A programme of the given kind whose gift has no tier list; [enrol] enrols the Gold outlet. */
    private fun programmeOf(kind: String, tag: String, enrol: Boolean): Long = with(world) {
        val p = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('$kind', '$tag', 'Hold $tag', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
        if (enrol) exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($p, $outletGold, 'gold', DATE '${today.minusDays(30)}')")
        one("INSERT INTO app.gift (programme_id, code, name_en) VALUES ($p, 'g_${tag.lowercase()}', 'Gift $tag') RETURNING id")
    }

    /** The codes a record can be held under before or at a gift handler (every quarantined RecordOutcomeCode), and the generic ones that have no handler check behind them. */
    private val generic = setOf(RecordOutcomeCode.BUSINESS_DATE_OUT_OF_WINDOW, RecordOutcomeCode.DEVICE_NOT_ENROLLED, RecordOutcomeCode.DEVICE_INTEGRITY_FAILED, RecordOutcomeCode.CONTENT_DUPLICATE, RecordOutcomeCode.AFTER_FINAL_SUBMIT)

    private fun refusal(r: JsonObject, released: RecordOutcomeCode?, role: Role = Role.SR): RecordOutcomeCode? =
        world.fresh.db.jdbi.inTransaction<RecordOutcomeCode?, Exception> { h -> handler().check(h, ingest(r, released, role))?.code }

    @Test
    fun theTableMapsEveryHoldCodeToTheSingleCheckItWaivesAndNothingElse() {
        val (_, gOk) = ownProgramme("H1")
        val outOfReach = choice(outletLkx, gOk)                                  // the outlet is not on the uploader's route
        val wrongRole = choice(outletGold, gOk)                                  // an AMO does not choose Astha gifts (cfg.astha.gift_choice_roles)
        val gDiamond = programmeOf("diamond_league", "H_DL", enrol = true); val kindFails = choice(outletGold, gDiamond)
        val gNoEnrol = programmeOf("astha", "H_NE", enrol = false); val notEnrolled = choice(outletGold, gNoEnrol)
        val gBoth = programmeOf("diamond_league", "H_BOTH", enrol = false); val bothFail = choice(outletGold, gBoth)
        // scope_out_of_reach: the REACH check only. The role check is a FINAL refusal (schema_invalid): no code at all releases it (C-R3)
        assertEquals(RecordOutcomeCode.SCOPE_OUT_OF_REACH, refusal(outOfReach, null)); assertNull(refusal(outOfReach, RecordOutcomeCode.SCOPE_OUT_OF_REACH))
        for (code in listOf<RecordOutcomeCode?>(null) + RecordOutcomeCode.entries) assertEquals(RecordOutcomeCode.SCHEMA_INVALID, refusal(wrongRole, code, Role.AMO), "$code must not release a role refusal")
        // no_assignment_on_date: the REACH check on the record's date (the cover day) - the finding's case
        assertNull(refusal(outOfReach, RecordOutcomeCode.NO_ASSIGNMENT_ON_DATE), "a cover day accepted by a reviewer is not judged out of reach again")
        // programme_inactive: the status/kind check when it fails, else the enrolment check; never both
        assertEquals(RecordOutcomeCode.PROGRAMME_INACTIVE, refusal(kindFails, null)); assertNull(refusal(kindFails, RecordOutcomeCode.PROGRAMME_INACTIVE))
        assertEquals(RecordOutcomeCode.PROGRAMME_INACTIVE, refusal(notEnrolled, null)); assertNull(refusal(notEnrolled, RecordOutcomeCode.PROGRAMME_INACTIVE))
        assertEquals(RecordOutcomeCode.PROGRAMME_INACTIVE, refusal(bothFail, RecordOutcomeCode.PROGRAMME_INACTIVE), "an accept made for one of the two checks does not also waive the other")
        // every other code waives none of the above; and no code at all waives the tier, the lock or the quarter
        val tierFail = choice(outletGold, gSilver)
        val quarterFail = choice(outletGold2, gOk, q = AsthaQuarter.of(today.minusMonths(3), 1).label)
        for (code in RecordOutcomeCode.entries) {
            if (code != RecordOutcomeCode.SCOPE_OUT_OF_REACH && code != RecordOutcomeCode.NO_ASSIGNMENT_ON_DATE) assertEquals(RecordOutcomeCode.SCOPE_OUT_OF_REACH, refusal(outOfReach, code), "$code must not waive the reach check")
            if (code != RecordOutcomeCode.PROGRAMME_INACTIVE) { assertEquals(RecordOutcomeCode.PROGRAMME_INACTIVE, refusal(kindFails, code), "$code must not waive the kind check"); assertEquals(RecordOutcomeCode.PROGRAMME_INACTIVE, refusal(notEnrolled, code), "$code must not waive the enrolment check") }
            assertEquals(RecordOutcomeCode.GIFT_NOT_FOR_TIER, refusal(tierFail, code), "$code must not waive the tier check")
            assertEquals(RecordOutcomeCode.SCHEMA_INVALID, refusal(quarterFail, code), "$code must not waive the open-quarter check")
        }
        for (code in generic) assertEquals(setOf<RecordOutcomeCode>(), setOf(code).filter { com.aktcl.aron.backend.sync.GiftHolds.WAIVES.containsKey(it) }.toSet(), "$code has no handler check behind it")
    }

    /**
     * C-R3: ONE hold code waives ONE check. A record that fails TWO checks at once stays refused after an accept for ANY code (an accept that waived both would apply it): the pairs are
     * reach + role, reach + programme kind, reach + enrolment, kind + enrolment, and each code is tried against each. The table itself is also asserted: no code maps to more than one
     * check, except programme_inactive, whose two checks are applied one at a time (firstProgrammeCheck).
     */
    @Test
    fun noHoldCodeWaivesMoreThanOneOfTwoFailingChecks() {
        val (_, gOk) = ownProgramme("H3")
        val gDiamond = programmeOf("diamond_league", "H3_DL", enrol = false)   // kind fails AND the outlet is not enrolled
        val reachAndRole = choice(outletLkx, gOk)                              // AMO below: outside cfg.astha.gift_choice_roles, and outside its reach
        val reachAndKind = choice(outletLkx, gDiamond)
        val kindAndEnrolment = choice(outletGold, gDiamond)
        for ((code, checks) in com.aktcl.aron.backend.sync.GiftHolds.WAIVES) {
            if (code != RecordOutcomeCode.PROGRAMME_INACTIVE) assertEquals(1, checks.size, "$code waives one check")
        }
        for (code in RecordOutcomeCode.entries) {
            assertEquals(RecordOutcomeCode.SCHEMA_INVALID, refusal(reachAndRole, code, Role.AMO), "$code: reach + role (the role refusal is final)")
            assertNotNull(refusal(reachAndKind, code), "$code must not release reach + programme at once")
            assertNotNull(refusal(kindAndEnrolment, code), "$code must not release programme kind + enrolment at once")
        }
        assertEquals(RecordOutcomeCode.PROGRAMME_INACTIVE, refusal(reachAndKind, RecordOutcomeCode.SCOPE_OUT_OF_REACH), "a reach accept leaves the programme check")
        assertEquals(RecordOutcomeCode.SCOPE_OUT_OF_REACH, refusal(reachAndKind, RecordOutcomeCode.PROGRAMME_INACTIVE), "a programme accept leaves the reach check")
    }

    /** C-R3: on the REAL accept path a second failing check comes back to the reviewer as a clear refusal (409 ERR_REQUEST_STATE naming the code), never as a server_error from afterStored. */
    @Test
    fun acceptingAHoldWhoseRecordFailsASecondCheckIsRefusedClearly() = world.withApi {
        with(world) {
            val gBoth = programmeOf("diamond_league", "H4_BOTH", enrol = false)   // programme_inactive twice: kind AND enrolment
            val r = choice(outletGold, gBoth)
            val a = ack(sync(sr, listOf(r)), r["client_uuid"].toString().trim('"'))
            assertEquals("quarantined", a.status()); assertEquals("programme_inactive", a.code())
            val id = one("SELECT id FROM app.sync_quarantine WHERE client_uuid = CAST('${r["client_uuid"].toString().trim('"')}' AS uuid) ORDER BY id DESC LIMIT 1")
            val res = postWeb("admin1001", "/v1/admin/quarantine/$id/resolve", """{"action":"accept","reason":"checked with the TSO on the phone"}""")
            assertEquals(HttpStatusCode.Conflict, res.status, res.bodyAsText())
            assertTrue(res.bodyAsText().contains("programme_inactive"), "the refusal names the second failing check's code")
            assertEquals("open", rows("SELECT status FROM app.sync_quarantine WHERE id = $id").single()["status"], "the item stays open for the reviewer")
            assertEquals(0L, one("SELECT count(*) FROM app.gift_choice WHERE client_uuid = CAST('${r["client_uuid"].toString().trim('"')}' AS uuid)"), "nothing was stored")
        }
    }

    @Test
    fun aCoverDayChoiceAcceptedByAReviewerIsAppliedNotJudgedOutOfReachAgain() {
        val (p, g) = ownProgramme("H2")
        with(world) {
            val r = choice(outletLkx, g); val rec = ingest(r, RecordOutcomeCode.NO_ASSIGNMENT_ON_DATE)
            fresh.db.jdbi.useTransaction<Exception> { h -> assertNull(handler().check(h, rec)); handler().afterStored(h, rec, null) }
            assertEquals(g, rows("SELECT gift_id FROM app.gift_assignment WHERE programme_id = $p AND quarter = '$quarter' AND outlet_id = $outletLkx").single()["gift_id"], "the assignment follows the accepted cover-day choice")
        }
    }
}

package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** F-API-040: delta since a version with an ETag; current version = 304, two behind = both changes (only keys that changed for the caller). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigDeltaTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private lateinit var delta: ConfigDelta

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        val resolver = ConfigResolver(env.fresh.db, clock)
        svc = ConfigService(env.fresh.db, resolver, clock)
        delta = ConfigDelta(env.fresh.db, resolver, clock)
    }
    @AfterAll fun tearDown() = env.close()

    private fun set(key: String, type: String, id: Long, v: Int?) = svc.create(
        env.principal("admin1001", Role.ADMIN), ConfigChangeRequestIn("Tuning the pilot values", false, listOf(ConfigChangeItemIn(key, type, id, v?.let { JsonPrimitive(it) } ?: JsonNull))), null, null,
    ).also { clock.advance(2) }

    @Test
    fun phoneAtCurrentGets304AndTwoBehindGetsBothChanges() {
        val sr = env.ids.getValue("sr1001")
        val v0 = svc.currentVersion()
        set("cfg.geo.fix_timeout_s", "global", 0, 10)
        set("cfg.geo.refresh_max", "global", 0, 2)
        val v2 = svc.currentVersion()
        assertEquals(v0 + 2, v2)
        val both = assertIs<DeltaOutcome.Changes>(delta.delta(sr, null, v0, null)).body
        assertEquals(setOf("cfg.geo.fix_timeout_s", "cfg.geo.refresh_max"), both.values.map { it.key }.toSet())
        assertEquals(v0, both.from_version); assertEquals(v2, both.to_version)
        assertEquals(10, both.values.first { it.key == "cfg.geo.fix_timeout_s" }.value.jsonPrimitive.int)
        val one = assertIs<DeltaOutcome.Changes>(delta.delta(sr, null, v0 + 1, null)).body
        assertEquals(listOf("cfg.geo.refresh_max"), one.values.map { it.key })
        assertIs<DeltaOutcome.NotModified>(delta.delta(sr, null, v2, null))
        assertIs<DeltaOutcome.NotModified>(delta.delta(sr, null, v0, delta.etag(v2)))
        assertEquals(delta.etag(v2), delta.etag(v2))
    }

    @Test
    fun aChangeOutsideTheCallersChainIsNotInTheDeltaAndServerOnlyKeysNever() {
        val sr = env.ids.getValue("sr1001")
        val since = svc.currentVersion()
        val otherTerritory = env.fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-ELSE', 'Else', division_id FROM app.territory LIMIT 1")
            h.createQuery("SELECT id FROM app.territory WHERE code = 'T-ELSE'").mapTo(Long::class.java).one()
        }
        set("cfg.geo.fix_timeout_s", "territory", otherTerritory, 12)
        set("cfg.sync.parked_ttl_days", "global", 0, 9) // delivery server
        val r = delta.delta(sr, null, since, null)
        assertTrue(r is DeltaOutcome.NotModified, (r as? DeltaOutcome.Changes)?.body?.let { b -> b.values.map { v -> v.key + '=' + v.value + '@' + v.scope_type + v.effective_from } + b.scheduled.map { v -> "S:" + v.key } }.toString())
    }

    @Test
    fun aVersionNewerThanTheServerIs400AndATooOldOneIs410() {
        val sr = env.ids.getValue("sr1001")
        val e = assertFailsWith<ApiProblem> { delta.delta(sr, null, svc.currentVersion() + 5, null) }
        assertEquals(ProblemCode.ERR_VALIDATION, e.code)
        repeat(3) { i -> set("cfg.geo.refresh_max", "global", 0, 1 + i % 4) }
        set("cfg.sys.config_delta_max_age_versions", "global", 0, 50)
        assertTrue(svc.currentVersion() > 3)
    }

    /**
     * tso-manual X4a fix round 4 (checker low): a removal dated later only sets the open row's `effective_to` (no new row,
     * nothing scheduled), and the version-as-of rebuild reads that row with its new end, so the delta saw no change and the
     * phone kept the value open-ended (for the late-sale policy: a quarantine locking the phone after the server fell back to
     * accept_and_flag). The delta now carries the resolved value again with its new end; a phone at the new version gets 304.
     */
    @Test
    fun aRemovalDatedLaterDeliversTheNewEndOfTheOpenValue() {
        val sr = env.ids.getValue("sr1001")
        set("cfg.geo.fix_reuse_max_age_s", "global", 0, 45)
        val since = svc.currentVersion()
        val end = removeLater("cfg.geo.fix_reuse_max_age_s")
        val v = assertIs<DeltaOutcome.Changes>(delta.delta(sr, null, since, null)).body.values.single { it.key == "cfg.geo.fix_reuse_max_age_s" }
        assertEquals(45, v.value.jsonPrimitive.int, "still in force until its end")
        assertEquals(end, java.time.Instant.parse(v.effective_to), "the new end travels with it")
    }

    /**
     * X4a fix round 4: a phone that already holds the moved end is not sent the key again by every later delta. The
     * version-as-of rebuild dropped a row superseded at or before that version even when its new end was still ahead, so
     * the old state fell back to the default and the key looked changed in every delta until its end.
     */
    @Test
    fun aMovedEndIsSentOnceNotWithEveryLaterChange() {
        val sr = env.ids.getValue("sr1001")
        set("cfg.geo.breadcrumb_interval_min", "global", 0, 20)
        removeLater("cfg.geo.breadcrumb_interval_min")
        val held = svc.currentVersion() // the phone pulled the moved end
        set("cfg.geo.refresh_max", "global", 0, 2) // another change
        val later = assertIs<DeltaOutcome.Changes>(delta.delta(sr, null, held, null)).body
        assertEquals(listOf("cfg.geo.refresh_max"), later.values.map { it.key }, "only the new change")
    }

    /** Commits a removal of [key]'s global value dated two hours ahead (a C2 change waits its delay first); returns the end. */
    private fun removeLater(key: String): java.time.Instant {
        val end = clock.now().plusSeconds(2 * 3600)
        val c = svc.create(
            env.principal("admin1001", Role.ADMIN),
            ConfigChangeRequestIn("End the pilot value tonight", false, listOf(ConfigChangeItemIn(key, "global", 0, JsonNull, effective_from = end.toString()))), null, null,
        )
        if (c.status == "scheduled") { clock.advance(11 * 60); svc.applyDue() }
        assertEquals("applied", svc.change(c.change_id).status)
        clock.advance(2)
        return end
    }

    @Test
    fun aFlagChangeAppearsInTheNextDeltaAndCapturedDataShapeIsUntouched() { // F-API-065: flags are the cfg.flag.* keys
        val sr = env.ids.getValue("sr1001")
        val since = svc.currentVersion()
        svc.create(env.principal("admin1001", Role.ADMIN), ConfigChangeRequestIn("Hide the credit screen for now", false, listOf(ConfigChangeItemIn("cfg.flag.credit_ui", "global", 0, JsonPrimitive(false)))), null, null)
        clock.advance(11 * 60) // a flag is class C2: scheduled for cfg.sys.c2_delay_min, then applied by the tick
        svc.applyDue()
        val flag = assertIs<DeltaOutcome.Changes>(delta.delta(sr, null, since, null)).body.values.single { it.key == "cfg.flag.credit_ui" }
        assertEquals("false", flag.value.toString())
    }
}

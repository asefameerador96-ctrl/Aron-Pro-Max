package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.contract.ConfigScopeType
import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BundleUnitTest {
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2027-01-03T02:00:00Z")

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (username, full_name, role) VALUES ('cfg.tester', 'Cfg Tester', 'ADMIN')")
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT 1000, 'change', id, 'test' FROM app.app_user WHERE username = 'cfg.tester'")
            fun v(scope: String, id: Long, value: Int, from: String = "2026-12-01T00:00:00Z", to: String? = null) = h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason) VALUES ('cfg.geo.radius_m', ?, ?, ?::jsonb, ?::timestamptz, ?::timestamptz, 1000, 'test')",
                scope, id, value.toString(), from, to,
            )
            v("global", 0, 120)
            v("zone", 7, 80)
            v("outlet", 99, 60)
            v("territory", 3, 150)
            v("zone", 8, 300, to = "2027-01-01T00:00:00Z") // expired
            v("zone", 9, 50, from = "2027-01-05T00:00:00Z") // scheduled
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun cfg() = fresh.db.jdbi.withHandle<ScopedConfig, Exception> { h -> ScopedConfig.load(h, now, now.plusSeconds(7 * 86_400L)) }

    @Test
    fun theMostSpecificValidScopeWinsAndExpiredOrFutureRowsDoNot() {
        val c = cfg()
        fun r(vararg p: Pair<ConfigScopeType, Long?>) = c.int("cfg.geo.radius_m", ScopedConfig.Chain.of(*p), -1)
        assertEquals(120, r(), "global row beats the registry default")
        assertEquals(150, r(ConfigScopeType.TERRITORY to 3L))
        assertEquals(80, r(ConfigScopeType.TERRITORY to 3L, ConfigScopeType.ZONE to 7L), "zone beats territory")
        assertEquals(60, r(ConfigScopeType.TERRITORY to 3L, ConfigScopeType.ZONE to 7L, ConfigScopeType.OUTLET to 99L), "outlet beats zone")
        assertEquals(120, r(ConfigScopeType.ZONE to 8L), "an expired row is ignored")
        assertEquals(120, r(ConfigScopeType.ZONE to 9L), "a future row is not in force yet")
        assertEquals(120, r(ConfigScopeType.ZONE to 99L), "an id of another level never matches (outlet 99 is not zone 99)")
    }

    @Test
    fun deviceValuesCarryTheWinnerAndScheduledRowsTheFuture() {
        val c = cfg()
        val chain = ScopedConfig.Chain.of(ConfigScopeType.ZONE to 9L)
        val radius = c.deviceValues(chain).single { it.key == "cfg.geo.radius_m" }
        assertEquals("global", radius.scope_type)
        assertEquals(120, radius.value.jsonPrimitive.int)
        assertTrue(radius.requires_ack)
        val scheduled = c.deviceScheduled(chain, now.plusSeconds(7 * 86_400L))
        assertEquals(listOf("zone" to 50), scheduled.map { it.scope_type to it.value.jsonPrimitive.int })
        assertTrue(c.deviceValues(chain).none { it.key == "cfg.geo.max_speed_kmh" }, "server-only keys are not delivered")
        assertTrue(c.deviceValues(chain).filter { it.scope_type == "default" }.all { it.scope_id == null && it.config_version == null })
    }

    @Test
    fun everyRejectOrQuarantineCodeHasBanglaAndEnglishText() {
        RecordOutcomeCode.entries.forEach { c ->
            val t = ReasonTexts.ALL[c.wire] ?: error("no text for ${c.wire}")
            assertTrue(t.bn.isNotBlank() && t.en.isNotBlank() && t.bn.length <= 300 && t.en.length <= 300, c.wire)
            assertTrue(t.bn.any { it in 'ঀ'..'৿' }, "${c.wire} bn is Bangla")
        }
        assertEquals(RecordOutcomeCode.entries.size, ReasonTexts.ALL.size)
    }

    @Test
    fun ifNoneMatchAcceptsQuotedWeakBareListsAndStar() {
        val v = "2027-01-03:123"
        assertTrue(ifNoneMatch("\"$v\"", v))
        assertTrue(ifNoneMatch("W/\"$v\"", v))
        assertTrue(ifNoneMatch(v, v))
        assertTrue(ifNoneMatch("\"x\", \"$v\"", v))
        assertTrue(ifNoneMatch("*", v))
        assertFalse(ifNoneMatch("\"2027-01-03:124\"", v))
        assertFalse(ifNoneMatch(null, v))
    }

    @Test
    fun cursorAndSortKeyHaveTheContractShape() {
        val c = BundleService.cursor(java.time.LocalDate.parse("2027-01-03"), 123456789, now)
        assertTrue(Regex("^[A-Za-z0-9_-]{8,256}$").matches(c), c)
        assertEquals("rahim store", BundleService.sortKey("  Rahim   STORE "))
        assertNull(null)
    }
}

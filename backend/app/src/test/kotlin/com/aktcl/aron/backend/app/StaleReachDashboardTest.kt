package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.DashboardService
import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reach cache and the node dashboards agree on the geography (D-REPORTS-REACH-GEO-01): a TSO/DMO/WM node dashboard reads the node's zones live from
 * `app.zone`, so a zone created in (or moved into) the caller's node must not leave a cached reach (300 s) that lacks it, which made the caller's own
 * dashboard 403 until the entry expired.
 */
class StaleReachDashboardTest {
    private val day = LocalDate.parse("2026-10-04")
    private val clock = AronClock { Instant.parse("2026-10-04T12:00:00Z") }   // fixed: the 300 s reach entry never expires inside the test
    private lateinit var fresh: FreshDb
    private lateinit var geo: GeoRepository
    private lateinit var resolver: SqlReachResolver
    private var uid = 0L
    private var t1 = 0L

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        exec("INSERT INTO app.wing (code, name) VALUES ('W1','Wing1')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D1','Div1', (SELECT id FROM app.wing WHERE code = 'W1'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T1','Terr1', (SELECT id FROM app.division WHERE code = 'D1'))")
        exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('Z1','Zone1', (SELECT id FROM app.territory WHERE code = 'T1'))")
        exec("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('tso1','Tso One','TSO',false)")
        t1 = id("SELECT id FROM app.territory WHERE code = 'T1'"); uid = id("SELECT id FROM app.app_user WHERE username = 'tso1'")
        exec("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) VALUES ($uid, 'territory', $t1, DATE '2026-01-01')")
        geo = GeoRepository(fresh.db, clock)
        resolver = SqlReachResolver(fresh.db, geo, clock)
    }

    @AfterEach
    fun tearDown() = fresh.close()

    private fun reach() = resolver.reach(uid, Role.TSO, 1, day)
    private fun dash() = DashboardService(fresh.db, clock)

    @Test
    fun aZoneAddedToTheCallersTerritoryAfterTheirReachWasCachedDoesNotLockThemOut() {
        val before = reach()
        assertEquals(1, before.zoneIds.size)
        assertEquals(0L, dash().summary(before, null, null, day, day).kpis.gross_mtk, "the default dashboard answers before the edit")
        // The admin write of AdminCrud: the row, then the snapshot is invalidated. scope_version does not move.
        exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('Z2','Zone2', $t1)")
        geo.invalidate()
        val after = reach()
        val z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        assertTrue(z2 in after.zoneIds, "the reach computed after the edit holds the new zone")
        assertEquals(0L, dash().summary(after, null, null, day, day).kpis.gross_mtk, "the default dashboard answers (was ERR_FORBIDDEN: the node held a zone the cached reach lacked)")
        assertEquals(0L, dash().summary(after, "territory", t1, day, day).kpis.gross_mtk)
    }

    @Test
    fun aZoneMovedIntoTheCallersTerritoryIsInTheNextReachToo() {
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT division_id FROM app.territory WHERE id = $t1))")
        exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('Z9','Zone9', (SELECT id FROM app.territory WHERE code = 'T2'))")
        geo.invalidate()
        val z9 = id("SELECT id FROM app.zone WHERE code = 'Z9'")
        assertTrue(z9 !in reach().zoneIds)
        exec("UPDATE app.zone SET territory_id = $t1 WHERE id = $z9")
        geo.invalidate()
        val moved = reach()
        assertTrue(z9 in moved.zoneIds)
        dash().summary(moved, null, null, day, day)   // 200, not 403
    }
}

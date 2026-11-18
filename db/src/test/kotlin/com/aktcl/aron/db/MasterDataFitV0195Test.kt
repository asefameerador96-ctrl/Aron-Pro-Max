package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** V0195 to V0200 (lane masterdata-fit, push 1): no-visit-plan routes, 3-character rep ids, the field's Astha tiers and cluster types. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MasterDataFitV0195Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    @Test
    fun aRouteWithNoVisitPlanIsKindNoneWithMaskZeroAndNothingInBetween() = db.connect().use { c0 -> c0.tx { val c = this
        val zone = c.scalar("SELECT id FROM app.zone ORDER BY id LIMIT 1")
        c.exec("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) VALUES ('NP-1', 'No plan', $zone, 'sr', 'none', 0)")
        c.exec("INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from) SELECT id, 'none', 0, '2026-01-01' FROM app.route WHERE code = 'NP-1'") // date-pin-ok: a rolled-back constraint probe, the date is data not a clock
        // existing shapes still hold
        c.exec("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) VALUES ('NP-2', 'Daily', $zone, 'sr', 'daily', 127), ('NP-3', 'Free', $zone, 'sr', NULL, 5)")
        for (bad in listOf("'sr', NULL, 0", "'sr', 'daily', 0", "'sr', 'none', 5", "'sr', '3f', 0", "'sr', 'none', 127", "'sr', NULL, 128")) {
            assertEquals("23514", c.refused("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) VALUES ('NP-X', 'Bad', $zone, $bad)"), bad)
        }
        assertEquals("23514", c.refused("INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from) SELECT id, NULL, 0, '2027-01-01' FROM app.route WHERE code = 'NP-1'")) // date-pin-ok: constraint probe
        assertEquals("true", c.scalar("SELECT (app.visit_kind_matches('none', 0::smallint) AND NOT app.visit_kind_matches('none', 5::smallint) AND NOT app.visit_kind_matches(NULL, 0::smallint) AND app.visit_kind_matches('2f', 36::smallint))::text"))
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_constraint WHERE convalidated = false AND connamespace = 'app'::regnamespace AND conname IN ('route_visit_kind_check','route_visit_days_mask_check','route_planned_visit_kind_check','route_planned_visit_days_mask_check','app_user_memo_username','memo_memo_no_check','code_list_list_key_check')"))
    } }

    @Test
    fun threeCharacterSalesRepIdsAreValidUsernamesAndMemoNumbers() = db.connect().use { c0 -> c0.tx { val c = this
        c.exec("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('ab1', 'Short Rep', 'SR', false), ('ab12345', 'Longer Rep', 'AMO', false)")
        assertEquals("23514", c.refused("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('ab', 'Too short', 'SR', false)"))
        assertEquals("23514", c.refused("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('Ab1', 'Upper', 'SR', false)"))
        assertEquals("true", c.scalar("SELECT bool_and(pg_get_constraintdef(oid) LIKE '%{2,31}%')::text FROM pg_constraint WHERE conname = 'memo_memo_no_check' AND conrelid = 'app.memo'::regclass"))
    } }

    @Test
    fun silverIsRetiredNotDeletedAndTitaniumIsAnAsthaTier() = db.connect().use { c0 -> c0.tx { val c = this
        assertEquals("inactive", c.scalar("SELECT status FROM app.sub_channel WHERE code = 'Silver'"))
        assertEquals("Astha", c.scalar("SELECT channel FROM app.sub_channel WHERE code = 'Titanium'"))
        assertEquals("active", c.scalar("SELECT status FROM app.sub_channel WHERE code = 'Titanium'"))
        // an outlet that references Silver keeps working: the row is still there and can still be read and updated
        val zone = c.scalar("SELECT id FROM app.zone ORDER BY id LIMIT 1"); val cl = c.scalar("SELECT id FROM app.cluster WHERE zone_id = $zone ORDER BY id LIMIT 1")
        c.exec("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel, sub_channel_id) SELECT 'SILV-1', 'Silver outlet', 'Owner', $zone, $cl, 'Astha', id FROM app.sub_channel WHERE code = 'Silver'")
        c.exec("UPDATE app.outlet SET name = 'Silver outlet 2' WHERE code = 'SILV-1'")
        assertEquals("Silver", c.scalar("SELECT s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.code = 'SILV-1'"))
        // the closed list of list keys still holds and now includes cluster_type
        assertEquals("23514", c.refused("INSERT INTO app.code_list (list_key, description) VALUES ('made_up_list', 'x')"))
    } }
}

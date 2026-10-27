package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0119 master history (review Medium 9, D-DB-MASTER-HISTORY-01): the master columns that are overwritten in place keep
 * their past in app.*_history, a period opens on the business date of a real change (never on a no-op write or an
 * origin re-tag), the past is recovered from the audit log's before-images, and dw exposes the periods as dims.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MasterHistoryTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    // The seed's clock (D-TEST-DATE-PIN-01).
    private val day = "app.dhaka_date(now())"
    private val zone = "(SELECT id FROM app.zone WHERE code = 'Z-MIR')"
    private val otherTerritory = "(SELECT id FROM app.territory WHERE code = 'T-HIST')"

    /** A second territory in the same division (the seed has one). */
    private fun Connection.secondTerritory() =
        exec("INSERT INTO app.territory (code, name, division_id) SELECT 'T-HIST', 'History test', division_id FROM app.territory t WHERE t.id = (SELECT territory_id FROM app.zone WHERE code = 'Z-MIR')")

    private fun Connection.periods(table: String, key: String, id: String, cols: String) =
        column("SELECT concat_ws('|', $cols, coalesce((valid_from - $day)::text, '-'), coalesce((valid_to - $day)::text, '-')) FROM app.$table WHERE $key = $id ORDER BY valid_from NULLS FIRST")

    @Test
    fun everyMasterRowHasOneOpenPeriodThatMatchesIt() = db.connect().use { c ->
        val maps = c.column("SELECT history_table || ':' || master_table || ':' || key_col FROM app.master_history_map ORDER BY 1")
        assertEquals(10, maps.size)
        for (m in maps) {
            val (h, master, key) = m!!.split(':')
            val cols = "app.master_history_cols('$h')"
            assertEquals("0", c.scalar("SELECT count(*) FROM app.$master x WHERE NOT EXISTS (SELECT 1 FROM app.$h y WHERE y.$key = x.id AND y.valid_to IS NULL AND app.jsonb_pick(to_jsonb(y), $cols) = app.jsonb_pick(to_jsonb(x), $cols))"), h)
            assertEquals(c.scalar("SELECT count(*) FROM app.$master"), c.scalar("SELECT count(*) FROM app.$h WHERE valid_to IS NULL"), h)
            assertEquals("t", c.scalar("SELECT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'app.$master'::regclass AND tgname = '${master}_history_track')"), h)
        }
    }

    @Test
    fun aChangeOpensAPeriodOnItsDateAndANoOpOrSameDayWriteDoesNot() = db.connect().use { c ->
        c.tx {
            secondTerritory()
            exec("SET LOCAL ROLE api_rw")                                          // the admin API's role writes the master
            val before = scalar("SELECT territory_id FROM app.zone WHERE code = 'Z-MIR'")!!
            assertEquals(listOf("$before|active|-|-"), periods("zone_history", "zone_id", zone, "territory_id, status"))
            exec("UPDATE app.zone SET name = name || ' renamed', status = status WHERE code = 'Z-MIR'")   // untracked and no-op
            assertEquals(1, periods("zone_history", "zone_id", zone, "territory_id").size)
            exec("UPDATE app.zone SET territory_id = $otherTerritory WHERE code = 'Z-MIR'")
            val after = scalar("SELECT territory_id FROM app.zone WHERE code = 'Z-MIR'")!!
            assertEquals(listOf("$before|-|0", "$after|0|-"), periods("zone_history", "zone_id", zone, "territory_id"))
            exec("UPDATE app.zone SET status = 'inactive' WHERE code = 'Z-MIR'")   // same date: rewrites that period
            assertEquals(listOf("$before|active|-|0", "$after|inactive|0|-"), periods("zone_history", "zone_id", zone, "territory_id, status"))
            exec("UPDATE app.zone SET territory_id = $before, status = 'active' WHERE code = 'Z-MIR'")  // back the same day
            assertEquals(listOf("$before|active|-|-"), periods("zone_history", "zone_id", zone, "territory_id, status"))
            // A history row is written only through the trigger.
            assertEquals("42501", refused("UPDATE app.zone_history SET status = 'inactive'"))
            assertEquals("42501", refused("INSERT INTO app.zone_history (zone_id, territory_id, status) SELECT $zone, 1, 'active'"))
            // The registry steers the SECURITY DEFINER trigger: the API role cannot change it.
            assertEquals("42501", refused("UPDATE app.master_history_map SET master_table = 'wing' WHERE history_table = 'route_history'"))
            assertEquals("42501", refused("INSERT INTO app.master_history_map VALUES ('x', 'y', 'id', ARRAY['status'], ARRAY['x'])"))
        }
    }

    @Test
    fun aChangeOnALaterDateClosesThePeriodAndAnOriginReTagOpensNone() = db.connect().use { c ->
        c.tx {
            val outlet = "(SELECT min(id) FROM app.outlet)"
            // The open period began three days ago (as if the outlet had been reclassified then).
            exec("UPDATE app.outlet_class_history SET valid_from = $day - 3 WHERE outlet_id = $outlet AND valid_to IS NULL")
            exec("UPDATE app.outlet SET data_origin = 'test' WHERE id = $outlet")                        // re-tag
            assertEquals(listOf("test|-3|-"), periods("outlet_class_history", "outlet_id", outlet, "data_origin"))
            exec("UPDATE app.outlet SET lat = lat, name = name || ' x' WHERE id = $outlet")               // untracked
            assertEquals(1, periods("outlet_class_history", "outlet_id", outlet, "data_origin").size)
            exec("UPDATE app.outlet SET outlet_kind = CASE outlet_kind WHEN 'retail' THEN 'wholesale' ELSE 'retail' END WHERE id = $outlet")
            assertEquals(
                listOf("test|-3|0", "test|0|-"),
                periods("outlet_class_history", "outlet_id", outlet, "data_origin"),
            )
            assertEquals("t", scalar("SELECT count(DISTINCT outlet_kind) = 2 FROM app.outlet_class_history WHERE outlet_id = $outlet"))
            exec("UPDATE app.outlet SET data_origin = 'seed' WHERE id = $outlet")
            assertEquals(listOf("seed|-3|0", "seed|0|-"), periods("outlet_class_history", "outlet_id", outlet, "data_origin"))
            assertEquals("seed", scalar("SELECT max(data_origin)::text FROM dw.v_dim_outlet_history WHERE outlet_id = $outlet"))
        }
    }

    @Test
    fun thePastIsRecoveredFromTheAuditLogsBeforeImages() = db.connect().use { c ->
        c.tx {
            val sku = "(SELECT min(id) FROM app.sku WHERE category_code = 'lighter')"
            val pack = scalar("SELECT base_per_pack FROM app.sku WHERE id = $sku")!!
            // Two audited admin edits, ten and four days ago (the chain trigger stamps today; the test dates the rows).
            exec("ALTER TABLE app.audit_log DISABLE TRIGGER audit_log_chain")
            for ((ago, before, after) in listOf(
                Triple(10, """{"base_per_pack": 1, "status": "inactive", "name": "old"}""", """{"base_per_pack": 12, "status": "active"}"""),
                Triple(4, """{"base_per_pack": 12, "report_factor": "1.000"}""", """{"base_per_pack": $pack}"""),
                Triple(4, """{"name": "only a name"}""", """{"name": "x"}"""),
                Triple(2, """{"base_per_pack": "not a number"}""", """{}"""),
            )) {
                exec(
                    "INSERT INTO app.audit_log (chain_seq, at, business_date, via, entity, entity_id, action, before, after, row_hash) " +
                        "SELECT coalesce(max(chain_seq), 0) + 1, now(), $day - $ago, 'api', 'sku', $sku::text, 'update', '$before', '$after', sha256('x') FROM app.audit_log",
                )
            }
            exec("ALTER TABLE app.audit_log ENABLE TRIGGER audit_log_chain")
            exec("SELECT app.rebuild_master_history('sku_history')")
            // The unreadable newest row stops the walk: its change and everything older cannot be dated, so the
            // current values count since ever.
            assertEquals(listOf("$pack|active|-|-"), periods("sku_history", "sku_id", sku, "base_per_pack, status"))
            exec("ALTER TABLE app.audit_log DISABLE TRIGGER audit_log_append_only")
            exec("DELETE FROM app.audit_log WHERE before ->> 'base_per_pack' = 'not a number'")
            exec("ALTER TABLE app.audit_log ENABLE TRIGGER audit_log_append_only")
            exec("SELECT app.rebuild_master_history('sku_history')")
            assertEquals(
                listOf("1|inactive|-|-10", "12|active|-10|-4", "$pack|active|-4|-"),
                periods("sku_history", "sku_id", sku, "base_per_pack, status"),
            )
            // Other SKUs have no audit rows: one period since ever.
            assertEquals("0", scalar("SELECT count(*) FROM app.sku_history WHERE sku_id <> $sku AND (valid_from IS NOT NULL OR valid_to IS NOT NULL)"))
            // As of eight days ago the product dim answers with the box of 12.
            assertEquals("12", scalar("SELECT base_per_pack FROM dw.v_dim_product_history WHERE sku_id = $sku AND daterange(valid_from, valid_to, '[)') @> ($day - 8)"))
            // Only the owner may rebuild.
            exec("SET LOCAL ROLE api_rw")
            assertEquals("42501", refused("SELECT app.rebuild_master_history('sku_history')"))
        }
    }

    @Test
    fun theGeoDimAnswersEachDateWithTheHierarchyOfThatDate() = db.connect().use { c ->
        c.tx {
            secondTerritory()
            val route = "(SELECT min(r.id) FROM app.route r WHERE r.zone_id = $zone)"
            val before = scalar("SELECT territory_id FROM app.zone WHERE code = 'Z-MIR'")!!
            exec("UPDATE app.zone_history SET valid_from = $day - 5 WHERE zone_id = $zone AND valid_to IS NULL")
            exec("INSERT INTO app.zone_history (zone_id, territory_id, house_id, status, valid_to) SELECT $zone, $otherTerritory, NULL, 'active', $day - 5")
            val other = scalar("SELECT $otherTerritory")!!
            assertEquals(
                listOf("$other|-|-5", "$before|-5|-"),
                column("SELECT concat_ws('|', territory_id, coalesce((valid_from - $day)::text, '-'), coalesce((valid_to - $day)::text, '-')) FROM dw.v_dim_geo_history WHERE route_id = $route ORDER BY valid_from NULLS FIRST"),
            )
            assertEquals(other, scalar("SELECT territory_id FROM dw.v_dim_geo_history WHERE route_id = $route AND daterange(valid_from, valid_to, '[)') @> ($day - 6)"))
            assertEquals("t", scalar("SELECT is_current AND is_effective FROM dw.v_dim_geo_history WHERE route_id = $route AND valid_to IS NULL"))
            // Every route and SKU has exactly one current dim period.
            assertEquals(scalar("SELECT count(*) FROM app.route"), scalar("SELECT count(*) FROM dw.v_dim_geo_history WHERE is_current"))
            assertEquals(scalar("SELECT count(*) FROM app.sku"), scalar("SELECT count(*) FROM dw.v_dim_product_history WHERE is_current"))
            assertEquals(scalar("SELECT count(*) FROM app.app_user"), scalar("SELECT count(*) FROM dw.v_dim_user_history WHERE is_current"))
        }
    }
}

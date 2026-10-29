package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * V0123 (D-DB-DIM-REKEY-01, backend-reports request dim-version-rekey): the dw dimension versions are dated by the V0119
 * app history, not by the day the projector wrote the change; a version whose id, start and versioned columns are
 * unchanged keeps its key, names are type 1, and a second rekey changes nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DimVersionRekeyTest {
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
    private val route = "(SELECT min(r.id) FROM app.route r JOIN app.zone z ON z.id = r.zone_id WHERE z.code = 'Z-MIR')"
    private val zone = "(SELECT id FROM app.zone WHERE code = 'Z-MIR')"

    /** The projector's dimension refresh (backend-analytics Aggregator.refreshDimensions), as the worker. */
    private fun Connection.refreshDims() {
        exec("SET LOCAL ROLE worker_rw")
        exec(
            """
            INSERT INTO dw.dim_geo (route_id, route_code, route_name, route_kind, visit_kind, zone_id, zone_name, territory_id, territory_name,
                                    division_id, division_name, wing_id, wing_name, status, updated_at)
            SELECT r.id, r.code, r.name, r.kind, r.visit_kind, z.id, z.name, t.id, t.name, d.id, d.name, w.id, w.name, r.status, now()
              FROM app.route r JOIN app.zone z ON z.id = r.zone_id JOIN app.territory t ON t.id = z.territory_id
              JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id
            ON CONFLICT (route_id) DO UPDATE SET route_code = excluded.route_code, route_name = excluded.route_name, route_kind = excluded.route_kind,
              visit_kind = excluded.visit_kind, zone_id = excluded.zone_id, zone_name = excluded.zone_name, territory_id = excluded.territory_id,
              territory_name = excluded.territory_name, division_id = excluded.division_id, division_name = excluded.division_name,
              wing_id = excluded.wing_id, wing_name = excluded.wing_name, status = excluded.status, updated_at = now()
            """,
        )
        exec(
            """
            INSERT INTO dw.dim_product (sku_id, sku_code, short_name, base_unit, base_per_pack, variant_id, variant_name, brand_id, brand_name,
                                        segment_id, segment_name, category_id, category_name, category_code, updated_at)
            SELECT s.id, s.code, s.short_name, s.base_unit, s.base_per_pack, v.id, v.name, b.id, b.name, sg.id, sg.name, c.id, c.name,
                   coalesce(s.category_code, c.code, ''), now()
              FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
              JOIN app.product_node sg ON sg.id = b.parent_id JOIN app.product_node c ON c.id = sg.parent_id
            ON CONFLICT (sku_id) DO UPDATE SET sku_code = excluded.sku_code, short_name = excluded.short_name, base_unit = excluded.base_unit,
              base_per_pack = excluded.base_per_pack, variant_id = excluded.variant_id, variant_name = excluded.variant_name,
              brand_id = excluded.brand_id, brand_name = excluded.brand_name, segment_id = excluded.segment_id, segment_name = excluded.segment_name,
              category_id = excluded.category_id, category_name = excluded.category_name, category_code = excluded.category_code, updated_at = now()
            """,
        )
        exec(
            """
            INSERT INTO dw.dim_outlet (outlet_id, outlet_code, outlet_name, route_id, cluster_id, zone_id, channel, sub_channel_id, geo_class,
                                       outlet_kind, location_confirmed, status, updated_at)
            SELECT id, code, name, route_id, cluster_id, zone_id, channel, sub_channel_id, geo_class, outlet_kind, location_confirmed, status, now()
              FROM app.outlet
            ON CONFLICT (outlet_id) DO UPDATE SET outlet_code = excluded.outlet_code, outlet_name = excluded.outlet_name, route_id = excluded.route_id,
              cluster_id = excluded.cluster_id, zone_id = excluded.zone_id, channel = excluded.channel, sub_channel_id = excluded.sub_channel_id,
              geo_class = excluded.geo_class, outlet_kind = excluded.outlet_kind, location_confirmed = excluded.location_confirmed,
              status = excluded.status, updated_at = now()
            """,
        )
        exec(
            """
            INSERT INTO dw.dim_user (user_id, role, designation, home_zone_id, status, pilot, updated_at)
            SELECT id, role, designation, home_zone_id, status, pilot, now() FROM app.app_user
            ON CONFLICT (user_id) DO UPDATE SET role = excluded.role, designation = excluded.designation, home_zone_id = excluded.home_zone_id,
              status = excluded.status, pilot = excluded.pilot, updated_at = now()
            """,
        )
        // Keys of recent facts written before their dimension row existed, then DashboardSummaries.refreshDimensions.
        for (t in listOf("fact_memo", "fact_visit")) exec(
            "UPDATE dw.$t SET geo_key = dw.geo_key_on(route_id, business_date), outlet_key = dw.outlet_key_on(outlet_id, business_date), user_key = dw.user_key_on(user_id, business_date) " +
                "WHERE business_date >= app.dhaka_date(now()) - 7 AND (geo_key IS NULL OR outlet_key IS NULL OR user_key IS NULL)",
        )
        exec(
            """
            UPDATE dw.dim_geo g SET
              route_class = r.route_class,
              house_id    = (SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name)
                                AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active'),
              house_name  = hs.name
              FROM app.route r JOIN app.zone z ON z.id = r.zone_id LEFT JOIN app.house hs ON hs.id = z.house_id
             WHERE g.route_id = r.id
               AND (g.route_class, g.house_id, g.house_name) IS DISTINCT FROM
                   (r.route_class,
                    (SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name)
                        AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active'),
                    hs.name)
            """,
        )
        exec(
            """
            UPDATE dw.dim_product p SET segment_code = coalesce(sg.code, 'n' || sg.id), brand_code = coalesce(b.code, 'n' || b.id), variant_code = coalesce(v.code, 'n' || v.id)
              FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
              JOIN app.product_node sg ON sg.id = b.parent_id
             WHERE p.sku_id = s.id
               AND (p.segment_code, p.brand_code, p.variant_code) IS DISTINCT FROM (coalesce(sg.code, 'n' || sg.id), coalesce(b.code, 'n' || b.id), coalesce(v.code, 'n' || v.id))
            """,
        )
        exec("RESET ROLE")
    }

    /** A projected memo with no keys yet (the projector stamps them), on a business date. */
    private fun Connection.fact(route: String, outlet: String, user: String, date: String) = exec(
        "INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, route_id, outlet_id, status, committed_at, line_count, gross_mtk, " +
            "offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit, captured_offline, received_at, last_event_id) " +
            "VALUES ($date, gen_random_uuid(), 'T-' || gen_random_uuid(), $user, $route, $outlet, 'active', now(), 1, 0, 0, 0, 0, 0, 0, 0, false, false, now(), 0)",
    )

    /** Every fact key exists and is the one valid on the fact's date. */
    private fun Connection.factKeysAreRight() {
        assertEquals("0", scalar(
            "SELECT count(*) FROM dw.fact_memo f WHERE f.geo_key IS DISTINCT FROM dw.geo_key_on(f.route_id, f.business_date) " +
                "OR f.outlet_key IS DISTINCT FROM dw.outlet_key_on(f.outlet_id, f.business_date) OR f.user_key IS DISTINCT FROM dw.user_key_on(f.user_id, f.business_date) " +
                "OR NOT EXISTS (SELECT 1 FROM dw.dim_geo_version v WHERE v.geo_key = f.geo_key)",
        ), "fact keys")
    }

    private fun Connection.rekey(dim: String? = null) =
        column("SELECT dim || ':' || inserted || ':' || deleted FROM dw.rekey_dim_versions(${dim?.let { "'$it'" } ?: "NULL"}) ORDER BY dim")

    private fun Connection.versions(id: String) =
        column("SELECT concat_ws('|', geo_key, territory_id, coalesce((valid_from - $day)::text, '-'), coalesce((valid_to - $day)::text, '-')) FROM dw.dim_geo_version WHERE route_id = $id ORDER BY valid_from NULLS FIRST")

    @Test
    fun theFirstRefreshFollowsTheHistoryAndASecondRekeyChangesNothing() = db.connect().use { c ->
        c.tx {
            refreshDims()
            for ((dim, k, src) in listOf(
                Triple("dim_geo", "route_id", "dw.v_dim_geo_history"), Triple("dim_outlet", "outlet_id", "dw.v_dim_outlet_history"),
                Triple("dim_product", "sku_id", "dw.v_dim_product_history"), Triple("dim_user", "user_id", "dw.v_dim_user_history"),
            )) {
                assertNotEquals("0", scalar("SELECT count(*) FROM dw.$dim"), dim)
                // The seed's history is one period per row since ever: one version each, current, since ever.
                assertEquals(scalar("SELECT count(DISTINCT $k) FROM $src"), scalar("SELECT count(*) FROM dw.${dim}_version WHERE valid_from IS NULL AND valid_to IS NULL"), dim)
                assertEquals(scalar("SELECT count(*) FROM dw.$dim"), scalar("SELECT count(*) FROM dw.${dim}_version"), dim)
            }
            val keys = column("SELECT geo_key FROM dw.dim_geo_version ORDER BY 1")
            // A second rekey leaves every row byte-identical and unwritten (keys, periods, values, updated_at; ctid moves on any UPDATE).
            val before = listOf("dim_geo_version", "dim_outlet_version", "dim_product_version", "dim_user_version")
                .map { scalar("SELECT md5(string_agg(to_jsonb(v)::text || v.ctid::text, ',' ORDER BY v.ctid)) FROM dw.$it v") }
            assertEquals(listOf("dim_geo:0:0", "dim_outlet:0:0", "dim_product:0:0", "dim_user:0:0"), rekey())
            assertEquals(before, listOf("dim_geo_version", "dim_outlet_version", "dim_product_version", "dim_user_version")
                .map { scalar("SELECT md5(string_agg(to_jsonb(v)::text || v.ctid::text, ',' ORDER BY v.ctid)) FROM dw.$it v") })
            refreshDims()                                                            // a refresh that changes nothing
            assertEquals(keys, column("SELECT geo_key FROM dw.dim_geo_version ORDER BY 1"))
            // The type-1 names are the current names of the node the version points at.
            assertEquals("0", scalar("SELECT count(*) FROM dw.dim_geo_version v JOIN app.zone z ON z.id = v.zone_id WHERE v.zone_name IS DISTINCT FROM z.name"))
        }
    }

    @Test
    fun aChangeIsDatedByTheHistoryAndTheClosedVersionKeepsItsKey() = db.connect().use { c ->
        c.tx {
            refreshDims()
            exec("INSERT INTO app.territory (code, name, division_id) SELECT 'T-REKEY', 'Rekey test', division_id FROM app.territory t WHERE t.id = (SELECT territory_id FROM app.zone WHERE code = 'Z-MIR')")
            val before = scalar("SELECT territory_id FROM app.zone WHERE code = 'Z-MIR'")!!
            val after = scalar("SELECT id FROM app.territory WHERE code = 'T-REKEY'")!!
            val key0 = scalar("SELECT dw.geo_key_on($route, $day - 30)")!!
            val someOutlet = scalar("SELECT min(id) FROM app.outlet")!!
            val someUser = scalar("SELECT min(id) FROM app.app_user")!!
            for (ago in listOf(5, 3, 1, 0)) fact(route, someOutlet, someUser, "$day - $ago")
            exec("UPDATE app.zone SET territory_id = $after WHERE code = 'Z-MIR'")
            // The admin change was two days ago; the projector writes it today.
            exec("UPDATE app.zone_history SET valid_to = $day - 2 WHERE zone_id = $zone AND valid_to = $day")
            exec("UPDATE app.zone_history SET valid_from = $day - 2 WHERE zone_id = $zone AND valid_from = $day")
            refreshDims()
            val v = versions(route)
            assertEquals(2, v.size)
            assertEquals("$key0|$before|-|-2", v[0], "the closed version keeps its key, closed on the history's date")
            val key1 = v[1]!!.substringBefore('|')
            assertEquals("$key1|$after|-2|-", v[1])
            assertEquals(key0, scalar("SELECT dw.geo_key_on($route, $day - 3)"))
            assertEquals(key1, scalar("SELECT dw.geo_key_on($route, $day - 2)"))
            assertEquals("Rekey test", scalar("SELECT territory_name FROM dw.dim_geo_version WHERE geo_key = $key1"))
            // The history is dated further back later (an audit-log recovery): the old key stays, the moved version is replaced.
            exec("UPDATE app.zone_history SET valid_to = $day - 4 WHERE zone_id = $zone AND valid_to = $day - 2")
            exec("UPDATE app.zone_history SET valid_from = $day - 4 WHERE zone_id = $zone AND valid_from = $day - 2")
            val routes = scalar("SELECT count(*) FROM dw.dim_geo WHERE zone_id = $zone")!!
            assertEquals(listOf("dim_geo:$routes:$routes"), rekey("dim_geo"))
            val w = versions(route)
            assertEquals("$key0|$before|-|-4", w[0])
            assertNotEquals(key1, w[1]!!.substringBefore('|'))
            factKeysAreRight()                                                      // the day -3 memo moved to the new version
            assertEquals(listOf("dim_geo:0:0"), rekey("dim_geo"))
            // A rename is type 1, updated in place and no key moves: the trigger renames the versions of the ids whose
            // dimension row changed (the current territory), a full rekey the past ones too.
            val keys = column("SELECT geo_key FROM dw.dim_geo_version ORDER BY 1")
            exec("UPDATE app.territory SET name = 'New territory renamed' WHERE id = $after")
            refreshDims()
            assertEquals("New territory renamed", scalar("SELECT territory_name FROM dw.dim_geo_version WHERE geo_key = dw.geo_key_on($route, $day)"))
            val oldName = scalar("SELECT territory_name FROM dw.dim_geo_version WHERE geo_key = $key0")
            exec("UPDATE app.territory SET name = 'Old territory renamed' WHERE id = $before")
            refreshDims()                                                            // no dimension row names it any more
            assertEquals(oldName, scalar("SELECT territory_name FROM dw.dim_geo_version WHERE geo_key = $key0"))
            assertEquals(listOf("dim_geo:0:0"), rekey("dim_geo"))
            assertEquals(keys, column("SELECT geo_key FROM dw.dim_geo_version ORDER BY 1"))
            assertEquals("Old territory renamed", scalar("SELECT territory_name FROM dw.dim_geo_version WHERE geo_key = $key0"))
            factKeysAreRight()
        }
    }

    @Test
    fun noFactKeepsAKeyThatARekeyDeleted() = db.connect().use { c ->
        c.tx {
            refreshDims()
            val outlet = scalar("SELECT min(id) FROM app.outlet")!!
            val user = scalar("SELECT min(id) FROM app.app_user")!!
            // A new dummy route: the Aggregator's upsert writes class normal (the history disagrees: the projector date),
            // DashboardSummaries then sets the class (the history agrees and replaces that version).
            exec("INSERT INTO app.route (code, name, zone_id, kind, visit_days_mask, route_class) VALUES ('R-REKEY', 'Rekey route', $zone, 'sr', 127, 'dummy_br')")
            val r = scalar("SELECT id FROM app.route WHERE code = 'R-REKEY'")!!
            fact(r, outlet, user, day)
            refreshDims()
            factKeysAreRight()
            assertEquals("dummy_br", scalar("SELECT route_class FROM dw.dim_geo_version WHERE geo_key = dw.geo_key_on($r, $day)"))
            // A change and its revert on the same day: the history merges the day away and the rekey deletes its version.
            exec("UPDATE app.route SET status = 'inactive' WHERE id = $r")
            refreshDims()
            factKeysAreRight()
            exec("UPDATE app.route SET status = 'active' WHERE id = $r")
            refreshDims()
            factKeysAreRight()
            assertEquals(1, versions(r).size)
        }
    }

    @Test
    fun aHouseGoingInactiveMovesNoPastVersion() = db.connect().use { c ->
        c.tx {
            refreshDims()
            val outlet = scalar("SELECT min(id) FROM app.outlet")!!
            val user = scalar("SELECT min(id) FROM app.app_user")!!
            for (ago in listOf(6, 3)) fact(route, outlet, user, "$day - $ago")       // inside the projector's 7-day key fix-up
            refreshDims()
            val keys = column("SELECT geo_key || '|' || coalesce(house_id::text, '-') FROM dw.dim_geo_version ORDER BY 1")
            exec("UPDATE app.house SET status = 'inactive' WHERE id = (SELECT house_id FROM app.zone WHERE code = 'Z-MIR')")
            refreshDims()
            assertEquals(listOf("dim_geo:0:0", "dim_outlet:0:0", "dim_product:0:0", "dim_user:0:0"), rekey())
            assertEquals(keys, column("SELECT geo_key || '|' || coalesce(house_id::text, '-') FROM dw.dim_geo_version ORDER BY 1"))
            factKeysAreRight()
        }
    }

    @Test
    fun aHouseMergeIntoAHigherIdVersionsTheSurvivorFromItsDate() = db.connect().use { c ->
        c.tx {
            refreshDims()
            val old = scalar("SELECT house_id FROM app.zone WHERE code = 'Z-MIR'")!!
            val key0 = scalar("SELECT dw.geo_key_on($route, $day - 30)")!!
            // As HouseDedup merges: a same-name house with a higher id keeps, the zones move to it, the other retires.
            exec("INSERT INTO app.house (code, name, territory_id) SELECT 'H-REKEY', name, territory_id FROM app.house WHERE id = $old")
            val keep = scalar("SELECT id FROM app.house WHERE code = 'H-REKEY'")!!
            exec("UPDATE app.zone SET house_id = $keep WHERE house_id = $old")
            exec("UPDATE app.house SET status = 'inactive' WHERE id = $old")
            refreshDims()
            assertEquals(keep, scalar("SELECT house_id FROM dw.dim_geo WHERE route_id = $route"), "DashboardSummaries keys the survivor")
            assertEquals(keep, scalar("SELECT house_id FROM dw.dim_geo_version WHERE geo_key = dw.geo_key_on($route, $day)"))
            assertEquals(old, scalar("SELECT house_id FROM dw.dim_geo_version WHERE geo_key = $key0"), "the past keeps the house it had")
            assertEquals(key0, scalar("SELECT dw.geo_key_on($route, $day - 30)"))
            assertEquals(listOf("dim_geo:0:0", "dim_outlet:0:0", "dim_product:0:0", "dim_user:0:0"), rekey())
        }
    }

    @Test
    fun aChangeTheDimensionDoesNotShowOpensNoVersion() = db.connect().use { c ->
        c.tx {
            refreshDims()
            val keys = column("SELECT geo_key || '|' || coalesce(valid_from::text, '-') || '|' || coalesce(valid_to::text, '-') FROM dw.dim_geo_version ORDER BY 1")
            // A zone status change splits the geo history into equal neighbours (status of a zone is not in the dimension).
            exec("UPDATE app.zone SET status = 'inactive' WHERE code = 'Z-MIR'")
            exec("UPDATE app.zone_history SET valid_to = $day - 3 WHERE zone_id = $zone AND valid_to = $day")
            exec("UPDATE app.zone_history SET valid_from = $day - 3 WHERE zone_id = $zone AND valid_from = $day")
            assertNotEquals("1", scalar("SELECT count(*) FROM dw.v_dim_geo_history WHERE route_id = $route"))
            refreshDims()
            assertEquals(listOf("dim_geo:0:0", "dim_outlet:0:0", "dim_product:0:0", "dim_user:0:0"), rekey())
            assertEquals(keys, column("SELECT geo_key || '|' || coalesce(valid_from::text, '-') || '|' || coalesce(valid_to::text, '-') FROM dw.dim_geo_version ORDER BY 1"))
        }
    }

    @Test
    fun anOutletMoveAUserMoveAndAPackSizeChangeAreDatedByTheirHistory() = db.connect().use { c ->
        c.tx {
            refreshDims()
            val outlet = scalar("SELECT min(id) FROM app.outlet WHERE route_id = $route")!!
            val otherRoute = scalar("SELECT min(id) FROM app.route WHERE id <> $route AND zone_id = $zone")!!
            val r0 = scalar("SELECT $route::text")
            val r1 = otherRoute
            // A direct SQL move the placement history did not see (the admin API writes it): the projector date, as V0043.
            exec("UPDATE app.outlet SET route_id = $otherRoute WHERE id = $outlet")
            refreshDims()
            assertEquals(r0, scalar("SELECT route_id FROM dw.dim_outlet_version WHERE outlet_key = dw.outlet_key_on($outlet, $day - 1)"))
            assertEquals(r1, scalar("SELECT route_id FROM dw.dim_outlet_version WHERE outlet_key = dw.outlet_key_on($outlet, $day)"))
            assertEquals(listOf("dim_outlet:0:0"), rekey("dim_outlet"), "a history that disagrees with the dimension leaves the id alone")
            // The history catches up (as AdminOutlets writes it, dated yesterday): a rekey dates the move by it.
            exec("UPDATE app.outlet_placement_history SET valid_to = $day - 1 WHERE outlet_id = $outlet AND valid_to IS NULL")
            exec("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from) SELECT id, route_id, cluster_id, $day - 1 FROM app.outlet WHERE id = $outlet")
            assertEquals(listOf("dim_outlet:1:1"), rekey("dim_outlet"))
            assertEquals(r0, scalar("SELECT route_id FROM dw.dim_outlet_version WHERE outlet_key = dw.outlet_key_on($outlet, $day - 2)"))
            assertEquals(r1, scalar("SELECT route_id FROM dw.dim_outlet_version WHERE outlet_key = dw.outlet_key_on($outlet, $day - 1)"))

            val user = scalar("SELECT min(id) FROM app.app_user WHERE home_zone_id IS NOT NULL")!!
            val homeZone = scalar("SELECT home_zone_id FROM app.app_user WHERE id = $user")!!
            exec("INSERT INTO app.zone (code, name, territory_id, house_id) SELECT 'Z-REKEY', 'Rekey test', territory_id, house_id FROM app.zone WHERE id = $homeZone")
            val otherZone = scalar("SELECT id FROM app.zone WHERE code = 'Z-REKEY'")!!
            exec("UPDATE app.app_user SET home_zone_id = $otherZone WHERE id = $user")
            exec("UPDATE app.user_history SET valid_to = $day - 5 WHERE user_id = $user AND valid_to = $day")
            exec("UPDATE app.user_history SET valid_from = $day - 5 WHERE user_id = $user AND valid_from = $day")
            refreshDims()
            assertEquals(homeZone, scalar("SELECT home_zone_id FROM dw.dim_user_version WHERE user_key = dw.user_key_on($user, $day - 6)"))
            assertEquals(otherZone, scalar("SELECT home_zone_id FROM dw.dim_user_version WHERE user_key = dw.user_key_on($user, $day - 5)"))

            val sku = scalar("SELECT min(id) FROM app.sku")!!
            val pack = scalar("SELECT base_per_pack FROM app.sku WHERE id = $sku")!!
            exec("UPDATE app.sku SET base_per_pack = base_per_pack * 2 WHERE id = $sku")
            exec("UPDATE app.sku_history SET valid_to = $day - 2 WHERE sku_id = $sku AND valid_to = $day")
            exec("UPDATE app.sku_history SET valid_from = $day - 2 WHERE sku_id = $sku AND valid_from = $day")
            refreshDims()
            assertEquals(pack, scalar("SELECT base_per_pack FROM dw.dim_product_version WHERE product_key = dw.product_key_on($sku, $day - 3)"))
            assertEquals((pack.toInt() * 2).toString(), scalar("SELECT base_per_pack FROM dw.dim_product_version WHERE product_key = dw.product_key_on($sku, $day - 2)"))
            assertEquals(listOf("dim_geo:0:0", "dim_outlet:0:0", "dim_product:0:0", "dim_user:0:0"), rekey())
        }
    }

    @Test
    fun aRolledBackRefreshLeavesEveryVersionFindableThroughTheExclusionIndex() = db.connect().use { c ->
        // On PostgreSQL 16.15 a statement that scanned the GiST exclusion index while inserting into it, after rolled-back
        // writes, left entries out of the index (round 1 of V0123); the rekey now reads first and writes by key.
        repeat(3) { c.tx { refreshDims() } }
        c.tx {
            refreshDims()
            exec("SET LOCAL enable_seqscan = off")
            exec("SET LOCAL enable_bitmapscan = off")
            for (t in listOf("dim_geo_version", "dim_outlet_version", "dim_product_version", "dim_user_version")) {
                // Only the GiST index answers && on the period: the count goes through it.
                val viaGist = "SELECT count(*) FROM dw.$t WHERE daterange(valid_from, valid_to, '[)') && daterange(NULL::date, NULL::date)"
                assertTrue(column("EXPLAIN $viaGist").any { it!!.contains("daterange_excl") }, t)
                assertEquals(scalar("SELECT count(*) FROM (SELECT * FROM dw.$t OFFSET 0) x"), scalar(viaGist), t)
            }
        }
    }

    @Test
    fun onlyTheProjectorAndTheOwnerRekey() = db.connect().use { c ->
        c.tx {
            refreshDims()
            exec("SET LOCAL ROLE worker_rw")
            assertEquals(4, rekey().size)
            exec("RESET ROLE")
            for (r in listOf("api_rw", "web_ro", "bi_reader")) {
                exec("SET LOCAL ROLE $r")
                assertEquals("42501", refused("SELECT * FROM dw.rekey_dim_versions()"), r)
                exec("RESET ROLE")
            }
            assertEquals("P0001", refused("SELECT * FROM dw.rekey_dim_versions('dim_nope')"))
        }
    }
}

package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0150 / V0151 and the seed 09 (AMO manual build F1, docs/requests/amo-manual-build-2026-11.md s2): the force-sale reason
 * on a visit with its two CHECKs, the verification event's route and cluster, the nine config keys, the view contract 1.7 (1.6 at V0152, 1.5 at V0151; V0162 raised it)
 * with `dw.v_visit.force_sale_reason`, and the dev content (Joint Call version 2, the brand list).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmoManualV0150Test {
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

    private fun visit(uuid: String, action: String, reason: String?) =
        "INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_kind, outlet_id, " +
            "opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, force_sale_reason) " +
            "VALUES ('$uuid', '$uuid', CURRENT_DATE, (SELECT id FROM app.app_user WHERE username = 'sr1001'), now(), 0, " +
            "'sr_call', (SELECT min(id) FROM app.outlet), now(), 1, true, 'in_range', 100, 50, 'master', '$action', ${reason?.let { "'$it'" } ?: "NULL"})"

    @Test
    fun aForceSaleReasonIsOnlyAllowedOnAForceSaleAndOnlyTwoValues() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec(visit("c0ffee00-0000-4000-8000-000000000a01", "force_sale", "internet_problem"))
            c.exec(visit("c0ffee00-0000-4000-8000-000000000a02", "force_sale", "location_change"))
            c.exec(visit("c0ffee00-0000-4000-8000-000000000a03", "force_sale", null))
            c.exec(visit("c0ffee00-0000-4000-8000-000000000a04", "sale_allowed", null))
            assertEquals("23514", c.refused(visit("c0ffee00-0000-4000-8000-000000000a05", "sale_allowed", "internet_problem")), "a reason without a force sale")
            assertEquals("23514", c.refused(visit("c0ffee00-0000-4000-8000-000000000a06", "blocked", "location_change")), "a reason on a blocked visit")
            assertEquals("23514", c.refused(visit("c0ffee00-0000-4000-8000-000000000a07", "force_sale", "battery_dead")), "an unknown reason")
            assertEquals("2", c.scalar("SELECT count(*) FROM app.visit WHERE force_sale_reason IS NOT NULL"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theConstraintsAreValidatedAndTheEventTakesARouteAndClusterThatExist() = db.connect().use { c ->
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_constraint WHERE NOT convalidated AND conname IN ('visit_force_sale_reason_check', 'visit_force_sale_reason_needs_force_sale', 'outlet_request_event_route_id_fkey', 'outlet_request_event_cluster_id_fkey')"))
        assertEquals("4", c.scalar("SELECT count(*) FROM pg_constraint WHERE conrelid IN ('app.visit'::regclass, 'app.outlet_request_event'::regclass) AND conname IN ('visit_force_sale_reason_check', 'visit_force_sale_reason_needs_force_sale', 'outlet_request_event_route_id_fkey', 'outlet_request_event_cluster_id_fkey')"))
        assertEquals("route_id,cluster_id", c.column("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'outlet_request_event' AND column_name IN ('route_id', 'cluster_id') ORDER BY ordinal_position").joinToString(","))
    }

    @Test
    fun theNineKeysAreRegisteredWithTheDefaultsTheBackendReads() = db.connect().use { c ->
        val d = { k: String -> c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = '$k'") }
        assertEquals("null", d("cfg.amo.call_target_month"))
        assertEquals("null", d("cfg.amo.control_call_target_month"))
        assertEquals("null", d("cfg.amo.joint_call_target_month"))
        assertEquals("false", d("cfg.amo.price_compliance_tile"))
        assertEquals("\"1\"", d("cfg.amo.joint_call_default_star"))
        assertEquals("[]", d("cfg.rubric.distribution_brands"))
        assertEquals("100", d("cfg.geo.update_base_max_distance_m"))
        assertEquals("1000", d("cfg.geo.update_base_max_move_m"))
        assertEquals("1", d("cfg.geo.update_base_max_per_outlet_month"))
        assertEquals("{global,role,territory,zone}", c.scalar("SELECT scope_levels::text FROM app.cfg_key WHERE key = 'cfg.amo.call_target_month'"))
    }

    @Test
    fun theViewContractIs18AndTheVisitViewCarriesTheReasonBeforeIsEffective() = db.connect().use { c ->
        assertEquals("1.8", c.scalar("SELECT dw.view_contract_version()"))   // V0175 (1.7 at V0162, 1.6 at V0152, 1.5 at V0151)
        val cols = c.column("SELECT column_name FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'v_visit' ORDER BY ordinal_position").filterNotNull()
        assertEquals("is_effective", cols.last())
        assertEquals(cols.indexOf("geo_action") + 1, cols.indexOf("force_sale_reason"))
        for (role in listOf("bi_reader", "web_ro")) assertEquals("true", c.scalar("SELECT has_table_privilege('$role', 'dw.v_visit', 'SELECT')::text"), role)
        assertTrue(c.scalar("SELECT obj_description('dw.v_visit'::regclass)")!!.endsWith("pii: none"))
        assertTrue(c.scalar("SELECT col_description('dw.v_visit'::regclass, ${cols.indexOf("force_sale_reason") + 1})")!!.contains("internet_problem"))
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_attribute a WHERE a.attrelid = 'dw.v_visit'::regclass AND a.attnum > 0 AND NOT a.attisdropped AND col_description(a.attrelid, a.attnum) IS NULL"), "every view column keeps its comment")
    }

    @Test
    fun theDevSeedHasJointCallVersionTwoWithThreeSectionsAndTheBrandListInTheScreenshotOrder() = db.connect().use { c ->
        assertEquals("2", c.scalar("SELECT version FROM app.rubric WHERE kind = 'joint_call'"))
        assertEquals("1,2", c.column("SELECT v.version FROM app.rubric_version v JOIN app.rubric r ON r.id = v.rubric_id WHERE r.kind = 'joint_call' ORDER BY v.version").joinToString(","))
        val criteria = "SELECT jsonb_array_elements(v.criteria) AS c FROM app.rubric_version v JOIN app.rubric r ON r.id = v.rubric_id AND v.version = r.version WHERE r.kind = 'joint_call'"
        assertEquals("7", c.scalar("SELECT count(*) FROM ($criteria) x"))
        assertEquals("5-step sales call assessment|Relationship assessment|Service quality assessment",
            c.column("SELECT c->>'section' FROM ($criteria) x GROUP BY 1 ORDER BY min((c->>'criterion_id')::int)").joinToString("|"))
        assertEquals("5,1,1", c.column("SELECT count(*) FROM ($criteria) x GROUP BY c->>'section' ORDER BY min((c->>'criterion_id')::int)").joinToString(","))
        assertEquals("0", c.scalar("SELECT count(*) FROM ($criteria) x WHERE c->>'guidance_en' IS NULL OR c->>'guidance_bn' IS NULL OR c->>'section_bn' IS NULL OR c->>'answer_type' <> 'score_1_5'"))
        assertEquals("(যা দেখতে হবে: OHS কাউন্ট, OOS, Product quality চেক, অপর্চুনিটি এবং ইস্যু বুঝা)", c.scalar("SELECT c->>'guidance_bn' FROM ($criteria) x WHERE c->>'criterion_id' = '1'"))
        // the bundle shape the v1 content had stays readable: version 1 has no section
        assertEquals("0", c.scalar("SELECT count(*) FROM app.rubric_version v JOIN app.rubric r ON r.id = v.rubric_id WHERE r.kind = 'joint_call' AND v.version = 1 AND v.criteria::text LIKE '%section%'"))
        val names = c.column("SELECT b.name FROM jsonb_array_elements_text((SELECT value FROM app.cfg_value WHERE key = 'cfg.rubric.distribution_brands')) WITH ORDINALITY e(id, n) JOIN app.product_node b ON b.id = e.id::bigint ORDER BY e.n").filterNotNull()
        assertEquals(17, names.size, "the dev catalogue has 17 of the 18 brands: $names")
        assertEquals(listOf("Maxim", "Avon", "ARIS", "Marise", "Black Diamond", "Sunmoon", "Supreme", "Special Abul Bidi"), names.take(8))
        assertTrue(names[8].startsWith("Existing Abul Bidi"))
        assertEquals(listOf("Ananda Bidi", "Abul Bidi Gold", "Abul Bidi Style", "Aster", "Flame Box", "Salmon", "ESSE", "MAX"), names.drop(9))
    }

    @Test
    fun theSeedIsIdempotent() = db.connect().use { c ->
        val before = c.scalar("SELECT (SELECT count(*) FROM app.rubric_version) || '/' || (SELECT count(*) FROM app.cfg_value)")
        SeedLoader.load(c)
        assertEquals(before, c.scalar("SELECT (SELECT count(*) FROM app.rubric_version) || '/' || (SELECT count(*) FROM app.cfg_value)"))
    }
}

package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Row N-008 acceptance: after seeding, the seeded SR has three routes (Daily, 3F, 2F) with 60 outlets and the seeded
 * TSO's scope covers them; re-running the seed changes nothing. Seed prices are the catalogue's, in milli-taka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeedTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterAll
    fun tearDown() = db.close()

    /** Row count and content hash of every table in app and dw (partitions folded into their parents). */
    private fun fingerprint(): Map<String, String> = db.connect().use { c ->
        val tables = c.column(
            "SELECT n.nspname || '.' || k.relname FROM pg_class k JOIN pg_namespace n ON n.oid = k.relnamespace " +
                "WHERE n.nspname IN ('app','dw') AND k.relkind IN ('r','p') AND NOT k.relispartition ORDER BY 1",
        ).filterNotNull()
        tables.associateWith { t ->
            c.scalar("SELECT count(*) || ':' || coalesce(md5(string_agg(x::text, '|' ORDER BY x::text)), '-') FROM $t x")!!
        }
    }

    @Test
    fun theSeededSrHasThreeRoutesOfEachKindWith60Outlets() = db.connect().use { c ->
        val routes = c.column(
            """
            SELECT r.visit_kind || ':' || r.visit_days_mask FROM app.route_assignment a
              JOIN app.route r ON r.id = a.route_id JOIN app.app_user u ON u.id = a.user_id
             WHERE u.username = 'sr1001' AND u.role = 'SR' AND a.kind = 'primary'
               AND daterange(a.valid_from, a.valid_to, '[)') @> app.dhaka_date(now())
             ORDER BY r.sequence_no
            """.trimIndent(),
        )
        assertEquals(listOf("daily:127", "3f:42", "2f:36"), routes)   // Daily; Sun/Tue/Thu; Mon/Thu (docs/22 P-17)
        val perRoute = c.column(
            """
            SELECT r.visit_kind || ':' || count(o.id) FROM app.route_assignment a
              JOIN app.route r ON r.id = a.route_id JOIN app.app_user u ON u.id = a.user_id
              JOIN app.outlet o ON o.route_id = r.id AND o.status = 'active'
             WHERE u.username = 'sr1001' GROUP BY r.visit_kind, r.sequence_no ORDER BY r.sequence_no
            """.trimIndent(),
        )
        assertEquals(listOf("daily:20", "3f:20", "2f:20"), perRoute)
        // Every outlet has a confirmed pin within 2 km of the test route's start (23.8069 N, 90.3687 E).
        val far = c.scalar(
            """
            SELECT count(*) FROM app.outlet o JOIN app.route r ON r.id = o.route_id
             WHERE r.code LIKE 'MIR-SR-%' AND (NOT o.location_confirmed OR o.lat IS NULL
                OR 2 * 6371008.8 * asin(sqrt(power(sin(radians(o.lat - 23.8069) / 2), 2)
                   + cos(radians(23.8069)) * cos(radians(o.lat)) * power(sin(radians(o.lng - 90.3687) / 2), 2))) > 2000)
            """.trimIndent(),
        )
        assertEquals("0", far)
        assertEquals("60", c.scalar("SELECT count(DISTINCT (lat, lng)) FROM app.outlet WHERE code LIKE 'MIR-%'"))
    }

    @Test
    fun theSeededTsoScopeCoversTheSrRoutesAndOutlets() = db.connect().use { c ->
        val uncovered = c.scalar(
            """
            SELECT count(*) FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id
              JOIN app.app_user sr ON sr.id = a.user_id AND sr.username = 'sr1001'
              JOIN app.outlet o ON o.route_id = r.id
             WHERE NOT EXISTS (
               SELECT 1 FROM app.user_scope s JOIN app.app_user t ON t.id = s.user_id AND t.username = 'tso1001' AND t.role = 'TSO'
                 JOIN app.zone z ON z.id = r.zone_id
                WHERE daterange(s.valid_from, s.valid_to, '[)') @> app.dhaka_date(now())
                  AND s.node_type = 'territory' AND s.node_id = z.territory_id AND o.zone_id = r.zone_id)
            """.trimIndent(),
        )
        assertEquals("0", uncovered)
        assertEquals("zone", c.scalar("SELECT s.node_type FROM app.user_scope s JOIN app.app_user u ON u.id = s.user_id WHERE u.username = 'amo1001'"))
    }

    @Test
    fun everyRoleOfTheChecksHasATestAccountWithoutAPasswordUnlessOneIsGiven() {
        db.connect().use { c ->
            assertEquals(
                listOf("ADMIN", "AMO", "DMO", "SR", "SUPERADMIN", "SUPPORT", "TSO"),
                c.column("SELECT role FROM app.app_user WHERE pilot ORDER BY role"),
            )
            assertEquals("0", c.scalar("SELECT count(*) FROM app.app_user WHERE password_hash IS NOT NULL"))
            assertEquals("0", c.scalar("SELECT count(*) FROM app.app_user WHERE pilot AND must_change_password"))
        }
        // With a password (generated here, never stored in the repository) every test account gets its Argon2id hash.
        val password = java.util.UUID.randomUUID().toString()
        TestPostgres.createDatabase().migrated().use { other ->
            other.connect().use { SeedLoader.load(it, password = password) }
            other.connect().use { c ->
                val hashes = c.column("SELECT password_hash FROM app.app_user WHERE pilot").filterNotNull()
                assertEquals(7, hashes.size)
                val argon2 = de.mkammerer.argon2.Argon2Factory.create(de.mkammerer.argon2.Argon2Factory.Argon2Types.ARGON2id)
                hashes.forEach { h ->
                    assertTrue(h.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), h)
                    assertTrue(argon2.verify(h, password.toCharArray()))
                }
                val before = c.column("SELECT password_hash FROM app.app_user WHERE pilot ORDER BY id")
                SeedLoader.load(c, password = "another")
                assertEquals(before, c.column("SELECT password_hash FROM app.app_user WHERE pilot ORDER BY id"), "a re-run keeps the hashes")
            }
        }
    }

    @Test
    fun theSeededSrHasAnActiveDevBindingAndTheCalendarAHoliday() = db.connect().use { c ->
        assertEquals(
            "active:0:active",
            c.scalar(
                "SELECT d.status || ':' || b.bind_ordinal || ':' || b.status FROM app.device d JOIN app.device_binding b ON b.device_id = d.id " +
                    "JOIN app.app_user u ON u.id = b.user_id WHERE u.username = 'sr1001' AND d.device_uuid = '00000000-0000-4000-8000-000000000001'",
            ),
        )
        assertEquals("Victory Day", c.scalar("SELECT name_en FROM app.calendar_holiday WHERE date = '2026-12-16'"))
    }

    @Test
    fun seedPricesAreTheCatalogueInMilliTaka() = db.connect().use { c ->
        val csv = File(System.getProperty("aron.seed"), "sku_catalog.csv").readLines().filter { it.isNotBlank() }
        val header = csv.first().split(",")
        val rows = csv.drop(1).map { line -> header.zip(line.split(",")).toMap() }
        assertEquals(42, rows.size)
        assertEquals(rows.size.toString(), c.scalar("SELECT count(*) FROM app.sku"))
        assertEquals((rows.size * 5).toString(), c.scalar("SELECT count(*) FROM app.sku_price"))
        val types = listOf("outlet" to "price_outlet", "cc" to "price_cc", "distributor" to "price_distributor",
            "reporting" to "price_reporting", "nto" to "price_nto")
        rows.forEach { r ->
            val code = r.getValue("sku_code").trim().replace(' ', '_')
            types.forEach { (type, column) ->
                val expected = BigDecimal(r.getValue(column)).multiply(BigDecimal(1000)).toBigIntegerExact().toString()
                assertEquals(
                    expected,
                    c.scalar("SELECT p.amount_mtk FROM app.sku_price p JOIN app.sku s ON s.id = p.sku_id WHERE s.code = ? AND p.price_type = ?", code, type),
                    "$code $type",
                )
            }
        }
        // The three-decimal price that motivates milli-taka: Maxim Regular distributor price 7.935 Tk a stick.
        assertEquals("7935", c.scalar("SELECT amount_mtk FROM app.sku_price p JOIN app.sku s ON s.id = p.sku_id WHERE s.code = 'MaxR-10S' AND price_type = 'distributor'"))
        assertEquals(listOf("4", "6", "17", "30"), c.column("SELECT count(*) FROM app.product_node GROUP BY level ORDER BY array_position(ARRAY['category','segment','brand','variant'], level)"))
        assertEquals("MaxDB-20S 20HL", c.scalar("SELECT short_name FROM app.sku WHERE code = 'MaxDB-20S_20HL'"))
        // The three SKUs priced 0 in every type stay off the sales plan.
        assertEquals("39", c.scalar("SELECT count(*) FROM app.sales_plan"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.sales_plan sp JOIN app.sku_price p ON p.sku_id = sp.sku_id AND p.price_type = 'outlet' WHERE p.amount_mtk = 0"))
    }

    @Test
    fun theDevOverridesGetTheirOwnNewConfigVersionOnABusyDatabase() = TestPostgres.createDatabase().migrated().use { other ->
        other.connect().use { c ->
            c.exec(
                "INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT v, 'change', id, 'earlier change ' || v " +
                    "FROM app.app_user, generate_series(2, 3) AS v WHERE username = 'aron.system'",
            )
            SeedLoader.load(c)
            assertEquals(
                listOf("4", "4", "4"),
                c.column("SELECT config_version FROM app.cfg_value WHERE key LIKE 'cfg.device.%' AND scope_type = 'global' ORDER BY key"),
            )
            assertEquals("Dev database overrides (docs/24 s9.4, seed)", c.scalar("SELECT summary FROM app.cfg_version WHERE config_version = 4"))
        }
    }

    @Test
    fun theDevDatabaseRelaxesEnrolmentAndIntegrity() = db.connect().use { c ->
        val sql = "SELECT value::text FROM app.cfg_value WHERE key = ? AND scope_type = 'global' AND effective_to IS NULL"
        assertEquals("false", c.scalar(sql, "cfg.device.require_enrolled"))
        assertEquals("\"dev\"", c.scalar(sql, "cfg.device.lockdown_level"))
        assertEquals("false", c.scalar(sql, "cfg.device.require_integrity"))
        assertTrue(c.scalar("SELECT count(*) FROM app.code_list_item WHERE list_key = 'force_reason' AND valid_to IS NULL")!!.toInt() >= 2)
    }

    @Test
    fun aDevDatabaseSeededBeforeV0027LosesTheInventedCodesAndLabels() = TestPostgres.createDatabase().migrated().use { other ->
        other.connect().use { c ->
            // What the pre-V0027 seed left behind: an invented code and a wrong label on a V0027 code.
            c.exec("INSERT INTO app.code_list_item (list_key, code, label_en) VALUES ('force_reason', 'gps_not_found', 'GPS not found')")
            c.exec("UPDATE app.code_list_item SET label_en = 'Wrong product', label_bn = 'ভুল পণ্য' WHERE list_key = 'edit_reason' AND code = 'wrong_sku'")
            SeedLoader.load(c)
            assertEquals("2026-10-07", c.scalar("SELECT valid_to::text FROM app.code_list_item WHERE list_key = 'force_reason' AND code = 'gps_not_found'"))
            assertEquals("ভুল SKU নির্বাচিত।", c.scalar("SELECT label_bn FROM app.code_list_item WHERE list_key = 'edit_reason' AND code = 'wrong_sku'"))
            assertEquals("4", c.scalar("SELECT count(*) FROM app.code_list_item WHERE list_key = 'force_reason' AND valid_to IS NULL"))
        }
    }

    @Test
    fun reRunningTheSeedChangesNothing() {
        val before = fingerprint()
        db.connect().use { SeedLoader.load(it) }
        db.connect().use { SeedLoader.load(it) }
        assertEquals(before, fingerprint())
    }
}

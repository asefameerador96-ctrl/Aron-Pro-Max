package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V0043 (AUD-DA-02 part 3): the type-1 dimensions keep an SCD2 version table beside them; a change opens a new version
 * on the business date of the change, so facts keyed to an earlier version keep their old attributes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DimensionHistoryTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    private val today = "app.dhaka_date(now())"

    @Test
    fun aDimensionChangeOpensANewVersionAndTheOldKeyStillAnswersEarlierDates() = db.connect().use { c ->
        c.tx {
            exec("SET LOCAL ROLE worker_rw")                                             // the projector's role
            exec(
                "INSERT INTO dw.dim_geo (route_id, route_code, route_name, route_kind, zone_id, zone_name, territory_id, territory_name, " +
                    "division_id, division_name, wing_id, wing_name, status) VALUES (1, 'R-1', 'Route 1', 'sr', 10, 'Zone A', 1, 'T', 1, 'D', 1, 'W', 'active')",
            )
            val first = scalar("SELECT geo_key FROM dw.dim_geo_version")!!
            exec("UPDATE dw.dim_geo SET updated_at = now() + interval '1 minute'")             // a rebuild that changes nothing
            assertEquals("1", scalar("SELECT count(*) FROM dw.dim_geo_version"))
            exec("UPDATE dw.dim_geo SET zone_id = 20, zone_name = 'Zone B'")                   // the route moves zone
            exec("UPDATE dw.dim_geo SET zone_name = 'Zone B (renamed)'")                       // same day: rewrites that version
            assertEquals(
                listOf("10|false|true", "20|true|false"),
                column("SELECT zone_id || '|' || is_current || '|' || (valid_from IS NULL) FROM dw.dim_geo_version ORDER BY geo_key"),
            )
            assertEquals(first, scalar("SELECT dw.geo_key_on(1, '2026-01-01')"))
            assertEquals("Zone B (renamed)", scalar("SELECT zone_name FROM dw.dim_geo_version WHERE geo_key = dw.geo_key_on(1, $today)"))
            exec("DELETE FROM dw.dim_geo")                                                       // the current version goes
            assertEquals(null, scalar("SELECT dw.geo_key_on(1, $today)"))
            assertEquals(first, scalar("SELECT dw.geo_key_on(1, '2026-01-01')"))
        }
    }

    @Test
    fun everyDimensionHasItsVersionTableAndTheFactsCanStoreTheKey() = db.connect().use { c ->
        assertEquals(
            listOf("dim_geo_version", "dim_outlet_version", "dim_product_version", "dim_user_version"),
            c.column("SELECT relname FROM pg_class WHERE relnamespace = 'dw'::regnamespace AND relkind = 'r' AND relname LIKE 'dim%version' ORDER BY 1"),
        )
        assertEquals(
            listOf("fact_memo.geo_key", "fact_memo.outlet_key", "fact_memo.user_key", "fact_visit.geo_key", "fact_visit.outlet_key", "fact_visit.user_key"),
            c.column(
                "SELECT k.relname || '.' || a.attname FROM pg_attribute a JOIN pg_class k ON k.oid = a.attrelid " +
                    "WHERE k.relnamespace = 'dw'::regnamespace AND k.relname IN ('fact_visit', 'fact_memo') AND a.attname LIKE '%\\_key' ORDER BY 1",
            ),
        )
        // dim_user carries no personal data.
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_attribute WHERE attrelid = 'dw.dim_user'::regclass AND attname IN ('full_name', 'username', 'phone', 'email')"))
    }
}

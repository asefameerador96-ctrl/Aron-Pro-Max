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
 * V0180 (Astha target upload ledger) and V0181 (one outlet row per month and brand), lane portal-loyalty-api push B3: the batch ledger refuses UPDATE, DELETE and TRUNCATE (trigger, even for
 * a superuser) and the API role holds no UPDATE, DELETE or TRUNCATE privilege on it; the unique index keeps one row per (month, outlet, brand) whatever the route, while route rows
 * (outlet NULL) and the null-brand memo row stay separate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsthaTargetV0180V0181Test {
    private lateinit var db: TestDatabase

    @BeforeAll fun setUp() { db = TestPostgres.createDatabase().migrated(); db.connect().use { SeedLoader.load(it) } }

    @AfterAll fun tearDown() = db.close()

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    @Test
    fun theBatchLedgerIsAppendOnlyAndTheApiRoleCannotChangeIt() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("INSERT INTO app.astha_target_batch (batch_uuid, quarter, request_fingerprint, row_count, inserted, updated, unchanged, reason, actor) SELECT gen_random_uuid(), '2027-Q1', 'fp', 1, 1, 0, 0, 'a reason of ten or more', id FROM app.app_user LIMIT 1")
            for (sql in listOf("UPDATE app.astha_target_batch SET reason = 'changed to something else'", "DELETE FROM app.astha_target_batch", "TRUNCATE app.astha_target_batch"))
                assertEquals(true, c.refused(sql) in setOf("P0001", "42501"), sql)   // the guard trigger (P0001) or the revoked privilege (42501), whichever answers first
            assertEquals("2", c.scalar("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'app.astha_target_batch'::regclass AND tgname IN ('astha_target_batch_append_only', 'astha_target_batch_no_truncate')"), "both guard triggers exist")
            for (p in listOf("UPDATE", "DELETE", "TRUNCATE")) assertEquals("false", c.scalar("SELECT has_table_privilege('api_rw', 'app.astha_target_batch', '$p')::text"), "api_rw $p")
            assertEquals("true", c.scalar("SELECT has_table_privilege('api_rw', 'app.astha_target_batch', 'INSERT')::text"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun anOutletHasOneTargetRowPerMonthAndBrandWhateverTheRoute() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val outlet = c.scalar("SELECT id FROM app.outlet WHERE route_id IS NOT NULL ORDER BY id LIMIT 1")!!
            val route = c.scalar("SELECT route_id FROM app.outlet WHERE id = $outlet")!!
            val other = c.scalar("SELECT id FROM app.route WHERE id <> $route ORDER BY id LIMIT 1")!!
            fun row(r: String, o: String, brand: String) = "INSERT INTO app.astha_target (month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES (DATE '2027-01-01', $r, $o, $brand, 1, 1)"
            c.exec(row(route, outlet, "NULL"))
            assertEquals("23505", c.refused(row(other, outlet, "NULL")), "the same outlet and month on another route is a second row: refused")
            c.exec("INSERT INTO app.astha_target (month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES (DATE '2027-01-01', $route, NULL, NULL, 1, 1)")   // the route row is separate
            c.exec("INSERT INTO app.astha_target (month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES (DATE '2027-02-01', $route, $outlet, NULL, 1, 1)")  // another month
        } finally { c.rollback(); c.autoCommit = true }
    }
}

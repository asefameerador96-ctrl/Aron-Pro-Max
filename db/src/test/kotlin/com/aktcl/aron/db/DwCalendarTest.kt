package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** V0048 (AUD-DA-07): dw.build_dim_date extends the calendar with V0011's attributes, idempotently. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DwCalendarTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() { db = TestPostgres.createDatabase().migrated() }

    @AfterAll
    fun tearDown() = db.close()

    @Test
    fun extendsPast2030WithTheSameAttributesAndIsIdempotent() = db.connect().use { c ->
        c.autoCommit = false
        try {
            assertEquals("0", c.scalar("SELECT dw.build_dim_date('2030-12-01', '2030-12-31')"))      // V0011 rows already there
            assertEquals("31", c.scalar("SELECT dw.build_dim_date('2030-12-15', '2031-01-31')"))
            assertEquals("0", c.scalar("SELECT dw.build_dim_date('2031-01-01', '2031-01-31')"))
            // 2031-01-03 is a Friday: weekend, ISO weekday 5, visit bit 6 (Saturday = 0)
            assertEquals("5|6|2031-01-01|2031-Q1|t", c.scalar(
                "SELECT concat_ws('|', iso_weekday, visit_day_bit, month, quarter, is_weekend) FROM dw.dim_date WHERE business_date = '2031-01-03'"))
            // the same formula as V0011 on a V0011 date
            assertEquals(
                c.scalar("SELECT concat_ws('|', iso_weekday, visit_day_bit, month, quarter, iso_week, is_weekend) FROM dw.dim_date WHERE business_date = '2030-12-27'"),
                c.scalar("SELECT concat_ws('|', 5, 6, '2030-12-01', '2030-Q4', extract(week FROM date '2030-12-27'), true)"),
            )
            c.exec("SAVEPOINT s")
            assertEquals("22023", assertFailsWith<SQLException> { c.scalar("SELECT dw.build_dim_date('2031-01-01', '2050-01-01')") }.sqlState)
            c.exec("ROLLBACK TO SAVEPOINT s")
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun onlyTheWorkerCanAddDates() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("SET LOCAL ROLE worker_rw")
            assertEquals("1", c.scalar("SELECT dw.build_dim_date('2031-02-01', '2031-02-01')"))
            c.exec("RESET ROLE")
            c.exec("SET LOCAL ROLE web_ro")
            c.exec("SAVEPOINT s")
            assertEquals("42501", assertFailsWith<SQLException> { c.scalar("SELECT dw.build_dim_date('2031-02-02', '2031-02-02')") }.sqlState)
            c.exec("ROLLBACK TO SAVEPOINT s")
        } finally { c.rollback(); c.autoCommit = true }
    }
}

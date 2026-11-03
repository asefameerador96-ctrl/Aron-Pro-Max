package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.AutoFinalSubmitJob
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-TSO-MAN-49 (D-262: auto-close stays off until the owner confirms): with NO `cfg.day.auto_final_submit_enabled` row (the shipped
 * default, false) the midnight job writes nothing: a due, planned zone-day is left open, no close, no inbox row. The job's own
 * behaviour with the switch on is [AutoFinalSubmitJobTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoFinalSubmitDefaultOffTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val afterMidnight = Instant.parse("2027-01-02T18:00:05Z")
    private val d01 = LocalDate.parse("2027-01-01")
    private lateinit var world: TsoWorld
    private lateinit var workerDb: Database
    private lateinit var job: AutoFinalSubmitJob
    private var zone = 0L

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("AFD"), start = afterMidnight)
        zone = world.one("SELECT id FROM app.zone WHERE code = 'Z-AFD'")
        world.exec(
            "INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id) SELECT r.id, DATE '$d01', true, " +
                "(SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary' LIMIT 1) FROM app.route r WHERE r.code = 'AFD-SR-D'",
        )
        workerDb = Database(HikariDataSource(HikariConfig().apply { jdbcUrl = world.fresh.url; maximumPoolSize = 2; connectionInitSql = "SET ROLE worker_rw" }))
        job = AutoFinalSubmitJob(
            workerDb, DbServerConfig(workerDb, RegistryDefaults(), world.clock), SqlReachResolver(workerDb, GeoRepository(workerDb, world.clock), world.clock),
            Nudger { _, _ -> }, world.clock,
        )
    }

    @AfterAll
    fun tearDown() { workerDb.close(); world.close() }

    @Test
    fun theShippedDefaultIsOffAndThePassClosesNothing() {
        assertEquals(0, world.rows("SELECT 1 FROM app.cfg_value WHERE key = 'cfg.day.auto_final_submit_enabled'").size, "no override row: the shipped default")
        assertEquals("false", world.rows("SELECT default_value::text AS v FROM app.cfg_key WHERE key = 'cfg.day.auto_final_submit_enabled'").single()["v"].toString())
        assertTrue(job.closeDate(d01).isEmpty(), "a planned, due zone-day is left alone under the default")
        assertTrue(job.runOnce().isEmpty(), "the whole window is left alone")
        assertEquals(0, world.rows("SELECT 1 FROM app.final_submit WHERE zone_id = $zone").size, "nothing is written")
        assertEquals(0, world.rows("SELECT 1 FROM app.user_notification").size, "no inbox row")
    }
}

package com.aktcl.aron.db

import org.junit.jupiter.api.AfterEach
import java.io.File
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Push 1b of lane masterdata-fit: the migrations run by hand over a database migrated to just before them, as a second session would meet them in prod:
 * V0197's lock loop waits holding nothing while a sync writer holds a hot table (no queue behind it), V0199 refuses a Titanium that already exists under
 * another channel (detected, not skipped), maps the old dev cluster type 'market' and adds the source columns and the candidate pin basis.
 */
class MasterDataFitV0199Test {
    private lateinit var db: TestDatabase
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val dir = File(System.getProperty("aron.migrations"))

    private fun prepare(target: String) {
        db = TestPostgres.createDatabase()
        org.flywaydb.core.Flyway.configure().configuration(db.flyway().configuration).target(target).load().migrate()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        if (::db.isInitialized) db.close()
    }

    private fun runFile(prefix: String) = db.connect().use { c ->
        c.autoCommit = false
        try { c.createStatement().use { it.execute(dir.listFiles()!!.single { it.name.startsWith(prefix) }.readText()) }; c.commit() } catch (e: Exception) { c.rollback(); throw e }
    }

    @Test
    fun v0197WaitsHoldingNothingWhileASyncWriterHoldsTheMemoTable() {
        prepare("196")
        db.connect().use { writer ->
            writer.autoCommit = false
            writer.exec("SELECT count(*) FROM app.memo_void")     // a writer's transaction holds a lock on one of the four tables
            val migration = pool.submit(Callable { runFile("V0197__") })
            val deadline = System.nanoTime() + 20_000_000_000L
            while (writer.also { it.exec("SELECT pg_stat_clear_snapshot()") }.scalar("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event = 'PgSleep' AND pid <> pg_backend_pid()") == "0") {
                check(System.nanoTime() < deadline) { "V0197 never reached its retry sleep" }
                Thread.sleep(100)
            }
            // While V0197 sleeps it holds none of the four tables: another session can still read app.app_user at once.
            db.connect().use { other -> other.exec("SET lock_timeout = '1s'"); other.exec("SELECT count(*) FROM app.app_user") }
            assertEquals(false, migration.isDone)
            writer.commit()
            migration.get(30, TimeUnit.SECONDS)
        }
        db.connect().use { c -> assertEquals("true", c.scalar("SELECT (pg_get_constraintdef(oid) LIKE '%{2,31}%')::text FROM pg_constraint WHERE conname = 'app_user_memo_username'")) }
    }

    @Test
    fun aTitaniumOfAnotherChannelIsDetectedNotSkipped() {
        prepare("198")
        db.connect().use { it.exec("INSERT INTO app.sub_channel (channel, code, name) VALUES ('GT', 'Titanium', 'Titanium GT')") }
        val e = assertFailsWith<SQLException> { runFile("V0199__") }
        assertTrue(e.message!!.contains("Titanium") && e.message!!.contains("GT"), e.message)
        // an inactive Titanium of Astha is re-activated instead
        db.connect().use { it.exec("UPDATE app.sub_channel SET channel = 'Astha', status = 'inactive' WHERE code = 'Titanium'") }
        runFile("V0199__")
        runFile("V0200__")
        db.connect().use { c -> assertEquals("Astha|active", c.scalar("SELECT channel || '|' || status FROM app.sub_channel WHERE code = 'Titanium'")) }
    }

    @Test
    fun oldMarketClustersAreMappedAndTheSourceColumnsAndCandidateBasisExist() {
        prepare("198")
        db.connect().use { c ->
            c.exec("INSERT INTO app.cluster (zone_id, name, cluster_type) SELECT id, 'Old Market', 'market' FROM app.zone ORDER BY id LIMIT 1")
            c.exec("INSERT INTO app.cluster (zone_id, name, cluster_type) SELECT id, 'Odd Type', 'Mall' FROM app.zone ORDER BY id LIMIT 1")
        }
        runFile("V0199__")
        runFile("V0200__")
        db.connect().use { c ->
            assertEquals("Mall|Bazar Hub", c.scalar("SELECT string_agg(cluster_type, '|' ORDER BY name) FROM app.cluster WHERE name IN ('Old Market', 'Odd Type')"))
            assertEquals("source_contact_number|source_created_at|source_sub_channel|source_updated_at",
                c.scalar("SELECT string_agg(column_name, '|' ORDER BY column_name) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'outlet' AND column_name LIKE 'source\\_%'"))
            assertEquals("true", c.scalar("SELECT (pg_get_constraintdef(oid) LIKE '%candidate%')::text FROM pg_constraint WHERE conname = 'outlet_location_history_basis_check'"))
            assertEquals("0", c.scalar("SELECT count(*) FROM pg_constraint WHERE NOT convalidated AND conname IN ('outlet_source_contact_number_len', 'outlet_source_sub_channel_len', 'outlet_location_history_basis_check')"))
            assertEquals("0", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.release.short_rep_id_version_code'"))
            assertEquals("null|true", c.scalar("SELECT coalesce(valid_to::text, 'null') || '|' || (attrs->>'retired') FROM app.code_list_item WHERE list_key = 'sub_channel' AND code = 'silver'"))
        }
    }
}

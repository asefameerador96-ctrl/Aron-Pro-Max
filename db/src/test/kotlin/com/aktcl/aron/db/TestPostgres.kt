package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * A throwaway PostgreSQL 16 database per test class (docs/24 s2.5, D24-05). The server comes from ARON_TEST_PG_URL
 * (CI service container, local server; the role needs CREATEDB) or, when that is unset, a Testcontainers
 * `postgres:16-alpine` (needs Docker). Each [TestDatabase] is created empty and dropped on close, so tests never see
 * each other's schema and never touch the shared database the URL names.
 */
object TestPostgres {
    private data class Server(val baseUrl: String, val user: String?, val password: String?)

    private val urlShape = Regex("^(jdbc:postgresql://[^/]+/)([^?]*)(\\?.*)?$")

    private val server: Server by lazy {
        val env = System.getenv("ARON_TEST_PG_URL").orEmpty()
        if (env.isNotBlank()) {
            Server(env, null, null)
        } else {
            val c = PostgreSQLContainer("postgres:16-alpine")
            c.start()
            Runtime.getRuntime().addShutdownHook(Thread { c.stop() })
            Server(c.jdbcUrl, c.username, c.password)
        }
    }

    private fun urlFor(database: String): String {
        val m = urlShape.matchEntire(server.baseUrl) ?: error("ARON_TEST_PG_URL is not a jdbc:postgresql URL")
        return m.groupValues[1] + database + m.groupValues[3]
    }

    internal fun connect(url: String): Connection =
        if (server.user == null) DriverManager.getConnection(url)
        else DriverManager.getConnection(url, server.user, server.password)

    fun createDatabase(): TestDatabase {
        val name = "aron_db_test_" + UUID.randomUUID().toString().replace("-", "").take(16)
        connect(server.baseUrl).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return TestDatabase(name, urlFor(name)) {
            // FORCE cannot terminate an autovacuum worker of another role (42501); it ends within moments, so retry.
            for (attempt in 1..20) {
                try {
                    connect(server.baseUrl).use { c -> c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
                    break
                } catch (e: java.sql.SQLException) {
                    if (e.sqlState != "42501" || attempt == 20) throw e
                    Thread.sleep(250)
                }
            }
        }
    }

    fun flyway(url: String, location: String = DbMigrations.LOCATION): Flyway =
        Flyway.configure()
            .dataSource(url, server.user, server.password)
            .locations(location)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .callbacks(RoleDdlLock.Around)
            .load()

    /**
     * Server-wide mutex for role DDL. The V0014/V0020 roles live in the cluster, not in a database, so two test JVMs that
     * migrate their throwaway databases at the same time (or a test that changes a role while another migrates) update
     * the same pg_authid / pg_auth_members tuples and fail with "tuple concurrently updated". Advisory locks are scoped
     * to one database, so the lock is taken in the shared database the URL names, which every test process connects to;
     * backend's FreshDb takes the same key (docs/requests/db-role-ddl-lock.md). Re-entrant per JVM; one session holds it.
     */
    object RoleDdlLock {
        const val KEY = 7_204_190_014L                   // fixed; the same number in every harness
        private val jvm = java.util.concurrent.locks.ReentrantLock()
        private var holder: Connection? = null

        fun <T> hold(block: () -> T): T {
            acquire()
            try { return block() } finally { release() }
        }

        private fun acquire() {
            jvm.lock()
            if (jvm.holdCount == 1) {
                holder = connect(server.baseUrl).also { c -> c.createStatement().use { it.execute("SELECT pg_advisory_lock($KEY)") } }
            }
        }

        private fun release() {
            try {
                if (jvm.holdCount == 1) holder?.use { c -> c.createStatement().use { it.execute("SELECT pg_advisory_unlock($KEY)") } }
            } finally {
                if (jvm.holdCount == 1) holder = null
                jvm.unlock()
            }
        }

        /** Every Flyway migrate of the test harness runs under the lock (configurations copied from it keep the callback). */
        object Around : Callback {
            override fun supports(event: Event, context: Context?) =
                event == Event.BEFORE_MIGRATE || event == Event.AFTER_MIGRATE || event == Event.AFTER_MIGRATE_ERROR
            override fun canHandleInTransaction(event: Event, context: Context?) = true
            override fun handle(event: Event, context: Context?) = if (event == Event.BEFORE_MIGRATE) acquire() else release()
            override fun getCallbackName() = "aron-role-ddl-lock"
        }
    }
}

class TestDatabase(val name: String, val url: String, private val drop: () -> Unit) : AutoCloseable {
    fun connect(): Connection = TestPostgres.connect(url)
    fun flyway(location: String = DbMigrations.LOCATION): Flyway = TestPostgres.flyway(url, location)

    /** Applies every packaged migration and returns this database. */
    fun migrated(): TestDatabase = apply { flyway().migrate() }

    override fun close() = drop()
}

/** Runs [sql] and returns the first column of every row as text. */
fun Connection.column(sql: String, vararg args: Any?): List<String?> =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

fun Connection.scalar(sql: String, vararg args: Any?): String? = column(sql, *args).single()

fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}

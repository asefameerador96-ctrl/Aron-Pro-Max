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
        return TestDatabase(name, urlFor(name)) { dropDatabase(name) }
    }

    private fun dropDatabase(name: String) {
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

    /**
     * A migrated database for tests that only need the finished schema: a copy (CREATE DATABASE ... TEMPLATE) of a template that is migrated once
     * per server and migration set, built and copied under [RoleDdlLock]. A full Flyway run per class cost about 11 s and grew with every migration
     * (push A2 profile of the "Shared, db and backend" job, docs/status/portal-loyalty-api.md). Tests whose subject is the migration run itself
     * use [TestDatabase.migratedByFlyway].
     */
    internal fun createMigratedCopy(): TestDatabase {
        val name = "aron_db_test_" + UUID.randomUUID().toString().replace("-", "").take(16)
        RoleDdlLock.hold {
            connect(server.baseUrl).use { c ->
                val tpl = "aron_dbtpl_" + fingerprint
                val exists = c.createStatement().use { st -> st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$tpl'").use { it.next() } }
                if (!exists) {
                    val wip = "aron_dbtplwip_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    c.createStatement().use { it.execute("CREATE DATABASE $wip") }
                    try {
                        Flyway.configure().dataSource(urlFor(wip), server.user, server.password).locations(DbMigrations.LOCATION).cleanDisabled(true).validateOnMigrate(true).load().migrate()
                        c.createStatement().use { it.execute("ALTER DATABASE $wip RENAME TO $tpl") }
                    } catch (e: Throwable) {
                        runCatching { c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $wip WITH (FORCE)") } }
                        throw e
                    }
                }
                for (attempt in 1..40) {          // a template must have no session; an autovacuum worker may touch it for a moment
                    try { c.createStatement().use { it.execute("CREATE DATABASE $name TEMPLATE $tpl") }; break }
                    catch (e: java.sql.SQLException) { if (e.sqlState != "55006" || attempt == 40) throw e; Thread.sleep(250) }
                }
            }
        }
        return TestDatabase(name, urlFor(name)) { dropDatabase(name) }
    }

    /** Digest of the packaged migrations (name and bytes): a changed or added migration gives a new template. */
    private val fingerprint: String by lazy {
        val files = sortedMapOf<String, ByteArray>()
        for (u in TestPostgres::class.java.classLoader.getResources("db/migration")) {
            if (u.protocol == "file") java.nio.file.Files.list(java.nio.file.Path.of(u.toURI())).use { st -> st.forEach { f -> if (f.fileName.toString().endsWith(".sql")) files[f.fileName.toString()] = java.nio.file.Files.readAllBytes(f) } }
            else if (u.protocol == "jar") (u.openConnection() as java.net.JarURLConnection).jarFile.let { jar ->
                jar.entries().asSequence().filter { it.name.startsWith("db/migration/") && it.name.endsWith(".sql") }.forEach { e -> files[e.name.substringAfterLast('/')] = jar.getInputStream(e).use { it.readBytes() } }
            }
        }
        check(files.isNotEmpty()) { "no db/migration resources on the classpath" }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        files.forEach { (n, b) -> md.update(n.toByteArray()); md.update(0); md.update(b); md.update(0) }
        md.digest().joinToString("") { "%02x".format(it) }.take(16)
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
            if (jvm.holdCount > 1) return
            var c: Connection? = null
            try {
                c = connect(server.baseUrl)
                c.createStatement().use { it.execute("SELECT pg_advisory_lock($KEY)") }
                holder = c
            } catch (e: Throwable) {                       // never keep the JVM lock (or a connection) after a failed acquire
                runCatching { c?.close() }
                jvm.unlock()
                throw e
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

    /** Returns a database holding the finished schema: [TestPostgres.createMigratedCopy] (this empty database is dropped). Use [migratedByFlyway] to run the migrations here. */
    fun migrated(): TestDatabase = TestPostgres.createMigratedCopy().also { close() }

    /** Applies every packaged migration with Flyway, on this database, and returns it: for tests whose subject is the migration run. */
    fun migratedByFlyway(): TestDatabase = apply { flyway().migrate() }

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

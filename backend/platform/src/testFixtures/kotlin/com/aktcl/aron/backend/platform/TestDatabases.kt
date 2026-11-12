package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import java.util.UUID

/**
 * A fresh, migrated PostgreSQL 16 database per test class (docs/24 s2.5): created on the server named by
 * ARON_TEST_PG_URL (the role needs CREATEDB), migrated with the db module's Flyway migrations, dropped on close.
 */
class FreshDb private constructor(val name: String, val url: String, private val adminUrl: String) : AutoCloseable {
    val dataSource: HikariDataSource = HikariDataSource(HikariConfig().apply { jdbcUrl = url; maximumPoolSize = 8 })
    val db: Database = Database(dataSource)

    override fun close() {
        dataSource.close()
        DriverManager.getConnection(adminUrl).use { c -> c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
    }

    companion object {
        /** Key of the db lane's TestPostgres.RoleDdlLock. */
        const val ROLE_DDL_LOCK = 7204190014L
        private val shape = Regex("^(jdbc:postgresql://[^/]+/)([^?]*)(\\?.*)?$")

        fun create(migrate: Boolean = true): FreshDb {
            val base = System.getenv("ARON_TEST_PG_URL").orEmpty().ifBlank { error("ARON_TEST_PG_URL is not set (docs/24 s2.5)") }
            val m = shape.matchEntire(base) ?: error("ARON_TEST_PG_URL is not a jdbc:postgresql URL")
            val name = "aron_be_" + UUID.randomUUID().toString().replace("-", "").take(16)
            if (migrate) {
                // A migrated database is a copy of a template migrated once per server and migration set (CREATE DATABASE ... TEMPLATE),
                // not a full Flyway run per class: the full run costs about 11 s and grows with every migration, and it ran under a
                // server-wide lock that serialised all test JVMs (push A2 profile, docs/status/portal-loyalty-api.md).
                // The V0014/V0020 roles are cluster-wide: role DDL of two migrations at once must not overlap (CI "tuple concurrently
                // updated"), so the template build and every copy run under the same server-wide advisory lock as the db harness
                // (TestPostgres.RoleDdlLock), taken in the shared base database (docs/requests/db-role-ddl-lock.md).
                DriverManager.getConnection(base).use { lock ->
                    lock.createStatement().use { it.execute("SELECT pg_advisory_lock($ROLE_DDL_LOCK)") }
                    try {
                        val copied = runCatching { copyFromTemplate(lock, base, m.groupValues[1], m.groupValues[3], name) }
                        if (copied.isFailure) {
                            System.err.println("FreshDb: template copy unavailable (${copied.exceptionOrNull()!!.javaClass.name}); migrating directly")
                            lock.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
                            lock.createStatement().use { it.execute("CREATE DATABASE $name") }
                            val direct = FreshDb(name, m.groupValues[1] + name + m.groupValues[3], base)
                            Migrator.migrate(direct.dataSource)
                            return direct
                        }
                    } finally { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock($ROLE_DDL_LOCK)") } }
                }
                return FreshDb(name, m.groupValues[1] + name + m.groupValues[3], base)
            }
            DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
            val fresh = FreshDb(name, m.groupValues[1] + name + m.groupValues[3], base)
            return fresh
        }

        /** Digest of the packaged migrations (name and bytes): a changed or added migration gives a new template. */
        private val migrationFingerprint: String by lazy {
            val files = sortedMapOf<String, ByteArray>()
            for (u in FreshDb::class.java.classLoader.getResources("db/migration")) {
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

        /** Builds the template once (under the caller's lock; renamed into place so a crash leaves no half-migrated template), then copies it to [name]. */
        private fun copyFromTemplate(lock: java.sql.Connection, base: String, urlHead: String, urlTail: String, name: String) {
            val tpl = "aron_tpl_$migrationFingerprint"
            val exists = lock.createStatement().use { st -> st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$tpl'").use { it.next() } }
            if (!exists) {
                val wip = "aron_tplwip_" + UUID.randomUUID().toString().replace("-", "").take(12)
                lock.createStatement().use { it.execute("CREATE DATABASE $wip") }
                try {
                    HikariDataSource(HikariConfig().apply { jdbcUrl = urlHead + wip + urlTail; maximumPoolSize = 2 }).use { Migrator.migrate(it) }
                    lock.createStatement().use { it.execute("ALTER DATABASE $wip RENAME TO $tpl") }
                } catch (e: Throwable) {
                    runCatching { lock.createStatement().use { it.execute("DROP DATABASE IF EXISTS $wip WITH (FORCE)") } }
                    throw e
                }
            }
            // A template must have no session; an autovacuum worker may touch it for a moment, so retry the copy.
            for (attempt in 1..40) {
                try { lock.createStatement().use { it.execute("CREATE DATABASE $name TEMPLATE $tpl") }; return }
                catch (e: java.sql.SQLException) { if (e.sqlState != "55006" || attempt == 40) throw e; Thread.sleep(250) }
            }
        }
    }
}

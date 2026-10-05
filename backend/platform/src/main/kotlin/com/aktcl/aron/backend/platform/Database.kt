package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import javax.sql.DataSource

/**
 * Hikari pools (write, and an optional read replica for dashboards, ARON_DB_READ_URL) and JDBI handles
 * (docs/24 s2.5, s6.3). SQL lives in each context's repository class and uses bind parameters only.
 */
class Database(val write: DataSource, val read: DataSource = write) : AutoCloseable {
    val jdbi: Jdbi = jdbiFor(write)
    val readJdbi: Jdbi = if (read === write) jdbi else jdbiFor(read)

    /** True when a connection can be borrowed and answers within 2 s (readiness probe). */
    fun ping(): Boolean = runCatching {
        write.connection.use { c -> c.isValid(2) }
    }.getOrDefault(false)

    override fun close() {
        (write as? AutoCloseable)?.close()
        if (read !== write) (read as? AutoCloseable)?.close()
    }

    companion object {
        fun jdbiFor(ds: DataSource): Jdbi = Jdbi.create(ds).installPlugin(KotlinPlugin())

        fun pool(url: String, user: String?, password: Secret?, maxSize: Int, name: String, readOnly: Boolean = false): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    user?.let { username = it }
                    password?.let { this.password = it.reveal() }
                    maximumPoolSize = maxSize
                    minimumIdle = 1
                    poolName = name
                    isReadOnly = readOnly
                    connectionTimeout = 5_000
                    validationTimeout = 2_000
                    // Sessions run in UTC; business dates are always explicit (docs/24 s3.8).
                    connectionInitSql = "SET TIME ZONE 'UTC'"
                    initializationFailTimeout = -1 // the API starts (health answers) even while the database is down
                },
            )

        fun fromSettings(s: Settings): Database {
            val w = pool(s.dbUrl, s.dbUser, s.dbPassword, s.dbPoolMax, "aron-write")
            val r = s.dbReadUrl?.let { pool(it, s.dbUser, s.dbPassword, s.dbReadPoolMax, "aron-read", readOnly = true) } ?: w
            return Database(w, r)
        }
    }
}

/** Flyway migrate from classpath:db/migration (the `migrate` role, docs/24 s6.1). Forward-only; validates checksums. */
object Migrator {
    fun migrate(ds: DataSource): Int =
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .validateOnMigrate(true)
            .load()
            .migrate()
            .migrationsExecuted
}

package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.BaseCallback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.flywaydb.core.api.configuration.FluentConfiguration
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

    /** True when a connection can be borrowed and answers within [timeoutMs] (readiness probe; never blocks longer). */
    fun ping(timeoutMs: Long = 2_000): Boolean = runCatching {
        java.util.concurrent.CompletableFuture.supplyAsync { write.connection.use { c -> c.isValid(2) } }
            .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }.getOrDefault(false)

    override fun close() {
        (write as? AutoCloseable)?.close()
        if (read !== write) (read as? AutoCloseable)?.close()
    }

    companion object {
        fun jdbiFor(ds: DataSource): Jdbi = Jdbi.create(ds).installPlugin(KotlinPlugin())

        /** [connectionTimeoutMs] stays 5 s for the api and worker (requests fail fast); only the migrate pool waits longer. */
        fun pool(url: String, user: String?, password: Secret?, maxSize: Int, name: String, readOnly: Boolean = false, connectionTimeoutMs: Long = 5_000): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    user?.let { username = it }
                    password?.let { this.password = it.reveal() }
                    maximumPoolSize = maxSize
                    minimumIdle = 1
                    poolName = name
                    isReadOnly = readOnly
                    connectionTimeout = connectionTimeoutMs
                    validationTimeout = 2_000
                    // No session state (the api pool goes through PgBouncer transaction pooling): every SQL statement
                    // binds timestamptz values and names Asia/Dhaka explicitly where a business date is derived.
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
    /** Connection timeout of the migrate role's pool: a fresh job replica may need a while for its first connection. */
    const val MIGRATE_CONNECTION_TIMEOUT_MS = 30_000L

    /**
     * [connectRetries] retries the first connection with Flyway's backoff (at most [connectRetriesIntervalS] apart), so a
     * migrate job that starts before private DNS, TLS and SCRAM are ready waits instead of failing
     * (docs/requests/backend-migrate-connect-retries.md). Any other migration failure still fails at once.
     */
    fun migrate(ds: DataSource, connectRetries: Int = 10, connectRetriesIntervalS: Int = 15): Int =
        configure(ds, connectRetries, connectRetriesIntervalS).load().migrate().migrationsExecuted

    /**
     * Every Aron PostgreSQL server sets `idle_in_transaction_session_timeout = 30000` (30 s; `infra/modules/postgres.bicep`,
     * parameter `idle_in_transaction_session_timeout`) so a pooled api or worker session that stalls inside a transaction cannot
     * hold locks or a replica's cleanup horizon for long. The migrate role is exempt on purpose: Flyway runs a migration on
     * one connection while a second one (schema history, advisory lock) waits idle inside a transaction for the whole
     * migration, so every migration longer than 30 s lost the waiting connection and failed on Flyway's next write with
     * SQLSTATE 08006 (deploys #308, #309 and #311; found and reproduced by db #15, D-OPS-MIGRATE-IDLE-TIMEOUT-01). The
     * exemption is this statement, run by [ExemptIdleInTransaction] on every connection Flyway opens, so only the migrate
     * role's sessions are exempt (the job's `replicaTimeout` bounds them instead); the api and worker pools are untouched.
     */
    const val INIT_SQL = "SET idle_in_transaction_session_timeout = 0"

    /**
     * Runs [INIT_SQL] after Flyway connects: the supported replacement (Flyway's `afterConnect` callback event) of the
     * deprecated `initSql` setting, which printed a DeprecationUtils WARN per connection in the migrate console (deploy
     * #315, D-OPS-FLYWAY-AFTERCONNECT-01).
     */
    object ExemptIdleInTransaction : BaseCallback() {
        // Flyway passes a null Context when it asks whether a callback supports an event, so the parameter is nullable.
        override fun supports(event: Event, context: Context?): Boolean = event == Event.AFTER_CONNECT
        override fun canHandleInTransaction(event: Event, context: Context?): Boolean = true
        override fun handle(event: Event, context: Context) {
            context.connection.createStatement().use { it.execute(INIT_SQL) }
        }
        override fun getCallbackName(): String = "aron-exempt-idle-in-transaction"
    }

    /** The migrate role's Flyway configuration; [locations] is overridable for tests only. */
    fun configure(
        ds: DataSource,
        connectRetries: Int = 10,
        connectRetriesIntervalS: Int = 15,
        locations: Array<String> = arrayOf("classpath:db/migration"),
    ): FluentConfiguration =
        Flyway.configure()
            .dataSource(ds)
            .connectRetries(connectRetries)
            .connectRetriesInterval(connectRetriesIntervalS)
            .locations(*locations)
            .validateOnMigrate(true)
            .callbacks(ExemptIdleInTransaction)
}

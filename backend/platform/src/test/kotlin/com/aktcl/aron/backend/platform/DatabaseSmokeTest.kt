package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the backend data path end to end: JDBC driver + HikariCP + Flyway (db module migrations) + JDBI against a
 * real PostgreSQL 16. Database source (docs/24 s2.5): ARON_TEST_PG_URL if set (CI service container, local
 * server), otherwise Testcontainers when Docker is available. With neither, the test FAILS on purpose.
 */
class DatabaseSmokeTest {
    @Test
    fun migratesAndQueries() {
        val url = System.getenv("ARON_TEST_PG_URL").orEmpty()
        val container = if (url.isBlank()) PostgreSQLContainer("postgres:16-alpine").also { it.start() } else null
        try {
            val cfg = HikariConfig().apply {
                if (container != null) {
                    jdbcUrl = container.jdbcUrl; username = container.username; password = container.password
                } else {
                    jdbcUrl = url
                }
                maximumPoolSize = 2
            }
            HikariDataSource(cfg).use { ds ->
                Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
                val jdbi = Jdbi.create(ds)
                val tz = jdbi.withHandle<String, Exception> { h ->
                    h.createQuery("select (timestamptz '2026-10-04 18:30:00+00' at time zone 'Asia/Dhaka')::date::text")
                        .mapTo(String::class.java).one()
                }
                // 18:30 UTC on 4 Oct is 00:30 on 5 Oct in Dhaka: the business date rule of docs/24 s3.8.
                assertEquals("2026-10-05", tz)
            }
        } finally {
            container?.stop()
        }
    }
}

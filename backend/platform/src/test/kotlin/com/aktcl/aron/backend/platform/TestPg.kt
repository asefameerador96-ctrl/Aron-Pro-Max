package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/** Test database (docs/24 s2.5): ARON_TEST_PG_URL, a real PostgreSQL 16. Each caller gets its own fresh schema. */
object TestPg {
    fun url(): String = System.getenv("ARON_TEST_PG_URL").orEmpty().ifBlank { error("ARON_TEST_PG_URL is not set (docs/24 s2.5)") }

    fun dataSource(maxPool: Int = 4): HikariDataSource = HikariDataSource(HikariConfig().apply { jdbcUrl = url(); maximumPoolSize = maxPool })
}

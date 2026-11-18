package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.notify.PushNotifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.NIL_GENERATION
import com.aktcl.aron.backend.sync.WaitingChildSweepJob
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.ConcurrentHashMap

private val jobsDbs = ConcurrentHashMap<String, Database>()

/** The worker's database login: every connection runs as `jobs_rw`, the privilege role of `app_jobs` (the worker container's login), so a missing grant fails the test as it would in production. */
fun jobsDatabase(url: String): Database = jobsDbs.getOrPut(url) { Database(HikariDataSource(HikariConfig().apply { jdbcUrl = url; maximumPoolSize = 4; connectionInitSql = "SET ROLE jobs_rw" })) }

/** The worker's waiting-item sweep built as Main builds it (D-LOY-API-28): the real job, the same ingest builder and handlers as the API, on the worker's own database role. */
fun waitingSweepJob(wiring: Wiring, clock: AronClock, jobsUrl: String): WaitingChildSweepJob {
    val db = jobsDatabase(jobsUrl)
    return WaitingChildSweepJob(Wiring.ingestService(db, wiring.config, SqlReachResolver(db, GeoRepository(db, clock), clock), clock, { NIL_GENERATION }, PushNotifier(db, wiring.config, null)))
}

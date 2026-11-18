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

private val apiDbs = ConcurrentHashMap<String, Database>()

/** The API's database login: every connection runs as `api_rw`, the privilege role of `app_api`, so a missing grant fails the test as it would in production (the sweep lives in the API process). */
fun apiRoleDatabase(url: String): Database = apiDbs.getOrPut(url) { Database(HikariDataSource(HikariConfig().apply { jdbcUrl = url; maximumPoolSize = 4; connectionInitSql = "SET ROLE api_rw" })) }

/** The waiting-item sweep built as the production wiring builds it (D-LOY-API-28): the real job, the same ingest builder and handlers as the API, on the API's database role. Not started: tests call `tick()` or `start()` themselves. */
fun waitingSweepJob(wiring: Wiring, clock: AronClock, url: String, env: (String) -> String? = { null }, firstDelayS: Long = 30, batch: Int? = null, config: com.aktcl.aron.backend.platform.ServerConfig = wiring.config): WaitingChildSweepJob {
    val db = apiRoleDatabase(url)
    return WaitingChildSweepJob(Wiring.ingestService(db, wiring.config, SqlReachResolver(db, GeoRepository(db, clock), clock), clock, { NIL_GENERATION }, PushNotifier(db, wiring.config, null)), config, env, firstDelayS, batch)
}

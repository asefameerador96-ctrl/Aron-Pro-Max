package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.FreshDb
import java.io.File

/** The db lane's dev seed (db/seed) in a fresh migrated database, plus a second territory, zone and TSO for scope tests. */
class SeededAdminDb : AutoCloseable {
    val fresh: FreshDb = FreshDb.create()
    val ids: Map<String, Long>

    init {
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            h.execute("INSERT INTO app.app_user (username, full_name, role, locale, home_zone_id, pilot, must_change_password) SELECT 'tso2001', 'Other TSO', 'TSO', 'bn', z.id, true, false FROM app.zone z WHERE z.code = 'Z-OTHER'")
            h.execute("INSERT INTO app.app_user (username, full_name, role, locale, home_zone_id, pilot, must_change_password) SELECT 'sr2001', 'Other SR', 'SR', 'bn', z.id, true, false FROM app.zone z WHERE z.code = 'Z-OTHER'")
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) SELECT u.id, 'territory', t.id, date '2026-01-01' FROM app.app_user u, app.territory t WHERE u.username = 'tso2001' AND t.code = 'T-OTHER'")
        }
        ids = fresh.db.jdbi.withHandle<Map<String, Long>, Exception> { h -> h.createQuery("SELECT username, id FROM app.app_user").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap() }
    }

    fun scalar(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }

    override fun close() = fresh.close()
}

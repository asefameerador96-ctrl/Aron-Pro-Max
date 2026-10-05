package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.FreshDb
import org.jdbi.v3.core.Handle

/**
 * Writes a [ScopeWorld] into a migrated database with the same ids. Rows the schema's exclusion constraints refuse
 * (overlapping scope rows, two primaries on one route-day) are dropped from the world too, so world and database
 * stay identical for the oracle.
 */
object WorldLoader {
    fun load(db: FreshDb, w: ScopeWorld) {
        db.db.jdbi.useHandle<Exception> { h ->
            for (wing in w.wings) ins(h, "INSERT INTO app.wing (id, code, name) OVERRIDING SYSTEM VALUE VALUES ($wing, 'W$wing', 'Wing $wing')")
            w.divisionWing.forEach { (d, wi) -> ins(h, "INSERT INTO app.division (id, code, name, wing_id) OVERRIDING SYSTEM VALUE VALUES ($d, 'D$d', 'Division $d', $wi)") }
            w.territoryDivision.forEach { (t, d) -> ins(h, "INSERT INTO app.territory (id, code, name, division_id) OVERRIDING SYSTEM VALUE VALUES ($t, 'T$t', 'Territory $t', $d)") }
            w.zoneTerritory.forEach { (z, t) ->
                ins(h, "INSERT INTO app.zone (id, code, name, territory_id) OVERRIDING SYSTEM VALUE VALUES ($z, 'Z$z', 'Zone $z', $t)")
                ins(h, "INSERT INTO app.cluster (id, zone_id, name) OVERRIDING SYSTEM VALUE VALUES ($z, $z, 'Cluster $z')")
            }
            w.routeZone.forEach { (r, z) -> ins(h, "INSERT INTO app.route (id, code, name, zone_id, kind, visit_kind, visit_days_mask) OVERRIDING SYSTEM VALUE VALUES ($r, 'R$r', 'Route $r', $z, 'sr', 'daily', 127)") }
            h.prepareBatch(
                "INSERT INTO app.outlet (id, code, name, owner_name, zone_id, route_id, cluster_id, channel, contact_number) OVERRIDING SYSTEM VALUE " +
                    "VALUES (:id, :code, :name, 'Owner', :z, :r, :z, 'GT', '01711000000')",
            ).also { b -> w.outlets.forEach { o -> b.bind("id", o.id).bind("code", "O${o.id}").bind("name", "Outlet ${o.id}").bind("z", o.zoneId).bind("r", o.routeId).add() } }.execute()
            val kept = w.users.map { u ->
                ins(h, "INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES (${u.id}, 'u${"%04d".format(u.id)}', 'User ${u.id}', '${u.role.wire}', false)")
                val scope = u.scope.filter { s ->
                    tryIns(h, "INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, valid_to) VALUES (:u, :t, :n, :f, :to)",
                        mapOf("u" to u.id, "t" to s.nodeType, "n" to s.nodeId, "f" to s.validFrom, "to" to s.validTo))
                }
                val asg = u.assignments.filter { a ->
                    tryIns(h, "INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to) VALUES (:r, :u, :k, :f, :to)",
                        mapOf("r" to a.routeId, "u" to u.id, "k" to a.kind, "f" to a.validFrom, "to" to a.validTo))
                }
                u.copy(scope = scope, assignments = asg)
            }
            w.users.clear(); w.users.addAll(kept)
        }
    }

    private fun ins(h: Handle, sql: String) { h.execute(sql) }

    /** Autocommit: a refused row (exclusion constraint) is simply not stored. */
    private fun tryIns(h: Handle, sql: String, args: Map<String, Any?>): Boolean = try {
        h.createUpdate(sql).bindMap(args).execute(); true
    } catch (e: org.jdbi.v3.core.statement.UnableToExecuteStatementException) {
        if ((e.cause as? java.sql.SQLException)?.sqlState == "23P01") false else throw e
    }
}

package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-API-011, GET /v1/routes (contract listRoutesInReach) on the seed: every role reads the routes in its reach with
 * today's assignees; the SR sees exactly its own routes (not the zone's other routes), the zone AMO the Mirpur routes,
 * the other territory's TSO only its own zone, national roles all; a selector outside the reach is 403 and no row of
 * another zone ever appears; the body equals the seeded rows.
 */
class RoutesInReachTest {
    @Test
    fun everyRoleSeesExactlyTheRoutesInItsReachWithTheirAssignees() {
        AdminEnv().use { env ->
            env.db.fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'OTH-SR-D', 'Other daily', 'Daily', id, 'sr', 'daily', 127, 1 FROM app.zone WHERE code = 'Z-OTHER'")
                h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'MIR-SR-X', 'Mirpur spare', 'Daily', id, 'sr', 'daily', 127, 9 FROM app.zone WHERE code = 'Z-MIR'")
                h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, u.id, 'primary', DATE '2026-01-01', 'test' FROM app.route r, app.app_user u WHERE r.code = 'OTH-SR-D' AND u.username = 'sr2001'")
            }
            fun codes(where: String) = env.db.fresh.db.jdbi.withHandle<Set<String>, Exception> { h -> h.createQuery("SELECT r.code FROM app.route r JOIN app.zone z ON z.id = r.zone_id WHERE $where").mapTo(String::class.java).set() }
            val mirpur = codes("z.code = 'Z-MIR'")
            val all = codes("true")
            val srOwn = env.db.fresh.db.jdbi.withHandle<Set<String>, Exception> { h ->
                h.createQuery("SELECT r.code FROM app.route r JOIN app.route_assignment a ON a.route_id = r.id JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001'").mapTo(String::class.java).set()
            }
            assertTrue(srOwn.isNotEmpty() && "MIR-SR-X" !in srOwn && "MIR-SR-X" in mirpur)
            val otherZone = env.sql("SELECT id FROM app.zone WHERE code = 'Z-OTHER'")!!.toLong()
            env.app {
                suspend fun page(user: String, role: Role, query: String = ""): Pair<HttpStatusCode, List<kotlinx.serialization.json.JsonObject>> {
                    val r = sendA1(HttpMethod.Get, "/routes?limit=500$query", env.token(user, role))
                    return r.status to if (r.status == HttpStatusCode.OK) r.objA1().itemsA1() else emptyList()
                }
                val (s1, sr) = page("sr1001", Role.SR)
                assertEquals(HttpStatusCode.OK, s1)
                assertEquals(srOwn, sr.map { it.strA1("code")!! }.toSet(), "an SR sees its own routes only")
                val daily = sr.first { it.strA1("code") == "MIR-SR-D" }
                assertEquals(listOf("sr1001"), daily["assignees"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content }, "assignees always present")
                assertEquals(env.sql("SELECT visit_days_mask FROM app.route WHERE code = 'MIR-SR-D'"), daily.strA1("visit_days_mask"))
                assertEquals(env.sql("SELECT name FROM app.route WHERE code = 'MIR-SR-D'"), daily.strA1("name"))

                assertEquals(mirpur, page("amo1001", Role.AMO).second.map { it.strA1("code")!! }.toSet())
                assertEquals(setOf("OTH-SR-D"), page("tso2001", Role.TSO).second.map { it.strA1("code")!! }.toSet())
                assertEquals(all, page("admin1001", Role.ADMIN).second.map { it.strA1("code")!! }.toSet())
                assertTrue(page("amo1001", Role.AMO).second.all { it["assignees"] != null })

                // A selector outside the reach is refused; an injected client scope list changes nothing.
                assertEquals(HttpStatusCode.Forbidden, page("amo1001", Role.AMO, "&zone_id=$otherZone").first)
                assertEquals(HttpStatusCode.Forbidden, page("sr1001", Role.SR, "&zone_id=$otherZone").first)
                assertEquals(mirpur, page("amo1001", Role.AMO, "&zone_ids=$otherZone&scope_ids=0").second.map { it.strA1("code")!! }.toSet())

                // updated_since pages incrementally; small pages never repeat or lose a row.
                val got = mutableListOf<String>()
                var cursor: String? = null
                do {
                    val r = sendA1(HttpMethod.Get, "/routes?limit=1&updated_since=2000-01-01T00:00:00Z" + (cursor?.let { "&cursor=$it" } ?: ""), env.token("admin1001", Role.ADMIN)).objA1()
                    got += r.itemsA1().map { it.strA1("code")!! }
                    cursor = r.strA1("next_cursor")
                } while (cursor != null)
                assertEquals(all.size, got.size); assertEquals(all, got.toSet())
            }
        }
    }
}

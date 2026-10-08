package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.contract.Role
import io.mockk.mockk
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GET /v1/media/{media_uuid}/read-url (contract getMediaReadUrl), scope case of the AUD-TP-3 registry: a read URL of 5
 * minutes for one stored photo, only inside the caller's reach. The SR sees only its own photos (not a cover SR's on its
 * route); a zone AMO sees the photos of SRs who held a route in its zone on the photo's date and of users whose home
 * zone it covers, never another zone's; national reach sees all; the reach comes from the resolver, never the request.
 * Unknown or voided is 404, out of reach 403 (before the blob status is looked at), a blob not stored yet 404.
 */
class MediaReadTest {
    private val now = Instant.parse("2027-01-03T04:00:00Z")
    private val day = LocalDate.parse("2027-01-03")
    private val issued = mutableListOf<Pair<String, Instant>>()
    private val reader = PhotoReadIssuer { path, until -> issued += path to until; "https://blob.invalid/media/$path?sig=r" }

    private class Fixture(val fresh: FreshDb, val ids: Map<String, Long>, val mirZone: Long, val otherZone: Long, val mirRoute: Long, val otherRoute: Long)

    private fun fixture(): Fixture {
        val fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        return fresh.db.jdbi.withHandle<Fixture, Exception> { h ->
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', territory_id FROM app.zone WHERE code = 'Z-MIR'")
            h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'OTH-SR-D', 'Other daily', 'Daily', id, 'sr', 'daily', 127, 1 FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.app_user (username, full_name, role, home_zone_id) SELECT 'sr2002', 'Other SR', 'SR', id FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.app_user (username, full_name, role, home_zone_id) SELECT 'sr3003', 'Cover SR', 'SR', id FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, u.id, 'primary', DATE '2026-01-01', 'test' FROM app.route r, app.app_user u WHERE r.code = 'OTH-SR-D' AND u.username = 'sr2002'")
            // sr3003 (home zone elsewhere) covered the Mirpur daily route on 2027-01-03 only.
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to, reason) SELECT r.id, u.id, 'cover', DATE '2027-01-03', DATE '2027-01-04', 'test' FROM app.route r, app.app_user u WHERE r.code = 'MIR-SR-D' AND u.username = 'sr3003'")
            fun id(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
            val ids = listOf("sr1001", "sr2002", "sr3003", "amo1001", "admin1001").associateWith { id("SELECT id FROM app.app_user WHERE username = '$it'") }
            Fixture(
                fresh, ids, id("SELECT id FROM app.zone WHERE code = 'Z-MIR'"), id("SELECT id FROM app.zone WHERE code = 'Z-OTHER'"),
                id("SELECT id FROM app.route WHERE code = 'MIR-SR-D'"), id("SELECT id FROM app.route WHERE code = 'OTH-SR-D'"),
            )
        }
    }

    private fun photo(f: Fixture, uuid: String, owner: Long, date: LocalDate = day, status: String = "stored", voided: Boolean = false) {
        val path = "photos/$date/00000000-0000-4000-8000-00000000000${owner % 10}/$uuid.jpg"
        f.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """
                INSERT INTO app.media (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, purpose, ref_type, ref_client_uuid,
                                       sha256, bytes, width, height, blob_path, taken_at, status, voided_at)
                VALUES (:u::uuid, :u::uuid, :d, :owner, :t, 1, 'outlet_capture', 'outlet', :u::uuid, decode(repeat('ab', 32), 'hex'), 1000, 640, 480, :p, :t, :s,
                        CASE WHEN :voided THEN now() END)
                """.trimIndent(),
            ).bind("u", uuid).bind("d", date).bind("owner", owner).bind("t", now).bind("p", path).bind("s", status).bind("voided", voided).execute()
        }
    }

    private fun principal(id: Long, role: Role) = AronPrincipal(id, "u$id", role, 1, Audience.API, null, null, "web", emptyList(), false, listOf("pwd"), "j", now)

    @Test
    fun aPhotoIsReadableOnlyInsideTheCallersReach() {
        val f = fixture()
        f.fresh.use {
            val sr = f.ids.getValue("sr1001"); val other = f.ids.getValue("sr2002"); val cover = f.ids.getValue("sr3003")
            val amo = f.ids.getValue("amo1001"); val admin = f.ids.getValue("admin1001")
            val reaches = mapOf(
                sr to Reach(sr, Role.SR, day, false, setOf(f.mirZone), setOf(f.mirRoute), true, emptyList()),
                amo to Reach(amo, Role.AMO, day, false, setOf(f.mirZone), emptySet(), false, emptyList()),
                admin to Reach(admin, Role.ADMIN, day, true, emptySet(), emptySet(), false, emptyList()),
                // sr3003's id stands in for a zone supervisor here (the resolver alone decides the reach).
                cover to Reach(cover, Role.TSO, day, false, setOf(f.mirZone), emptySet(), false, emptyList()),
            )
            val asked = mutableListOf<LocalDate>()
            val resolver = ReachResolver { u, _, _, d -> asked += d; reaches.getValue(u) }
            val m = MediaRead(MediaDeps(f.fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, reach = resolver, reader = reader))

            val own = "11111111-1111-4111-8111-111111111111"; photo(f, own, sr)
            val otherZone = "22222222-2222-4222-8222-222222222222"; photo(f, otherZone, other)
            val coverOnMir = "33333333-3333-4333-8333-333333333333"; photo(f, coverOnMir, cover)
            val coverLater = "44444444-4444-4444-8444-444444444444"; photo(f, coverLater, cover, date = day.plusDays(1))

            val r = m.readUrl(principal(sr, Role.SR), own)
            assertEquals("2027-01-03T04:05:00.000Z", r.expires_at, "valid for 5 minutes")
            assertEquals("photos/2027-01-03/00000000-0000-4000-8000-00000000000${sr % 10}/$own.jpg" to now.plusSeconds(300), issued.single())
            assertTrue(r.url.startsWith("https://"))

            fun denied(p: AronPrincipal, uuid: String) = assertEquals("ERR_OUT_OF_SCOPE", assertFailsWith<ApiProblem> { m.readUrl(p, uuid) }.code.name, "$uuid for ${p.role}")
            denied(principal(sr, Role.SR), otherZone)
            denied(principal(sr, Role.SR), coverOnMir) // an SR sees its own photos only, not a cover's on its route
            m.readUrl(principal(amo, Role.AMO), own)
            m.readUrl(principal(amo, Role.AMO), coverOnMir) // the cover held a Mirpur route on the photo's date
            denied(principal(amo, Role.AMO), coverLater) // not on the next day, and the cover's home zone is elsewhere
            denied(principal(amo, Role.AMO), otherZone)
            // An AMO's own photo (no route that day) is in reach through its home zone; an SR who moved into the zone
            // later does not bring its old-zone photos along (it held another zone's route on the photo's date).
            val amoOwn = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"; photo(f, amoOwn, amo)
            m.readUrl(principal(cover, Role.TSO), amoOwn)
            f.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.app_user SET home_zone_id = ? WHERE id = ?", f.mirZone, other) }
            denied(principal(amo, Role.AMO), otherZone)
            for (u in listOf(own, otherZone, coverOnMir, coverLater)) m.readUrl(principal(admin, Role.ADMIN), u)
            assertTrue(asked.all { it == day }, "reach on today's Dhaka date")
            assertEquals(1 + 2 + 1 + 4, issued.size, "no URL minted for a refused read")
        }
    }

    @Test
    fun unknownVoidedPendingAndMalformedAreRefused() {
        val f = fixture()
        f.fresh.use {
            val sr = f.ids.getValue("sr1001"); val other = f.ids.getValue("sr2002"); val amo = f.ids.getValue("amo1001")
            val resolver = ReachResolver { u, _, _, _ -> Reach(u, Role.AMO, day, false, setOf(f.mirZone), emptySet(), false, emptyList()) }
            val m = MediaRead(MediaDeps(f.fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, reach = resolver, reader = reader))
            val pending = "55555555-5555-4555-8555-555555555555"; photo(f, pending, sr, status = "pending_blob")
            val voided = "66666666-6666-4666-8666-666666666666"; photo(f, voided, sr, voided = true)
            val pendingOut = "77777777-7777-4777-8777-777777777777"; photo(f, pendingOut, other, status = "pending_blob")
            fun code(uuid: String?) = assertFailsWith<ApiProblem> { m.readUrl(principal(amo, Role.AMO), uuid) }.code.name
            assertEquals("ERR_NOT_FOUND", code("88888888-8888-4888-8888-888888888888"))
            assertEquals("ERR_NOT_FOUND", code(voided))
            assertEquals("ERR_NOT_FOUND", code(pending))
            assertEquals("ERR_OUT_OF_SCOPE", code(pendingOut), "scope is decided before the blob status")
            for (bad in listOf(null, "NOT-A-UUID", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "55555555-5555-1555-8555-555555555555")) assertEquals("ERR_VALIDATION", code(bad))

            val stored = "99999999-9999-4999-8999-999999999999"; photo(f, stored, sr)
            val unwired = MediaRead(MediaDeps(f.fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }))
            assertEquals("ERR_SERVICE_UNAVAILABLE", assertFailsWith<ApiProblem> { unwired.readUrl(principal(amo, Role.AMO), stored) }.code.name)
            val down = MediaRead(MediaDeps(f.fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, reach = resolver, reader = PhotoReadIssuer { _, _ -> error("storage down") }))
            assertEquals("ERR_SERVICE_UNAVAILABLE", assertFailsWith<ApiProblem> { down.readUrl(principal(amo, Role.AMO), stored) }.code.name)
            assertEquals(0, issued.size)
        }
    }
}

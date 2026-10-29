package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Q5: role grants come from app.role_grant_map per request through the user store's short cache, so a grant and a revoke hold within it. */
class RoleGrantsTest {
    private val fresh = FreshDb.create()
    private val now = AtomicReference(Instant.parse("2026-10-18T06:00:00Z"))
    private val store = JdbiUserStore(fresh.db, AronClock { now.get() }, svCacheMs = 10_000)

    @AfterTest
    fun close() = fresh.close()

    private fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }
    private fun later(seconds: Long) = now.set(now.get().plusSeconds(seconds))

    @Test
    fun aGrantAndARevokeTakeEffectWithinTheCacheWindow() {
        // V0085 seeds the cfg.edit.* grants of ADMIN and SUPERADMIN (V0086 adds pii.reveal); this test judges finance.backdate on top of them.
        val seeded = store.rolePermissions("ADMIN")
        assertTrue(seeded.none { it == "finance.backdate" } && seeded.all { it.startsWith("cfg.edit.") || it == "pii.reveal" || it == "support.ticket.manage" }, "$seeded")   // V0086: pii.reveal; V0134: the support desk (D-TSO-MAN-22)
        sql("INSERT INTO app.role_grant_map (role, permission) VALUES ('ADMIN', 'finance.backdate')")
        assertEquals(seeded, store.rolePermissions("ADMIN"), "cached for up to 10 s")
        later(11)
        assertEquals(seeded + "finance.backdate", store.rolePermissions("ADMIN"))
        assertTrue("finance.backdate" !in store.rolePermissions("SUPERADMIN"), "per role")
        sql("DELETE FROM app.role_grant_map WHERE role = 'ADMIN' AND permission = 'finance.backdate'")
        later(11)
        assertEquals(seeded, store.rolePermissions("ADMIN"), "revoked")
    }
}

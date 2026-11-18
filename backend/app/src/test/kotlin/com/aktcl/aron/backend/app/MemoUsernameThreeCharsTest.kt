package com.aktcl.aron.backend.app

import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 1.20.0 (D-SV2-16; `docs/requests/android-gates.md` section 5): a login of three characters makes memo numbers like `srx-270103-101`. The phone has accepted
 * `^[a-z][a-z0-9]{2,31}$` since 2026-11-13; the server's memo number rule (MemoNumbers) and the database CHECKs (V0204) said four. A whole sale family of such a user is stored,
 * and the numbers of another user are still refused.
 */
class MemoUsernameThreeCharsTest : NightSyncHarness() {
    private val phone get() = seededPhone

    @Test
    fun aThreeCharacterLoginSellsAndItsMemoNumberIsStored() = testApplication {
        application { aronApi(wiring) }
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.app_user SET username = 'srx' WHERE id = $srId") }
        val t = client.token("srx", phone)
        val no = "srx-270103-${memoSeq.incrementAndGet()}"
        val acks = client.send(t, phone, sale(no))
        assertTrue(acks.all { it.startsWith("accepted") }, acks.toString())
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"))
        // Another user's prefix is still refused (the rule names the uploader).
        val other = client.send(t, phone, sale("sr1001-270103-${memoSeq.incrementAndGet()}"))
        assertEquals("rejected:memo_no_invalid", other[1])
    }
}

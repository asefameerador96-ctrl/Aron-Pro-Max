package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** N-042 (qa-release-publish-route): drafts by CI, maker-checker publish, staged rollout, policy, and the update check that reads them. */
class ReleaseAdminTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var resolver: ConfigResolver
    private lateinit var rel: ReleaseAdmin
    private val cert = "ab".repeat(32)

    @BeforeEach fun setUp() {
        env = SeededConfigDb()
        resolver = ConfigResolver(env.fresh.db, clock)
        rel = ReleaseAdmin(env.fresh.db, resolver, clock, expectedCertSha256 = cert)
    }
    @AfterEach fun tearDown() = env.close()

    private val admin get() = env.principal("admin1001", Role.ADMIN)
    private val sa get() = env.principal("superadmin1001", Role.SUPERADMIN)
    private val sa2 get() = env.principal("superadmin1002", Role.SUPERADMIN)
    private fun w(code: Int, sha: String = "%064x".format(code), abi: String = "universal", certHex: String = cert, flavour: String = "sr") =
        AppReleaseWriteIn(flavour, "1.0.$code", code, abi, sha, 9_000_000, "https://cdn.example.test/sr-$code.apk", certHex, "Fixes", "সংশোধন")
    private fun code(block: () -> Unit) = assertFailsWith<ApiProblem> { block() }.code

    @Test
    fun ciRegistersADraftIdempotentlyAndBadApksAreRefused() {
        val d = rel.create(admin, w(10), null)
        assertEquals("draft", d.status); assertEquals(0, d.rollout_pct); assertNull(d.published_at); assertEquals(1, d.version)
        assertEquals(d, rel.create(admin, w(10), null))                                                   // CI retry: same draft, no second row
        assertEquals(ProblemCode.ERR_CONFLICT, code { rel.create(admin, w(10, sha = "%064x".format(99)), null) })   // same code+abi, other APK
        assertEquals(ProblemCode.ERR_CONFLICT, code { rel.create(admin, w(11, sha = d.sha256), null) })              // same APK hash, other code
        assertEquals(ProblemCode.ERR_VALIDATION, code { rel.create(admin, w(12, certHex = "cd".repeat(32)), null) }) // not the release key
        assertEquals(ProblemCode.ERR_VALIDATION, code { rel.create(admin, w(12).copy(download_url = "http://cdn.example.test/a.apk"), null) })
        assertEquals(ProblemCode.ERR_VALIDATION, code { rel.create(admin, w(12).copy(size_bytes = 31 * 1024 * 1024), null) })   // cfg.release.apk_max_mb 30
        assertEquals(ProblemCode.ERR_VALIDATION, code { rel.create(admin, w(12).copy(sha256 = "xyz"), null) })
        assertEquals(ProblemCode.ERR_VALIDATION, code { rel.create(admin, w(12).copy(version_name = "1.0"), null) })
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { rel.create(env.principal("support1001", Role.SUPPORT), w(12), null) })
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { rel.create(env.principal("tso1001", Role.TSO), w(12), null) })
        assertEquals("1", env.one("SELECT count(*) FROM app.app_release"))
        assertEquals("1", env.one("SELECT count(*) FROM app.audit_log WHERE entity = 'app_release' AND action = 'create'"))
    }

    @Test
    fun publishIsASecondPersonsAndTheUpdateCheckFollowsTheLifecycle() {
        val d = rel.create(sa, w(20), null)
        val pub = ConfigPublic(env.fresh.db, resolver, clock)
        assertFalse(pub.updateCheck("sr", 10, "arm64-v8a", null).update_available)                       // a draft is never offered
        // The author may not publish; ADMIN may not publish at all; a stale If-Match is 412.
        assertEquals(ProblemCode.ERR_SEPARATION_OF_DUTIES, code { rel.patch(sa, d.release_id, d.version, AppReleasePatchIn(status = "published", rollout_pct = 100), emptySet(), null) })
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { rel.patch(admin, d.release_id, d.version, AppReleasePatchIn(status = "published"), emptySet(), null) })
        assertEquals(ProblemCode.ERR_PRECONDITION_FAILED, code { rel.patch(sa2, d.release_id, d.version + 1, AppReleasePatchIn(status = "published"), emptySet(), null) })
        // ADMIN may stage a draft's rollout and edit its notes.
        val staged = rel.patch(admin, d.release_id, d.version, AppReleasePatchIn(rollout_pct = 100, notes_en = null), setOf("notes_en"), null)
        assertEquals(100, staged.rollout_pct); assertNull(staged.notes_en); assertEquals("সংশোধন", staged.notes_bn); assertEquals(d.version + 1, staged.version)
        val p = rel.patch(sa2, d.release_id, staged.version, AppReleasePatchIn(status = "published", change_reason = "Pilot release for the SR app"), emptySet(), null)
        assertEquals("published", p.status); assertNotNull(p.published_at)
        assertEquals(20, pub.updateCheck("sr", 10, "arm64-v8a", null).latest?.version_code)
        // Rollout of a published release and a block are the super admin's; a blocked release is not offered.
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { rel.patch(admin, d.release_id, p.version, AppReleasePatchIn(rollout_pct = 10), emptySet(), null) })
        val b = rel.patch(sa, d.release_id, p.version, AppReleasePatchIn(status = "blocked", change_reason = "Crash on the Symphony phones"), emptySet(), null)
        assertFalse(pub.updateCheck("sr", 10, "arm64-v8a", null).update_available)
        // Same body again changes nothing (no version bump, no audit row); retired is final.
        assertEquals(b, rel.patch(sa, d.release_id, b.version, AppReleasePatchIn(status = "blocked"), emptySet(), null))
        val r = rel.patch(sa, d.release_id, b.version, AppReleasePatchIn(status = "retired"), emptySet(), null)
        assertEquals(ProblemCode.ERR_CONFLICT, code { rel.patch(sa, d.release_id, r.version, AppReleasePatchIn(status = "published"), emptySet(), null) })
        assertEquals(ProblemCode.ERR_CONFLICT, code { rel.patch(sa, d.release_id, r.version, AppReleasePatchIn(notes_bn = "x"), setOf("notes_bn"), null) })
        // A draft cannot be blocked (it was never out); an unknown release is 404.
        val d2 = rel.create(admin, w(21), null)
        assertEquals(ProblemCode.ERR_CONFLICT, code { rel.patch(sa, d2.release_id, d2.version, AppReleasePatchIn(status = "blocked"), emptySet(), null) })
        assertEquals(ProblemCode.ERR_NOT_FOUND, code { rel.patch(sa, 999_999, 1, AppReleasePatchIn(status = "retired"), emptySet(), null) })
        assertEquals(ProblemCode.ERR_VALIDATION, code { rel.patch(sa, d2.release_id, d2.version, AppReleasePatchIn(), emptySet(), null) })
        assertEquals("4", env.one("SELECT count(*) FROM app.audit_log WHERE entity = 'app_release' AND action <> 'create'"))
    }

    @Test
    fun listPagesNewestFirstAndPolicyReadsTheConfig() {
        (30..34).forEach { rel.create(admin, w(it), null) }
        rel.create(admin, w(35, flavour = "amo"), null)
        val first = rel.list(env.principal("support1001", Role.SUPPORT), "sr", 2, null)
        assertEquals(listOf(34, 33), first.items.map { it.version_code }); assertNotNull(first.next_cursor)
        assertEquals(listOf(32, 31), rel.list(admin, "sr", 2, first.next_cursor).items.map { it.version_code })
        assertEquals(6, rel.list(admin, null, 100, null).items.size)
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { rel.list(env.principal("tso1001", Role.TSO), null, 10, null) })
        assertEquals(ProblemCode.ERR_VALIDATION, code { rel.list(admin, null, 10, "bad*cursor") })
        val pol = rel.policy(admin).items.associateBy { it.flavour }
        assertEquals(setOf("amo", "sr", "tso"), pol.keys); assertNull(pol.getValue("sr").latest_version_code); assertEquals(emptyList(), pol.getValue("sr").blocked_version_codes)
        assertTrue(pol.values.all { it.min_version_code >= 1 && it.devices_below_min >= 0 })
    }

    @Test
    fun blockedVersionCodesArePerFlavour() {
        val v = buildJsonObject { put("sr", buildJsonArray { add(JsonPrimitive(7)); add(JsonPrimitive(9)) }); put("amo", buildJsonArray { }) }
        assertEquals(listOf(7, 9), ReleaseAdmin.blockedCodes(v, "sr"))
        assertEquals(emptyList(), ReleaseAdmin.blockedCodes(v, "amo")); assertEquals(emptyList(), ReleaseAdmin.blockedCodes(v, "tso"))
        assertEquals(listOf(3), ReleaseAdmin.blockedCodes(buildJsonArray { add(JsonPrimitive(3)) }, "tso"))   // older bare-list shape
        assertEquals(emptyList(), ReleaseAdmin.blockedCodes(null, "sr"))
    }
    /** CHECKER (T1): maker-checker must hold on every path to published, including blocked -> published by the author. */
    @Test
    fun checkerTheAuthorCannotRepublishABlockedRelease() {
        val d = rel.create(sa, w(40), null)
        val p = rel.patch(sa2, d.release_id, d.version, AppReleasePatchIn(status = "published", rollout_pct = 100), emptySet(), null)
        val b = rel.patch(sa, d.release_id, p.version, AppReleasePatchIn(status = "blocked", change_reason = "Crash on the Symphony phones"), emptySet(), null)
        assertEquals(ProblemCode.ERR_SEPARATION_OF_DUTIES, code { rel.patch(sa, d.release_id, b.version, AppReleasePatchIn(status = "published"), emptySet(), null) })
    }

    /** CHECKER (T1): a phone running a release blocked through PATCH is told it is blocked. */
    @Test
    fun checkerAPhoneOnABlockedReleaseIsBlocked() {
        val d = rel.create(sa, w(41), null)
        val p = rel.patch(sa2, d.release_id, d.version, AppReleasePatchIn(status = "published", rollout_pct = 100), emptySet(), null)
        rel.patch(sa2, d.release_id, p.version, AppReleasePatchIn(status = "blocked"), emptySet(), null)
        assertTrue(ConfigPublic(env.fresh.db, resolver, clock).updateCheck("sr", 41, "universal", null).blocked)
    }
}

package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-042 (public config), update check, F-API-064 (permissions are a C3 request, never an immediate grant). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigPublicPermissionsTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private lateinit var pub: ConfigPublic
    private lateinit var perms: ConfigPermissions

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        val r = ConfigResolver(env.fresh.db, clock)
        svc = ConfigService(env.fresh.db, r, clock)
        pub = ConfigPublic(env.fresh.db, r, clock)
        perms = ConfigPermissions(env.fresh.db, svc, clock)
    }
    @AfterAll fun tearDown() = env.close()

    private fun release(code: Int, status: String, pct: Int, abi: String = "universal", flavour: String = "sr") = env.fresh.db.jdbi.useHandle<Exception> { h ->
        val a = env.ids.getValue("admin1001"); val b = env.ids.getValue("superadmin1001")
        h.createUpdate(
            "INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, rollout_pct, created_by, published_at, published_by) " +
                "VALUES (:f, :n, :c, :abi, sha256(CAST(:n2 AS bytea)), 1000, 'https://example.test/a.apk', sha256(CAST('cert' AS bytea)), :s, :p, :a, CASE WHEN :s IN ('published','blocked') THEN now() END, CASE WHEN :s IN ('published','blocked') THEN CAST(:b AS bigint) END)",
        ).bind("f", flavour).bind("n", "1.0.$code").bind("n2", "rel$code$abi$flavour").bind("c", code).bind("abi", abi).bind("s", status).bind("p", pct).bind("a", a).bind("b", b).execute()
    }

    @Test
    fun publicConfigHasMinVersionsBannerAndNoUserData() {
        val c = pub.publicConfig()
        assertTrue(c.min_version_codes.sr >= 1 && c.min_version_codes.amo >= 1 && c.min_version_codes.tso >= 1)
        assertNull(c.maintenance_banner)
        svc.create(env.principal("admin1001", Role.ADMIN), ConfigChangeRequestIn("Planned maintenance tonight", false, listOf(ConfigChangeItemIn("cfg.ops.maintenance_banner", "global", 0, kotlinx.serialization.json.buildJsonObject { put("bn", JsonPrimitive("রক্ষণাবেক্ষণ")); put("en", JsonPrimitive("Maintenance")); put("severity", JsonPrimitive("warning")) }))), null, null)
        clock.advance(2)
        val b = ConfigPublic(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock).publicConfig().maintenance_banner
        assertEquals("Maintenance", b?.en); assertEquals("warning", b?.severity)
        val json = kotlinx.serialization.json.Json.encodeToString(PublicConfigDto.serializer(), c)
        assertFalse(json.contains("user") || json.contains("password") || json.contains("token"))
    }

    @Test
    fun updateCheckPicksTheNewestPublishedReleaseWithStableRollout() {
        release(5, "published", 100); release(7, "published", 100, abi = "arm64-v8a"); release(9, "draft", 100); release(11, "published", 0)
        val u = pub.updateCheck("sr", 4, "arm64-v8a", "11111111-1111-4111-8111-111111111111")
        assertTrue(u.update_available); assertEquals(7, u.latest!!.version_code); assertEquals(64, u.latest!!.sha256.length)
        assertFalse(pub.updateCheck("sr", 7, "arm64-v8a", null).update_available)
        assertEquals(5, pub.updateCheck("sr", 4, "armeabi-v7a", null).latest!!.version_code)
        release(13, "published", 50)
        val answers = (1..40).map { i -> pub.updateCheck("sr", 4, "arm64-v8a", "00000000-0000-4000-8000-%012d".format(i)).latest?.version_code }
        assertTrue(answers.contains(13) && answers.contains(7))
        assertEquals(answers, (1..40).map { i -> pub.updateCheck("sr", 4, "arm64-v8a", "00000000-0000-4000-8000-%012d".format(i)).latest?.version_code })
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { pub.updateCheck("xx", 4, "universal", null) }.code)
    }

    @Test
    fun permissionGrantIsAPendingC3RequestNotAnImmediateGrant() {
        val before = perms.matrix()
        assertTrue(before.roles.any { it.role == "TSO" && it.menus.isNotEmpty() }); assertTrue(before.admin_roster.any { it.username == "admin1001" })
        val sa = env.principal("superadmin1001", Role.SUPERADMIN)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { perms.write(env.principal("admin1001", Role.ADMIN), "TSO", RolePermissionsWrite(listOf(MenuPermissionDto("reports.reports", listOf("view"))), "Trim the TSO menu now"), null) }.code)
        val c = perms.write(sa, "TSO", RolePermissionsWrite(listOf(MenuPermissionDto("reports.reports", listOf("view", "export"))), "Trim the TSO menu to reports"), null)
        assertEquals("pending_approval", c.status); assertEquals(3, c.risk_class)
        assertEquals(before.roles.first { it.role == "TSO" }.menus.size, perms.matrix().roles.first { it.role == "TSO" }.menus.size)
        clock.setDhaka(12, 0); clock.advance(2)
        svc.decide(env.principal("superadmin1002", Role.SUPERADMIN), c.change_id, ConfigDecisionIn("approve"), null)
        clock.advance(2)
        val tso = ConfigPermissions(env.fresh.db, svc, clock).matrix().roles.first { it.role == "TSO" }
        assertEquals(listOf("reports.reports"), tso.menus.map { it.menu_id }); assertEquals(listOf("view", "export"), tso.menus.single().actions)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { perms.write(sa, "SUPERADMIN", RolePermissionsWrite(listOf(MenuPermissionDto("reports.reports", listOf("view"))), "Remove my own permission page"), null) }.code)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { perms.write(sa, "TSO", RolePermissionsWrite(listOf(MenuPermissionDto("reports.reports", listOf("fly"))), "Unknown action in this write"), null) }.code)
    }
}

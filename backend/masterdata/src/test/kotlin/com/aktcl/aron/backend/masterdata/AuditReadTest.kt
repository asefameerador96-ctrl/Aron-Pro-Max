package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuditLog
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** listAudit (docs/24 s8.5 "Audit log"): four national readers, default-deny masking for ANALYST and SUPPORT, never-show values for everyone, keyset paging. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditReadTest {
    private lateinit var env: SeededAdminDb
    private var admin = 0L

    @BeforeAll fun setUp() {
        env = SeededAdminDb()
        admin = env.ids.getValue("admin1001")
        val actor = AronPrincipal(admin, "admin1001", Role.ADMIN, 1, "aron-api", null, null, "web", emptyList(), false, listOf("pwd", "mfa"), "j", Instant.now().plusSeconds(600))
        env.fresh.db.jdbi.useTransaction<Exception> { h ->
            // An outlet update: a plain change, an unchanged member, personal data and a nested image.
            AuditLog.write(
                h, actor, "outlet", "t-audit-1", "update",
                buildJsonObject { put("status", "active"); put("name", "Shop A"); put("owner_name", "Rahim"); put("code", "O-1") },
                buildJsonObject {
                    put("status", "closed"); put("name", "Shop A"); put("owner_name", "Karim"); put("code", "O-1"); put("brand_new_key", "x")
                    put("scope", buildJsonObject { put("national", false); put("phone", "01711111111") })
                },
                "Owner sold the shop to his cousin", null,
            )
            // An enrolment token: its prefix is a fragment of the secret, and a secret-looking member a future writer might add.
            AuditLog.write(h, actor, "enrolment_token", "t-audit-2", "create", null, buildJsonObject { put("flavour", "sr"); put("prefix", "AbC123"); put("max_uses", 5); put("api_secret_v2", "zzz") }, null, null)
            // Raw config values: a secret-looking key loses its value, a plain one keeps it.
            AuditLog.write(
                h, actor, "cfg_version", "t-audit-3", "commit", null,
                JsonObject(mapOf("items" to buildJsonArray {
                    add(buildJsonObject { put("key", "cfg.int.signing_secret"); put("value", "s3cr3t"); put("old_value", "old") })
                    add(buildJsonObject { put("key", "cfg.geo.radius_m"); put("value", 60); put("old_value", 50) })
                })),
                null, null,
            )
            // An entity nobody listed: ANALYST and SUPPORT see member names only.
            AuditLog.write(h, actor, "zz_unknown", "t-audit-4", "make", null, buildJsonObject { put("count", 3); put("Owner_Phone", "01712345678"); put("PREFIX", "AbC123"); put("home_lat", 23.7); put("phone_memo_uuid", "m-1") }, null, null)
        }
    }
    @AfterAll fun tearDown() = env.close()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(5000)))
        application {
            installAronPlatform(PlatformContext(com.aktcl.aron.backend.platform.AronClock.SYSTEM, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            routing { route("/v1") { auditReadRoutes(AuditReadDeps(env.fresh.db, guard)) } }
        }
        block()
    }

    private fun tok(role: Role, pii: Boolean = false) = TestTokens.web(admin, role, pii = pii)
    private suspend fun ApplicationTestBuilder.get(q: String, role: Role, pii: Boolean = false) = client.get("/v1/admin/audit?$q") { bearerAuth(tok(role, pii)) }
    private suspend fun ApplicationTestBuilder.one(entityId: String, role: Role, pii: Boolean = false): JsonObject {
        val r = get("entity_id=$entityId", role, pii)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray.single().jsonObject
    }
    private fun JsonObject.side(k: String) = this[k]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun onlyAnalystSupportAdminAndSuperadminRead() = app {
        for (r in listOf(Role.SR, Role.AMO, Role.TSO, Role.DMO, Role.WM, Role.TOP)) assertEquals(HttpStatusCode.Forbidden, get("limit=1", r).status, "$r")
        for (r in listOf(Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)) assertEquals(HttpStatusCode.OK, get("limit=1", r).status, "$r")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/audit").status)
    }

    @Test
    fun adminSeesEveryValueButSecretsAndPersonalData() = app {
        for (role in listOf(Role.ADMIN, Role.SUPERADMIN)) {
            val e = one("t-audit-1", role)
            assertEquals("closed", e.side("after").s("status")); assertEquals("Shop A", e.side("after").s("name"))
            assertEquals("x", e.side("after").s("brand_new_key"))
            assertEquals(AuditMask.MASK, e.side("after").s("owner_name")); assertEquals(AuditMask.MASK, e.side("before").s("owner_name"))
            val scope = Json.parseToJsonElement(e.side("after").s("scope")).jsonObject   // nested image as JSON text (contract: scalars)
            assertEquals(false, scope["national"]!!.jsonPrimitive.content.toBoolean()); assertEquals(AuditMask.MASK, scope.s("phone"))
            assertEquals("Owner sold the shop to his cousin", e.s("reason"))
            assertTrue(e.s("row_hash").matches(Regex("^[0-9a-f]{64}$")))
            val t = one("t-audit-2", role).side("after")
            assertEquals("sr", t.s("flavour")); assertEquals(AuditMask.MASK, t.s("prefix")); assertEquals(AuditMask.MASK, t.s("api_secret_v2"))
            val items = Json.parseToJsonElement(one("t-audit-3", role).side("after").s("items")).jsonArray.map { it.jsonObject }
            assertEquals(AuditMask.MASK, items[0].s("value")); assertEquals(AuditMask.MASK, items[0].s("old_value")); assertEquals("cfg.int.signing_secret", items[0].s("key"))
            assertEquals("60", items[1].s("value"))
            // Case and suffix variants of personal and secret names are caught too.
            val u = one("t-audit-4", role).side("after")
            assertEquals("3", u.s("count")); for (k in listOf("Owner_Phone", "PREFIX", "home_lat")) assertEquals(AuditMask.MASK, u.s(k), k)
            assertEquals("m-1", u.s("phone_memo_uuid"), "an id named after a phone is not personal data")
        }
        // The PII grant of docs/21 s4.5 (no login sets it yet) shows personal data, never secrets.
        val withPii = one("t-audit-1", Role.ADMIN, pii = true)
        assertEquals("Karim", withPii.side("after").s("owner_name"))
        assertEquals(AuditMask.MASK, one("t-audit-2", Role.SUPERADMIN, pii = true).side("after").s("prefix"))
    }

    @Test
    fun analystAndSupportSeeOnlyChangedMembersAndOnlyAllowlistedValues() = app {
        for (role in listOf(Role.ANALYST, Role.SUPPORT)) {
            val e = one("t-audit-1", role, pii = true)
            val before = e.side("before"); val after = e.side("after")
            assertEquals(setOf("status", "owner_name"), before.keys, "$role: unchanged members are not listed")
            assertEquals(setOf("status", "owner_name", "brand_new_key", "scope"), after.keys)
            assertEquals("active", before.s("status")); assertEquals("closed", after.s("status"))
            assertEquals(AuditMask.MASK, after.s("owner_name"), "personal data even with the PII grant")
            assertEquals(AuditMask.MASK, after.s("brand_new_key"), "a member nobody listed is masked")
            assertEquals(AuditMask.MASK, after.s("scope"), "nested images are masked")
            assertEquals(AuditMask.MASK, e.s("reason"), "free-text reason")
            val t = one("t-audit-2", role).side("after")
            assertEquals("sr", t.s("flavour")); assertEquals("5", t.s("max_uses")); assertEquals(AuditMask.MASK, t.s("prefix"))
            assertEquals(AuditMask.MASK, one("t-audit-3", role).side("after").s("items"))
            val u = one("t-audit-4", role)
            assertEquals(AuditMask.MASK, u.side("after").s("count")); assertEquals(JsonNull, u["before"] ?: JsonNull)
        }
    }

    @Test
    fun pagesNewestFirstByIdWithoutGapsOrOverlap() = app {
        val all = Json.parseToJsonElement(get("limit=500", Role.ADMIN).bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject.s("id").toLong() }
        assertTrue(all.size >= 4); assertEquals(all.sortedDescending(), all)
        val seen = mutableListOf<Long>(); var cursor: String? = null
        do {
            val page = Json.parseToJsonElement(get("limit=2" + (cursor?.let { "&cursor=$it" } ?: ""), Role.ANALYST).bodyAsText()).jsonObject
            val ids = page["items"]!!.jsonArray.map { it.jsonObject.s("id").toLong() }
            assertTrue(ids.size <= 2); seen += ids
            cursor = (page["next_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        } while (cursor != null)
        assertEquals(all, seen)
        val last = Json.parseToJsonElement(get("limit=${all.size}", Role.ADMIN).bodyAsText()).jsonObject
        assertEquals(JsonNull, last["next_cursor"], "an exact last page has no cursor")
    }

    @Test
    fun filtersNarrowTheRows() = app {
        fun ids(body: String) = Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.map { it.jsonObject.s("entity_id") }
        assertEquals(listOf("t-audit-2"), ids(get("entity=enrolment_token&action=create", Role.ADMIN).bodyAsText()))
        assertEquals(listOf("t-audit-1"), ids(get("entity=outlet&entity_id=t-audit-1", Role.ADMIN).bodyAsText()))
        assertTrue(ids(get("actor_user_id=$admin", Role.ADMIN).bodyAsText()).containsAll(listOf("t-audit-1", "t-audit-2", "t-audit-3", "t-audit-4")))
        assertEquals(emptyList(), ids(get("actor_user_id=${admin + 100000}", Role.ADMIN).bodyAsText()))
        val today = LocalDate.ofInstant(Instant.now(), ZoneOffset.ofHours(6))
        assertTrue("t-audit-4" in ids(get("from=$today&to=$today", Role.ADMIN).bodyAsText()))
        assertEquals(emptyList(), ids(get("from=${today.plusDays(1)}&entity=zz_unknown", Role.ADMIN).bodyAsText()))
        assertEquals(emptyList(), ids(get("to=${today.minusDays(1)}&entity=zz_unknown", Role.ADMIN).bodyAsText()))
    }

    @Test
    fun badQueriesAre400NotA500() = app {
        for (q in listOf(
            "limit=0", "limit=501", "limit=x", "cursor=abc", "cursor=0", "cursor=-5", "cursor=007", "entity=Outlet", "entity=a", "action=UP", "actor_user_id=0",
            "entity_id=" + "x".repeat(65), "from=2026-13-01", "from=2026-10-10&to=2026-10-01", "from=2026-01-01&to=2026-06-01",
        )) {
            val r = get(q, Role.ADMIN)
            assertEquals(HttpStatusCode.BadRequest, r.status, "$q: ${r.bodyAsText()}")
            assertFalse(r.bodyAsText().contains("Exception"))
        }
        assertNull(Json.parseToJsonElement(get("limit=1&cursor=1", Role.ADMIN).bodyAsText()).jsonObject["next_cursor"]?.takeIf { it != JsonNull })
    }
}

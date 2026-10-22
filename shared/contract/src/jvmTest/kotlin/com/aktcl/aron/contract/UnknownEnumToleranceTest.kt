package com.aktcl.aron.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract 1.4.0 condition (lead, 2026-10-22 09:16 UTC): a client on an older build must not fail on a role (or problem
 * code) it does not know. The wire DTOs carry `role` and `code` as strings, so the phones' lenient reader (WireJson:
 * ignoreUnknownKeys, coerceInputValues) decodes a response with a new value; mapping to the enum is a lookup that
 * answers null, never an exception.
 */
class UnknownEnumToleranceTest {
    private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun aMeOrLoginUserWithANewRoleDecodes() {
        for (role in listOf("NATIONAL", "BRAND", "SOME_FUTURE_ROLE")) {
            val me = lenient.decodeFromString(
                Me.serializer(),
                """{"user":{"user_id":1,"username":"u1","full_name":"U","role":"$role","locale":"bn"},"permissions":[],
                   "scope":{"scope_version":1,"nodes":[]},"modules":{"reports":"r"},"brands":[7],"some_new_member":true}""",
            )
            assertEquals(role, me.user.role)
            assertEquals(Role.entries.firstOrNull { it.wire == role }?.wire, if (role == "SOME_FUTURE_ROLE") null else role)
        }
        assertNull(Role.entries.firstOrNull { it.wire == "SOME_FUTURE_ROLE" })
        assertNull(ProblemCode.entries.firstOrNull { it.wire == "ERR_SOME_FUTURE_CODE" })
        assertEquals(403, ProblemCode.ERR_AUTH_PORTAL_NOT_ALLOWED.httpStatus)
    }
}

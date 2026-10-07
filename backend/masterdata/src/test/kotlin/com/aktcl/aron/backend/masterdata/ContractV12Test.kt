package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Contract v1.2: `include=assignees` on admin routes (one query) and the employee code and zone columns of the device OTP panel. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractV12Test {
    private lateinit var env: AdminEnv
    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    @Test
    fun routesListCarriesAssigneesOnlyWithInclude() = env.app {
        val adm = env.token("admin1001", Role.ADMIN)
        val plain = json(sendA1(HttpMethod.Get, "/admin/routes", adm).bodyAsText()).getValue("items").jsonArray
        assertTrue(plain.none { it.jsonObject.containsKey("assignees") })
        val inc = json(sendA1(HttpMethod.Get, "/admin/routes?include=assignees", adm).bodyAsText()).getValue("items").jsonArray
        assertEquals(plain.size, inc.size)
        val sr = inc.first { it.jsonObject.getValue("code").jsonPrimitive.content == "MIR-SR-D" }.jsonObject.getValue("assignees").jsonArray
        assertTrue(sr.any { it.jsonObject.getValue("username").jsonPrimitive.content == "sr1001" && it.jsonObject.getValue("role").jsonPrimitive.content == "SR" })
        assertEquals(setOf("user_id", "full_name", "role", "username"), sr.first().jsonObject.keys)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Get, "/admin/routes?include=everything", adm).status)
    }
}

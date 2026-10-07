package com.aktcl.aron.core.network

import com.aktcl.aron.contract.BundleMeta
import com.aktcl.aron.contract.BundleUser
import com.aktcl.aron.contract.LoginDevice
import com.aktcl.aron.contract.LoginRequest
import com.aktcl.aron.contract.LoginResponse
import com.aktcl.aron.contract.LogoutRequest
import com.aktcl.aron.contract.NodeRef
import com.aktcl.aron.contract.RefreshRequest
import com.aktcl.aron.contract.ScopeSummary
import com.aktcl.aron.contract.TokenPair
import com.aktcl.aron.contract.UserSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-written wire DTOs must use exactly the contract's member names (docs/26 s1: never invent a field). */
class AuthDtoContractTest {
    private fun check(serializer: KSerializer<*>, schema: String, mustCoverAll: Boolean) {
        val dto = serializer.descriptor.elementNames.toSet()
        val yaml = ContractYaml.propertyNames(schema)
        assertTrue("$schema: members not in the contract: ${dto - yaml}", yaml.containsAll(dto))
        assertTrue("$schema: required members missing: ${ContractYaml.requiredNames(schema) - dto}", dto.containsAll(ContractYaml.requiredNames(schema)))
        if (mustCoverAll) assertEquals("$schema: request DTO must cover every member", yaml, dto)
    }

    @Test fun loginRequest() = check(LoginRequest.serializer(), "LoginRequest", mustCoverAll = true)
    @Test fun loginResponse() = check(LoginResponse.serializer(), "LoginResponse", mustCoverAll = true)
    @Test fun userSummary() = check(UserSummary.serializer(), "UserSummary", mustCoverAll = true)
    @Test fun scopeSummary() = check(ScopeSummary.serializer(), "ScopeSummary", mustCoverAll = true)
    @Test fun nodeRef() = check(NodeRef.serializer(), "NodeRef", mustCoverAll = true)
    @Test fun loginDevice() = check(LoginDevice.serializer(), "LoginDevice", mustCoverAll = true)
    @Test fun refreshRequest() = check(RefreshRequest.serializer(), "RefreshRequest", mustCoverAll = true)
    @Test fun tokenPair() = check(TokenPair.serializer(), "TokenPair", mustCoverAll = true)
    @Test fun logoutRequest() = check(LogoutRequest.serializer(), "LogoutRequest", mustCoverAll = true)
    @Test fun bundleMeta() = check(BundleMeta.serializer(), "BundleMeta", mustCoverAll = false)
    @Test fun bundleUser() = check(BundleUser.serializer(), "BundleUser", mustCoverAll = true)

    @Test
    fun theContractsExamplesDecode() {
        val login = ContractYaml.toJson(ContractYaml.node("paths", "/v1/auth/login", "post", "responses", "200", "content", "application/json", "examples", "phoneOk", "value")).toString()
        val decoded = WireJson.responses.decodeFromString(LoginResponse.serializer(), login)
        assertEquals("ok", decoded.status)
        assertEquals(1001L, decoded.user.userId)
        assertEquals(0, decoded.device?.bindOrdinal)
        val bundle = WireJson.responses.decodeFromString(BundleHead.serializer(), ContractYaml.componentExampleJson("BundleSr"))
        assertEquals("2026-10-05:3", bundle.meta.bundleVersion)
        assertEquals("sr334001", bundle.user.username)
    }

    @Test
    fun secretsNeverAppearInToString() {
        val r = LoginRequest("sr334001", "hunter22", "app_sr", null)
        assertTrue(!r.toString().contains("hunter22"))
        val t = TokenPair("a.b.c", "2026-10-05T03:12:44.120Z", "r".repeat(43), "2027-01-02T00:00:00.000Z", 7, "2026-10-05T02:12:44.120Z")
        assertTrue(!t.toString().contains("a.b.c") && !t.toString().contains("rrrr"))
    }
}

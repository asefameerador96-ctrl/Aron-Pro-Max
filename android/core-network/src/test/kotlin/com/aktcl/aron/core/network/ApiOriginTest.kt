package com.aktcl.aron.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiOriginTest {
    @Test
    fun acceptsBareHttpsOrigins() {
        assertEquals("https://api.aron-dev.invalid", ApiOrigin.parse("https://api.aron-dev.invalid").origin)
        assertEquals("https://api.aron-dev.invalid", ApiOrigin.parse(" https://api.aron-dev.invalid/ ").origin)
        assertEquals("https://h.example:8443", ApiOrigin.parse("https://h.example:8443").origin)
        assertEquals("https://api.aron-dev.invalid/v1/auth/login", ApiOrigin.parse("https://api.aron-dev.invalid").path("/v1/auth/login").toString())
    }

    @Test
    fun rejectsPathsQueriesCredentialsAndCleartext() {
        for (bad in listOf(
            "https://api.aron-dev.invalid/v1", "https://api.aron-dev.invalid/v1/", "https://h/x?y=1", "https://h/#f",
            "https://u:p@h", "http://api.aron-dev.invalid", "api.aron-dev.invalid", "", "ftp://h",
        )) {
            assertThrows(bad, IllegalArgumentException::class.java) { ApiOrigin.parse(bad) }
        }
        // Cleartext only for loopback and only when allowed (MockWebServer, a laptop API).
        assertThrows(IllegalArgumentException::class.java) { ApiOrigin.parse("http://127.0.0.1:8080") }
        assertEquals("http://127.0.0.1:8080", ApiOrigin.parse("http://127.0.0.1:8080", allowCleartextLoopback = true).origin)
        assertThrows(IllegalArgumentException::class.java) { ApiOrigin.parse("http://10.1.1.1:8080", allowCleartextLoopback = true) }
    }

    @Test
    fun provisioningExtraWinsWhenValid() {
        assertEquals("https://p.example", ApiOrigin.resolve("https://b.example", "https://p.example").origin)
        assertEquals("https://b.example", ApiOrigin.resolve("https://b.example", null).origin)
        assertEquals("https://b.example", ApiOrigin.resolve("https://b.example", "  ").origin)
        assertEquals("https://b.example", ApiOrigin.resolve("https://b.example", "https://p.example/v1").origin)
    }

    @Test
    fun contractPathsMustStartWithV1() {
        assertThrows(IllegalArgumentException::class.java) { ApiOrigin.parse("https://h.example").path("/auth/login") }
    }

    @Test
    fun proofStringsFollowSection83() {
        val s = ProofStrings.refresh("6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", "tok", 1_759_633_964_120)
        val lines = s.split("\n")
        assertEquals(listOf("aron-proof-v1", "refresh", "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"), lines.take(3))
        assertEquals(ProofStrings.sha256Hex("tok".toByteArray()), lines[3])
        assertEquals((1_759_633_964_120L / 1000 / 300).toString(), lines[4])
        assertEquals(5_865_446L, ProofStrings.nonceBucket(1_759_633_964_120))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ProofStrings.sha256Hex(ByteArray(0)))
    }
}

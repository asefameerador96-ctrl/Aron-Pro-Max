package com.aktcl.aron.backend.auth

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 6238 appendix B vectors (SHA-1, last six digits), the one-step window, base32 and the sealed secret. */
class TotpTest {
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun matchesTheRfcVectors() {
        for ((t, expected) in listOf(59L to "287082", 1111111109L to "081804", 1111111111L to "050471", 1234567890L to "005924", 2000000000L to "279037")) {
            assertEquals(expected, Totp.code(rfcSecret, Totp.step(Instant.ofEpochSecond(t))), "t=$t")
        }
    }

    @Test
    fun acceptsOneStepEitherSideOnly() {
        val now = Instant.ofEpochSecond(1111111111)
        val s = Totp.step(now)
        assertEquals(s - 1, Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, s - 1), now))
        assertEquals(s + 1, Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, s + 1), now))
        assertNull(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, s - 2), now))
        assertNull(Totp.matchingStep(rfcSecret, Totp.code(rfcSecret, s + 2), now))
    }

    @Test
    fun base32AndTheCipherRoundTrip() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(rfcSecret))
        val c = MfaCipher(ByteArray(32) { 1 })
        val sealed = c.seal(rfcSecret, 7)
        assertTrue(c.open(sealed, 7)!!.contentEquals(rfcSecret))
        assertNull(c.open(sealed, 8), "the AAD binds the cipher to its user")
        assertTrue(c.recoveryMac("ABCD-1234", 7) != c.recoveryMac("ABCD-1234", 8))
        assertEquals(10, c.recoveryCodes().toSet().size)

        // Rotation: a new current key still opens and matches what the previous key sealed; without it, nothing opens.
        val rotated = MfaCipher(listOf(ByteArray(32) { 2 }, ByteArray(32) { 1 }))
        assertTrue(rotated.open(sealed, 7)!!.contentEquals(rfcSecret))
        val oldMac = c.recoveryMac("ABCD-1234", 7)
        assertEquals(oldMac, rotated.matchRecovery("ABCD-1234", 7, listOf("x", oldMac)))
        assertNull(MfaCipher(ByteArray(32) { 2 }).open(sealed, 7))
        assertNull(rotated.matchRecovery("ABCD-9999", 7, listOf(oldMac)))
    }
}

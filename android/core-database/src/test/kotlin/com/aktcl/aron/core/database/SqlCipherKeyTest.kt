package com.aktcl.aron.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** AUD-PERF-05: the random key goes to SQLCipher in raw form, so no PBKDF2 runs on open. */
class SqlCipherKeyTest {
    @Test fun aThirtyTwoByteKeyBecomesTheRawHexBlob() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", SqlCipher.rawKey(key).decodeToString())
        assertEquals(67, SqlCipher.rawKey(ByteArray(32) { -1 }).size)
    }

    @Test fun anyOtherLengthIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { SqlCipher.rawKey(ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { SqlCipher.rawKey(ByteArray(33)) }
    }
}

package com.aktcl.aron.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIdsTest {
    @Test fun aDerivedIdIsTheSameForTheSamePartsAndPassesTheV4Check() {
        val a = ClientIds.derivedUuid("gift_photo", "red-1", "4", "2")
        assertEquals(a, ClientIds.derivedUuid("gift_photo", "red-1", "4", "2"))
        assertTrue(ClientIds.isUuidV4(a))
    }

    @Test fun anyDifferentPartGivesAnotherIdAndPartBoundariesCannotBlur() {
        val a = ClientIds.derivedUuid("gift_photo", "red-1", "4", "2")
        assertNotEquals(a, ClientIds.derivedUuid("gift_photo", "red-1", "4", "3"))
        assertNotEquals(a, ClientIds.derivedUuid("gift_photo", "red-1", "5", "2"))
        assertNotEquals(ClientIds.derivedUuid("ab", "c"), ClientIds.derivedUuid("a", "bc"))
    }
}

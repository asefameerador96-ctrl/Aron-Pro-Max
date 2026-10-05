package com.aktcl.aron.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleDigitsTest {
    @Test
    fun banglaShowsBengaliDigits() {
        assertEquals("০১২৩৪৫৬৭৮৯", LocaleDigits.toBengali("0123456789"))
        assertEquals("২০২৬-১০-০৫", LocaleDigits.localize("2026-10-05", AppLanguage.BN))
        assertEquals("v০.১.০ (১)", LocaleDigits.localize("v0.1.0 (1)", AppLanguage.BN))
    }

    @Test
    fun englishShowsAsciiDigitsEvenFromBengaliInput() {
        assertEquals("2026-10-05", LocaleDigits.localize("২০২৬-১০-০৫", AppLanguage.EN))
        assertEquals("1234", LocaleDigits.toAscii("১২৩৪"))
    }

    @Test
    fun mixedTextKeepsLetters() {
        assertEquals("SR-রুট-৪২", LocaleDigits.toBengali("SR-রুট-42"))
    }

    @Test
    fun groupsTheSouthAsianWay() {
        assertEquals("0", LocaleDigits.groupSouthAsian(0))
        assertEquals("999", LocaleDigits.groupSouthAsian(999))
        assertEquals("1,000", LocaleDigits.groupSouthAsian(1000))
        assertEquals("12,34,567", LocaleDigits.groupSouthAsian(1234567))
        assertEquals("1,00,00,000", LocaleDigits.groupSouthAsian(10_000_000))
        assertEquals("-1,000", LocaleDigits.groupSouthAsian(-1000))
        assertEquals("-92,23,37,20,36,85,47,75,808", LocaleDigits.groupSouthAsian(Long.MIN_VALUE))
        assertEquals("১২,৩৪,৫৬৭", LocaleDigits.formatInteger(1234567, AppLanguage.BN))
        assertEquals("1234567", LocaleDigits.formatInteger(1234567, AppLanguage.EN, grouping = false))
    }

    @Test
    fun languageTags() {
        assertEquals(AppLanguage.BN, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.BN, AppLanguage.fromTag("xx"))
        assertEquals(AppLanguage.EN, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.BN, AppLanguage.fromTag("bn_BD"))
    }

    @Test
    fun clientIdsAreLowerCaseV4() {
        repeat(200) {
            val id = ClientIds.newUuid()
            assertTrue(id, ClientIds.isUuidV4(id))
        }
        assertFalse(ClientIds.isUuidV4("6F1C2B0E-8D1A-4C5E-9F3A-2B7D4E6A8C10"))
        assertFalse(ClientIds.isUuidV4("6f1c2b0e-8d1a-1c5e-9f3a-2b7d4e6a8c10"))
    }
}

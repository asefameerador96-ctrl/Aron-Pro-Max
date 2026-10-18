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
    fun groupsInThrees() {
        assertEquals("0", LocaleDigits.groupWestern(0))
        assertEquals("999", LocaleDigits.groupWestern(999))
        assertEquals("1,000", LocaleDigits.groupWestern(1000))
        assertEquals("1,234,567", LocaleDigits.groupWestern(1234567))
        assertEquals("10,000,000", LocaleDigits.groupWestern(10_000_000))
        assertEquals("-1,000", LocaleDigits.groupWestern(-1000))
        assertEquals("-999", LocaleDigits.groupWestern(-999))
        assertEquals("2,147,483,648", LocaleDigits.groupWestern(2_147_483_648L)) // above 2^31
        assertEquals("-9,223,372,036,854,775,808", LocaleDigits.groupWestern(Long.MIN_VALUE))
        assertEquals("9,223,372,036,854,775,807", LocaleDigits.groupWestern(Long.MAX_VALUE))
    }

    @Test
    fun theDigitsFollowTheLanguageNotTheGrouping() {
        assertEquals("1,234,567", LocaleDigits.formatInteger(1234567, AppLanguage.EN))
        assertEquals("১,২৩৪,৫৬৭", LocaleDigits.formatInteger(1234567, AppLanguage.BN))
        assertEquals("২,১৪৭,৪৮৩,৬৪৮", LocaleDigits.formatInteger(2_147_483_648L, AppLanguage.BN))
        assertEquals("2,147,483,648", LocaleDigits.formatInteger(2_147_483_648L, AppLanguage.EN))
        assertEquals("1234567", LocaleDigits.formatInteger(1234567, AppLanguage.EN, grouping = false))
        assertEquals("১২৩৪৫৬৭", LocaleDigits.formatInteger(1234567, AppLanguage.BN, grouping = false))
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

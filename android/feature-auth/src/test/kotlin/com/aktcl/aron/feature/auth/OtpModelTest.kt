package com.aktcl.aron.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-002. */
class OtpModelTest {
    @Test fun fourDigitsEnableVerifyAndBengaliDigitsAreAccepted() {
        var s = OtpModel.enter(OtpState(), "১২৩")
        assertFalse(s.canVerify); s = OtpModel.enter(s, "১২৩৪৫")
        assertEquals("1234", s.digits); assertTrue(s.canVerify)
    }

    @Test fun lettersAreDropped() = assertEquals("12", OtpModel.enter(OtpState(), "1a2b").digits)

    @Test fun wrongExpiredAndTooManyAttemptsMapFromTheContractCodes() {
        assertEquals(OtpError.INVALID, OtpModel.errorOf("ERR_AUTH_OTP_INVALID", false))
        assertEquals(OtpError.EXPIRED, OtpModel.errorOf("ERR_AUTH_OTP_EXPIRED", false))
        assertEquals(OtpError.ATTEMPTS_EXCEEDED, OtpModel.errorOf("ERR_AUTH_OTP_ATTEMPTS_EXCEEDED", false))
        assertEquals(OtpError.LOCKED, OtpModel.errorOf("ERR_AUTH_BIND_LOCKED", false))
        assertEquals(OtpError.OFFLINE, OtpModel.errorOf(null, true))
    }

    @Test fun wrongOtpClearsBoxesAndTooManyAttemptsBlocksVerify() {
        val s = OtpState("1234")
        assertEquals("", OtpModel.failed(s, "ERR_AUTH_OTP_INVALID", false).digits)
        assertFalse(OtpModel.failed(s, "ERR_AUTH_OTP_ATTEMPTS_EXCEEDED", false).canVerify)
        assertTrue(OtpModel.enter(OtpModel.failed(s, "ERR_AUTH_OTP_INVALID", false), "9").error == null)
    }

    @Test fun correctOtpBindsAndFreezesTheBoxes() {
        val s = OtpModel.succeeded(OtpState("1234"))
        assertTrue(s.bound); assertEquals("1234", OtpModel.enter(s, "9999").digits); assertFalse(s.canVerify)
    }
}

package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.contract.ProblemCode
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenVerifierTest {
    private val f = AuthFixture()
    private val sr = f.users.findById(1001)!!

    private fun code(block: () -> Unit) = assertFailsWith<ApiProblem> { block() }.code

    @Test
    fun derivedPublicKeyEqualsTheGeneratedOne() {
        repeat(5) {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            assertEquals((kp.public as ECPublicKey).w, JwtKeys.derivePublic(kp.private as ECPrivateKey).w)
        }
    }

    @Test
    fun rejectsTamperedForeignWrongAudienceAndExpiredTokens() {
        val t = f.issuer.access(TokenSubject(sr, 501, f.srDevice, "sr")).token
        assertEquals(1001, f.verifier.verify(t, setOf(Audience.API)).userId)
        val parts = t.split('.')
        val tampered = parts[0] + "." + parts[1].dropLast(2) + "AA" + "." + parts[2]
        assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify(tampered, setOf(Audience.API)) })
        val foreign = TokenIssuer(throwawayKeys("test-1"), f.config, f.clock).access(TokenSubject(sr, 501, f.srDevice, "sr")).token
        assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify(foreign, setOf(Audience.API)) })
        assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify(t, setOf(Audience.UPLOAD)) })
        val bind = f.issuer.access(TokenSubject(sr, 501, f.srDevice, "sr"), Audience.BIND).token
        assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify(bind, setOf(Audience.API)) })
        assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify("not.a.jwt", setOf(Audience.API)) })
        val unsigned = parts[0].let { "eyJhbGciOiJub25lIn0" } + "." + parts[1] + "."
        assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify(unsigned, setOf(Audience.API)) })

        val short = f.issuer.mint(TokenSubject(sr, 501, f.srDevice, "sr"), Audience.API, Duration.ofMinutes(1)).token
        f.clock.advance(90)
        assertEquals(ProblemCode.ERR_TOKEN_EXPIRED, code { f.verifier.verify(short, setOf(Audience.API)) })
        // sync/batch grace: expired by at most 60 s is still accepted there.
        assertEquals(1001, f.verifier.verify(short, setOf(Audience.API), expiredGraceS = 60).userId)
    }
}

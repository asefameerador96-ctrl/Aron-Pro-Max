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

    /** D-AUTHZ-PII: the `pii` claim holds only with a `pii_until` window that is still open (fail closed). */
    @Test
    fun aPiiClaimHoldsOnlyInsideItsWindow() {
        val admin = f.users.findById(9001)!!
        val noWindow = f.issuer.access(TokenSubject(admin, null, null, "web", pii = true)).token
        assertEquals(false, f.verifier.verify(noWindow, setOf(Audience.API)).pii, "no pii_until: no reveal")
        val until = f.clock.now().plusSeconds(300)
        val open = f.issuer.access(TokenSubject(admin, null, null, "web", pii = true, piiUntil = until)).token
        f.verifier.verify(open, setOf(Audience.API)).let { assertEquals(true, it.pii); assertEquals(until.epochSecond, it.piiUntil!!.epochSecond) }
        f.clock.advance(301)
        assertEquals(false, f.verifier.verify(open, setOf(Audience.API)).pii, "the window closed before the token expired")
        val off = f.issuer.access(TokenSubject(admin, null, null, "web", piiUntil = f.clock.now().plusSeconds(300))).token
        assertEquals(false, f.verifier.verify(off, setOf(Audience.API)).pii, "a window without the pii flag reveals nothing")
    }

    /** D-ROLES-02: `modules` for an ADMIN and `brands` for a BRAND web token, minted from the grants once they are live. */
    @Test
    fun portalGrantClaimsAreMintedForWebTokensOnlyOnceLive() {
        val admin = f.users.findById(9001)!!
        val brand = admin.copy(id = 9101, username = "brand1", role = com.aktcl.aron.contract.Role.BRAND)
        var live = false
        val grants = object : com.aktcl.aron.backend.platform.PortalGrants {
            override fun live() = live
            override fun modules(userId: Long) = mapOf("users" to "r", "prices" to "w")
            override fun brands(userId: Long) = listOf(11L, 12L)
        }
        val issuer = TokenIssuer(f.keys, f.config, f.clock, grants = grants)
        fun verify(t: String) = f.verifier.verify(t, setOf(Audience.API))
        verify(issuer.access(TokenSubject(admin, null, null, "web")).token).let { assertEquals(null, it.modules); assertEquals(null, it.brands) }
        live = true
        verify(issuer.access(TokenSubject(admin, null, null, "web")).token).let { assertEquals(mapOf("users" to "r", "prices" to "w"), it.modules); assertEquals(null, it.brands) }
        verify(issuer.access(TokenSubject(brand, null, null, "web")).token).let { assertEquals(listOf(11L, 12L), it.brands); assertEquals(null, it.modules) }
        verify(issuer.access(TokenSubject(sr, 501, f.srDevice, "sr")).token).let { assertEquals(null, it.modules); assertEquals(null, it.brands) }
        val superadmin = admin.copy(role = com.aktcl.aron.contract.Role.SUPERADMIN)
        assertEquals(null, verify(issuer.access(TokenSubject(superadmin, null, null, "web")).token).modules)
    }

    /** A signed token whose grant claims are not in the shape this server mints is refused (fail closed). */
    @Test
    fun malformedGrantClaimsAreRefused() {
        fun signed(extra: com.nimbusds.jwt.JWTClaimsSet.Builder.() -> Unit): String {
            val now = java.util.Date.from(f.clock.now())
            val c = com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("aron").audience(Audience.API).subject("9001").claim("uname", "admin")
                .claim("role", "ADMIN").claim("sv", 7L).claim("flv", "web").claim("perm", emptyList<String>()).claim("amr", listOf("pwd"))
                .issueTime(now).notBeforeTime(now).expirationTime(java.util.Date.from(f.clock.now().plusSeconds(600))).jwtID("j").apply(extra).build()
            val jwt = com.nimbusds.jwt.SignedJWT(com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.ES256).keyID(f.keys.kid).build(), c)
            jwt.sign(f.keys.signer)
            return jwt.serialize()
        }
        assertEquals(mapOf("mdm" to "r"), f.verifier.verify(signed { claim("modules", mapOf("mdm" to "r")) }, setOf(Audience.API)).modules)
        for (bad in listOf<com.nimbusds.jwt.JWTClaimsSet.Builder.() -> Unit>(
            { claim("modules", mapOf("everything" to "w")) }, { claim("modules", mapOf("mdm" to "rw")) }, { claim("modules", mapOf("mdm" to 1)) },
            { claim("modules", listOf("mdm")) }, { claim("brands", listOf("11")) }, { claim("brands", listOf(0)) }, { claim("brands", "11") },
        )) assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify(signed(bad), setOf(Audience.API)) })
    }

    /** Lead 2026-10-22: a token whose signature string is a non-canonical encoding of the same bytes is refused. */
    @Test
    fun aNonCanonicalSignatureEncodingIsRefused() {
        val t = f.issuer.access(TokenSubject(sr, 501, f.srDevice, "sr")).token
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        // 64 signature bytes in 86 characters: the last character's low 4 bits are padding; flipping one keeps the bytes.
        val last = alphabet.indexOf(t.last())
        val twin = t.dropLast(1) + alphabet[last xor 1]
        assertEquals(
            com.nimbusds.jwt.SignedJWT.parse(t).signature.decode().toList(), com.nimbusds.jwt.SignedJWT.parse(twin).signature.decode().toList(),
            "the twin decodes to the same signature bytes",
        )
        assertEquals(1001, f.verifier.verify(t, setOf(Audience.API)).userId)
        assertEquals(ProblemCode.ERR_UNAUTHENTICATED, code { f.verifier.verify(twin, setOf(Audience.API)) })
    }
}

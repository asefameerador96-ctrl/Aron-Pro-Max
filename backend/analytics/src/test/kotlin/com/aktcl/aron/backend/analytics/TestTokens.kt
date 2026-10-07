package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.contract.Role
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Throwaway ES256 keys and web access tokens for analytics tests (tests only; never a real key). */
object TestTokens {
    val keys: JwtKeys by lazy {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pem = "-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().private.encoded) + "\n-----END PRIVATE KEY-----"
        JwtKeys(JwtKeys.parsePrivatePem(pem), "t")
    }

    fun web(userId: Long, role: Role, sv: Long = 1, pii: Boolean = false): String {
        val now = Instant.now()
        val c = JWTClaimsSet.Builder().issuer("aron").audience("aron-api").subject(userId.toString()).claim("uname", "u$userId")
            .claim("role", role.wire).claim("sv", sv).claim("flv", "web").claim("perm", emptyList<String>()).claim("pii", pii)
            .claim("amr", listOf("pwd")).issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
            .jwtID(UUID.randomUUID().toString()).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("t").build(), c).apply { sign(keys.signer) }.serialize()
    }
}

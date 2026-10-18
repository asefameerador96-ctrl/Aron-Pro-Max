package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.PiiReveal
import com.aktcl.aron.backend.platform.PiiRevealLog
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.ServerConfig
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

/** Throwaway ES256 keys and web access tokens for masterdata tests (tests only; never a real key). */
object TestTokens {
    val keys: JwtKeys by lazy {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pem = "-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().private.encoded) + "\n-----END PRIVATE KEY-----"
        JwtKeys(JwtKeys.parsePrivatePem(pem), "t")
    }

    fun web(userId: Long, role: Role, sv: Long = 1, pii: Boolean = false): String {
        // wall-clock-ok: token validity window against the app guard, which verifies with the system clock
        val now = Instant.now()
        val c = JWTClaimsSet.Builder().issuer("aron").audience("aron-api").subject(userId.toString()).claim("uname", "u$userId")
            .claim("role", role.wire).claim("sv", sv).claim("flv", "web").claim("perm", emptyList<String>()).claim("pii", pii)
            .apply { if (pii) claim("pii_until", now.plusSeconds(900).epochSecond) }
            .claim("amr", if (role.wire in listOf("ADMIN", "SUPERADMIN", "SUPPORT")) listOf("pwd", "mfa") else listOf("pwd")).issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
            .jwtID(UUID.randomUUID().toString()).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("t").build(), c).apply { sign(keys.signer) }.serialize()
    }

    /** Reveal audit rows the [guard] wrote ("METHOD path"), D-AUTHZ-PII. */
    val reveals = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val revealLog = object : PiiRevealLog {
        override fun stepUp(p: AronPrincipal, until: Instant, requestId: String?) { reveals += "step_up" }
        override fun read(p: AronPrincipal, method: String, path: String, requestId: String?) { reveals += "$method $path" }
    }

    /**
     * The test guard: every role also holds `pii.reveal` and reveals are recorded, so a `web(..., pii = true)` token (an
     * open `pii_until` window) is served unmasked as after a real step-up (D-AUTHZ-PII); other tokens are unchanged.
     */
    fun guard(config: ServerConfig, lookup: ScopeVersionLookup = ScopeVersionLookup { 1 }): AuthGuardDeps =
        AuthGuardDeps(AccessTokenVerifier(keys), object : ScopeVersionLookup by lookup {
            override fun rolePermissions(role: String) = lookup.rolePermissions(role) + PiiReveal.PERMISSION
        }, config, piiReveals = revealLog)
}

package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.ServerConfig

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Who the token is for: everything the claims of docs/24 s8.2 need. */
data class TokenSubject(
    val user: UserRecord,
    val deviceId: Long?,
    val deviceUuid: String?,
    /** `sr`, `amo`, `tso` or `web`. */
    val flavour: String,
    val permissions: List<String> = emptyList(),
    val pii: Boolean = false,
    val amr: List<String> = listOf("pwd"),
)

data class IssuedAccess(val token: String, val expiresAt: Instant)

/**
 * Mints ES256 access tokens. Phone lifetimes are 60 min plus U(0, 10) min of jitter (cfg.auth.access_ttl_min,
 * cfg.auth.access_ttl_jitter_min) so 8,500 tokens minted in the morning storm do not expire together; web access
 * tokens live 15 min (docs/24 s8.1, s6.5).
 */
class TokenIssuer(
    private val keys: JwtKeys,
    private val config: ServerConfig,
    private val clock: AronClock = AronClock.SYSTEM,
    private val random: SecureRandom = SecureRandom(),
) {
    fun access(subject: TokenSubject, audience: String = Audience.API): IssuedAccess {
        val ttl = when {
            audience == Audience.BIND -> Duration.ofMinutes(10)
            audience == Audience.MFA -> Duration.ofMinutes(5)
            subject.flavour == "web" -> Duration.ofMinutes(15)
            else -> {
                val jitterS = config.int("cfg.auth.access_ttl_jitter_min").toLong() * 60
                Duration.ofMinutes(config.int("cfg.auth.access_ttl_min").toLong())
                    .plusSeconds(if (jitterS > 0) random.nextLong(jitterS + 1) else 0)
            }
        }
        return mint(subject, audience, ttl)
    }

    fun mint(subject: TokenSubject, audience: String, ttl: Duration): IssuedAccess {
        val now = clock.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val exp = now.plus(ttl)
        val claims = JWTClaimsSet.Builder()
            .issuer("aron")
            .audience(audience)
            .subject(subject.user.id.toString())
            .claim("uname", subject.user.username)
            .claim("role", subject.user.role.wire)
            .claim("sv", subject.user.scopeVersion)
            .apply {
                subject.deviceId?.let { claim("did", it) }
                subject.deviceUuid?.let { claim("dvu", it) }
            }
            .claim("flv", subject.flavour)
            .claim("perm", subject.permissions)
            .claim("pii", subject.pii)
            .claim("amr", subject.amr)
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keys.kid).type(JOSEObjectType.JWT).build(), claims)
        jwt.sign(keys.signer)
        return IssuedAccess(jwt.serialize(), exp)
    }

    /** Opaque 256-bit refresh token, base64url without padding (43 characters). */
    fun newOpaqueToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

}

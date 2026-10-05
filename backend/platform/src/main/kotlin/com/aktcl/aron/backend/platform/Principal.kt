package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.nimbusds.jose.JOSEException
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException
import java.time.Instant

object Audience {
    const val API = "aron-api"
    const val UPLOAD = "aron-upload"
    const val BIND = "aron-bind"
    const val MFA = "aron-mfa"
    val ALL = setOf(API, UPLOAD, BIND, MFA)
}

/** The verified caller, built only from a signed access token (docs/24 s8.2). Identity is never read from a body. */
data class AronPrincipal(
    val userId: Long,
    val username: String,
    val role: Role,
    val scopeVersion: Long,
    val audience: String,
    val deviceId: Long?,
    val deviceUuid: String?,
    val flavour: String,
    val permissions: List<String>,
    val pii: Boolean,
    val amr: List<String>,
    val jti: String,
    val expiresAt: Instant,
) {
    val isPhone: Boolean get() = flavour != "web"
}

/** Verifies ES256 access tokens: signature by a known kid, `iss`, `aud`, `nbf` and `exp` (with an optional grace). */
class AccessTokenVerifier(private val keys: JwtKeys, private val clock: AronClock = AronClock.SYSTEM) {

    fun verify(token: String, audiences: Set<String>, expiredGraceS: Long = 0): AronPrincipal {
        val jwt = try { SignedJWT.parse(token) } catch (e: ParseException) { throw unauth("malformed token") }
        if (jwt.header.algorithm?.name != "ES256") throw unauth("wrong algorithm")
        val verifier = keys.verifier(jwt.header.keyID) ?: throw unauth("unknown key id")
        val ok = try { jwt.verify(verifier) } catch (e: JOSEException) { false }
        if (!ok) throw unauth("bad signature")
        val c = try { jwt.jwtClaimsSet } catch (e: ParseException) { throw unauth("bad claims") }
        if (c.issuer != "aron") throw unauth("wrong issuer")
        val aud = c.audience.singleOrNull() ?: throw unauth("audience")
        if (aud !in audiences) throw unauth("audience not accepted here")
        val now = clock.now()
        val exp = c.expirationTime?.toInstant() ?: throw unauth("no exp")
        c.notBeforeTime?.toInstant()?.let { if (now.plusSeconds(5).isBefore(it)) throw unauth("not yet valid") }
        if (!now.isBefore(exp.plusSeconds(expiredGraceS))) throw ApiProblem(ProblemCode.ERR_TOKEN_EXPIRED, "access token expired")
        val principal = try {
            AronPrincipal(
                userId = c.subject.toLong(),
                username = c.getStringClaim("uname"),
                role = Role.valueOf(c.getStringClaim("role")),
                scopeVersion = c.getLongClaim("sv"),
                audience = aud,
                deviceId = c.getLongClaim("did"),
                deviceUuid = c.getStringClaim("dvu"),
                flavour = c.getStringClaim("flv"),
                permissions = c.getStringListClaim("perm") ?: emptyList(),
                pii = c.getBooleanClaim("pii") ?: false,
                amr = c.getStringListClaim("amr") ?: emptyList(),
                jti = c.jwtid ?: throw unauth("no jti"),
                expiresAt = exp,
            )
        } catch (e: ApiProblem) {
            throw e
        } catch (e: Exception) {
            throw unauth("bad claims")
        }
        // Every phone token is bound to a device (dvu); a phone flavour without it would skip device binding.
        if (principal.flavour !in FLAVOURS) throw unauth("unknown flavour")
        if (principal.isPhone && (principal.deviceUuid == null || !UUID_V4.matches(principal.deviceUuid))) throw unauth("phone token without device")
        if (!principal.isPhone && principal.deviceUuid != null) throw unauth("web token with a device")
        return principal
    }

    private companion object {
        val FLAVOURS = setOf("sr", "amo", "tso", "web")
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }

    private fun unauth(why: String) = ApiProblem(ProblemCode.ERR_UNAUTHENTICATED, "invalid access token ($why)")
}

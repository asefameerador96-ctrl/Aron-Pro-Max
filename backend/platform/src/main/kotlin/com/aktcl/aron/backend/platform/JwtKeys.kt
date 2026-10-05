package com.aktcl.aron.backend.platform

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The ES256 (P-256) access-token keys (docs/24 s8.2, D24-19): the current signing key, loaded once at startup from
 * settings (Key Vault secret aron-jwt-signing-key), plus an optional next public key that JWKS publishes 24 h
 * before it signs. The private key never leaves this object and is never logged.
 */
class JwtKeys(
    private val privateKey: ECPrivateKey,
    val kid: String,
    private val nextPublic: Pair<String, ECPublicKey>? = null,
) {
    val publicKey: ECPublicKey = derivePublic(privateKey)

    val signer: ECDSASigner = ECDSASigner(privateKey)

    private val verifiers: Map<String, ECDSAVerifier> = buildMap {
        put(kid, ECDSAVerifier(publicKey))
        nextPublic?.let { (k, pub) -> put(k, ECDSAVerifier(pub)) }
    }

    fun verifier(kid: String?): ECDSAVerifier? = kid?.let { verifiers[it] }

    /** Public JWKs (contract JwkEcPublic): current key, then the next one during a rotation. */
    fun publicJwks(): List<ECKey> = buildList {
        add(jwk(publicKey, kid))
        nextPublic?.let { (k, pub) -> add(jwk(pub, k)) }
    }

    /**
     * A 256-bit secret derived from the signing key for [label] (HMAC-SHA256 keyed by the private scalar), so that
     * one Key Vault secret serves the token family's needs (refresh-rotation derivation, D24 decision log).
     */
    fun derivedSecret(label: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(privateKey.s.toByteArray(), "HmacSHA256"))
        return mac.doFinal(label.toByteArray())
    }

    override fun toString(): String = "JwtKeys(kid=$kid)"

    companion object {
        fun fromSettings(s: Settings): JwtKeys {
            val pem = s.jwtSigningKeyPem ?: throw SettingsException("ARON_JWT_SIGNING_KEY is required")
            val next = s.jwtNextPublicKeyPem?.let { (s.jwtNextKid ?: throw SettingsException("ARON_JWT_NEXT_KID is required with ARON_JWT_NEXT_PUBLIC_KEY")) to parsePublicPem(it) }
            return JwtKeys(parsePrivatePem(pem.reveal()), s.jwtKid, next)
        }

        fun parsePrivatePem(pem: String): ECPrivateKey {
            val der = pemBody(pem, "PRIVATE KEY")
            val key = runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) }
                .getOrElse { throw SettingsException("ARON_JWT_SIGNING_KEY is not an EC PKCS#8 PEM") }
            val ec = key as? ECPrivateKey ?: throw SettingsException("ARON_JWT_SIGNING_KEY is not an EC key")
            if (ec.params.curve.field.fieldSize != 256) throw SettingsException("ARON_JWT_SIGNING_KEY must be a P-256 key")
            return ec
        }

        fun parsePublicPem(pem: String): ECPublicKey {
            val der = pemBody(pem, "PUBLIC KEY")
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
        }

        private fun pemBody(pem: String, label: String): ByteArray {
            val body = pem.replace("\\n", "\n")
                .substringAfter("-----BEGIN $label-----", "")
                .substringBefore("-----END $label-----", "")
                .filterNot { it.isWhitespace() }
            if (body.isEmpty()) throw SettingsException("expected a PEM block '$label'")
            return Base64.getDecoder().decode(body)
        }

        private fun jwk(pub: ECPublicKey, kid: String): ECKey =
            ECKey.Builder(Curve.P_256, pub).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).build()

        /** Q = d·G on the private key's own curve (affine double-and-add; startup only, not secret-timing sensitive in use). */
        fun derivePublic(priv: ECPrivateKey): ECPublicKey {
            val params = priv.params
            val p = (params.curve.field as java.security.spec.ECFieldFp).p
            val a = params.curve.a
            fun add(P: ECPoint, Q: ECPoint): ECPoint {
                if (P == ECPoint.POINT_INFINITY) return Q
                if (Q == ECPoint.POINT_INFINITY) return P
                if (P.affineX == Q.affineX) {
                    if ((P.affineY + Q.affineY).mod(p) == BigInteger.ZERO) return ECPoint.POINT_INFINITY
                    val l = (P.affineX.pow(2) * BigInteger.valueOf(3) + a) * (P.affineY.shiftLeft(1)).modInverse(p) % p
                    val x = (l * l - P.affineX.shiftLeft(1)).mod(p)
                    return ECPoint(x, (l * (P.affineX - x) - P.affineY).mod(p))
                }
                val l = (Q.affineY - P.affineY) * (Q.affineX - P.affineX).mod(p).modInverse(p) % p
                val x = (l * l - P.affineX - Q.affineX).mod(p)
                return ECPoint(x, (l * (P.affineX - x) - P.affineY).mod(p))
            }
            var r = ECPoint.POINT_INFINITY
            var g = params.generator
            val d = priv.s
            for (i in 0 until d.bitLength()) {
                if (d.testBit(i)) r = add(r, g)
                g = add(g, g)
            }
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(r, params)) as ECPublicKey
        }
    }
}

package com.aktcl.aron.backend.analytics.devices

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** A minimal DER reader: just enough to walk the Android key-attestation extension (tags may use the high-tag-number form). */
internal class Der(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    data class Tlv(val tagClass: Int, val constructed: Boolean, val tag: Int, val start: Int, val len: Int, val bytes: ByteArray) {
        val content: ByteArray get() = bytes.copyOfRange(start, start + len)
        fun children(): List<Tlv> = Der(bytes, start, start + len).all()
    }

    fun hasMore() = pos < end

    fun next(): Tlv {
        require(pos < end) { "DER: truncated" }
        val first = b[pos++].toInt() and 0xff
        val cls = first ushr 6; val constructed = first and 0x20 != 0
        var tag = first and 0x1f
        if (tag == 0x1f) { tag = 0; do { require(pos < end) { "DER: truncated tag" }; val x = b[pos++].toInt() and 0xff; tag = (tag shl 7) or (x and 0x7f); if (x and 0x80 == 0) break } while (true) }
        require(pos < end) { "DER: truncated length" }
        var len = b[pos++].toInt() and 0xff
        if (len and 0x80 != 0) { val n = len and 0x7f; require(n in 1..4 && pos + n <= end) { "DER: bad length" }; len = 0; repeat(n) { len = (len shl 8) or (b[pos++].toInt() and 0xff) } }
        require(len >= 0 && pos + len <= end) { "DER: length beyond data" }
        val t = Tlv(cls, constructed, tag, pos, len, b); pos += len; return t
    }

    fun all(): List<Tlv> = buildList { while (hasMore()) add(next()) }
}

/** What the phone's Keystore attested about the enrolment key (Android `KeyDescription`, OID 1.3.6.1.4.1.11129.2.1.17). */
data class AttestationFacts(
    val challenge: ByteArray, val securityLevel: Int, val packageName: String?, val signerDigestsSha256: Set<String>,
    val verifiedBootState: Int?, val deviceLocked: Boolean?, val rootSha256: String, val chainLength: Int,
) {
    val hardwareBacked: Boolean get() = securityLevel >= 1
}

class AttestationFailed(message: String) : RuntimeException(message)

/** Which roots to trust (SHA-256 of the root certificate, lower-case hex). Empty means "any self-consistent chain": only for lockdown `dev`. */
class AttestationTrust(val trustedRootSha256: Set<String>)

/**
 * Verifies a Keystore key-attestation chain (docs/24 s8.7, s10.4): every certificate is signed by the next and valid now, the chain ends in a trusted root,
 * the leaf certifies [expectedPublicKey], and the extension carries the challenge, the package and the signing-certificate digest. Parsing only; nothing is fetched.
 */
object KeyAttestation {
    private const val OID = "1.3.6.1.4.1.11129.2.1.17"

    fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun verify(chainBase64: List<String>, expectedPublicKey: PublicKey, trust: AttestationTrust, requireTrustedRoot: Boolean, nowMs: Long): AttestationFacts {
        val cf = CertificateFactory.getInstance("X.509")
        val chain = try {
            chainBase64.map { cf.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(it))) as X509Certificate }
        } catch (e: Exception) { throw AttestationFailed("certificate chain is not valid X.509") }
        if (chain.isEmpty()) throw AttestationFailed("empty chain")
        val leaf = chain.first()
        if (leaf.publicKey.encoded.contentEquals(expectedPublicKey.encoded).not()) throw AttestationFailed("the attested key is not the enrolment key")
        for (i in chain.indices) {
            val c = chain[i]
            try { c.checkValidity(java.util.Date(nowMs)) } catch (e: Exception) { throw AttestationFailed("certificate $i is not valid now") }
            val issuerKey = if (i + 1 < chain.size) chain[i + 1].publicKey else c.publicKey   // the last one must be self-signed
            try { c.verify(issuerKey) } catch (e: Exception) { throw AttestationFailed("certificate $i is not signed by certificate ${if (i + 1 < chain.size) i + 1 else i}") }
        }
        val rootHash = sha256Hex(chain.last().encoded)
        if (requireTrustedRoot && rootHash !in trust.trustedRootSha256) throw AttestationFailed("the chain does not end in a trusted root")
        val ext = leaf.getExtensionValue(OID) ?: throw AttestationFailed("no key attestation extension")
        val facts = try { parse(ext, rootHash, chain.size) } catch (e: AttestationFailed) { throw e } catch (e: Exception) { throw AttestationFailed("malformed key attestation extension") }
        return facts
    }

    /** [extensionValue] is `getExtensionValue`'s DER OCTET STRING wrapping the extension's own DER. */
    private fun parse(extensionValue: ByteArray, rootHash: String, chainLen: Int): AttestationFacts {
        val inner = Der(extensionValue).next().content           // the OCTET STRING content = DER of KeyDescription
        val kd = Der(inner).next().children()                    // SEQUENCE
        // attestationVersion, attestationSecurityLevel, keyMintVersion, keyMintSecurityLevel, attestationChallenge, uniqueId, softwareEnforced, teeEnforced
        val level = kd[1].content.fold(0) { a, x -> (a shl 8) or (x.toInt() and 0xff) }
        val keyMintLevel = kd[3].content.fold(0) { a, x -> (a shl 8) or (x.toInt() and 0xff) }
        val challenge = kd[4].content
        val lists = listOf(kd[6], kd[7])
        var pkg: String? = null; val digests = mutableSetOf<String>(); var bootState: Int? = null; var locked: Boolean? = null
        for (list in lists) for (item in list.children()) {
            if (item.tagClass != 2) continue
            when (item.tag) {
                709 -> {   // attestationApplicationId: [709] EXPLICIT OCTET STRING { SEQUENCE { SET of package infos, SET of signature digests } }
                    val octets = item.children().first().content
                    val seq = Der(octets).next().children()
                    pkg = seq[0].children().firstOrNull()?.children()?.firstOrNull()?.content?.toString(Charsets.UTF_8)
                    seq[1].children().forEach { digests += it.content.joinToString("") { b -> "%02x".format(b) } }
                }
                704 -> {   // rootOfTrust: SEQUENCE { verifiedBootKey, deviceLocked, verifiedBootState, hash }
                    val rot = item.children().first().children()
                    locked = rot[1].content.firstOrNull()?.let { it.toInt() != 0 }
                    bootState = rot[2].content.fold(0) { a, x -> (a shl 8) or (x.toInt() and 0xff) }
                }
            }
        }
        return AttestationFacts(challenge, maxOf(level, keyMintLevel), pkg, digests, bootState, locked, rootHash, chainLen)
    }
}

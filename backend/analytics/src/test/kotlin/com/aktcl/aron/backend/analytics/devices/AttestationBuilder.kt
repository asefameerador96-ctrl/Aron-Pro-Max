package com.aktcl.aron.backend.analytics.devices

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Builds Android-style key-attestation chains for tests (BouncyCastle is a test dependency only). */
object AttestationBuilder {
    class Built(val chainBase64: List<String>, val rootSha256: String, val rootKey: KeyPair)

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun cert(subject: String, subjectKey: PublicKey, issuer: String, issuerKey: KeyPair, serial: Long, ext: Pair<String, ByteArray>? = null, notAfterDays: Int = 3650, ca: Boolean = false): X509Certificate {
        val b = JcaX509v3CertificateBuilder(X500Principal(issuer), BigInteger.valueOf(serial), Date(System.currentTimeMillis() - 5L * 365 * 86_400_000L), Date(System.currentTimeMillis() + notAfterDays * 86_400_000L), X500Principal(subject), subjectKey)
        if (ca) b.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, org.bouncycastle.asn1.x509.BasicConstraints(true))
        ext?.let { (oid, der) -> b.addExtension(ASN1ObjectIdentifier(oid), false, org.bouncycastle.asn1.ASN1Primitive.fromByteArray(der)) }
        return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.private)))
    }

    private fun seq(vararg e: ASN1Encodable) = DERSequence(ASN1EncodableVector().apply { e.forEach { add(it) } })

    fun keyDescription(challenge: ByteArray, packageName: String, signerDigest: ByteArray, securityLevel: Int = 1, deviceLocked: Boolean = true, bootState: Int = 0): ByteArray {
        val appId = seq(DERSet(seq(DEROctetString(packageName.toByteArray()), ASN1Integer(1))), DERSet(DEROctetString(signerDigest)))
        val rootOfTrust = seq(DEROctetString(ByteArray(32)), ASN1Boolean.getInstance(deviceLocked), ASN1Enumerated(bootState), DEROctetString(ByteArray(32)))
        val tee = seq(DERTaggedObject(true, 704, rootOfTrust), DERTaggedObject(true, 709, DEROctetString(appId.encoded)))
        return seq(ASN1Integer(4), ASN1Enumerated(securityLevel), ASN1Integer(100), ASN1Enumerated(securityLevel), DEROctetString(challenge), DEROctetString(ByteArray(0)), seq(), tee).encoded
    }

    /**
     * The chain-extension attack: [attested] is a genuine chain of an attested (non-CA) Keystore key [attestedKey]; the attacker uses that key to sign a
     * forged leaf for [forgedKey] carrying [forgedDesc], and prepends it. Every link verifies and the root is unchanged.
     */
    fun extendChain(attested: Built, attestedKey: KeyPair, forgedKey: PublicKey, forgedDesc: ByteArray): Built {
        val fake = cert("CN=Forged Leaf", forgedKey, "CN=Test Leaf", attestedKey, 99, "1.3.6.1.4.1.11129.2.1.17" to forgedDesc)
        return Built(listOf(Base64.getEncoder().encodeToString(fake.encoded)) + attested.chainBase64, attested.rootSha256, attested.rootKey)
    }

    /** A leaf for [deviceKey] under an intermediate under a root; [keyDesc] is what the extension carries. */
    fun chain(deviceKey: PublicKey, keyDesc: ByteArray): Built {
        val root = keyPair(); val mid = keyPair()
        val rootCert = cert("CN=Test Root", root.public, "CN=Test Root", root, 1, ca = true)
        val midCert = cert("CN=Test Intermediate", mid.public, "CN=Test Root", root, 2, ca = true)
        val leaf = cert("CN=Test Leaf", deviceKey, "CN=Test Intermediate", mid, 3, "1.3.6.1.4.1.11129.2.1.17" to keyDesc)
        val enc = Base64.getEncoder()
        return Built(listOf(enc.encodeToString(leaf.encoded), enc.encodeToString(midCert.encoded), enc.encodeToString(rootCert.encoded)), MessageDigest.getInstance("SHA-256").digest(rootCert.encoded).joinToString("") { "%02x".format(it) }, root)
    }
}

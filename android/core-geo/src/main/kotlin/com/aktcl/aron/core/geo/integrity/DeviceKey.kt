package com.aktcl.aron.core.geo.integrity

import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The device key created at enrolment with its attestation (docs/24 s8.3, s10.4). */
data class AttestedKey(
    val alias: String,
    val publicKey: EcJwk,
    /** DER certificates, standard base64, leaf first (contract `key_attestation_chain`, 1 to 6 items). */
    val chain: List<String>,
    val strongBox: Boolean,
    /** False when the phone produced no attestation extension (the server will fail it; enrolment still proceeds). */
    val attested: Boolean,
)

/** Creates and uses the non-exportable EC P-256 device key in the Android Keystore. */
interface DeviceKeyStore {
    fun create(alias: String, challenge: ByteArray): AttestedKey
    fun exists(alias: String): Boolean
    /** ES256 over [data], returned as raw r‖s base64url (86 chars), or null when the key is missing or unusable. */
    fun sign(alias: String, data: ByteArray): String?
    fun delete(alias: String)
    /** Device-key aliases present (prefix [DeviceKeySpecs.ALIAS_PREFIX]). */
    fun aliases(): List<String>
    /** Deletes every device key except [keep]: after the server accepted [keep], or at start to clear keys orphaned by a crash. */
    fun deleteAllExcept(keep: String) = aliases().filter { it != keep }.forEach(::delete)
}

object DeviceKeySpecs {

    /** The key spec: P-256, SIGN with SHA-256, attestation [challenge], StrongBox when asked; never exportable. */
    /** A fresh alias for a new device key (re-enrolment never overwrites the key in use). */
    fun newAlias(): String = ALIAS_PREFIX + java.util.UUID.randomUUID().toString()

    const val ALIAS_PREFIX = "aron-device-key-"


    fun spec(alias: String, challenge: ByteArray?, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (challenge != null) setAttestationChallenge(challenge) }
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()

    /** Standard base64 DER of each certificate, at most 6 (contract maxItems). */
    fun encodeChain(chain: Array<out Certificate>?): List<String> =
        chain.orEmpty().take(6).map { Base64.getEncoder().encodeToString(it.encoded) }

    /** Key attestation extension OID (KeyDescription) on the leaf certificate. */
    const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
}

class AndroidDeviceKeyStore(context: Context) : DeviceKeyStore {
    private val app = context.applicationContext
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Creates the key under a NEW [alias] (see [DeviceKeySpecs.newAlias]); an existing key is never touched, so a failed
     * or refused re-enrolment leaves the key the server holds working. The caller deletes the old alias only after the
     * server accepted the new key. Tries StrongBox, then the TEE, then (phones whose attestation is broken) the TEE
     * without attestation, which the server will mark as failed attestation while enrolment still proceeds.
     */
    override fun create(alias: String, challenge: ByteArray): AttestedKey {
        require(!exists(alias)) { "alias in use; create the new key under a new alias" }
        val wantStrongBox = Build.VERSION.SDK_INT >= 28 && app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val attempts = listOfNotNull(
            if (wantStrongBox) Triple(true, challenge, "strongbox") else null,
            Triple(false, challenge, "tee"),
            Triple(false, null, "tee-unattested"),
        )
        var strongBox = false
        var last: Exception? = null
        val made = attempts.any { (sb, ch, _) ->
            try {
                generate(alias, ch, sb); strongBox = sb; true
            } catch (e: Exception) {
                last = e
                runCatching { keyStore().deleteEntry(alias) }
                false
            }
        }
        if (!made) throw IllegalStateException("device key could not be created", last)
        val ks = keyStore()
        val chain = ks.getCertificateChain(alias)
        val leaf = chain?.firstOrNull() as? java.security.cert.X509Certificate
        val pub = (leaf?.publicKey ?: ks.getCertificate(alias).publicKey) as ECPublicKey
        return AttestedKey(
            alias = alias,
            publicKey = EcJwk.of(pub),
            chain = DeviceKeySpecs.encodeChain(chain).ifEmpty { DeviceKeySpecs.encodeChain(arrayOf(ks.getCertificate(alias))) },
            strongBox = strongBox,
            attested = leaf?.getExtensionValue(DeviceKeySpecs.ATTESTATION_OID) != null,
        )
    }

    private fun generate(alias: String, challenge: ByteArray?, strongBox: Boolean) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(DeviceKeySpecs.spec(alias, challenge, strongBox))
            generateKeyPair()
        }
    }

    override fun exists(alias: String): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    override fun sign(alias: String, data: ByteArray): String? = runCatching {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: return null
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }
        IntegrityCodec.derToRawB64url(der)
    }.getOrNull()

    override fun delete(alias: String) { runCatching { keyStore().deleteEntry(alias) } }

    override fun aliases(): List<String> =
        runCatching { keyStore().aliases().toList().filter { it.startsWith(DeviceKeySpecs.ALIAS_PREFIX) } }.getOrDefault(emptyList())

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

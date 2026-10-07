package com.aktcl.aron.core.system.support

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** What the support file carries (docs/17 s10.4): never the raw database, never a token or a password. */
data class SupportInput(
    val appVersion: String,
    val schemaVersion: Int,
    val userId: Long,
    val deviceUuid: String?,
    val lastSyncAt: String?,
    /** Rows per outbox state (pending, in_flight, acked, rejected, quarantined). */
    val counts: Map<String, Int>,
    /** Payloads of rows not yet accepted (pending, in flight, rejected, quarantined), exactly as stored: the recovery copy. */
    val unsentPayloads: List<String>,
    /** Payloads of accepted rows inside the re-sync window; dropped first when the file is too large. */
    val recentAckedPayloads: List<String>,
    /** The last log lines, already free of personal data (the local ring buffer, docs/24 s5.8). */
    val logLines: List<String>,
    val createdAt: String,
)

@Serializable
internal data class SupportFile(
    val format: Int = 1,
    @SerialName("app_version") val appVersion: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("user_id") val userId: Long,
    @SerialName("device_uuid") val deviceUuid: String?,
    @SerialName("last_sync_at") val lastSyncAt: String?,
    val counts: Map<String, Int>,
    val unsent: List<JsonElement>,
    @SerialName("recent_acked") val recentAcked: List<JsonElement>,
    @SerialName("recent_acked_dropped") val recentAckedDropped: Int,
    val log: List<String>,
    @SerialName("created_at") val createdAt: String,
)

class SupportFileTooLargeException(val bytes: Int, val limit: Int) : IllegalStateException("support file $bytes bytes over $limit even without optional parts")

/**
 * Builds the encrypted support file (F-SYS-021). JSON, gzip, then hybrid encryption to the AKTCL support public key:
 * a fresh AES-256-GCM key per file, wrapped with RSA-OAEP (SHA-256). Only support's private key opens it. Over the size
 * cap the optional parts go first (acked payloads, then old log lines); unsent payloads are never dropped.
 *
 * Layout: "ARONSUP1" | u16 wrapped-key length | wrapped key | 12-byte IV | AES-GCM ciphertext with its 16-byte tag.
 */
object SupportBundle {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val MAGIC = "ARONSUP1".toByteArray(Charsets.US_ASCII)

    /** docs/17 s10.4: at most the last 200 log lines go to support. */
    const val MAX_LOG_LINES = 200

    fun build(input: SupportInput, supportPublicKeySpkiBase64: String, maxBytes: Int, random: SecureRandom = SecureRandom()): ByteArray {
        var acked = input.recentAckedPayloads
        var log = input.logLines.takeLast(MAX_LOG_LINES)
        while (true) {
            val plain = gzip(json.encodeToString(SupportFile.serializer(), file(input, acked, log)).toByteArray())
            val sealed = encrypt(plain, supportPublicKeySpkiBase64, random)
            if (sealed.size <= maxBytes) return sealed
            when {
                acked.isNotEmpty() -> acked = acked.drop(maxOf(1, acked.size / 2)) // oldest first
                log.size > 20 -> log = log.takeLast(log.size / 2)
                else -> throw SupportFileTooLargeException(sealed.size, maxBytes)
            }
        }
    }

    private fun file(input: SupportInput, acked: List<String>, log: List<String>) = SupportFile(
        appVersion = input.appVersion, schemaVersion = input.schemaVersion, userId = input.userId, deviceUuid = input.deviceUuid,
        lastSyncAt = input.lastSyncAt, counts = input.counts,
        unsent = input.unsentPayloads.map(::parse), recentAcked = acked.map(::parse),
        recentAckedDropped = input.recentAckedPayloads.size - acked.size, log = log, createdAt = input.createdAt,
    )

    /** A stored payload is JSON; a damaged one is kept as a string rather than lost. */
    private fun parse(payload: String): JsonElement =
        runCatching { Json.parseToJsonElement(payload) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(payload) }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    fun encrypt(plain: ByteArray, spkiBase64: String, random: SecureRandom = SecureRandom()): ByteArray {
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64.trim())))
        val aes = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        val iv = ByteArray(12).also(random::nextBytes)
        val body = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv)) }.doFinal(plain)
        val wrap = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, pub, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }.doFinal(aes.encoded)
        return ByteBuffer.allocate(MAGIC.size + 2 + wrap.size + iv.size + body.size)
            .put(MAGIC).putShort(wrap.size.toShort()).put(wrap).put(iv).put(body).array()
    }
}

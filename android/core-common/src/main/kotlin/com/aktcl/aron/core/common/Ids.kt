package com.aktcl.aron.core.common

import java.util.UUID

/**
 * Device-originated ids (docs/24 s3.1 item 5, s3.3): lower-case UUID v4 strings minted on the phone when a row is
 * committed. Server ids are separate integers and never minted here.
 */
object ClientIds {
    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /** A new lower-case UUID v4 (java.util.UUID.randomUUID uses SecureRandom). */
    fun newUuid(): String = UUID.randomUUID().toString().lowercase()

    /**
     * A DETERMINISTIC id for a record whose identity is its subject (a gift photo of one unit, of one Astha assignment): the same [parts] always give the same id, so a retry, a kill and
     * relaunch or a second tap writes the same client uuid and the record is one (the server upserts by it). The contract and the capture layer require a v4-shaped uuid, so it is the first
     * 16 bytes of SHA-256 over a fixed namespace and the parts, with the version nibble set to 4 and the variant bits to 10 (still 122 bits of the hash); it is not a v5 uuid.
     */
    fun derivedUuid(vararg parts: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("aron.client-id.v1".toByteArray(Charsets.UTF_8))
        parts.forEach { md.update(0); md.update(it.toByteArray(Charsets.UTF_8)) }
        val b = md.digest().copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = b.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /** True for a lower-case UUID v4 as the contract's `Uuid` schema expects. */
    fun isUuidV4(value: String): Boolean = UUID_V4.matches(value)
}

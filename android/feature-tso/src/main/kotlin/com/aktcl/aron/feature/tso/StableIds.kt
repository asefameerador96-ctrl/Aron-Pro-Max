package com.aktcl.aron.feature.tso

import java.security.MessageDigest

/**
 * A stable client uuid for a name: the same name always gives the same id, and the id is a valid UUID version 4 (the
 * contract's `Uuid` schema and the server's ingest accept only version 4, so `UUID.nameUUIDFromBytes`, which makes a
 * version 3, is refused). Taken from the SHA-256 of the name with the version nibble set to 4 and the variant bits to 10.
 */
object StableIds {
    private val V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun v4(name: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    fun isV4(s: String) = V4.matches(s)
}

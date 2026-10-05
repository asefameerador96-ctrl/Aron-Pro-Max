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

    /** True for a lower-case UUID v4 as the contract's `Uuid` schema expects. */
    fun isUuidV4(value: String): Boolean = UUID_V4.matches(value)
}

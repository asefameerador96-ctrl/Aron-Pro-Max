package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Server-side reads of `cfg.*` keys (docs/24 s9). Phase-1 seam: [RegistryDefaults] answers with the registry
 * defaults of s9.5 until backend:config resolves scoped values from app.cfg_value (it implements this interface).
 */
interface ServerConfig {
    /** The global value of [key] now (server-side keys are global or role-level in Phase 1). */
    fun value(key: String): JsonElement

    /** Current global config_version (X-Config-Version on every response). */
    fun configVersion(): Long

    fun int(key: String): Int = value(key).jsonPrimitive.intOrNull ?: error("$key is not an int")
    fun bool(key: String): Boolean = value(key).jsonPrimitive.booleanOrNull ?: error("$key is not a bool")
    fun string(key: String): String = value(key).jsonPrimitive.content
}

/** The defaults of docs/24 s9.5 that the backend reads on Day 1, with environment overrides of s9.4 (dev database). */
class RegistryDefaults(env: ServerEnv = ServerEnv.PROD, private val overrides: Map<String, JsonElement> = emptyMap()) : ServerConfig {
    private val defaults: Map<String, JsonElement> = mapOf(
        "cfg.auth.access_ttl_min" to JsonPrimitive(60),
        "cfg.auth.access_ttl_jitter_min" to JsonPrimitive(10),
        "cfg.auth.refresh_ttl_days" to JsonPrimitive(30),
        "cfg.auth.refresh_absolute_days" to JsonPrimitive(90),
        "cfg.auth.refresh_absolute_jitter_days" to JsonPrimitive(15),
        "cfg.auth.refresh_grace_s" to JsonPrimitive(60),
        "cfg.auth.upload_grant_idle_days" to JsonPrimitive(7),
        "cfg.auth.lockout_attempts" to JsonPrimitive(10),
        "cfg.auth.lockout_window_min" to JsonPrimitive(15),
        "cfg.auth.lockout_min" to JsonPrimitive(15),
        "cfg.auth.lockout_key_mode" to JsonPrimitive("username_device_ipclass"),
        "cfg.auth.bind_otp_required" to JsonPrimitive(true),
        "cfg.auth.mfa_required_roles" to kotlinx.serialization.json.JsonArray(listOf("ADMIN", "SUPERADMIN", "SUPPORT").map(::JsonPrimitive)),
        "cfg.device.require_enrolled" to JsonPrimitive(env == ServerEnv.PROD),
        "cfg.memo.seq_block_size" to JsonPrimitive(500),
        "cfg.api.rl.device_per_min" to JsonPrimitive(120),
        "cfg.api.rl.user_per_min" to JsonPrimitive(300),
    )

    override fun value(key: String): JsonElement = overrides[key] ?: defaults[key] ?: error("unknown config key $key")

    override fun configVersion(): Long = 0
}

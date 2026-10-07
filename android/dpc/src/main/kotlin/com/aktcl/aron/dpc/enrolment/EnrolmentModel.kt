package com.aktcl.aron.dpc.enrolment

import com.aktcl.aron.dpc.policy.DevicePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The enrolment request is shared:contract's `EnrolDeviceRequest` (v1.2); its `public_key` and `device_info` are JSON,
// built from the two classes below. `EnrolDeviceResponse` has no shared DTO yet (REQUEST:
// docs/requests/android-core-contract-dtos.md); EnrolmentTest checks its members against contract/openapi.yaml.

@Serializable
data class JwkEcPublic(val kty: String = "EC", val crv: String = "P-256", val x: String, val y: String)

@Serializable
data class DeviceInfoDto(
    val manufacturer: String,
    val model: String,
    @SerialName("os_api_level") val osApiLevel: Int,
    @SerialName("os_version") val osVersion: String,
    @SerialName("security_patch") val securityPatch: String?,
    val abi: String,
    @SerialName("ram_mb") val ramMb: Int,
    @SerialName("storage_total_mb") val storageTotalMb: Int?,
)

@Serializable
data class EnrolDeviceResponse(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("device_uuid") val deviceUuid: String,
    @SerialName("enrolled_at") val enrolledAt: String,
    @SerialName("lockdown_level") val lockdownLevel: String,
    @SerialName("trust_level") val trustLevel: String,
    val policy: DevicePolicy,
    @SerialName("server_time") val serverTime: String,
)

/** The `aron.*` admin extras of the provisioning QR (docs/24 s10.4 step 2), validated. */
data class EnrolmentExtras(
    val enrolmentToken: String,
    val apiBaseUrl: String,
    val env: String,
    val flavour: String,
    val lockdownLevel: String,
    val zoneCode: String?,
) {
    override fun toString() = "EnrolmentExtras(api=$apiBaseUrl, env=$env, flavour=$flavour, lockdown=$lockdownLevel, zone=$zoneCode, token=<redacted>)"

    companion object {
        const val KEY_TOKEN = "aron.enrolment_token"
        const val KEY_API = "aron.api_base_url"
        const val KEY_ENV = "aron.env"
        const val KEY_FLAVOUR = "aron.flavour"
        const val KEY_LOCKDOWN = "aron.lockdown_level"
        const val KEY_ZONE = "aron.zone_code"

        private val TOKEN = Regex("^[A-Za-z0-9_-]{43,64}$")
        private val FLAVOURS = setOf("sr", "amo", "tso")
        private val ENVS = setOf("dev", "staging", "prod")
        private val ZONE = Regex("^[A-Za-z0-9_-]{1,40}$")

        /**
         * Validates the extras for this app ([ownPackage] = `com.aktcl.aron.<flavour>`); returns null with the reason in
         * [onInvalid] when anything is missing or wrong, so a QR for another flavour or with an http URL is refused.
         */
        fun parse(get: (String) -> String?, ownPackage: String, onInvalid: (String) -> Unit = {}): EnrolmentExtras? {
            fun bad(why: String): EnrolmentExtras? { onInvalid(why); return null }
            // No value may carry control characters (they would corrupt the stored state).
            listOf(KEY_TOKEN, KEY_API, KEY_ENV, KEY_FLAVOUR, KEY_LOCKDOWN, KEY_ZONE).forEach { k ->
                if (get(k)?.any { it.isISOControl() } == true) return bad("control_character")
            }
            val token = get(KEY_TOKEN)?.trim() ?: return bad("token_missing")
            if (!TOKEN.matches(token)) return bad("token_malformed")
            val api = get(KEY_API)?.trim()?.trimEnd('/') ?: return bad("api_missing")
            val uri = runCatching { java.net.URI(api) }.getOrNull() ?: return bad("api_malformed")
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || (uri.path ?: "").isNotEmpty() || uri.query != null ||
                uri.fragment != null || uri.userInfo != null) return bad("api_not_https_origin")
            val flavour = get(KEY_FLAVOUR)?.trim() ?: return bad("flavour_missing")
            if (flavour !in FLAVOURS || ownPackage != "com.aktcl.aron.$flavour") return bad("flavour_mismatch")
            val lockdown = get(KEY_LOCKDOWN)?.trim() ?: return bad("lockdown_missing")
            if (lockdown !in setOf("dev", "prod")) return bad("lockdown_invalid")
            val env = get(KEY_ENV)?.trim().orEmpty().ifEmpty { return bad("env_missing") }
            if (env !in ENVS) return bad("env_invalid")
            val zone = get(KEY_ZONE)?.trim()?.ifEmpty { null }
            if (zone != null && !ZONE.matches(zone)) return bad("zone_invalid")
            return EnrolmentExtras(token, api, env, flavour, lockdown, zone)
        }
    }
}

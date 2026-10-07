package com.aktcl.aron.backend.analytics.devices

import com.aktcl.aron.backend.platform.ServerConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Renders the `DevicePolicy` of docs/24 s10.2 from the resolved `cfg.device.*` and `cfg.geo.*` values and the lockdown level of the phone. `policy_version` is the
 * config version at render; the phone applies it offline and re-fetches when a config delta says `policy_changed`. A key the config does not carry yet falls back to
 * the registry default of s9.5 (which is also what the table of s10.2 shows).
 */
class DevicePolicyRenderer(private val config: ServerConfig, private val now: () -> Instant = Instant::now) {
    private fun el(key: String): JsonElement? = runCatching { config.value(key) }.getOrNull()
    private fun bool(key: String, d: Boolean) = el(key)?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: d
    private fun int(key: String, d: Int) = el(key)?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: d
    private fun str(key: String, d: String) = el(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: d
    private fun list(key: String, d: List<String>): List<String> = el(key)?.let { runCatching { it.jsonArray.map { x -> x.jsonPrimitive.content } }.getOrNull() } ?: d

    fun policyVersion(): Long = config.configVersion()

    fun render(lockdown: String): JsonObject {
        val prod = lockdown == "prod"
        return buildJsonObject {
            put("policy_version", policyVersion()); put("lockdown_level", lockdown); put("generated_at", now().toString().let { if (it.length == 20) it.dropLast(1) + ".000Z" else it })
            put("user_restrictions", buildJsonObject {
                put("no_debugging_features", prod); put("no_install_unknown_sources", prod); put("no_install_apps", prod); put("no_factory_reset", prod); put("no_safe_boot", prod)
                put("no_add_user", prod); put("no_config_date_time", prod); put("no_usb_file_transfer", prod); put("no_config_location", prod)
            })
            put("global_settings", buildJsonObject { put("auto_time_required", true); put("adb_enabled", !prod); put("location_mode_high_accuracy", true) })
            put("self_protection", buildJsonObject { put("uninstall_blocked", prod); put("user_control_disabled", prod); put("battery_optimisation_exempt", bool("cfg.device.battery_exemption", true)) })
            put("permission_grants", buildJsonArray {
                fun g(p: String, state: String, minApi: Int) = add(buildJsonObject { put("permission", p); put("state", state); put("min_api", minApi) })
                g("android.permission.ACCESS_FINE_LOCATION", "granted", 26); g("android.permission.ACCESS_COARSE_LOCATION", "granted", 26); g("android.permission.CAMERA", "granted", 26)
                g("android.permission.BLUETOOTH_CONNECT", "granted", 31); g("android.permission.BLUETOOTH_SCAN", "granted", 31); g("android.permission.POST_NOTIFICATIONS", "granted", 33)
                g("android.permission.RECORD_AUDIO", "denied", 26)
                if (bool("cfg.geo.breadcrumbs_enabled", false)) g("android.permission.ACCESS_BACKGROUND_LOCATION", "granted", 29)
            })
            put("app_control", buildJsonObject {
                put("mode", str("cfg.device.app_control_mode", "blocklist"))
                put("blocked_packages", strings(list("cfg.device.blocked_packages", DEFAULT_BLOCKED)))
                put("allowed_packages", strings(list("cfg.device.allowed_packages", emptyList())))
                put("always_allowed_packages", strings(list("cfg.device.always_allowed_packages", DEFAULT_ALWAYS_ALLOWED)))
            })
            put("schedule", buildJsonObject {
                put("enabled", bool("cfg.device.blocking_enabled", true)); put("starts_on", "check_in"); put("ends_on", "check_out")
                put("hard_end_time", str("cfg.device.blocking_hard_end_time", "20:00")); put("working_days_only", bool("cfg.device.blocking_working_days_only", true))
            })
            put("location", buildJsonObject {
                put("require_precise", true); put("require_location_on", true); put("breadcrumbs_enabled", bool("cfg.geo.breadcrumbs_enabled", false)); put("breadcrumb_interval_min", int("cfg.geo.breadcrumb_interval_min", 30))
            })
            put("status_report", buildJsonObject {
                put("on_events", strings(listOf("enrolment", "policy_applied", "check_in", "check_out", "boot", "integrity_change", "app_update"))); put("min_interval_min", int("cfg.device.status_min_interval_min", 60))
            })
            put("integrity", buildJsonObject {
                put("play_integrity_required", prod && bool("cfg.device.require_integrity", true)); put("refresh_h", int("cfg.device.integrity_refresh_h", 24)); put("key_attestation_required", bool("cfg.device.key_attestation_required", true))
            })
        }
    }

    private fun strings(l: List<String>): JsonArray = JsonArray(l.map { JsonPrimitive(it) })

    companion object {
        val DEFAULT_BLOCKED = listOf("com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.google.android.youtube", "com.snapchat.android", "com.dts.freefireth", "com.tencent.ig")
        val DEFAULT_ALWAYS_ALLOWED = listOf("com.whatsapp", "com.facebook.orca", "com.google.android.dialer", "com.samsung.android.dialer", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.apps.maps", "com.android.settings", "com.sec.android.app.camera", "com.google.android.inputmethod.latin", "com.sec.android.app.launcher")
    }
}

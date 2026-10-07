package com.aktcl.aron.dpc.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Contract `DevicePolicy` (docs/24 s10.2). REQUEST: docs/requests/android-core-contract-dtos.md (DTOs in shared:contract);
// DevicePolicyContractTest checks every member against contract/openapi.yaml until then.

@Serializable
data class DevicePolicy(
    @SerialName("policy_version") val policyVersion: Long,
    @SerialName("lockdown_level") val lockdownLevel: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("user_restrictions") val userRestrictions: UserRestrictions,
    @SerialName("global_settings") val globalSettings: GlobalSettings,
    @SerialName("self_protection") val selfProtection: SelfProtection,
    @SerialName("permission_grants") val permissionGrants: List<PermissionGrant>,
    @SerialName("app_control") val appControl: AppControl,
    val schedule: BlockingSchedule,
    val location: LocationPolicy,
    @SerialName("status_report") val statusReport: StatusReportPolicy,
    val integrity: IntegrityPolicy,
) {
    val isProd: Boolean get() = lockdownLevel == "prod"

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
        fun parse(text: String): DevicePolicy = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}

@Serializable
data class UserRestrictions(
    @SerialName("no_debugging_features") val noDebuggingFeatures: Boolean,
    @SerialName("no_install_unknown_sources") val noInstallUnknownSources: Boolean,
    @SerialName("no_install_apps") val noInstallApps: Boolean,
    @SerialName("no_factory_reset") val noFactoryReset: Boolean,
    @SerialName("no_safe_boot") val noSafeBoot: Boolean,
    @SerialName("no_add_user") val noAddUser: Boolean,
    @SerialName("no_config_date_time") val noConfigDateTime: Boolean,
    @SerialName("no_usb_file_transfer") val noUsbFileTransfer: Boolean,
    @SerialName("no_config_location") val noConfigLocation: Boolean,
) {
    /** Contract key to value, in contract order. */
    fun asMap(): Map<String, Boolean> = linkedMapOf(
        "no_debugging_features" to noDebuggingFeatures, "no_install_unknown_sources" to noInstallUnknownSources,
        "no_install_apps" to noInstallApps, "no_factory_reset" to noFactoryReset, "no_safe_boot" to noSafeBoot,
        "no_add_user" to noAddUser, "no_config_date_time" to noConfigDateTime, "no_usb_file_transfer" to noUsbFileTransfer,
        "no_config_location" to noConfigLocation,
    )
}

@Serializable
data class GlobalSettings(
    @SerialName("auto_time_required") val autoTimeRequired: Boolean,
    @SerialName("adb_enabled") val adbEnabled: Boolean,
    @SerialName("location_mode_high_accuracy") val locationModeHighAccuracy: Boolean,
)

@Serializable
data class SelfProtection(
    @SerialName("uninstall_blocked") val uninstallBlocked: Boolean,
    @SerialName("user_control_disabled") val userControlDisabled: Boolean,
    @SerialName("battery_optimisation_exempt") val batteryOptimisationExempt: Boolean,
)

@Serializable
data class PermissionGrant(val permission: String, val state: String, @SerialName("min_api") val minApi: Int)

@Serializable
data class AppControl(
    val mode: String,
    @SerialName("blocked_packages") val blockedPackages: List<String>,
    @SerialName("allowed_packages") val allowedPackages: List<String>,
    @SerialName("always_allowed_packages") val alwaysAllowedPackages: List<String>,
)

@Serializable
data class BlockingSchedule(
    val enabled: Boolean,
    @SerialName("starts_on") val startsOn: String,
    @SerialName("ends_on") val endsOn: String,
    /** Dhaka `HH:mm`, or null: only a check-out releases. */
    @SerialName("hard_end_time") val hardEndTime: String?,
    @SerialName("working_days_only") val workingDaysOnly: Boolean,
)

@Serializable
data class LocationPolicy(
    @SerialName("require_precise") val requirePrecise: Boolean,
    @SerialName("require_location_on") val requireLocationOn: Boolean,
    @SerialName("breadcrumbs_enabled") val breadcrumbsEnabled: Boolean,
    @SerialName("breadcrumb_interval_min") val breadcrumbIntervalMin: Int,
)

@Serializable
data class StatusReportPolicy(@SerialName("on_events") val onEvents: List<String>, @SerialName("min_interval_min") val minIntervalMin: Int)

@Serializable
data class IntegrityPolicy(
    @SerialName("play_integrity_required") val playIntegrityRequired: Boolean,
    @SerialName("refresh_h") val refreshH: Int,
    @SerialName("key_attestation_required") val keyAttestationRequired: Boolean,
)

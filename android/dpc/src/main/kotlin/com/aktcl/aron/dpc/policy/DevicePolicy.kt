package com.aktcl.aron.dpc.policy

import kotlinx.serialization.json.Json

// Contract `DevicePolicy` (docs/24 s10.2) is shared:contract's generated DTO (v1.2); the names below keep the DPC's code
// readable. DevicePolicyContractTest still checks the members against contract/openapi.yaml.

typealias DevicePolicy = com.aktcl.aron.contract.DevicePolicy
typealias UserRestrictions = com.aktcl.aron.contract.DevicePolicyUserRestrictions
typealias PermissionGrant = com.aktcl.aron.contract.PermissionGrant

/** Parsing and encoding of the stored policy. */
object DevicePolicies {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    fun parse(text: String): DevicePolicy = json.decodeFromString(DevicePolicy.serializer(), text)
    fun encode(p: DevicePolicy): String = json.encodeToString(DevicePolicy.serializer(), p)
}

val DevicePolicy.isProd: Boolean get() = lockdownLevel == "prod"

fun DevicePolicy.encode(): String = DevicePolicies.encode(this)

/** Contract key to value, in contract order. */
fun UserRestrictions.asMap(): Map<String, Boolean> = linkedMapOf(
    "no_debugging_features" to noDebuggingFeatures, "no_install_unknown_sources" to noInstallUnknownSources,
    "no_install_apps" to noInstallApps, "no_factory_reset" to noFactoryReset, "no_safe_boot" to noSafeBoot,
    "no_add_user" to noAddUser, "no_config_date_time" to noConfigDateTime, "no_usb_file_transfer" to noUsbFileTransfer,
    "no_config_location" to noConfigLocation,
)

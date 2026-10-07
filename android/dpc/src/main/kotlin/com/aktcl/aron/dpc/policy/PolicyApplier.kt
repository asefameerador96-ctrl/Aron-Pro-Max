package com.aktcl.aron.dpc.policy

/** What one application of the policy did; feeds the status report (docs/24 s10.3). */
data class ApplyReport(
    val policyVersion: Long,
    val lockdownLevel: String,
    val deviceOwner: Boolean,
    /** Contract restriction key to the state read back after applying. */
    val restrictionsApplied: Map<String, Boolean>,
    /** Short stable codes, e.g. `restriction:no_safe_boot`, `permission:android.permission.CAMERA`. */
    val errors: List<String>,
    val batteryOptimisationExempt: Boolean,
    val uninstallBlocked: Boolean,
)

/**
 * Applies a [DevicePolicy] as device owner (docs/24 s10.2): user restrictions, global settings, self-protection and
 * permission grants. Idempotent: applying the same policy twice changes nothing. One failing call never stops the
 * others; it is reported. Nothing here touches the network. App blocking (N-032) is separate.
 */
class PolicyApplier(private val gw: DpmGateway) {

    /** [installingOwnUpdate]: the managed update (N-034) is running, so `no_install_apps` stays lifted (D24-36). */
    fun apply(policy: DevicePolicy, installingOwnUpdate: Boolean = false): ApplyReport {
        if (!gw.isDeviceOwner()) {
            return ApplyReport(policy.policyVersion, policy.lockdownLevel, false, emptyMap(), listOf(NOT_DEVICE_OWNER), false, false)
        }
        val errors = mutableListOf<String>()
        fun attempt(code: String, block: () -> Unit) {
            try { block() } catch (e: Exception) { errors += code }
        }

        // 1. Restrictions. Debugging-features off is what stops developer options, USB debugging and the mock-location
        //    app selection on a prod phone.
        for ((key, on) in policy.userRestrictions.asMap()) {
            val wanted = if (key == "no_install_apps" && installingOwnUpdate) false else on
            for (androidKey in androidKeys(key)) attempt("restriction:$key") { gw.setRestriction(androidKey, wanted) }
        }

        // 2. Global settings.
        if (!policy.globalSettings.adbEnabled) attempt("global:adb_enabled") { gw.setGlobalSetting(ADB_ENABLED, "0") }
        if (policy.globalSettings.autoTimeRequired) attempt("global:auto_time_required") { gw.setAutoTimeRequired(true) }
        if (policy.globalSettings.locationModeHighAccuracy || policy.location.requireLocationOn) {
            attempt("global:location_on") { gw.setLocationEnabled(true) }
        }

        // 3. Self-protection.
        attempt("self:uninstall_blocked") { gw.setUninstallBlocked(policy.selfProtection.uninstallBlocked) }
        if (gw.sdkInt >= 30) attempt("self:user_control_disabled") { gw.setUserControlDisabled(policy.selfProtection.userControlDisabled) }
        val exempt = runCatching { gw.isIgnoringBatteryOptimizations() }.getOrDefault(false)
        if (policy.selfProtection.batteryOptimisationExempt && !exempt) errors += BATTERY_NOT_EXEMPT

        // 4. Permissions: granted and pinned in prod, granted then released to the user in dev; denied is pinned in both.
        //    Background location follows the breadcrumb switch, whatever the list says.
        val grants = policy.permissionGrants.filter { it.minApi <= gw.sdkInt && it.permission != BACKGROUND_LOCATION }
            .map { it.permission to it.state } +
            listOf(BACKGROUND_LOCATION to if (policy.location.breadcrumbsEnabled) "granted" else "denied").filter { gw.sdkInt >= 29 }
        for ((permission, state) in grants) {
            if (!gw.requestsPermission(permission)) continue // not declared by this app: nothing to grant
            attempt("permission:$permission") {
                val ok = when (state) {
                    "granted" -> gw.setPermissionGrantState(permission, DpmGateway.GRANT_GRANTED) &&
                        (policy.isProd || gw.setPermissionGrantState(permission, DpmGateway.GRANT_DEFAULT))
                    "denied" -> gw.setPermissionGrantState(permission, DpmGateway.GRANT_DENIED)
                    else -> gw.setPermissionGrantState(permission, DpmGateway.GRANT_DEFAULT)
                }
                check(ok)
            }
        }

        val present = runCatching { gw.restrictions() }.getOrDefault(emptySet())
        val applied = policy.userRestrictions.asMap().keys.associateWith { key -> androidKeys(key).first() in present }
        for ((key, want) in policy.userRestrictions.asMap()) {
            val expected = if (key == "no_install_apps" && installingOwnUpdate) false else want
            if (applied[key] != expected && "restriction:$key" !in errors) errors += "restriction:$key"
        }
        return ApplyReport(
            policyVersion = policy.policyVersion,
            lockdownLevel = policy.lockdownLevel,
            deviceOwner = true,
            restrictionsApplied = applied,
            errors = errors.distinct(),
            batteryOptimisationExempt = exempt,
            uninstallBlocked = runCatching { gw.isUninstallBlocked() }.getOrDefault(false),
        )
    }

    /** The UserManager keys behind a contract restriction (two for unknown sources on API 29+). */
    fun androidKeys(contractKey: String): List<String> = when (contractKey) {
        "no_debugging_features" -> listOf("no_debugging_features")
        "no_install_unknown_sources" -> listOfNotNull("no_install_unknown_sources", "no_install_unknown_sources_globally".takeIf { gw.sdkInt >= 29 })
        "no_install_apps" -> listOf("no_install_apps")
        "no_factory_reset" -> listOf("no_factory_reset")
        "no_safe_boot" -> listOf("no_safe_boot")
        "no_add_user" -> listOf("no_add_user")
        "no_config_date_time" -> listOf("no_config_date_time")
        "no_usb_file_transfer" -> listOf("no_usb_file_transfer")
        "no_config_location" -> listOf("no_config_location")
        else -> error("unknown restriction $contractKey")
    }

    companion object {
        const val NOT_DEVICE_OWNER = "not_device_owner"
        const val BATTERY_NOT_EXEMPT = "self:battery_optimisation_exempt"
        const val ADB_ENABLED = "adb_enabled"
        const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    }
}

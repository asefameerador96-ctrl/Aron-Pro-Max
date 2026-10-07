package com.aktcl.aron.dpc.policy

/**
 * The device-owner calls the policy engine needs, so the rules are tested without a phone. The Android implementation
 * ([AndroidDpmGateway]) wraps DevicePolicyManager; every call may throw (OEM quirks) and the applier records the error.
 */
interface DpmGateway {
    val sdkInt: Int
    val ownPackage: String
    fun isDeviceOwner(): Boolean
    fun setRestriction(androidKey: String, on: Boolean)
    fun restrictions(): Set<String>
    fun setGlobalSetting(name: String, value: String)
    fun setAutoTimeRequired(required: Boolean)
    fun setLocationEnabled(enabled: Boolean)
    fun setUninstallBlocked(blocked: Boolean)
    fun isUninstallBlocked(): Boolean
    fun setUserControlDisabled(disabled: Boolean)
    fun isIgnoringBatteryOptimizations(): Boolean
    fun requestsPermission(permission: String): Boolean
    /** [state] is one of [GRANT_GRANTED], [GRANT_DENIED], [GRANT_DEFAULT]; returns false when Android refused. */
    fun setPermissionGrantState(permission: String, state: Int): Boolean
    fun permissionGrantState(permission: String): Int

    companion object {
        // Values of DevicePolicyManager.PERMISSION_GRANT_STATE_*.
        const val GRANT_DEFAULT = 0
        const val GRANT_GRANTED = 1
        const val GRANT_DENIED = 2
    }
}

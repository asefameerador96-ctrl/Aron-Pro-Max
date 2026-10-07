package com.aktcl.aron.dpc.policy

/** In-memory device owner with the behaviour of DevicePolicyManager that the applier relies on. */
class FakeGateway(
    override var sdkInt: Int = 34,
    var owner: Boolean = true,
    var declared: Set<String> = setOf(
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.CAMERA",
        "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.POST_NOTIFICATIONS",
    ),
) : DpmGateway {
    override val ownPackage = "com.aktcl.aron.sr"
    val restrictionSet = linkedSetOf<String>()
    val globals = mutableMapOf<String, String>()
    var autoTime = false
    var locationOn = false
    var uninstallBlockedNow = false
    var userControlOff = false
    var batteryExempt = true
    val grants = mutableMapOf<String, Int>()
    val calls = mutableListOf<String>()
    val failing = mutableSetOf<String>()

    private fun call(name: String) { calls += name; if (name in failing) throw SecurityException(name) }

    override fun isDeviceOwner() = owner
    override fun setRestriction(androidKey: String, on: Boolean) { call("r:$androidKey"); if (on) restrictionSet += androidKey else restrictionSet -= androidKey }
    override fun restrictions(): Set<String> = restrictionSet.toSet()
    override fun setGlobalSetting(name: String, value: String) { call("g:$name"); globals[name] = value }
    override fun setAutoTimeRequired(required: Boolean) { call("autotime"); autoTime = required }
    override fun setLocationEnabled(enabled: Boolean) { call("location"); locationOn = enabled }
    override fun setUninstallBlocked(blocked: Boolean) { call("uninstall"); uninstallBlockedNow = blocked }
    override fun isUninstallBlocked() = uninstallBlockedNow
    override fun setUserControlDisabled(disabled: Boolean) { call("usercontrol"); userControlOff = disabled }
    override fun isIgnoringBatteryOptimizations() = batteryExempt
    override fun requestsPermission(permission: String) = permission in declared
    override fun setPermissionGrantState(permission: String, state: Int): Boolean {
        call("p:$permission:$state")
        grants[permission] = state
        return true
    }
    override fun permissionGrantState(permission: String) = grants[permission] ?: DpmGateway.GRANT_DEFAULT
}

fun policyFixture(): DevicePolicy =
    DevicePolicy.parse(FakeGateway::class.java.getResource("/policy-prod.json")!!.readText())

/** The dev variant of docs/24 s10.2's table. */
fun DevicePolicy.asDev(): DevicePolicy = copy(
    lockdownLevel = "dev",
    userRestrictions = userRestrictions.copy(
        noDebuggingFeatures = false, noInstallUnknownSources = false, noInstallApps = false, noFactoryReset = false,
        noSafeBoot = false, noAddUser = false, noConfigDateTime = false,
    ),
    globalSettings = globalSettings.copy(adbEnabled = true),
    selfProtection = selfProtection.copy(uninstallBlocked = false, userControlDisabled = false),
)

package com.aktcl.aron.core.system.integrity

/**
 * Which of the developer settings are on: Developer options, USB debugging, and wireless debugging (`adb_wifi_enabled`,
 * Android 11+). A rep with an on-phone adb client could switch the first two off with `settings put` while wireless
 * debugging stays active, so all three are gate flags.
 */
data class DevFlags(val devOptions: Boolean, val adb: Boolean, val adbWifi: Boolean = false) {
    val any: Boolean get() = devOptions || adb || adbWifi

    /** Stable wire/log codes of the flags that are on. */
    val codes: List<String> get() = listOfNotNull("dev_options".takeIf { devOptions }, "adb".takeIf { adb }, "adb_wifi".takeIf { adbWifi })
}

/** What the gate decides on one check. [flags] are the settings that are on; [exempt] says why a gated phone still runs. */
data class GateState(val flags: DevFlags, val exempt: Exemption) {
    /** The blocking screen is shown. */
    val blocked: Boolean get() = flags.any && exempt == Exemption.NONE
}

enum class Exemption { NONE, DEBUG_BUILD, BENCHMARK_BUILD, LAB }

/**
 * The one rule (D-OWNER-MDM-02): while Developer options or USB debugging is on, the app does not run. Nothing here
 * persists an "ok": the state is derived from the settings on every check, so a restart, a deep link or a clock change
 * cannot open the app. Exemptions: a debug build (lab devices, CI), a benchmark build (speed and battery gates), and a live lab exemption once its server side exists.
 */
object DevOptionsPolicy {
    /** [buildExemption]: [Exemption.DEBUG_BUILD] or [Exemption.BENCHMARK_BUILD] for those variants, null for release. */
    fun decide(flags: DevFlags, buildExemption: Exemption?, labExemptionActive: Boolean): GateState = GateState(
        flags,
        when {
            !flags.any -> Exemption.NONE
            buildExemption != null -> buildExemption
            labExemptionActive -> Exemption.LAB
            else -> Exemption.NONE
        },
    )
}

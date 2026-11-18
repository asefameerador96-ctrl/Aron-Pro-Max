package com.aktcl.aron.dpc.policy

import kotlinx.serialization.json.Json

// Contract `DevicePolicy` is still part of the enrolment response (the server keeps sending it; the phone ignores it since
// D-OWNER-MDM-02, nothing applies it). Kept only so the response parses.

typealias DevicePolicy = com.aktcl.aron.contract.DevicePolicy
typealias UserRestrictions = com.aktcl.aron.contract.DevicePolicyUserRestrictions
typealias PermissionGrant = com.aktcl.aron.contract.PermissionGrant

/** Parsing and encoding of the stored policy. */
object DevicePolicies {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    fun parse(text: String): DevicePolicy = json.decodeFromString(DevicePolicy.serializer(), text)
    fun encode(p: DevicePolicy): String = json.encodeToString(DevicePolicy.serializer(), p)
}

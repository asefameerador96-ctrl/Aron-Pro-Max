package com.aktcl.aron.backend.analytics.mdm

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Contract 1.4.0, tag admin-mdm (docs/34 s5). Members are snake_case like the contract; unknown members are refused by receiveStrict.

@Serializable
data class MdmDto(
    val device_id: Long, val device_uuid: String, val flavour: String, val status: String, val device_owner: Boolean, val users: List<String>, val model: String? = null,
    val app_version: String? = null, val latest_app_version: String? = null, val last_contact_at: String? = null, val battery_pct: Int? = null, val network: String? = null,
    val policy_id: Long? = null, val policy_name: String? = null, val compliance_flags: List<String>, val pending_commands: Int,
)

@Serializable
data class MdmDevicePage(val items: List<MdmDto>, val next_cursor: String? = null)

@Serializable
data class MdmDeviceDetail(
    val device: MdmDto, val enrolled_at: String, val trust_level: String, val integrity_verdict: String, val lockdown_level: String? = null, val zone_id: Long? = null,
    val last_report: kotlinx.serialization.json.JsonElement? = null, val policy: MdmPolicy? = null, val recent_commands: List<MdmCommand>,
)

@Serializable
data class MdmActivityEntry(val at: String, val kind: String, val device_id: Long? = null, val summary: String, val actor: String? = null, val reason: String? = null, val ref: String? = null)

@Serializable
data class MdmActivityPage(val items: List<MdmActivityEntry>, val next_cursor: String? = null)

@Serializable
data class MdmAlert(val device_id: Long, val device_uuid: String? = null, val users: List<String> = emptyList(), val flavour: String? = null, val flag: String, val since: String, val detail: String? = null)

@Serializable
data class MdmAlertPage(val items: List<MdmAlert>, val next_cursor: String? = null)

@Serializable
data class MdmBlockWindow(val days: List<Int>, val start: String, val end: String, val packages: List<String> = emptyList())

@Serializable
data class MdmKiosk(val enabled: Boolean, val packages: List<String> = emptyList())

@Serializable
data class MdmLocationSampling(val mode: String, val interval_min: Int? = null)

/** `MdmAllowlistSchedule` (contract 1.9 queue row 6, docs/requests/contract-v1.9-shapes-mdm.md): `calendar` or `window`, Dhaka, end > start. */
@Serializable
data class MdmAllowlistSchedule(val mode: String, val days: List<Int> = emptyList(), val start: String, val end: String)

/** `MdmWorkHoursAllowlist` (D-NIGHT-MDM-ALLOWLIST-01): during working hours every launcher app outside [packages] is suspended. */
@Serializable
data class MdmWorkHoursAllowlist(val enabled: Boolean, val packages: List<String>, val schedule: MdmAllowlistSchedule, val grace_min: Int = 5)

// work_hours_allowlist is optional and left out when absent, so a policy without it is byte-for-byte the 1.8 shape.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MdmPolicyWrite(
    val name: String, val description: String? = null, val allowed_packages: List<String>, val block_windows: List<MdmBlockWindow>, val kiosk: MdmKiosk,
    val location_sampling: MdmLocationSampling, val version: Int? = null, val reason: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val work_hours_allowlist: MdmWorkHoursAllowlist? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MdmPolicy(
    val policy_id: Long, val name: String, val description: String? = null, val allowed_packages: List<String>, val block_windows: List<MdmBlockWindow>, val kiosk: MdmKiosk,
    val location_sampling: MdmLocationSampling, val version: Int, val device_count: Int, val updated_at: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val work_hours_allowlist: MdmWorkHoursAllowlist? = null,
)

@Serializable
data class MdmPolicyPage(val items: List<MdmPolicy>, val next_cursor: String? = null)

@Serializable
data class MdmPolicyAssignRequest(val policy_id: Long?, val reason: String)

@Serializable
data class MdmCommandCreateRequest(val command_id: String, val type: String, val params: JsonObject? = null, val reason: String, val ttl_h: Int = 24)

@Serializable
data class MdmCommandDecisionRequest(val decision: String, val reason: String)

@Serializable
data class MdmCommand(
    val command_id: String, val device_id: Long, val type: String, val params: JsonObject? = null, val state: String, val reason: String, val created_at: String,
    val created_by: String? = null, val approved_by: String? = null, val approved_at: String? = null, val sent_at: String? = null, val acked_at: String? = null,
    val expires_at: String, val result: JsonObject? = null,
)

@Serializable
data class MdmCommandPage(val items: List<MdmCommand>, val next_cursor: String? = null)

@Serializable
data class DeviceCommand(val command_id: String, val type: String, val params: JsonObject? = null, val created_at: String, val expires_at: String, val sig: String)

@Serializable
data class DeviceCommandList(val items: List<DeviceCommand>)

@Serializable
data class DeviceCommandAck(val state: String, val result: JsonObject? = null)

@Serializable
data class DeviceCommandAckResult(val command_id: String, val state: String)

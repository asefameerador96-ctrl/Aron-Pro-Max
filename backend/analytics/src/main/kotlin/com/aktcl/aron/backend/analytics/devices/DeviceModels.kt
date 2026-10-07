package com.aktcl.aron.backend.analytics.devices

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class DeviceInfoDto(
    val manufacturer: String, val model: String, val os_api_level: Int, val os_version: String, val security_patch: String? = null, val abi: String, val ram_mb: Int, val storage_total_mb: Int? = null,
)

@Serializable
data class JwkEcPublicDevice(val kty: String, val crv: String, val x: String, val y: String)

@Serializable
data class PlayIntegrityEvidence(val token: String, val nonce: String)

@Serializable
data class PlayIntegrityUnavailable(val reason: String, val detail: String? = null)

@Serializable
data class PolicyApplyError(val item: String, val error: String)

@Serializable
data class PrinterInfo(val bonded_name: String? = null, val mac_sha256: String? = null)

/** Contract `DeviceStatusReport` (docs/24 s10.3); unknown members are refused like everywhere else. */
@Serializable
data class DeviceStatusReportDto(
    val reported_at: String, val trigger: String? = null, val app_version: String, val device_info: DeviceInfoDto, val device_owner: Boolean, val lockdown_level_applied: String,
    val policy_version_applied: Long?, val policy_apply_errors: List<PolicyApplyError> = emptyList(), val restrictions_applied: Map<String, Boolean> = emptyMap(), val blocking_active: Boolean,
    val blocking_since: String? = null, val suspended_packages: List<String> = emptyList(), val permissions: Map<String, String> = emptyMap(), val battery_optimisation_ignored: Boolean? = null,
    val user_control_disabled: Boolean? = null, val location_enabled: Boolean, val location_mode: String? = null, val dev_options_enabled: Boolean, val adb_enabled: Boolean,
    val auto_time_enabled: Boolean, val time_zone: String? = null, val mock_location_apps: List<String>, val play_services_version: Long? = null, val play_integrity: PlayIntegrityEvidence? = null,
    val play_integrity_unavailable: PlayIntegrityUnavailable? = null, val root_hints: List<String> = emptyList(), val pending_rows: Int, val pending_media: Int? = null,
    val last_sync_at: String? = null, val battery_pct: Int, val charging: Boolean? = null, val free_storage_mb: Int? = null, val printer: PrinterInfo? = null,
)

@Serializable
data class EnrolDeviceRequest(
    val enrolment_token: String, val device_uuid: String, val app_package: String, val app_version: String, val app_signing_cert_sha256: String, val device_owner: Boolean,
    val public_key: JwkEcPublicDevice, val key_attestation_chain: List<String>, val device_info: DeviceInfoDto, val status: DeviceStatusReportDto? = null,
    val play_integrity_unavailable: PlayIntegrityUnavailable? = null,
)

@Serializable
data class EnrolDeviceResponse(val device_id: Long, val device_uuid: String, val enrolled_at: String, val lockdown_level: String, val trust_level: String, val policy: JsonObject, val server_time: String)

@Serializable
data class DeviceNonce(val nonce: String, val expires_at: String)

@Serializable
data class DeviceTrustDto(val enrolled: Boolean, val device_owner_verified: Boolean, val integrity_verdict: String, val integrity_checked_at: String? = null, val trust_level: String)

@Serializable
data class DirectiveDto(val directive_id: String, val type: String, val params: JsonObject? = null, val created_at: String, val expires_at: String, val acked_at: String? = null, val sig: String)

@Serializable
data class DeviceStatusAck(val device_id: Long, val trust: DeviceTrustDto, val policy_version_current: Long, val directives: List<DirectiveDto> = emptyList(), val server_time: String)

@Serializable
data class WifiDto(val ssid: String, val security_type: String, val password: String? = null)

@Serializable
data class EnrolmentTokenCreateRequest(val flavour: String, val lockdown_level: String, val max_uses: Int, val expires_in_h: Int, val zone_id: Long? = null, val release_id: Long? = null, val wifi: WifiDto? = null, val note: String? = null)

@Serializable
data class EnrolmentTokenDto(
    val token_id: Long, val token_prefix: String, val flavour: String, val lockdown_level: String, val max_uses: Int, val used_count: Int, val zone_id: Long? = null, val expires_at: String,
    val created_by: Long, val created_at: String, val revoked_at: String? = null, val note: String? = null,
)

@Serializable
data class AdminExtras(
    @SerialName("aron.enrolment_token") val token: String, @SerialName("aron.api_base_url") val apiBaseUrl: String, @SerialName("aron.env") val env: String,
    @SerialName("aron.flavour") val flavour: String, @SerialName("aron.lockdown_level") val lockdown: String, @SerialName("aron.zone_code") val zoneCode: String? = null,
)

@Serializable
data class ProvisioningQrPayload(
    @SerialName("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME") val component: String,
    @SerialName("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION") val downloadLocation: String,
    @SerialName("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM") val signatureChecksum: String,
    @SerialName("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED") val leaveSystemApps: Boolean = true,
    @SerialName("android.app.extra.PROVISIONING_SKIP_ENCRYPTION") val skipEncryption: Boolean? = null,
    @SerialName("android.app.extra.PROVISIONING_WIFI_SSID") val wifiSsid: String? = null,
    @SerialName("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE") val wifiSecurity: String? = null,
    @SerialName("android.app.extra.PROVISIONING_WIFI_PASSWORD") val wifiPassword: String? = null,
    @SerialName("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE") val extras: AdminExtras,
)

@Serializable
data class EnrolmentTokenCreated(val token: EnrolmentTokenDto, val enrolment_token: String, val qr_payload: ProvisioningQrPayload, val qr_text: String)

@Serializable
data class EnrolmentTokenPage(val items: List<EnrolmentTokenDto>, val next_cursor: String? = null)

@Serializable
data class BoundUser(val user_id: Long, val username: String, val bind_ordinal: Int, val bound_at: String, val status: String)

@Serializable
data class DeviceDto(
    val device_id: Long, val device_uuid: String, val flavour: String, val status: String, val device_owner: Boolean, val lockdown_level: String, val trust_level: String,
    val hardware_backed_key: Boolean, val integrity_verdict: String, val integrity_checked_at: String? = null, val enrolled_at: String, val enrolment_token_id: Long? = null,
    val device_info: DeviceInfoDto? = null, val app_version: String? = null, val policy_version_applied: Long? = null, val config_version_applied: Long? = null,
    val last_contact_at: String? = null, val pending_rows_reported: Int? = null, val bound_users: List<BoundUser> = emptyList(), val last_status: JsonElement? = null,
)

@Serializable
data class DevicePage(val items: List<DeviceDto>, val next_cursor: String? = null)

@Serializable
data class DeviceStatusPage(val items: List<JsonElement>, val next_cursor: String? = null)

@Serializable
data class DeviceStateChangeRequest(val action: String, val reason: String)

@Serializable
data class DirectiveCreateRequest(val type: String, val resend_from: String? = null, val ttl_h: Int = 24)

@Serializable
data class DirectiveList(val items: List<DirectiveDto>)

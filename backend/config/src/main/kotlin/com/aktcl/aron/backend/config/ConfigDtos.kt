package com.aktcl.aron.backend.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Contract `ConfigKey` (registry row). `bounds` is passed through as stored (contract ConfigBounds). */
@Serializable
data class ConfigKeyDto(
    val key: String, val area: String, val kind: String, val value_type: String, val default_value: JsonElement,
    val bounds: JsonElement, val scope_levels: List<String>, val risk_class: Int, val effect: String, val delivery: String,
    val requires_ack: Boolean, val future_dated_only: Boolean, val restrictive_dir: String, val editor_permission: String,
    val description_en: String, val description_bn: String?,
)

@Serializable
data class ConfigKeyList(val items: List<ConfigKeyDto>)

@Serializable
data class ConfigValueDto(
    val id: Long, val key: String, val scope_type: String, val scope_id: Long, val value: JsonElement,
    val effective_from: String, val effective_to: String?, val config_version: Long, val superseded_in_version: Long?,
    val set_by: Long, val set_at: String, val reason: String,
)

@Serializable
data class ConfigValuePage(val items: List<ConfigValueDto>, val next_cursor: String?)

@Serializable
data class ResolvedConfigValue(
    val key: String, val value: JsonElement, val scope_type: String, val scope_id: Long?, val effective_from: String?,
    val effective_to: String?, val config_version: Long?, val requires_ack: Boolean, val bounds: JsonElement?,
)

/** One item of a change request; `value` JSON null removes the override at the scope (contract ConfigChangeItem). */
@Serializable
data class ConfigChangeItemIn(
    val key: String, val scope_type: String, val scope_id: Long, val value: JsonElement,
    val effective_from: String? = null, val effective_to: String? = null,
)

@Serializable
data class ConfigChangeRequestIn(val reason: String, val break_glass: Boolean = false, val changes: List<ConfigChangeItemIn>)

/** Stored and returned item: the request item plus `old_value`. */
@Serializable
data class ConfigChangeItemOut(
    val key: String, val scope_type: String, val scope_id: Long, val value: JsonElement, val effective_from: String?,
    val effective_to: String?, val old_value: JsonElement,
)

@Serializable
data class BlastRadius(val zones: Int = 0, val routes: Int = 0, val outlets: Int = 0, val devices: Int = 0, val outlets_changed: Int? = null, val overrides_kept: Int? = null)

@Serializable
data class ConfigChangeDto(
    val change_id: Long, val status: String, val risk_class: Int, val changes: List<ConfigChangeItemOut>, val reason: String,
    val requested_by: Long, val requested_at: String, val approved_by: Long?, val approved_at: String?, val apply_at: String?,
    val config_version: Long?, val is_revert_of: Long?, val blast_radius: BlastRadius,
)

@Serializable
data class ConfigChangePage(val items: List<ConfigChangeDto>, val next_cursor: String?)

@Serializable
data class ConfigDecisionIn(val decision: String, val note: String? = null)

@Serializable
data class ConfigVersionDto(
    val version: Long, val kind: String, val committed_at: String, val committed_by: Long, val summary: String,
    val max_risk_class: Int, val is_revert_of: Long?, val change_id: Long?,
)

@Serializable
data class ConfigVersionPage(val items: List<ConfigVersionDto>, val next_cursor: String?)

@Serializable
data class ConfigRollbackIn(val mode: String, val reason: String)

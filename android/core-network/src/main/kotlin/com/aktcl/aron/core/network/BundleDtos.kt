package com.aktcl.aron.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The head of the day bundle (contract `Bundle.meta` and `Bundle.user`). The full bundle is applied to Room by the
// bundle row (F-SYS-006, Day 2) from the raw JSON kept in [BundleDownload]; this head is what login and the
// home placeholder need. REQUEST: docs/requests/android-core-contract-dtos.md.

@Serializable
data class BundleHead(
    val meta: BundleMeta,
    val user: BundleUser,
)

@Serializable
data class BundleMeta(
    @SerialName("bundle_version") val bundleVersion: String,
    @SerialName("valid_for_business_date") val validForBusinessDate: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("server_time") val serverTime: String,
    @SerialName("user_id") val userId: Long,
    val role: String,
    @SerialName("config_version") val configVersion: Long,
    @SerialName("schema_version") val schemaVersion: Int,
    val cursor: String,
    @SerialName("is_prefetch") val isPrefetch: Boolean = false,
    @SerialName("paged_sections") val pagedSections: List<PagedSection> = emptyList(),
)

@Serializable
data class PagedSection(
    val section: String,
    val pages: Int,
    val rows: Int,
)

@Serializable
data class BundleUser(
    @SerialName("user_id") val userId: Long,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val role: String,
    val designation: String? = null,
    val locale: String,
    @SerialName("bind_ordinal") val bindOrdinal: Int,
    @SerialName("memo_seq_block_size") val memoSeqBlockSize: Int,
    @SerialName("zone_id") val zoneId: Long? = null,
    @SerialName("territory_id") val territoryId: Long? = null,
)

/** A downloaded bundle: the parsed head, the raw JSON (applied to Room in one transaction by the bundle row) and its ETag. */
class BundleDownload(val head: BundleHead, val rawJson: String, val etag: String?) {
    override fun toString(): String = "BundleDownload(version=${head.meta.bundleVersion}, bytes=${rawJson.length}, etag=$etag)"
}

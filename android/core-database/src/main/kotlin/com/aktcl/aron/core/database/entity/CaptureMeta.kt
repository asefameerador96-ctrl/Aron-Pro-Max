package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo

/**
 * The envelope facts every device-originated row keeps (docs/24 s4.3, s3.8): business date (Asia/Dhaka), trusted
 * capture time and the clock evidence, the route worked and the bundle and config versions in force. Identity (user,
 * device) is never stored on a row: each user has their own database (docs/24 s5.3).
 */
data class CaptureMeta(
    /** `YYYY-MM-DD`, Asia/Dhaka, from trusted time. */
    @ColumnInfo(name = "business_date") val businessDate: String,
    /** RFC 3339 UTC with milliseconds, trusted time. */
    @ColumnInfo(name = "captured_at") val capturedAt: String,
    @ColumnInfo(name = "captured_elapsed_ms") val capturedElapsedMs: Long,
    @ColumnInfo(name = "boot_count") val bootCount: Int,
    @ColumnInfo(name = "clock_offset_ms") val clockOffsetMs: Long?,
    @ColumnInfo(name = "captured_offline") val capturedOffline: Boolean,
    @ColumnInfo(name = "route_id") val routeId: Long?,
    @ColumnInfo(name = "acting_for_user_id") val actingForUserId: Long? = null,
    @ColumnInfo(name = "bundle_version") val bundleVersion: String?,
    @ColumnInfo(name = "bundle_stale") val bundleStale: Boolean = false,
    @ColumnInfo(name = "config_version") val configVersion: Long,
)

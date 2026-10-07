package com.aktcl.aron.feature.outlet

/** One AV or KV item as the phone holds it (contract ContentItem plus where its file is cached; null path = not downloaded). */
data class ContentAsset(
    val contentId: Long,
    val version: Int,
    val kind: ContentKindLocal,
    val sequence: Int,
    val validFrom: String,
    val validTo: String,
    /** Empty = every outlet of the user's routes. */
    val outletIds: Set<Long>,
    val localPath: String?,
)

enum class ContentKindLocal { AV, KV }

/** What the call shows for one asset: a missing file is logged as skipped and never blocks the sale (F-SR-020). */
data class ContentStep(val asset: ContentAsset, val missing: Boolean)

enum class ContentOutcome(val wire: String) { VIEWED("viewed"), SKIPPED_MISSING("skipped_missing"), SKIPPED_USER("skipped_user") }

object CallPlan {
    /** Assets valid today for this outlet in the fixed call order: every AV (by sequence), then every KV (by sequence). The survey and the sale follow. */
    fun content(assets: List<ContentAsset>, outletId: Long, businessDate: String): List<ContentStep> =
        assets
            .filter { businessDate >= it.validFrom && businessDate <= it.validTo }
            .filter { it.outletIds.isEmpty() || outletId in it.outletIds }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.sequence }, { it.contentId }))
            .map { ContentStep(it, missing = it.localPath == null) }
}

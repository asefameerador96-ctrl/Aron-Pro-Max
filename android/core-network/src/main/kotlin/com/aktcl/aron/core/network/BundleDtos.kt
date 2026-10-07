package com.aktcl.aron.core.network

import com.aktcl.aron.contract.BundleMeta
import com.aktcl.aron.contract.BundleUser
import kotlinx.serialization.Serializable

// The head of the day bundle: contract `Bundle.meta` and `Bundle.user` (shared:contract `BundleMeta`, `BundleUser`).
// `BundleHead` is not a contract schema; the full bundle is applied to Room by core-sync from the raw JSON kept in
// [BundleDownload]; this head is what login and the home screen need.

@Serializable
data class BundleHead(
    val meta: BundleMeta,
    val user: BundleUser,
)

/** A downloaded bundle: the parsed head, the raw JSON (applied to Room in one transaction by the bundle row) and its ETag. */
class BundleDownload(val head: BundleHead, val rawJson: String, val etag: String?) {
    override fun toString(): String = "BundleDownload(version=${head.meta.bundleVersion}, bytes=${rawJson.length}, etag=$etag)"
}

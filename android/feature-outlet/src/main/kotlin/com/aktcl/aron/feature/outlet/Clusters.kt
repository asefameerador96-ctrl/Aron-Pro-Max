package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.common.ClientIds

/**
 * A cluster the SR sees on the Clusters screen (SRM-B-43). [clusterId] is the server's id; a cluster the SR created on the phone and the
 * server has not confirmed has [clusterId] null and its [clientUuid] (a "pending" cluster: it cannot be used for a new shop yet).
 */
data class SrCluster(val clusterId: Long?, val clientUuid: String?, val name: String, val routeName: String, val pending: Boolean)

/** A cluster created on the phone by the SR (a `cluster_create` row, local only until the contract record exists): the client uuid is made when the dialog opens. */
data class PendingCluster(val clientUuid: String, val name: String, val routeId: Long, val routeName: String, val createdAt: String)

enum class ClusterNameError { EMPTY, TOO_SHORT, TOO_LONG, DUPLICATE, NOT_SAVED }

object SrClusters {
    const val MIN = 2
    const val MAX = 80

    /**
     * Interim source until the bundle carries a `clusters` section (needs contract 1.14.0, SRM-B-33): the distinct clusters of the
     * route's outlets, in name order, each with the route's name, then the clusters created on this phone and still pending.
     */
    fun list(outlets: List<OutletEntity>, routeName: String, pending: List<PendingCluster>): List<SrCluster> {
        val real = outlets.map { it.clusterId to it.clusterName }.filter { it.second.isNotBlank() }.distinctBy { it.first }
            .sortedBy { it.second.lowercase() }.map { SrCluster(it.first, null, it.second, routeName, false) }
        val known = real.map { it.name.trim().lowercase() }.toSet()
        val mine = pending.filter { it.name.trim().lowercase() !in known }.sortedBy { it.name.lowercase() }
            .map { SrCluster(null, it.clientUuid, it.name, it.routeName.ifBlank { routeName }, true) }
        return real + mine
    }

    /** Clusters a new shop may be put in: confirmed ones only. */
    fun selectable(all: List<SrCluster>): List<ClusterOption> = all.mapNotNull { c -> c.clusterId?.let { ClusterOption(it, c.name) } }

    /**
     * null = fine. [ownUuid] is the client uuid of THIS dialog opening: when a cluster with that uuid is already stored the answer is null
     * (it IS saved) BEFORE any name check, so a retry after a recreate that finds its own record reports saved, never "duplicate".
     */
    fun validate(name: String, existing: List<SrCluster>, ownUuid: String? = null): ClusterNameError? {
        if (ownUuid != null && existing.any { it.clientUuid == ownUuid }) return null
        val n = name.trim()
        return when {
            n.isEmpty() -> ClusterNameError.EMPTY
            n.length < MIN -> ClusterNameError.TOO_SHORT
            n.length > MAX -> ClusterNameError.TOO_LONG
            existing.any { it.name.trim().equals(n, ignoreCase = true) } -> ClusterNameError.DUPLICATE
            else -> null
        }
    }

    fun newUuid(): String = ClientIds.newUuid()
}

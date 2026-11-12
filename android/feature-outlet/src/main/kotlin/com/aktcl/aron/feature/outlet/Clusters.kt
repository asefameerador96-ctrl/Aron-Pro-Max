package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.common.ClientIds

/**
 * A cluster the SR sees on the Clusters screen (SRM-B-43). [clusterId] is the server's id; a cluster the SR created on the phone and the
 * server has not confirmed has [clusterId] null and its [clientUuid] (a "pending" cluster: it cannot be used for a new shop yet).
 */
data class SrCluster(val clusterId: Long?, val clientUuid: String?, val name: String, val routeName: String, val pending: Boolean)

/** A cluster created offline by the SR: the client uuid is generated when the dialog opens, so a double tap or a retry is one record. */
data class PendingCluster(val clientUuid: String, val name: String, val routeId: Long, val routeName: String, val createdAt: String)

/** Where the pending clusters of the signed-in SR live on the phone (survives a kill and relaunch). */
interface PendingClusterStore {
    fun all(): List<PendingCluster>
    /** Idempotent by [PendingCluster.clientUuid]. */
    fun add(c: PendingCluster)
}

enum class ClusterNameError { EMPTY, TOO_SHORT, TOO_LONG, DUPLICATE }

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

    fun validate(name: String, existing: List<SrCluster>): ClusterNameError? {
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

package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.rules.TextRules

/**
 * Outlet detail card (F-SR-049) built from local data only. No loyalty points field (TRIM, docs/27). Open dues are
 * milli-taka as downloaded; formatting is the screen's job. [lastVisitAt] is the newest visit of this outlet on the phone.
 */
data class OutletCard(
    val name: String,
    val nameBn: String?,
    val ownerName: String,
    /** Normalised 11 digits, or null when the stored number is unusable. */
    val phone: String?,
    val clusterName: String,
    val channel: String,
    val openDueMtk: Long,
    /** The date the dues figure is as of (`open_due_as_of`), shown so an old figure is not read as live. */
    val openDueAsOf: String?,
    val lastVisitAt: String?,
    val pendingRequest: Boolean,
) {
    companion object {
        fun of(o: OutletEntity, lastVisitAt: String?) = OutletCard(
            name = o.name, nameBn = o.nameBn, ownerName = o.ownerName,
            phone = o.contactNumber?.let { TextRules.normalisePhone(it)?.value },
            clusterName = o.clusterName, channel = o.channel, openDueMtk = o.openDueMtk, openDueAsOf = o.openDueAsOf,
            lastVisitAt = lastVisitAt, pendingRequest = o.pendingRequest,
        )
    }
}

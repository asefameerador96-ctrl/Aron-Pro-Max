package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.reference.BundleReference
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The route of the day as the SR app reads it offline. */
data class RouteDay(val route: RouteEntity, val outlets: List<OutletEntity>)

/**
 * Reference data of the day bundle (docs/24 s4.10). [apply] replaces routes, outlets and SKUs in one transaction, so a
 * kill mid-apply leaves the previous day intact; captures and the outbox are never touched by it.
 */
class ReferenceRepository(private val db: AronDatabase) {
    private val dao = db.referenceDao()

    suspend fun apply(bundle: BundleReference) {
        val date = bundle.meta.validForBusinessDate
        val version = bundle.meta.bundleVersion
        val routes = bundle.routes.map { s ->
            RouteEntity(
                routeId = s.routeId, code = s.route.code, name = s.route.name, displayLabel = s.route.displayLabel,
                zoneId = s.route.zoneId, kind = s.route.kind, visitKind = s.route.visitKind, visitDaysMask = s.route.visitDaysMask,
                sequenceNo = s.route.sequenceNo, status = s.route.status, assignmentKind = s.assignmentKind,
                actingForUserId = s.actingForUserId, plannedToday = s.plannedToday, targetOutlets = s.targetOutlets,
                routeSnapshotVersion = s.routeSnapshotVersion, businessDate = date, bundleVersion = version,
            )
        }
        val outlets = bundle.routes.flatMap { it.outlets }.map { o ->
            OutletEntity(
                outletId = o.outletId, routeId = o.routeId, code = o.code, name = o.name, nameBn = o.nameBn, nameSortKey = o.nameSortKey,
                ownerName = o.ownerName, contactNumber = o.contactNumber, lat = o.lat, lng = o.lng, locationConfirmed = o.locationConfirmed,
                provisionalLat = o.provisionalLat, provisionalLng = o.provisionalLng, clusterId = o.clusterId, clusterName = o.clusterName,
                channel = o.channel, subChannelId = o.subChannelId, geoClass = o.geoClass, status = o.status, priceType = o.priceType,
                outletKind = o.outletKind, radiusM = o.radiusM, maxAccuracyM = o.maxAccuracyM, visitSequence = o.visitSequence,
                openDueMtk = o.openDueMtk, openDueAsOf = o.openDueAsOf,
                programmeFlagsJson = Json.encodeToString(ListSerializer(String.serializer()), o.programmeFlags),
                pendingRequest = o.pendingRequest,
            )
        }
        val skus = bundle.products.skus.map { k ->
            SkuEntity(
                skuId = k.id, code = k.code, variantId = k.variantId, categoryCode = k.categoryCode, name = k.name, shortName = k.shortName,
                nameBn = k.nameBn, baseUnit = k.baseUnit, basePerPack = k.basePerPack, entryUnitDefault = k.entryUnitDefault,
                reportUnit = k.reportUnit, reportFactor = k.reportFactor, sort = k.sort, status = k.status, version = k.version,
            )
        }
        db.withTransaction {
            dao.clearOutlets()
            dao.clearRoutes()
            dao.clearSkus()
            dao.insertRoutes(routes)
            dao.insertOutlets(outlets)
            dao.insertSkus(skus)
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_VERSION, version))
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_DATE, date))
        }
    }

    /** The routes of [businessDate] with their outlets in visit order; planned routes first. */
    suspend fun routesOfDay(businessDate: String): List<RouteDay> = db.withTransaction {
        dao.routesFor(businessDate).map { RouteDay(it, dao.outletsOf(it.routeId)) }
    }

    suspend fun skus(): List<SkuEntity> = dao.activeSkus()

    suspend fun bundleVersion(): String? = dao.meta(KEY_BUNDLE_VERSION)

    companion object {
        const val KEY_BUNDLE_VERSION = "bundle_version"
        const val KEY_BUNDLE_DATE = "bundle_business_date"
    }
}

package com.aktcl.aron.sr

import android.content.Context
import com.aktcl.aron.feature.outlet.PendingCluster
import com.aktcl.aron.feature.outlet.PendingClusterStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The clusters this SR created on the phone (SRM-B-44), kept per user in SharedPreferences so they survive a kill and relaunch.
 * INTERIM: there is no cluster-creation record in the contract yet (needs contract 1.14.0 from the sr-server lane); until the lane wires
 * it in train 2 these stay on the phone and are shown as "waiting to be sent". Idempotent by client uuid. A damaged entry is skipped,
 * never a crash.
 */
class PrefsPendingClusters(context: Context, userId: Long) : PendingClusterStore {
    private val prefs = context.getSharedPreferences("aron-clusters-$userId", Context.MODE_PRIVATE)

    @Synchronized override fun all(): List<PendingCluster> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = try { Json.parseToJsonElement(raw) as? JsonArray } catch (_: kotlinx.serialization.SerializationException) { null } ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            PendingCluster(s("uuid") ?: return@mapNotNull null, s("name") ?: return@mapNotNull null, (o["route_id"] as? JsonPrimitive)?.longOrNull ?: 0L, s("route_name").orEmpty(), s("created_at").orEmpty())
        }
    }

    @Synchronized override fun add(c: PendingCluster) {
        val now = all()
        if (now.any { it.clientUuid == c.clientUuid }) return
        val arr = JsonArray((now + c).map { p ->
            buildJsonObject { put("uuid", p.clientUuid); put("name", p.name); put("route_id", p.routeId); put("route_name", p.routeName); put("created_at", p.createdAt) }
        })
        prefs.edit().putString(KEY, Json.encodeToString(JsonArray.serializer(), arr)).commit()
    }

    private companion object { const val KEY = "pending" }
}

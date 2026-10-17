package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What an AMO's menu depends on (F-AMO-001, F-AMO-040), read from the bundle's supervisor section: the subordinate SRs in
 * scope and the pending SR verification requests. [asthaOutlets] is false until the loyalty programme is built (docs/27:
 * deferred, the hook stays). [pendingVerifications] counts only requests waiting for this AMO's verification.
 */
data class AmoReach(val subordinateSrs: Int, val pendingVerifications: Int, val asthaOutlets: Boolean = false) {
    /** The SS designation (MQ-19, assumed): a supervisor-tier user with no SR under them, shown the reduced menu. */
    val ss: Boolean get() = subordinateSrs == 0

    companion object {
        /** No supervisor section yet (before the first bundle): the reduced menu and no badge. */
        val NONE = AmoReach(0, 0)

        /** Pure parse of the stored `supervisor` section; a missing or malformed section is [NONE], never a crash. */
        fun parse(sectionJson: String?, ownUserId: Long): AmoReach {
            val o = runCatching { Json.parseToJsonElement(sectionJson ?: return NONE) as? JsonObject }.getOrNull() ?: return NONE
            fun str(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.contentOrNull
            val srs = (o["team"] as? JsonArray).orEmpty().count { (it as? JsonObject)?.let { m -> str(m, "role") == "SR" } == true }
            val pending = (o["pending_outlet_requests"] as? JsonArray).orEmpty().count { e ->
                val r = e as? JsonObject ?: return@count false
                str(r, "status") == "pending" && (r["requested_by_user_id"] as? JsonPrimitive)?.longOrNull != ownUserId
            }
            return AmoReach(srs, pending)
        }
    }
}

/** The AMO's destinations; the Home groups them into hub tiles, the count is the "17 / 13" of the menu (Settings included). */
enum class AmoLeaf {
    ATTENDANCE, JOINT_CALL, CONTROL_CALL, TEAM_LOCATION, TEAM_PERFORMANCE, TASK_DELEGATION, LIVE_DASHBOARD, SR_STOCK,
    OUTLET, STOCK, SALE, MEMO, SUMMARY, SALES_SUBMIT, ASTHA, REPORT, SETTINGS,
}

object AmoMenu {
    /**
     * The destinations resolved for [reach]: 17 for a full AMO with Astha outlets, 16 while Astha is deferred, 13 for an SS user
     * (no Team Location, Team Performance or SR Stock, which need subordinate SRs; no Astha).
     */
    fun leaves(reach: AmoReach): List<AmoLeaf> = AmoLeaf.entries.filter { leaf ->
        when (leaf) {
            AmoLeaf.TEAM_LOCATION, AmoLeaf.TEAM_PERFORMANCE, AmoLeaf.SR_STOCK -> reach.subordinateSrs > 0
            AmoLeaf.ASTHA -> reach.asthaOutlets
            else -> true
        }
    }

    /** Badge by hub tile id: Outlet carries the pending SR verifications, and only above zero (F-AMO-001). */
    fun badges(reach: AmoReach): Map<String, Int> =
        if (reach.pendingVerifications > 0) mapOf(com.aktcl.aron.feature.home.RoleHomes.AmoTile.OUTLET to reach.pendingVerifications) else emptyMap()

    /** Leaves behind each hub tile (Home shows eight hubs; the Team and Sale hubs open a list of these). */
    fun hub(tileId: String, reach: AmoReach): List<AmoLeaf> {
        val live = leaves(reach).toSet()
        val members = when (tileId) {
            com.aktcl.aron.feature.home.RoleHomes.AmoTile.TEAM ->
                listOf(AmoLeaf.TEAM_LOCATION, AmoLeaf.TEAM_PERFORMANCE, AmoLeaf.TASK_DELEGATION, AmoLeaf.LIVE_DASHBOARD, AmoLeaf.SR_STOCK)
            com.aktcl.aron.feature.home.RoleHomes.AmoTile.SALE -> listOf(AmoLeaf.SALE, AmoLeaf.STOCK, AmoLeaf.MEMO, AmoLeaf.SUMMARY)
            else -> emptyList()
        }
        return members.filter { it in live }
    }
}

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
data class AmoReach(val subordinateSrs: Int, val pendingVerifications: Int, val asthaOutlets: Boolean = false, val zoneName: String? = null) {
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
            // the zone name for the Home header; the server adds it to the supervisor section (parity finding 2), absent until then
            val zone = (str(o, "zone_name") ?: str(o, "territory_name"))?.trim()?.takeIf { it.isNotEmpty() }
            return AmoReach(srs, pending, zoneName = zone)
        }
    }
}

/** The AMO's destinations in the manual's order (D-AMO-MAN-02): one Home tile each, Settings included; Astha stays hidden while deferred. */
enum class AmoLeaf {
    ATTENDANCE, JOINT_CALL, CONTROL_CALL, TEAM_LOCATION, TEAM_PERFORMANCE, TASK_DELEGATION, LIVE_DASHBOARD, SR_STOCK,
    OUTLET, STOCK, SALE, MEMO, SUMMARY, SALES_SUBMIT, ASTHA, REPORT, SALES_JOURNEY, CAMPAIGN_ENROLLMENT, SETTINGS,
}

object AmoMenu {
    /**
     * The destinations resolved for [reach]: 17 for a full AMO with Astha outlets, 16 while Astha is deferred, 13 for an SS user
     * (no Team Location, Team Performance or SR Stock, which need subordinate SRs; no Astha).
     */
    /** The Home tile id of a leaf (the stable key the host maps to a screen and the test tag `rh_tile_<id>` uses). */
    fun tileId(leaf: AmoLeaf): String = leaf.name.lowercase()

    /**
     * The Home tiles (D-AMO-MAN-02, R5): one per leaf in the manual's order, so for a full AMO: Attendance, Joint Call, Control Call, Team
     * Location, Team Performance, Task Delegation, Live Dashboard, SR Stock, Outlet, Stock, Sales, Memo, Summary, Sales Submit, Report,
     * Sales Journey, Settings (Astha and Campaign Enrollment stay hidden: docs/27). [badges] by tile id (Outlet: pending SR verifications).
     */
    fun tiles(reach: AmoReach, badges: Map<String, Int> = emptyMap()): List<com.aktcl.aron.feature.home.RoleTile> = tileLeaves(reach).map { l -> // Astha and Campaign Enrollment are hook tiles in the old position (D-PAR-AMO-01)
        val (label, icon) = tileFace(l)
        com.aktcl.aron.feature.home.RoleTile(tileId(l), label, icon, badges[tileId(l)] ?: 0)
    }

    /** The leaves shown as Home tiles: the live leaves plus the two deferred hooks (Astha, Campaign Enrollment) in the old app's place, in the old order. */
    internal fun tileLeaves(reach: AmoReach): List<AmoLeaf> {
        val live = leaves(reach).toSet()
        return AmoLeaf.entries.filter { it in live || it == AmoLeaf.ASTHA || it == AmoLeaf.CAMPAIGN_ENROLLMENT }
    }

    /** True for the tiles that open the "coming later" state (docs/27, deferred programmes). */
    fun isHook(tileId: String): Boolean = tileId == tileId(AmoLeaf.ASTHA) || tileId == tileId(AmoLeaf.CAMPAIGN_ENROLLMENT)

    private fun tileFace(l: AmoLeaf): Pair<Int, androidx.compose.ui.graphics.vector.ImageVector> {
        val i = com.aktcl.aron.core.ui.AronIcons
        return when (l) {
            AmoLeaf.ATTENDANCE -> com.aktcl.aron.feature.home.R.string.hm_tile_attendance to i.Attendance
            AmoLeaf.JOINT_CALL -> com.aktcl.aron.feature.home.R.string.rh_amo_joint_call to i.Team
            AmoLeaf.CONTROL_CALL -> com.aktcl.aron.feature.home.R.string.rh_amo_control_call to i.Place
            AmoLeaf.TEAM_LOCATION -> R.string.amo_leaf_team_location to i.Place
            AmoLeaf.TEAM_PERFORMANCE -> R.string.amo_leaf_team_performance to i.Trend
            AmoLeaf.TASK_DELEGATION -> R.string.amo_leaf_task_delegation to i.Team
            AmoLeaf.LIVE_DASHBOARD -> R.string.amo_leaf_live_dashboard to i.Trend
            AmoLeaf.SR_STOCK -> R.string.amo_leaf_sr_stock to i.Team
            AmoLeaf.OUTLET -> com.aktcl.aron.feature.home.R.string.hm_tile_outlet to i.Outlet
            AmoLeaf.STOCK -> com.aktcl.aron.feature.home.R.string.hm_tile_stock to i.Sale
            AmoLeaf.SALE -> com.aktcl.aron.feature.home.R.string.hm_tile_sale to i.Sale
            AmoLeaf.MEMO -> com.aktcl.aron.feature.home.R.string.hm_tile_memo to i.Info
            AmoLeaf.SUMMARY -> com.aktcl.aron.feature.home.R.string.hm_tile_summary to i.Info
            AmoLeaf.SALES_SUBMIT -> com.aktcl.aron.feature.home.R.string.hm_tile_sales_submit to i.Submit
            AmoLeaf.ASTHA -> com.aktcl.aron.feature.home.R.string.hm_tile_astha to i.Info
            AmoLeaf.CAMPAIGN_ENROLLMENT -> R.string.amo_leaf_campaign_enrollment to i.Info
            AmoLeaf.REPORT -> com.aktcl.aron.feature.home.R.string.rh_amo_report to i.Trend
            AmoLeaf.SALES_JOURNEY -> com.aktcl.aron.feature.home.R.string.hm_tile_sales_journey to i.Place
            AmoLeaf.SETTINGS -> com.aktcl.aron.feature.home.R.string.set_title to i.Info
        }
    }

    fun leaves(reach: AmoReach): List<AmoLeaf> = AmoLeaf.entries.filter { leaf ->
        when (leaf) {
            AmoLeaf.TEAM_LOCATION, AmoLeaf.TEAM_PERFORMANCE, AmoLeaf.SR_STOCK -> reach.subordinateSrs > 0
            AmoLeaf.ASTHA -> reach.asthaOutlets
            AmoLeaf.CAMPAIGN_ENROLLMENT -> false
            else -> true
        }
    }

    /** Badge by hub tile id: Outlet carries the pending SR verifications, and only above zero (F-AMO-001). */
    fun badges(reach: AmoReach): Map<String, Int> =
        if (reach.pendingVerifications > 0) mapOf(tileId(AmoLeaf.OUTLET) to reach.pendingVerifications) else emptyMap()

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

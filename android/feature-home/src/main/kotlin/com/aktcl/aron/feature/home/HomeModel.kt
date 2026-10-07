package com.aktcl.aron.feature.home

/** Header (F-SR-008): `SR - name (username)` and `<route name (visit days)>, ISO date`. */
data class HomeHeader(val title: String, val subtitle: String, val routeKind: RouteKind?)

/** Daily, 3F (three days a week) or 2F (two days a week); the label itself is a string resource. */
enum class RouteKind(val wire: String) {
    DAILY("daily"), THREE_F("3f"), TWO_F("2f");

    companion object { fun of(wire: String?): RouteKind? = entries.firstOrNull { it.wire == wire } }
}

object HomeModel {
    fun header(rolePrefix: String, name: String, username: String, routeName: String?, visitKind: String?, isoDate: String): HomeHeader {
        val route = routeName?.trim().orEmpty()
        return HomeHeader(
            title = "$rolePrefix - ${name.trim()} ($username)",
            subtitle = if (route.isEmpty()) isoDate else "$route, $isoDate",
            routeKind = RouteKind.of(visitKind),
        )
    }
}

/** The Home tiles in screenshot order (F-SR-009). Loyalty is deferred (docs/27) and has no entry at all. */
enum class HomeTile(val key: String, val alwaysShown: Boolean) {
    ATTENDANCE("attendance", true), STOCK("stock", true), SALE("sale", true), MEMO("memo", true),
    SUMMARY("summary", true), SALES_SUBMIT("sales_submit", true), OUTLET("outlet", true), TUTORIAL("tutorial", true),
    TASKS("tasks", true), PHOTO_CAPTURE("photo_capture", false), SALES_JOURNEY("sales_journey", true), KPI("kpi", true),
}

data class ResolvedTile(val tile: HomeTile, val badge: Int?)

object HomeTiles {
    /**
     * Tiles in order, resolved per user: tiles that are not always shown appear only when [enabledForUser] holds their
     * key (the bundle's `config`); the Tasks tile carries a badge equal to the open task count (hidden at zero).
     */
    fun resolve(enabledForUser: Set<String>, openTasks: Int): List<ResolvedTile> =
        HomeTile.entries
            .filter { it.alwaysShown || it.key in enabledForUser }
            .map { ResolvedTile(it, if (it == HomeTile.TASKS && openTasks > 0) openTasks else null) }
}

/** Device-health line (F-SR-064): battery, free storage, pending rows, last sync, each with a warning flag. */
data class DeviceHealth(
    val batteryPct: Int,
    val freeStorageMb: Long,
    val pendingRows: Int,
    val lastSyncAgeMin: Long?,
    val batteryWarn: Boolean,
    val storageWarn: Boolean,
    val pendingWarn: Boolean,
    val syncWarn: Boolean,
) {
    val anyWarning: Boolean get() = batteryWarn || storageWarn || pendingWarn || syncWarn
}

object DeviceHealthModel {
    /** Defaults are the thresholds of docs/04 style budgets; the shell may pass configured values. */
    fun of(
        batteryPct: Int, freeStorageMb: Long, pendingRows: Int, lastSyncAgeMin: Long?,
        batteryWarnBelow: Int = 20, storageWarnBelowMb: Long = 300, pendingWarnAbove: Int = 200, syncWarnAboveMin: Long = 24 * 60,
    ): DeviceHealth {
        require(batteryPct in 0..100) { "battery percent" }
        return DeviceHealth(
            batteryPct, freeStorageMb, pendingRows, lastSyncAgeMin,
            batteryWarn = batteryPct < batteryWarnBelow, storageWarn = freeStorageMb < storageWarnBelowMb,
            pendingWarn = pendingRows > pendingWarnAbove,
            syncWarn = lastSyncAgeMin == null || lastSyncAgeMin > syncWarnAboveMin,
        )
    }
}

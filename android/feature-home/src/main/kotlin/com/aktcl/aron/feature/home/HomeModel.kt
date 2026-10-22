package com.aktcl.aron.feature.home

/**
 * Header (F-SR-008): `SR - name (username)`, the one-line `<route name (visit days)>, ISO date` ([subtitle], kept for
 * earlier callers) and its parts ([routeName], [isoDate]) so the screen can print the two caption lines of screens-sr s2:
 * the route name over `<ISO date> · <route kind>`.
 */
data class HomeHeader(val title: String, val subtitle: String, val routeKind: RouteKind?, val routeName: String? = null, val isoDate: String? = null)

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
            routeName = route.takeIf { it.isNotEmpty() }, isoDate = isoDate,
        )
    }
}

/** The Home tiles in screenshot order (F-SR-009). Loyalty is deferred (docs/27) and has no entry at all. */
enum class HomeTile(val key: String, val alwaysShown: Boolean) {
    ATTENDANCE("attendance", true), STOCK("stock", true), SALE("sale", true), MEMO("memo", true),
    SUMMARY("summary", true), SALES_SUBMIT("sales_submit", true), OUTLET("outlet", true), TUTORIAL("tutorial", true),
    TASKS("tasks", true), PHOTO_CAPTURE("photo_capture", true), SALES_JOURNEY("sales_journey", true), KPI("kpi", true),
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
        batteryWarnBelow: Int = 40, storageWarnBelowMb: Long = 500, pendingWarnAbove: Int = 200, syncWarnAboveMin: Long = 24 * 60,
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

/**
 * Where the SR's day stands, from local state only (screens-sr s2 "next-step dock" table). The order of the checks is the
 * table's precedence: a day that is not checked in is NOT_CHECKED_IN whatever else happened.
 */
enum class HomeDayState {
    NOT_CHECKED_IN, NO_STOCK, IN_FIELD, READY_TO_SUBMIT,
    /** Submitted before the check-out time: no dock yet (the table names no step), no success chip either. */
    SUBMITTED,
    READY_TO_CHECK_OUT, COMPLETE,
}

/** The next-step dock of Home: its label is a string resource, [target] the tile it opens (that tile carries the accent ring). */
enum class HomeDock(val target: HomeTile) {
    CHECK_IN(HomeTile.ATTENDANCE), TAKE_STOCK(HomeTile.STOCK), START_SELLING(HomeTile.SALE), SUBMIT(HomeTile.SALES_SUBMIT), CHECK_OUT(HomeTile.ATTENDANCE),
}

object HomeDayModel {
    /**
     * [routeDone] is every planned outlet visited or skipped; [afterCheckoutTime] is `cfg.day.checkout_earliest_time` (17:00)
     * reached on trusted Dhaka time. Pure, so the dock is testable without a database.
     */
    fun dayState(
        checkedIn: Boolean, checkedOut: Boolean, stockIssued: Boolean, routeDone: Boolean, afterCheckoutTime: Boolean, submitted: Boolean,
    ): HomeDayState = when {
        !checkedIn -> HomeDayState.NOT_CHECKED_IN
        submitted && checkedOut -> HomeDayState.COMPLETE
        submitted && afterCheckoutTime -> HomeDayState.READY_TO_CHECK_OUT
        submitted -> HomeDayState.SUBMITTED
        routeDone || afterCheckoutTime -> HomeDayState.READY_TO_SUBMIT
        !stockIssued -> HomeDayState.NO_STOCK
        else -> HomeDayState.IN_FIELD
    }

    /** The dock of a day state; null when the day shows no dock (complete, or submitted before check-out time). */
    fun dock(state: HomeDayState): HomeDock? = when (state) {
        HomeDayState.NOT_CHECKED_IN -> HomeDock.CHECK_IN
        HomeDayState.NO_STOCK -> HomeDock.TAKE_STOCK
        HomeDayState.IN_FIELD -> HomeDock.START_SELLING
        HomeDayState.READY_TO_SUBMIT -> HomeDock.SUBMIT
        HomeDayState.READY_TO_CHECK_OUT -> HomeDock.CHECK_OUT
        HomeDayState.SUBMITTED, HomeDayState.COMPLETE -> null
    }
}

/**
 * The Home hero (screens-sr s2): the day's grand total pre-formatted ("4,391.00 ৳", digits per language), outlets visited
 * of the planned count, the Dhaka check-in time ("08:52", null before check-in) and the day state for the success chip.
 */
data class HomeHero(val grandTotalText: String, val visited: Int, val target: Int, val checkInText: String?, val dayState: HomeDayState)

/** One stock line of the Home stock card: issue and current stock in the category's own unit; hidden when both are zero. */
data class HomeStockLine(val categoryLabel: String, val issueBase: Long, val currentBase: Long, val unitLabel: String) {
    val zero: Boolean get() = issueBase == 0L && currentBase == 0L
}

/** The KPI row and the stock card (F-SR-010). [strikeRateText] is pre-formatted ("10%"); null when nothing is planned. */
data class HomeKpis(val strikeRateText: String?, val nonVisit: Int, val noSale: Int, val stock: List<HomeStockLine>)

/** One category line of the money card: label and its pre-formatted net amount (zero categories are not passed). */
/** [amountBare] is the same amount without the ৳ sign (the reference's rows carry none). */
data class HomeMoneyLine(val label: String, val amountText: String, val amountBare: String = amountText)

/**
 * The Home money card (F-SR-069) with canonical words: category lines, gross (`মোট`), the deductions only when non-zero
 * (pre-formatted with a true minus by the caller) and the grand total (`সর্বমোট`).
 */
data class HomeMoneyLines(
    val categories: List<HomeMoneyLine>,
    val grossText: String,
    val discountText: String?,
    val qcText: String?,
    val grandTotalText: String,
    /** Cigarette and bidi packets sold today (entry unit, docs/34 s4.2): the sales panel's headline figure. */
    val packetsSold: Long = 0L,
    /**
     * The bare figures of the two reference panels, EACH bound to its own field (a discount can never render under the QC label):
     * the gross, the offer discount (zero while docs/27 defers programmes), the QC deduction, the net, then the second panel's
     * total discount, DRP discount and total QC. Pre-formatted by the caller, no ৳, no sign (the panel adds the "- ").
     */
    val grossBare: String = grossText, val offerDiscountBare: String = "0.00", val qcBare: String = "0.00", val netBare: String = grandTotalText,
    val totalDiscountBare: String = "0.00", val drpDiscountBare: String = "0.00", val totalQcBare: String = "0.00",
)

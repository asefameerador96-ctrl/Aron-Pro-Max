package com.aktcl.aron.sr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.home.HomeDayModel
import com.aktcl.aron.feature.home.HomeDayState
import com.aktcl.aron.feature.home.HomeHero
import com.aktcl.aron.feature.home.HomeKpis
import com.aktcl.aron.feature.home.HomeMoneyLine
import com.aktcl.aron.feature.home.HomeMoneyLines
import com.aktcl.aron.feature.home.HomeStockLine
import com.aktcl.aron.feature.memo.domain.HomeMoney
import com.aktcl.aron.feature.memo.domain.KpiStrip
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Home hero, KPI row and cards show, read from Room in one pass off the main thread (screens-sr s2). */
data class SrHomeSnapshot(val hero: HomeHero, val kpis: HomeKpis, val money: HomeMoneyLines, val dayState: HomeDayState)

/** The sync chip's inputs: outbox counts and the newest ack (screens-sr s19 reads the same counters). */
data class SrSyncSnapshot(val online: Boolean, val pending: Int, val inFlight: Int, val lastSyncText: String?, val lastSyncEpochMs: Long? = null) {
    val chip: SyncChipState
        get() = when {
            !online -> SyncChipState.Offline
            inFlight > 0 -> SyncChipState.Syncing
            pending > 0 -> SyncChipState.Waiting(pending)
            lastSyncText != null -> SyncChipState.SyncedAt(lastSyncText)
            else -> SyncChipState.Synced
        }
}

/**
 * The Home reads of the SR shell: the KPI strip and money of feature-memo mapped onto the feature-home model (feature-home
 * does not depend on feature-memo), the day state of the dock from local facts only, and the sync chip from the outbox.
 * Every read is a Room query on [Dispatchers.IO]; nothing here waits for the network.
 */
object SrHomeReads {
    /** A true minus before a deduction (screens-sr s0 "money words"). */
    private const val MINUS = "−"

    fun locale(language: com.aktcl.aron.core.common.AppLanguage): UiLocale = if (language == com.aktcl.aron.core.common.AppLanguage.BN) UiLocale.BN else UiLocale.EN

    /** Dhaka `HH:mm` of an ISO instant; null when the text is not an instant. */
    fun timeOf(iso: String?, locale: UiLocale): String? = iso?.let { runCatching { Formats.time(java.time.Instant.parse(it).toEpochMilli(), locale) }.getOrNull() }

    /** "10%" from hundredths (1000); "10.5%" when the rate is not whole; null when nothing is planned. */
    fun strikeRateText(hundredths: Int?): String? = hundredths?.let { h ->
        val whole = h / 100; val frac = h % 100
        if (frac == 0) "$whole%" else "$whole.${frac.toString().padStart(2, '0').trimEnd('0')}%"
    }

    fun hero(strip: KpiStrip, money: HomeMoney, attendance: AttendanceState, dayState: HomeDayState, locale: UiLocale) = HomeHero(
        grandTotalText = Formats.money(money.grandTotalMtk, locale),
        visited = strip.visitedOutlets, target = strip.plannedOutlets,
        checkInText = timeOf(attendance.checkedInAt, locale), dayState = dayState,
    )

    fun kpis(strip: KpiStrip, categoryLabel: (String) -> String, unitLabel: (String) -> String) = HomeKpis(
        strikeRateText = strikeRateText(strip.strikeRateHundredths), nonVisit = strip.nonVisitOutlets, noSale = strip.noSaleOutlets,
        stock = strip.categories.map { HomeStockLine(categoryLabel(it.categoryCode), it.issueBase, it.currentStockBase, unitLabel(it.unit)) },
    )

    /** A taka amount without the ৳ sign and without a minus (the reference's panel rows: "4,828.50"), digits per locale. */
    fun bare(mtk: Long, locale: UiLocale): String = Formats.digits(com.aktcl.aron.rules.Money.formatTaka(mtk, 2), locale)

    /**
     * The two reference panels (sr/home-2-kpis.webp). Every figure comes from ITS OWN field: the offer discount is the total
     * discount less the DRP part (zero while docs/27 defers the programmes), the QC deduction is `totalQcMtk`, and neither can
     * land under the other's label. Deductions in the older one-line fields keep a true minus.
     */
    fun money(m: HomeMoney, categoryLabel: (String) -> String, locale: UiLocale, packetsSold: Long = 0L) = HomeMoneyLines(
        categories = m.categories.filter { it.netMtk != 0L }.map { HomeMoneyLine(categoryLabel(it.categoryCode), Formats.money(it.netMtk, locale), bare(it.netMtk, locale)) },
        grossText = Formats.money(m.grossMtk, locale),
        discountText = m.totalDiscountMtk.takeIf { it != 0L }?.let { MINUS + Formats.money(it, locale) },
        qcText = m.totalQcMtk.takeIf { it != 0L }?.let { MINUS + Formats.money(it, locale) },
        grandTotalText = Formats.money(m.grandTotalMtk, locale),
        packetsSold = packetsSold,
        grossBare = bare(m.grossMtk, locale),
        offerDiscountBare = bare(m.totalDiscountMtk - m.drpDiscountMtk, locale),
        qcBare = bare(m.totalQcMtk, locale),
        netBare = bare(m.grandTotalMtk, locale),
        totalDiscountBare = bare(m.totalDiscountMtk, locale),
        drpDiscountBare = bare(m.drpDiscountMtk, locale),
        totalQcBare = bare(m.totalQcMtk, locale),
    )

    /**
     * The day state from local facts: attendance (the flow's state), stock issued today, every planned outlet visited,
     * the check-out time reached (the flow's `checkOutEnabled`, or already checked out) and a day submit stored.
     */
    fun dayState(strip: KpiStrip, attendance: AttendanceState, stockIssued: Boolean, submitted: Boolean): HomeDayState = HomeDayModel.dayState(
        checkedIn = attendance.checkedInAt != null, checkedOut = attendance.checkedOutAt != null, stockIssued = stockIssued,
        routeDone = strip.plannedOutlets > 0 && strip.nonVisitOutlets == 0,
        afterCheckoutTime = attendance.checkOutEnabled || attendance.checkedOutAt != null, submitted = submitted,
    )

    suspend fun snapshot(day: SrDay, attendance: AttendanceState, locale: UiLocale, categoryLabel: (String) -> String, unitLabel: (String) -> String): SrHomeSnapshot =
        withContext(Dispatchers.IO) {
            val (strip, money) = day.sale.kpi()
            val sticks = day.sale.packetsSold()
            val date = day.businessDate()
            val dao = day.database.captureDao()
            val state = dayState(strip, attendance, stockIssued = dao.stockOn(date).isNotEmpty(), submitted = dao.daySubmitsOn(date).isNotEmpty())
            SrHomeSnapshot(hero(strip, money, attendance, state, locale), kpis(strip, categoryLabel, unitLabel), money(money, categoryLabel, locale, sticks), state)
        }

    /** The outbox counts, the newest ack and one connectivity read (a binder call), all off the main thread. */
    suspend fun sync(day: SrDay, context: Context, locale: UiLocale): SrSyncSnapshot = withContext(Dispatchers.IO) {
        val dao = day.database.outboxDao()
        val last = dao.lastAckedAtAny()
        SrSyncSnapshot(
            online(context), dao.countInState(OutboxState.PENDING), dao.countInState(OutboxState.IN_FLIGHT), timeOf(last, locale),
            last?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    /** Battery under 20 percent and storage under 500 MB warn (screens-sr s2 device line); the Home shows danger itself under 200 MB. */
    private const val BATTERY_WARN_BELOW = 20

    /**
     * The device line (F-SR-064): battery and free storage read once per resume (no receiver, no timer, docs/04), pending
     * rows and the last sync from the outbox snapshot. Null when the phone reports nothing usable.
     */
    fun health(context: Context, sync: SrSyncSnapshot?, nowMs: Long): com.aktcl.aron.feature.home.DeviceHealth? = runCatching {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: return null
        val stat = android.os.StatFs(context.filesDir.path)
        val freeMb = stat.availableBytes / (1024L * 1024L)
        val ageMin = sync?.lastSyncEpochMs?.let { ((nowMs - it) / 60_000L).coerceAtLeast(0L) }
        com.aktcl.aron.feature.home.DeviceHealthModel.of(battery, freeMb, sync?.pending ?: 0, ageMin, batteryWarnBelow = BATTERY_WARN_BELOW)
    }.getOrNull()

    /** One connectivity read (no callback, no polling): validated internet on the active network. */
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

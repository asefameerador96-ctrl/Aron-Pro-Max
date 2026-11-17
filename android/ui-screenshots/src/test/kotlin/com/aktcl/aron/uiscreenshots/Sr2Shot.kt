package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.memo.ui.MemoSwitchOption
import com.aktcl.aron.feature.memo.ui.MemoSwitcher
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.aktcl.aron.feature.home.R as HomeR

/**
 * Lane P-SR2 (android-parity-sr-2) goldens: the SR screens of the second half of the journey in the host's own configuration,
 * next to the owner's manual images (docs/ui-reference/sr/manual). Record on Linux only: `-Paron.screenshots.recordTo=src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class Sr2Shot {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage = AppLanguage.BN, sunlight: Boolean = false, fontScale: Float = 1.0f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") {
            assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        }
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    // ---- Memo of a credit sale (manual 38) ----
    private val names = mapOf(1L to "MaxR-10S", 2L to "MaxR-20S", 3L to "AB-25s", 4L to "Aster", 5L to "SL")
    private val cats = mapOf(1L to "cigarette", 2L to "cigarette", 3L to "bidi", 4L to "lighter", 5L to "match")
    private val creditMemo = StoredMemo(
        "m1", "sr334001-261105-001", 1, "2026-11-05", "2026-11-05T04:00:00.000Z", null,
        listOf(
            MemoItem(1, 10, 8_000, 80_000, 10, "stick"), MemoItem(2, 20, 8_000, 160_000, 20, "stick"), MemoItem(3, 25, 800, 20_000, 25, "stick"),
            MemoItem(4, 1, 12_500, 12_500, 1, "piece"), MemoItem(5, 12, 1_583, 19_000, 12, "piece"),
        ), emptyList(), emptyList(), 291_500, 0, 0, 0, 291_500, 0, 291_500,
    )

    @Composable
    private fun CreditMemo(paidDialog: Boolean, canMarkPaid: Boolean = true) = ParityFrame(stringResource(HomeR.string.hm_tile_memo), com.aktcl.aron.core.ui.R.drawable.aron_art_sale, printerConnected = true) {
        MemoDetailView(
            MemoMenu.detail(creditMemo), canMarkPaid, 291_500, { names[it] ?: "" }, {}, {}, {}, skuCategory = { cats[it] }, skuUnit = { "" },
            outletName = "Tasmin (1618415)", dashedDueBox = true, dueSideBySide = true, subtotalQuantities = true, letterFilter = true,
            switcher = MemoSwitcher(listOf(MemoSwitchOption("m1", "Tasmin (1618415)", 291_500, 291_500)), "m1") {},
            detailBanner = stringResource(com.aktcl.aron.feature.memo.R.string.memo_banner_manual), buttonsInRow = true,
            paidDialog = paidDialog, onPaidDialogOk = {}, vendorLayout = true,
        )
    }

    @Test fun edit_reason_bn() = shot("sr2_edit_reason_bn") { ParityFrame(stringResource(HomeR.string.hm_tile_memo), com.aktcl.aron.core.ui.R.drawable.aron_art_sale) { com.aktcl.aron.feature.sale.ui.EditReasonDialog({}, {}) } }
    @Config(qualifiers = "bn-w360dp-h1400dp") @Test fun memo_credit_bn() = shot("sr2_memo_credit_bn") { CreditMemo(false) }
    @Config(qualifiers = "bn-w360dp-h1400dp") @Test fun memo_paid_bn() = shot("sr2_memo_paid_bn") { CreditMemo(true) }
    @Test fun memo_credit_font20_bn() = shot("sr2_memo_credit_bn_font20", fontScale = 2.0f) { CreditMemo(false) }

    // ---- Sale data (manual 23) ----
    private fun histMemo(id: String, date: String, vararg l: MemoItem) =
        StoredMemo(id, "sr334001-261110-$id", 1, date, "${date}T04:00:00.000Z", null, l.toList(), emptyList(), emptyList(), 0, 0, 0, 0, 0, 0, 0)
    private val history = com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder.build(
        listOf(histMemo("001", "2026-11-10", MemoItem(1, 20, 6_500, 130_000), MemoItem(2, 10, 6_500, 65_000), MemoItem(3, 20, 6_500, 130_000))), 1,
    )

    @Composable
    private fun SaleData() = ParityFrame(stringResource(com.aktcl.aron.feature.memo.R.string.sale_data_title), null) {
        com.aktcl.aron.feature.memo.ui.SaleDataScreen(history, today = "2026-11-11", outletLabel = null, offlineBanner = false, skuName = { names[it] ?: "" }, skuCategory = { cats[it] })
    }

    @Test fun sale_data_bn() = shot("sr2_sale_data_bn") { SaleData() }
    @Test fun sale_data_sunlight_bn() = shot("sr2_sale_data_sunlight_bn", sunlight = true) { SaleData() }

    // ---- Sales summary with every category incl. the match row (manual 39) ----
    private val sumNames = mapOf(1L to "MaxR-10S", 2L to "MaxR-20S", 3L to "AB-25s", 4L to "Aster", 5L to "SL")
    private val summary = com.aktcl.aron.feature.memo.domain.DaySummaryCalculator.compute(
        listOf(
            com.aktcl.aron.feature.memo.domain.SummaryMemo("a", null, listOf(
                com.aktcl.aron.feature.memo.domain.SummaryLine(1, 10, 80_000), com.aktcl.aron.feature.memo.domain.SummaryLine(2, 20, 160_000), com.aktcl.aron.feature.memo.domain.SummaryLine(3, 25, 20_000),
                com.aktcl.aron.feature.memo.domain.SummaryLine(4, 1, 12_500), com.aktcl.aron.feature.memo.domain.SummaryLine(5, 12, 19_000),
            ), emptyList(), 0, 291_500),
        ),
        cats.mapValues { (id, c) -> com.aktcl.aron.feature.memo.domain.SummarySku(id, c) },
        mapOf(1L to 100L, 2L to 100L, 3L to 100L, 4L to 10L, 5L to 50L),
    )
    private val catNames = mapOf("cigarette" to "সিগারেট", "bidi" to "বিড়ি", "lighter" to "লাইটার", "match" to "ম্যাচ")
    private val unitNames = mapOf("cigarette" to "স্টিক", "bidi" to "স্টিক", "lighter" to "পিস", "match" to "ডজন")

    @Config(qualifiers = "bn-w360dp-h1000dp")
    @Test fun summary_bn() = shot("sr2_summary_bn") {
        ParityFrame(stringResource(com.aktcl.aron.feature.memo.R.string.summary_title), com.aktcl.aron.core.ui.R.drawable.aron_art_sale, printerConnected = true) {
            // the SR host lists the categories in the vendor's order on its screen only (lead gate finding 3)
            com.aktcl.aron.feature.memo.ui.SummaryScreen(com.aktcl.aron.feature.memo.domain.DaySummaryCalculator.inCategoryOrder(summary), { sumNames[it] ?: "" }, {}, categoryLabel = { catNames[it] ?: it }, unitLabel = { unitNames[it] ?: "" })
        }
    }

    // ---- Sync and submit (manual 40, 41): business figures, device against server ----
    private fun qty(c: String, base: Long, entered: Long, unit: String) = com.aktcl.aron.feature.dayclose.CategoryQty(c, base, entered, unit)
    private val dev = com.aktcl.aron.feature.dayclose.BusinessTotals(39, listOf(qty("cigarette", 300, 30, "packet"), qty("match", 12, 1, "dozen")), listOf(qty("cigarette", 1000, 100, "packet")), emptyList(), 0)
    private val srv = com.aktcl.aron.feature.dayclose.BusinessTotals(39, listOf(qty("cigarette", 300, 30, "packet"), qty("match", 12, 1, "dozen")), listOf(qty("cigarette", 1000, 100, "packet")), emptyList(), 0)

    private val recLabels = mapOf("visit" to "আউটলেট", "memo" to "বিক্রয়", "stock_movement" to "স্টক", "qc_line" to "কিউসি", "memo_discount" to "প্রমোশন")

    @Composable
    private fun Submit(dues: Int, serverFigures: com.aktcl.aron.feature.dayclose.BusinessTotals?) {
        val counts = listOf(com.aktcl.aron.feature.dayclose.CountRow("memo", 6, 6))
        val gate = com.aktcl.aron.feature.dayclose.SalesSubmitRules.gate(com.aktcl.aron.feature.dayclose.OutboxStates(0, 0, 0, 0, 0), counts, com.aktcl.aron.feature.dayclose.DuesAtSubmit(dues, if (dues > 0) 84_000 else 0), false)
        ParityFrame(stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_title), com.aktcl.aron.core.ui.R.drawable.aron_art_sale) {
            com.aktcl.aron.feature.dayclose.SalesSubmitScreen(
                true, counts, gate, com.aktcl.aron.feature.dayclose.SubmitProgress.Idle, { recLabels[it] ?: it }, {}, {},
                business = dev, serverBusiness = serverFigures, categoryLabel = { catNames[it] ?: it }, vendorDialogs = true, srDuesInDialog = true, checkoutHint = true,
            )
        }
    }

    @Test fun submit_bn() = shot("sr2_submit_bn") { Submit(0, srv) }
    @Test fun submit_due_bn() = shot("sr2_submit_due_bn") { Submit(1, srv) }

    // ---- Tasks (manual 47): the card, the type in red, no raw codes ----
    private val taskState = com.aktcl.aron.feature.tasks.TaskListState(
        listOf(
            com.aktcl.aron.feature.tasks.TaskItem("t1", "poor_brand_visibility", "Poor Brand Visibility", "রুকু স্টোরে দ্রুত প্রোডাক্ট দেও", 5, "Ruku Store", "2026-11-11", "ongoing", null),
            com.aktcl.aron.feature.tasks.TaskItem("t2", "visit_outlet", "OOS", "Babu Store দোকানে Avon এ OOS আছে", 6, "Babu Store", "2026-11-30", "ongoing", null),
            com.aktcl.aron.feature.tasks.TaskItem("t3", "collect_due", "Due collection", null, 7, "Tasmin", "2026-11-09", "ongoing", null),
        ),
        3, "2026-11-11T04:42:00.000Z",
    )

    @Test fun tasks_bn() = shot("sr2_tasks_bn") {
        ParityFrame("টাস্ক", null) {
            com.aktcl.aron.feature.tasks.TaskContent(taskState, {}, nowMs = java.time.Instant.parse("2026-11-11T05:00:00Z").toEpochMilli(), manual = true)
        }
    }
    @Test fun tasks_empty_bn() = shot("sr2_tasks_empty_bn") {
        ParityFrame("টাস্ক", null) {
            com.aktcl.aron.feature.tasks.TaskContent(com.aktcl.aron.feature.tasks.TaskListState(emptyList(), 0, null, showEmptyState = true), {}, manual = true)
        }
    }

    // ---- Product QC (manuals 25, 26) ----
    private val maxr = com.aktcl.aron.feature.sale.domain.SaleSku(100, "MAXR10", "cigarette", "MaxR-10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400)
    private val maxb = com.aktcl.aron.feature.sale.domain.SaleSku(101, "MAXB10", "cigarette", "MaxB-10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400)
    private val qcSkus = listOf(maxr, maxb, com.aktcl.aron.feature.sale.domain.SaleSku(102, "MAXWB10", "cigarette", "MaxWB-10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400))
    private val qcCatalog = qcSkus.associateBy { it.skuId }
    private fun qcDraft0() = com.aktcl.aron.feature.sale.domain.SaleDraft("v1", 50001, 10231, "2026-11-05", memoUuid = "m1")
    private val qcBase = com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQuantity(qcDraft0(), 100, 10, com.aktcl.aron.rules.QtyUnit.PACK)
    private val qcNone = com.aktcl.aron.feature.sale.domain.SaleReviewCalculator.review(qcBase, qcCatalog)
    private val qcSaved = com.aktcl.aron.feature.sale.domain.SaleReviewCalculator.review(
        com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQc(com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQc(qcBase, com.aktcl.aron.feature.sale.domain.QcEntry(100, "damaged_crushed", "MFC", 5)), com.aktcl.aron.feature.sale.domain.QcEntry(100, "expired_stock", "MKT", 5)), qcCatalog,
    )

    @Composable
    private fun QcFrame(title: String, review: com.aktcl.aron.feature.sale.domain.SaleReview, startOnEntry: Boolean = false) = ParityFrame(title, null) {
        val qc = review.qc
        com.aktcl.aron.feature.sale.ui.QcManualScreen(
            qcSkus, review, { id, code -> qc.firstOrNull { it.entry.skuId == id && it.entry.faultTypeCode == code }?.entry?.defectQty ?: 0L }, { _, _, _ -> }, false, {},
            capMtkOf = { 594_500L }, startOnEntrySku = if (startOnEntry) 100L else null,
        )
    }

    @Config(qualifiers = "bn-w360dp-h700dp") @Test fun qc_tiles_empty_bn() = shot("sr2_qc_tiles_empty_bn") { QcFrame("QC", qcNone) }
    @Config(qualifiers = "bn-w360dp-h700dp") @Test fun qc_tiles_saved_bn() = shot("sr2_qc_tiles_saved_bn") { QcFrame("QC", qcSaved) }
    @Config(qualifiers = "bn-w360dp-h900dp") @Test fun qc_entry_bn() = shot("sr2_qc_entry_bn") { QcFrame("QC এন্ট্রি", qcSaved, startOnEntry = true) }

    // ---- DRP slide (manuals 33, 34) ----
    private val slideSkus = listOf(
        com.aktcl.aron.feature.sale.domain.SaleSku(100, "MAXR10", "cigarette", "MaxR-10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400, drp = com.aktcl.aron.feature.sale.domain.DrpRule(10)),
        com.aktcl.aron.feature.sale.domain.SaleSku(101, "MAXR20", "cigarette", "MaxR-20S", null, "stick", 20, 16_000, 1, "2026-09-01", stockBase = 400, drp = com.aktcl.aron.feature.sale.domain.DrpRule(10)),
    )
    private val slideReview = com.aktcl.aron.feature.sale.domain.SaleReviewCalculator.review(
        com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQuantity(com.aktcl.aron.feature.sale.domain.SaleDraft("v1", 50001, 10231, "2026-11-05", memoUuid = "m1"), 100, 10, com.aktcl.aron.rules.QtyUnit.PACK),
        slideSkus.associateBy { it.skuId },
    )

    @Test fun slide_bn() = shot("sr2_slide_bn") {
        ParityFrame("স্লাইড", null) {
            com.aktcl.aron.feature.sale.ui.SlideManualScreen(
                slideSkus, slideReview, { if (it == 100L) 10L else 0L }, { _, _ -> }, {},
                listOf("Savar Metro (1618409)", "রুট: Savar BazarDaily", "ক্লাস্টার: Madrasa Road"),
            )
        }
    }
    @Test fun slide_empty_bn() = shot("sr2_slide_empty_bn") {
        ParityFrame("স্লাইড", null) {
            com.aktcl.aron.feature.sale.ui.SlideManualScreen(emptyList(), slideReview, { 0L }, { _, _ -> }, {}, listOf("Savar Metro (1618409)"))
        }
    }

    // ---- Programme hook pages (manuals 10, 60, 61) ----
    @Test fun hook_target_bn() = shot("sr2_hook_target_bn") {
        ParityFrame(com.aktcl.aron.feature.memo.ui.programmeHookTitle(com.aktcl.aron.feature.memo.ui.ProgrammeHook.TARGET), null) {
            com.aktcl.aron.feature.memo.ui.ProgrammeHookContent(com.aktcl.aron.feature.memo.ui.ProgrammeHook.TARGET, {})
        }
    }
    @Test fun hook_kpi_bn() = shot("sr2_hook_kpi_bn") {
        ParityFrame(com.aktcl.aron.feature.memo.ui.programmeHookTitle(com.aktcl.aron.feature.memo.ui.ProgrammeHook.KPI), null) {
            com.aktcl.aron.feature.memo.ui.ProgrammeHookContent(com.aktcl.aron.feature.memo.ui.ProgrammeHook.KPI, {})
        }
    }

    // ================= slice 4 =================
    // The outlet screens (manuals 42 to 45), Tutorial (46), Sales Journey (59), Settings and Logout (63 to 65).
    // The fixtures carry the manual's own test data so a golden can be laid beside docs/ui-reference/sr/manual/NN-*.jpg.

    // ---- Outlet requests: new shop, permanent close, change information (manuals 42, 43, 44) ----
    private fun reqOutlet(id: Long, code: String, name: String, owner: String, mobile: String, clusterId: Long, cluster: String) = OutletEntity(
        outletId = id, routeId = 1, code = code, name = name, nameBn = null, nameSortKey = com.aktcl.aron.rules.TextRules.nameSortKey(name),
        ownerName = owner, contactNumber = mobile, lat = 23.79, lng = 90.40, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = clusterId, clusterName = cluster, channel = "GT", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = id.toInt(), openDueMtk = 0, openDueAsOf = null,
        programmeFlagsJson = "[]", pendingRequest = false,
    )

    private val reqClusters = listOf(
        com.aktcl.aron.feature.outlet.ClusterOption(1, "111 No Road"), com.aktcl.aron.feature.outlet.ClusterOption(2, "108 No Road"),
        com.aktcl.aron.feature.outlet.ClusterOption(3, "Road No 108"), com.aktcl.aron.feature.outlet.ClusterOption(4, "Lakepar"),
        com.aktcl.aron.feature.outlet.ClusterOption(5, "Agora Road"), com.aktcl.aron.feature.outlet.ClusterOption(6, "107 No Road"),
    )
    // The manual's own shops. Its mobile column holds the test value "123"; the labels here carry real 11-digit numbers so the
    // golden shows the "name (code-phone-cluster)" shape the rep sees in the field (an unusable number is left out of the label).
    private val reqOutlets = listOf(
        reqOutlet(1, "3700936", "Mojammel Store", "Mojammel", "01711000936", 1, "111 No Road"),
        reqOutlet(2, "2680604", "Mofijul Stlre", "Md Mofijul Islam", "01712680604", 6, "107 No Road"),
        reqOutlet(3, "2650259", "Hafiz Store", "Hafiz", "01713650259", 5, "Agora Road"),
        reqOutlet(4, "2657503", "Bashar Telecom", "Bashar", "01714657503", 2, "108 No Road"),
    )

    @Composable
    private fun RequestForm(kind: com.aktcl.aron.feature.outlet.OutletRequestKind, form: com.aktcl.aron.feature.outlet.OutletRequestForm) =
        ParityFrame(
            stringResource(com.aktcl.aron.feature.outlet.srRequestTitle(kind)),
            when (kind) {
                com.aktcl.aron.feature.outlet.OutletRequestKind.NEW -> com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new
                com.aktcl.aron.feature.outlet.OutletRequestKind.CLOSE -> com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_close
                else -> com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_edit
            },
        ) {
            com.aktcl.aron.feature.outlet.SrOutletRequestContent(
                form, emptySet(), reqClusters, reqOutlets, com.aktcl.aron.feature.outlet.GeoPhotoState(),
                saving = false, savedDialog = false, onChange = {}, onPickOutlet = {}, onShutter = {}, onSave = {}, onSavedOk = {},
            )
        }

    @Config(qualifiers = "bn-w360dp-h900dp") @Test fun req_new_bn() = shot("sr2_req_new_bn") {
        RequestForm(com.aktcl.aron.feature.outlet.OutletRequestKind.NEW, com.aktcl.aron.feature.outlet.OutletRequestForm(com.aktcl.aron.feature.outlet.OutletRequestKind.NEW))
    }
    @Config(qualifiers = "bn-w360dp-h900dp") @Test fun req_close_empty_bn() = shot("sr2_req_close_empty_bn") {
        RequestForm(com.aktcl.aron.feature.outlet.OutletRequestKind.CLOSE, com.aktcl.aron.feature.outlet.OutletRequestForm(com.aktcl.aron.feature.outlet.OutletRequestKind.CLOSE))
    }
    @Config(qualifiers = "bn-w360dp-h900dp") @Test fun req_close_picked_bn() = shot("sr2_req_close_picked_bn") {
        RequestForm(
            com.aktcl.aron.feature.outlet.OutletRequestKind.CLOSE,
            com.aktcl.aron.feature.outlet.OutletRequestForm(com.aktcl.aron.feature.outlet.OutletRequestKind.CLOSE, outletId = 1, clusterId = 1),
        )
    }
    @Config(qualifiers = "bn-w360dp-h1000dp") @Test fun req_info_bn() = shot("sr2_req_info_bn") {
        RequestForm(
            com.aktcl.aron.feature.outlet.OutletRequestKind.INFO,
            com.aktcl.aron.feature.outlet.OutletRequestForm(
                com.aktcl.aron.feature.outlet.OutletRequestKind.INFO, outletId = 2, name = "Mofijul Stlre", ownerName = "Md Mofijul Islam",
                mobile = "123", clusterId = 6, currentClusterId = 6,
            ),
        )
    }

    // ---- Outlet menu (the four tiles the requests open from) ----
    @Test fun outlet_menu_bn() = shot("sr2_outlet_menu_bn") {
        ParityFrame(stringResource(HomeR.string.hm_tile_outlet), com.aktcl.aron.core.ui.R.drawable.aron_art_outlet) {
            com.aktcl.aron.feature.outlet.OutletMenuContent({})
        }
    }

    // ---- Clusters (manual 45) ----
    private val srClusters = listOf(
        com.aktcl.aron.feature.outlet.SrCluster(1, null, "111 No Road", "Agora GoliDaily", false),
        com.aktcl.aron.feature.outlet.SrCluster(2, null, "108 No Road", "Agora GoliDaily", false),
        com.aktcl.aron.feature.outlet.SrCluster(3, null, "Road No 108", "Agora GoliDaily", false),
        com.aktcl.aron.feature.outlet.SrCluster(4, null, "Road No 108,House 14", "Agora GoliDaily", false),
        com.aktcl.aron.feature.outlet.SrCluster(5, null, "Lakepar", "Agora GoliDaily", false),
        com.aktcl.aron.feature.outlet.SrCluster(6, null, "Lake View Sight", "Agora GoliDaily", false),
        com.aktcl.aron.feature.outlet.SrCluster(7, null, "Agora Road", "Agora GoliDaily", false),
        com.aktcl.aron.feature.outlet.SrCluster(8, null, "Pink City", "Agora GoliDaily", false),
    )

    @Config(qualifiers = "bn-w360dp-h900dp") @Test fun clusters_bn() = shot("sr2_clusters_bn") {
        ParityFrame(stringResource(com.aktcl.aron.feature.outlet.R.string.out_clusters_title), null) {
            com.aktcl.aron.feature.outlet.ClustersContent(srClusters, { _, _ -> null })
        }
    }
    @Test fun clusters_empty_bn() = shot("sr2_clusters_empty_bn") {
        ParityFrame(stringResource(com.aktcl.aron.feature.outlet.R.string.out_clusters_title), null) {
            com.aktcl.aron.feature.outlet.ClustersContent(emptyList(), { _, _ -> null })
        }
    }

    // ---- Sales Journey (manual 59) ----
    private fun call(uuid: String, outlet: Long, from: String, to: String?, lat: Double?, lng: Double?, sold: Boolean, edited: Boolean = false) =
        com.aktcl.aron.rules.JourneyCallInput(
            uuid, outlet, java.time.Instant.parse(from).toEpochMilli(), to?.let { java.time.Instant.parse(it).toEpochMilli() },
            lat, lng, sold, edited,
        )
    private val journeyRows = com.aktcl.aron.rules.JourneyTimeline.build(
        listOf(
            call("v1", 1, "2026-11-11T03:53:35Z", "2026-11-11T03:59:02Z", 23.7925, 90.4078, true),
            call("v2", 2, "2026-11-11T04:06:55Z", "2026-11-11T04:28:54Z", 23.7962, 90.4090, true),
            call("v3", 3, "2026-11-11T04:29:04Z", "2026-11-11T04:34:16Z", 23.7962, 90.4090, true),
            call("v4", 4, "2026-11-11T04:34:35Z", "2026-11-11T04:47:51Z", 23.7966, 90.4095, true, edited = true),
        ),
    )
    private val journeyLines = mapOf(
        1L to "Fahim Store (2689713-01925658444-Agora Road)", 2L to "Ruku Store (2681268-01778553683-Agora Goli, Gulshan-2)",
        3L to "Kuddus Store (3700734-123-Agora Road)", 4L to "Hafiz Store (2650259-123-Agora Road)",
    )

    @Config(qualifiers = "bn-w360dp-h1200dp") @Test fun journey_bn() = shot("sr2_journey_bn") {
        ParityFrame(stringResource(HomeR.string.hm_tile_sales_journey), com.aktcl.aron.core.ui.R.drawable.aron_art_sales_journey) {
            com.aktcl.aron.feature.memo.ui.SalesJourneyScreen(
                journeyRows.map { com.aktcl.aron.feature.memo.ui.SalesJourneyCall(it, journeyLines[it.input.outletId].orEmpty()) },
                com.aktcl.aron.rules.UiLocale.BN,
            )
        }
    }
    @Test fun journey_empty_bn() = shot("sr2_journey_empty_bn") {
        ParityFrame(stringResource(HomeR.string.hm_tile_sales_journey), com.aktcl.aron.core.ui.R.drawable.aron_art_sales_journey) {
            com.aktcl.aron.feature.memo.ui.SalesJourneyScreen(emptyList(), com.aktcl.aron.rules.UiLocale.BN)
        }
    }

    // ---- Tutorial (manual 46: the empty state is the manual's own screen) ----
    @Test fun tutorial_empty_bn() = shot("sr2_tutorial_empty_bn") {
        com.aktcl.aron.feature.home.TutorialListContent(emptyList(), online = true, onPlay = {}, onBack = {})
    }

    // ---- KPI Dashboard and Brand wise KPI (manuals 60, 61; lead ruling 2026-11-13) ----
    private val kpiStrip = com.aktcl.aron.feature.memo.domain.KpiStrip(
        plannedOutlets = 30, visitedOutlets = 22, strikeRateHundredths = 7333, nonVisitOutlets = 8, noSaleOutlets = 3,
        categories = listOf(
            com.aktcl.aron.feature.memo.domain.KpiCategory("cigarette", 240, 96, "packet"),
            com.aktcl.aron.feature.memo.domain.KpiCategory("match", 60, 24, "dozen"),
        ),
    )
    private val kpiCats = mapOf("cigarette" to "সিগারেট", "match" to "ম্যাচ")
    private val kpiUnits = mapOf("packet" to "প্যাকেট", "dozen" to "ডজন")

    @Config(qualifiers = "bn-w360dp-h1900dp") @Test fun kpi_dashboard_bn() = shot("sr2_kpi_dashboard_bn") {
        ParityFrame(stringResource(com.aktcl.aron.feature.memo.R.string.hook_kpi_title), com.aktcl.aron.core.ui.R.drawable.aron_art_kpi) {
            com.aktcl.aron.feature.memo.ui.KpiDashboardContent(
                kpiStrip, com.aktcl.aron.feature.memo.ui.KpiMonth("November 2026", 10, 30),
                { kpiCats[it] ?: it }, { kpiUnits[it] ?: it }, onSeeAllBrands = {},
            )
        }
    }
    @Config(qualifiers = "bn-w360dp-h1000dp") @Test fun kpi_brand_bn() = shot("sr2_kpi_brand_bn") {
        ParityFrame(stringResource(com.aktcl.aron.feature.memo.R.string.hook_brand_title), com.aktcl.aron.core.ui.R.drawable.aron_art_kpi) {
            com.aktcl.aron.feature.memo.ui.BrandKpiContent({})
        }
    }

    // ---- Settings, with the fourth Update Configuration tile (manuals 63, 64) and Logout (65) ----
    @Config(qualifiers = "bn-w360dp-h900dp") @Test fun settings_bn() = shot("sr2_settings_bn") {
        com.aktcl.aron.feature.home.SettingsContent(
            "0.1.0 (12)", {}, {}, onUpdate = {}, updateAvailable = false, photosWifiOnly = true, onSupport = {},
            onUpdateConfig = { com.aktcl.aron.feature.home.ConfigUpdate.UPDATED }, onBack = {}, sunlight = false,
        )
    }
}

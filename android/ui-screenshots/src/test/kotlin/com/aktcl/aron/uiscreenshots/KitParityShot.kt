package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.R
import com.aktcl.aron.core.ui.aron.AronDataTable
import com.aktcl.aron.core.ui.aron.AronHomeHeader
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.AronNoticeCard
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronPrinterBadge
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.AronStatusDot
import com.aktcl.aron.core.ui.aron.AronTargetCard
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.AronTileGrid
import com.aktcl.aron.core.ui.aron.AronTopBar
import com.aktcl.aron.core.ui.aron.ButtonGlyph
import com.aktcl.aron.core.ui.aron.LetterFilterBar
import com.aktcl.aron.core.ui.aron.PackCard
import com.aktcl.aron.core.ui.aron.PackListHeader
import com.aktcl.aron.core.ui.aron.RetailerPickerField
import com.aktcl.aron.core.ui.aron.SummaryRow
import com.aktcl.aron.core.ui.aron.SummaryTable
import com.aktcl.aron.core.ui.aron.SummaryTotal
import com.aktcl.aron.core.ui.aron.TotalsLine
import com.aktcl.aron.core.ui.aron.TotalsPanel
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Parity mock-ups: each reference screen of docs/ui-reference/sr/ rebuilt from the parity kit only, with the reference's own
 * sample data, at 411 dp (the reference phone: 1146 px at about 2.8x) in Bangla. Look-and-compare captures for docs/evidence/ui-parity/kit-*.png (not goldens, never verified);
 * the screens themselves belong to the feature lanes. Run: `--tests '*KitParityShot*'`, PNGs in `build/kitshots/parity_*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "bn-w360dp-h640dp")
class KitParityShot {
    @get:Rule val rule = createComposeRule()

    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private fun shot(name: String, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.BN) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "parity_$name.png").path, roborazziOptions = options)
    }

    @Test @Config(qualifiers = "bn-w411dp-h733dp") fun home() = shot("home") {
        val tiles = listOf(
            "অ্যাটেনডেন্স" to R.drawable.aron_art_attendance, "স্টক" to R.drawable.aron_art_stock, "বিক্রয়" to R.drawable.aron_art_sale, "মেমো" to R.drawable.aron_art_memo,
            "সারসংক্ষেপ" to R.drawable.aron_art_summary, "বিক্রয় জমা" to R.drawable.aron_art_sales_submit, "আউটলেট" to R.drawable.aron_art_outlet, "টিউটোরিয়াল" to R.drawable.aron_art_tutorial,
            "টাস্ক ডেলিগেশন" to R.drawable.aron_art_task_delegation, "Photo Capture" to R.drawable.aron_art_photo_capture, "বিক্রয় যাত্রা" to R.drawable.aron_art_sales_journey, "কেপিআই" to R.drawable.aron_art_kpi,
        )
        Column(Modifier.fillMaxSize().background(LocalAronLook.current.pageHome)) {
            AronHomeHeader("SR - Testing Banani (sr334001)", "Apsis RouteDaily, ২০২৬-১০-০৫", onSettings = {}, settingsLabel = "সেটিংস")
            AronTileGrid(tiles, Modifier.padding(6.dp)) { (label, icon), m -> AronTile(label, painterResource(icon), onClick = {}, modifier = m) }
            AronTargetCard("টার্গেট ও অ্যাচিভমেন্ট", "আজকের বিক্রয়", Modifier.padding(6.dp), value = "৪,৩৯১.০০৳")
        }
    }

    @Test @Config(qualifiers = "bn-w411dp-h717dp") fun stock() = shot("stock") {
        val packs = listOf("MaxR-10S", "MaxR-20S", "MaxB-10S", "MaxB-20S", "MaxWB-10S")
        AronScaffold(
            topBar = {
                AronTopBar("স্টক", icon = painterResource(R.drawable.aron_art_stock), onBack = {}, backLabel = "পেছনে",
                    hanging = { AronPrinterBadge(connected = false, label = "প্রিন্টার সংযুক্ত নয়", onClick = {}) })
            },
            bottomPanel = {
                TotalsPanel(
                    TotalsLine("ক্যাটাগরি", listOf("মোট ইস্যু", "স্টক")),
                    listOf(TotalsLine("সিগারেট", listOf("০", "০")), TotalsLine("বিড়ি", listOf("০", "০")), TotalsLine("লাইটার", listOf("৪০০", "২৫")), TotalsLine("ম্যাচ", listOf("৪০০", "৩৯৪"))),
                )
            },
            bottomButtons = {
                AronPrimaryButton("সংরক্ষণ", {}, Modifier.weight(1f), glyph = ButtonGlyph.Save)
                AronSecondaryButton("প্রিন্ট", {}, Modifier.weight(1f), enabled = false, glyph = ButtonGlyph.Print)
            },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(16.dp))
                PackListHeader("এসকেইউ", "ইস্যু", "স্টক")
                Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    packs.forEach { PackCard(it, it, "০", {}, {}, {}, "০", "কমান", "বাড়ান", "পরিমাণ", packBadge = "০") }
                }
            }
        }
    }

    @Test @Config(qualifiers = "bn-w411dp-h272dp") fun sale_select() = shot("sale_select") {
        Column(Modifier.fillMaxSize().background(LocalAronLook.current.page)) {
            AronTopBar("বিক্রয়", icon = painterResource(R.drawable.aron_art_sale), onBack = {}, backLabel = "পেছনে")
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RetailerPickerField(null, "রিটেইলার নির্বাচন করুন", onClick = {})
                LetterFilterBar(listOf("All", "A", "B", "E", "J", "M", "N", "R", "S", "T", "a", "b", "c", "j", "k", "o", "s", "t"), "All", {})
            }
        }
    }

    @Test @Config(qualifiers = "bn-w411dp-h649dp") fun summary() = shot("summary") {
        Column(Modifier.fillMaxSize().background(LocalAronLook.current.page)) {
            AronTopBar("বিক্রয় সারসংক্ষেপ", icon = painterResource(R.drawable.aron_art_summary), onBack = {}, backLabel = "পেছনে")
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AronInfoCard("আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে", trailing = { AronPrinterBadge(false, "প্রিন্টার সংযুক্ত নয়", {}) })
                SummaryTable(
                    listOf("মেমো", "পরিমাণ", "মূল্য", "ডিসকাউন্ট", "ডিসকাউন্ট মূল্য", "ফেরত"),
                    listOf(
                        SummaryRow("Aster", listOf("৪", "৩৭৫", "৪,৬৮৭.৫০৳", "৪৩৭.৫০", "৪,২৫০.০০৳", "২৫")),
                        SummaryRow("FB", listOf("১", "৩", "৮৪.০০৳", "০.০০", "৮৪.০০৳", "১৯৭")),
                        SummaryRow("SL", listOf("১", "৩", "৫৭.০০৳", "০.০০", "৫৭.০০৳", "১৯৭")),
                    ),
                    subtotals = listOf(TotalsLine("লাইটার", listOf("৩৭৫", "৪,৬৮৭.৫০৳", "৪৩৭.৫", "৪,২৫০.০০৳", "২৫")), TotalsLine("ম্যাচ", listOf("৬", "১৪১.০০৳", "০.০", "১৪১.০০৳", "৩৯৪"))),
                    totals = listOf(SummaryTotal("ডিসকাউন্ট এবং অন্যান্য (-)", "-৪৩৭.৫০"), SummaryTotal("সর্বমোট", "৪,৩৯১.০০")),
                )
                AronPrimaryButton("প্রিন্ট", {}, Modifier.fillMaxWidth(), glyph = ButtonGlyph.Print, reverse = true)
            }
        }
    }

    @Test @Config(qualifiers = "bn-w411dp-h481dp") fun sales_submit() = shot("sales_submit") {
        Column(Modifier.fillMaxSize().background(LocalAronLook.current.page)) {
            AronTopBar("বিক্রয় জমা", icon = painterResource(R.drawable.aron_art_sales_submit), onBack = {}, backLabel = "পেছনে")
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AronStatusDot("ডিভাইস স্ট্যাটাস", "অনলাইন", online = true)
                AronDataTable(
                    listOf("বিষয়", "ডিভাইস", "সার্ভার"),
                    listOf(listOf("আউটলেট", "৬", "০"), listOf("বিক্রয়", "৩৮১", "০"), listOf("স্টক", "৮০০", "০"), listOf("কিউসি", "০", "০"), listOf("প্রমোশন", "০", "০")),
                )
                AronNoticeCard("আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন।")
                AronPrimaryButton("ডাটা সিঙ্ক করুন", {}, Modifier.fillMaxWidth(), glyph = ButtonGlyph.Sync)
                AronPrimaryButton("বিক্রয় জমা", {}, Modifier.fillMaxWidth(), enabled = false, glyph = ButtonGlyph.Submit)
            }
        }
    }

    @Test @Config(qualifiers = "bn-w411dp-h401dp") fun outlet_menu() = shot("outlet_menu") {
        val look = LocalAronLook.current
        val items = listOf(
            "নতুন দোকান" to R.drawable.aron_art_outlet_new, "স্থায়ী বন্ধ" to R.drawable.aron_art_outlet_close,
            "তথ্য পরিবর্তন করুন" to R.drawable.aron_art_outlet_edit, "ক্লাস্টার" to R.drawable.aron_art_cluster,
        )
        Column(Modifier.fillMaxSize().background(look.page)) {
            AronTopBar("আউটলেট", icon = painterResource(R.drawable.aron_art_outlet), onBack = {}, backLabel = "পেছনে")
            Box(Modifier.padding(12.dp)) {
                AronTileGrid(items, columns = 3) { (label, icon), m -> AronTile(label, painterResource(icon), {}, m, menu = true, labelColor = look.tileLabelOutlet) }
            }
        }
    }
}

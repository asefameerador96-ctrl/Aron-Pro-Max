package com.aktcl.aron.feature.tso.targets

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.TsoKitTags
import com.aktcl.aron.feature.tso.TsoLoginTheme
import com.aktcl.aron.feature.tso.a11yProblems
import com.aktcl.aron.rules.Band
import com.aktcl.aron.rules.Bands
import com.aktcl.aron.rules.Targets
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Target Status and Target Details (build contract s4.3 and s8 rows 39 to 42; D-TSO-MAN-39, 40; R-F-01, R-F-24): every s4.3 fixture
 * through the parser and the printers (the shared rules only), the cards of screenshots 39 and 40, the Details of 41 and 42, the
 * tab state that keeps the node and the filter, and the category filter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h2600dp")
class TargetStatusTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val en = UiLocale.EN
    private val stamp = "\"month\":\"2026-10\",\"as_of\":\"2026-10-25T09:35:00Z\",\"day\":\"2026-10-25\",\"tilldate_basis\":\"calendar_days\",\"last_updated_at\":null"

    private fun cat(code: String, name: String, t: Long, a: Long, td: Long?) =
        """{"product_level":"category","product_id":${code.length},"name":"$name","unit":"stick","target_qty":"$t.000","achieved_qty":"$a.000","tilldate_target_qty":"${td ?: 0}.000","remaining_qty":"0.000","category_code":"$code","target_qty_base":$t,"achieved_qty_base":$a,"tilldate_target_qty_base":${td ?: "null"},"remaining_qty_base":0,"remaining_tilldate_qty_base":null,"achievement_pct_bp":9999,"tilldate_achievement_pct_bp":9999}"""

    /** Screenshots 39 and 40 (D = 2026-10-25, e = 25, n = 31): the server's category rows, in a shuffled order. */
    private val categories = listOf(
        cat("match", "Match", 170, 72, 137), cat("cigarette", "Cigarette", 40999, 1030, 33064),
        cat("lighter", "Lighter", 100, 85, 81), cat("bidi", "Bidi", 11000, 50, 8871),
    ).joinToString(",")

    private fun status(childPeriod: Int = 31, childRows: String = categories, childId: Long = 9) = """{$stamp,
        "node":{"type":"territory","id":3,"code":"T3","name":"Apsis"},"elapsed_days":25,"period_days":31,"rows":[$categories],
        "children":[{"node":{"type":"zone","id":$childId,"name":"Banani - Test"},"elapsed_days":25,"period_days":$childPeriod,"rows":[$childRows]}]}"""

    private fun item(id: Long, name: String, code: String, t: Long, a: Long, td: Long?) =
        """{"product_level":"variant","product_id":$id,"name":"$name","unit":"stick","target_qty":"$t.000","achieved_qty":"$a.000","tilldate_target_qty":"0.000","remaining_qty":"0.000","category_code":"$code","target_qty_base":$t,"achieved_qty_base":$a,"tilldate_target_qty_base":${td ?: "null"},"remaining_qty_base":0,"remaining_tilldate_qty_base":null,"achievement_pct_bp":null,"tilldate_achievement_pct_bp":null}"""

    /** The eight Cigarette items of screenshots 41 and 42 (s4.3: monthly 40999, achieved 1030) with T' at [e]/[n], and Aster. */
    private val cigarettes = listOf(
        Triple("Maxim Regular", 1000L, 350L), Triple("Maxim Bolt", 1000L, 330L), Triple("Maxim Double Burst", 10000L, 20L),
        Triple("Marise Special Blend", 10000L, 200L), Triple("Black Diamond Double Filter", 5000L, 30L), Triple("Black Diamond Advanced", 6000L, 30L),
        Triple("Sunmoon American blend", 2999L, 40L), Triple("Supreme Signature", 5000L, 30L),
    )
    private fun details(e: Long = 25, n: Long = 31, period: Int = n.toInt()) = """{$stamp,"node":{"type":"territory","id":3,"name":"Apsis"},"elapsed_days":$e,"period_days":$period,"rows":[""" +
        (cigarettes.mapIndexed { i, (name, t, a) -> item(100L + i, name, "cigarette", t, a, Targets.tillDate(t, e, n)) } +
            item(200, "Ananda Bidi 25", "bidi", 11000, 50, Targets.tillDate(11000, e, n)) +
            item(300, "Aster", "lighter", 100, 85, Targets.tillDate(100, e, n)) +
            item(400, "Dhaka Match", "match", 170, 72, Targets.tillDate(170, e, n))).joinToString(",") + "]}"

    // ---- s4.3 fixtures through the parser and the printers ------------------------------------------------------------------

    @Test fun theCardsOfScreenshots39And40() {
        val a = TargetStatus.parse(status())!!
        assertEquals(listOf("Apsis", "Banani - Test"), a.cards.map { it.ref.name })
        val rows = TargetStatus.ordered(a.node.rows)
        assertEquals(listOf("cigarette", "bidi", "lighter", "match"), rows.map { it.categoryCode })
        // monthly (screenshot 39)
        assertEquals(listOf("1030/40999", "50/11000", "85/100", "72/170"), rows.map { TargetStatus.fraction(it, TargetTab.MONTHLY, en) })
        assertEquals(listOf("3%", "0%", "85%", "42%"), rows.map { TargetStatus.cardPctText(it, TargetTab.MONTHLY, en) })
        assertEquals(listOf(Band.RED, Band.RED, Band.GREEN, Band.AMBER), rows.map { TargetStatus.band(it, TargetTab.MONTHLY, Bands.DEFAULT) })
        // till date (screenshot 40): 33064 / 8871 / 81 / 137, 85 of 81 capped at 100
        assertEquals(listOf("1030/33064", "50/8871", "85/81", "72/137"), rows.map { TargetStatus.fraction(it, TargetTab.TILL_DATE, en) })
        assertEquals(listOf("3%", "1%", "100%", "53%"), rows.map { TargetStatus.cardPctText(it, TargetTab.TILL_DATE, en) })
        assertEquals(listOf(Band.RED, Band.RED, Band.GREEN, Band.AMBER), rows.map { TargetStatus.band(it, TargetTab.TILL_DATE, Bands.DEFAULT) })
        // Bangla digits; "%" and "/" stay Latin
        assertEquals("১০৩০/৪০৯৯৯", TargetStatus.fraction(rows[0], TargetTab.MONTHLY, UiLocale.BN))
        assertEquals("১০০%", TargetStatus.cardPctText(rows[2], TargetTab.TILL_DATE, UiLocale.BN))
        // the server's *_pct_bp (9999 here) is never what prints
        assertTrue(rows.none { TargetStatus.cardPctText(it, TargetTab.MONTHLY, en) == "100%" && it.categoryCode != "lighter" })
    }

    @Test fun everyTillDateItemFixtureAndTheCardSums() {
        val items = mapOf(1000L to 806L, 10000L to 8065L, 5000L to 4032L, 6000L to 4839L, 2999L to 2419L, 100L to 81L, 170L to 137L, 11000L to 8871L)
        items.forEach { (t, td) -> assertEquals("tillDate($t)", td, Targets.tillDate(t, 25, 31)) }
        val a = TargetStatus.parse(details())!!
        val cards = TargetStatus.categoryCards(a.node.rows, a.node.periodDays)
        val cig = cards.first { it.categoryCode == "cigarette" }
        assertEquals(40999L, cig.target); assertEquals(1030L, cig.achieved); assertEquals(33064L, cig.tillDateTarget)
        assertEquals("1030/33064", TargetStatus.fraction(cig, TargetTab.TILL_DATE, en))
        assertEquals(listOf("cigarette", "bidi", "lighter", "match"), cards.map { it.categoryCode })
        assertEquals(listOf(8871L, 81L, 137L), cards.drop(1).map { it.tillDateTarget })
    }

    /** R-F-01 (F3 ask): a card built from the eight Cigarette items at 21/26 is 33114, never tillDate(40999) = 33115; 20/25 is 32799. */
    @Test fun aCardIsTheSumOfItsItemRowsRF01() {
        fun card(e: Long, n: Long) = TargetStatus.parse(details(e, n))!!.let { a -> TargetStatus.categoryCards(a.node.rows, a.node.periodDays).first { it.categoryCode == "cigarette" } }
        assertEquals(33114L, card(21, 26).tillDateTarget)
        assertEquals(33115L, Targets.tillDate(40999, 21, 26))
        assertEquals(32799L, card(20, 25).tillDateTarget)
        assertEquals("1030/33114", TargetStatus.fraction(card(21, 26), TargetTab.TILL_DATE, en))
    }

    @Test fun everyDetailsFixtureUncappedWithTwoDecimalsAndRemaining() {
        val rows = TargetStatus.parse(details())!!.node.rows
        fun r(name: String) = rows.first { it.name == name }
        // monthly (screenshot 41)
        assertEquals("35.00%", TargetStatus.detailsText(r("Maxim Regular"), TargetTab.MONTHLY, en)); assertEquals(650L, TargetStatus.remaining(r("Maxim Regular"), TargetTab.MONTHLY))
        assertEquals("33.00%", TargetStatus.detailsText(r("Maxim Bolt"), TargetTab.MONTHLY, en))
        assertEquals("0.20%", TargetStatus.detailsText(r("Maxim Double Burst"), TargetTab.MONTHLY, en)); assertEquals(9980L, TargetStatus.remaining(r("Maxim Double Burst"), TargetTab.MONTHLY))
        assertEquals("1.33%", TargetStatus.detailsText(r("Sunmoon American blend"), TargetTab.MONTHLY, en)); assertEquals(2959L, TargetStatus.remaining(r("Sunmoon American blend"), TargetTab.MONTHLY))
        assertEquals("0.60%", TargetStatus.detailsText(r("Supreme Signature"), TargetTab.MONTHLY, en))
        // till date (screenshots 41 right and 42)
        assertEquals("806", TargetStatus.qty(TargetStatus.target(r("Maxim Regular"), TargetTab.TILL_DATE), en))
        assertEquals("43.42%", TargetStatus.detailsText(r("Maxim Regular"), TargetTab.TILL_DATE, en)); assertEquals(456L, TargetStatus.remaining(r("Maxim Regular"), TargetTab.TILL_DATE))
        assertEquals("40.94%", TargetStatus.detailsText(r("Maxim Bolt"), TargetTab.TILL_DATE, en))
        assertEquals("0.25%", TargetStatus.detailsText(r("Maxim Double Burst"), TargetTab.TILL_DATE, en)); assertEquals(8045L, TargetStatus.remaining(r("Maxim Double Burst"), TargetTab.TILL_DATE))
        assertEquals("2.48%", TargetStatus.detailsText(r("Marise Special Blend"), TargetTab.TILL_DATE, en))
        assertEquals("0.74%", TargetStatus.detailsText(r("Black Diamond Double Filter"), TargetTab.TILL_DATE, en))
        assertEquals("0.62%", TargetStatus.detailsText(r("Black Diamond Advanced"), TargetTab.TILL_DATE, en))
        assertEquals("1.65%", TargetStatus.detailsText(r("Sunmoon American blend"), TargetTab.TILL_DATE, en))
        // Aster: 85 of 81 is 104.94% in the table (uncapped) and 100% on the card (capped); remaining 0
        assertEquals("104.94%", TargetStatus.detailsText(r("Aster"), TargetTab.TILL_DATE, en))
        assertEquals(0L, TargetStatus.remaining(r("Aster"), TargetTab.TILL_DATE))
        assertEquals("100%", TargetStatus.cardPctText(r("Aster"), TargetTab.TILL_DATE, en))
        assertEquals("১০৪.৯৪%", TargetStatus.detailsText(r("Aster"), TargetTab.TILL_DATE, UiLocale.BN))
    }

    @Test fun bandsComeFromTheConfigOrTheDefault() {
        assertEquals(Bands.DEFAULT, TargetStatus.bandsOf(null))
        assertEquals(Bands.DEFAULT, TargetStatus.bandsOf("[40,80]"))
        assertEquals(Bands(30, 70), TargetStatus.bandsOf("[30,70]"))
        assertEquals(Bands.DEFAULT, TargetStatus.bandsOf("[80,40]"))
        assertEquals(Bands.DEFAULT, TargetStatus.bandsOf("\"x\""))
        assertEquals(Bands.DEFAULT, TargetStatus.bandsOf("[1,\"a\"]"))
        val match = TargetStatus.ordered(TargetStatus.parse(status())!!.node.rows).last()
        assertEquals(Band.AMBER, TargetStatus.band(match, TargetTab.MONTHLY, Bands.DEFAULT)) // 42 on [40, 80]
        assertEquals(Band.GREEN, TargetStatus.band(match, TargetTab.MONTHLY, Bands(30, 40))) // 42 on [30, 40]
    }

    /** R-F-24: a child zone whose period is 0 prints "—" for its till-date figures while its territory does not. */
    @Test fun aNodeWithoutWorkingDaysPrintsTheDashPerNode() {
        val a = TargetStatus.parse(status(childPeriod = 0))!!
        val zone = TargetStatus.ordered(a.cards[1].rows).first()
        val territory = TargetStatus.ordered(a.cards[0].rows).first()
        assertNull(zone.tillDateTarget)
        assertEquals("1030/—", TargetStatus.fraction(zone, TargetTab.TILL_DATE, en))
        assertEquals("—", TargetStatus.cardPctText(zone, TargetTab.TILL_DATE, en))
        assertNull(TargetStatus.band(zone, TargetTab.TILL_DATE, Bands.DEFAULT))
        assertEquals("3%", TargetStatus.cardPctText(territory, TargetTab.TILL_DATE, en))
        assertEquals("3%", TargetStatus.cardPctText(zone, TargetTab.MONTHLY, en))
        val d = TargetStatus.parse(details(period = 0))!!
        val aster = d.node.rows.first { it.name == "Aster" }
        assertEquals("—", TargetStatus.detailsText(aster, TargetTab.TILL_DATE, en))
        assertNull(TargetStatus.remaining(aster, TargetTab.TILL_DATE))
        assertNull(TargetStatus.categoryCards(d.node.rows, d.node.periodDays).first().tillDateTarget)
    }

    @Test fun aZoneAnsweringItselfIsOneCardAndAnEmptyCategoryIsTheDash() {
        val a = TargetStatus.parse("""{$stamp,"node":{"type":"zone","id":9,"name":"Banani - Test"},"elapsed_days":25,"period_days":31,"rows":[$categories],
            "children":[{"node":{"type":"zone","id":9,"name":"Banani - Test"},"elapsed_days":25,"period_days":31,"rows":[$categories]}]}""")!!
        assertEquals(1, a.cards.size)
        val none = TargetStatus.parse("""{$stamp,"node":{"type":"territory","id":3,"name":"Apsis"},"elapsed_days":25,"period_days":31,"rows":[${cat("bidi", "Bidi", 0, 0, 0)}]}""")!!.node.rows.single()
        assertEquals("0/0", TargetStatus.fraction(none, TargetTab.MONTHLY, en))
        assertEquals("—", TargetStatus.cardPctText(none, TargetTab.MONTHLY, en))
        assertNull(TargetStatus.parse("not json")); assertNull(TargetStatus.parse("""{"month":"2026-10"}"""))
    }

    @Test fun theNodeKeyIsTopOrOneZoneNeverAList() {
        val a = TargetStatus.parse(status())!!
        assertEquals("top", TargetNodeKey.of(a, a.cards[0]).wire)
        assertEquals("zone:9", TargetNodeKey.of(a, a.cards[1]).wire)
        assertEquals(TargetNodeKey("zone", 9), TargetNodeKey.parse("zone:9"))
        assertEquals(TargetNodeKey.Top, TargetNodeKey.parse("top"))
        assertEquals(TargetNodeKey.Top, TargetNodeKey.parse(null))
        assertEquals(TargetNodeKey.Top, TargetNodeKey.parse("zone:1,2"))
        assertEquals(TargetNodeKey.Top, TargetNodeKey.parse("ZONE;DROP:1"))
    }

    // ---- the screens --------------------------------------------------------------------------------------------------------

    private fun ctx(language: AppLanguage) = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)

    @Test fun statusShowsTheCardsAndTheTabSwitchesTheFiguresAndDetailsGivesTheNode() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx(AppLanguage.EN)) {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    var tab by remember { mutableStateOf(TargetTab.MONTHLY) }
                    TargetStatusContent(CardLoad(TargetStatus.parse(status())), tab, Bands.DEFAULT, false, { tab = it }, { opened += it.wire }, {})
                }
            }
        }
        compose.onNodeWithText("Territory: Apsis").assertExists()
        compose.onNodeWithText("Zone: Banani - Test").assertExists()
        compose.onNodeWithTag(TsoKitTags.segment(0)).assertIsSelected()
        compose.onAllNodes(hasText("1030/40999")).assertCountEquals(2)
        compose.onNodeWithTag(TsoKitTags.segment(1)).performClick()
        compose.onNodeWithTag(TsoKitTags.segment(1)).assertIsSelected()
        compose.onAllNodes(hasText("1030/33064")).assertCountEquals(2)
        compose.onAllNodes(hasText("85/81")).assertCountEquals(2)
        assertEquals(emptyList<String>(), compose.a11yProblems())
        compose.onNodeWithTag(TargetTags.details(1)).performScrollTo().performClick()
        compose.onNodeWithTag(TargetTags.details(0)).performScrollTo().performClick()
        assertEquals(listOf("zone:9", "top"), opened)
    }

    @Test fun statusSaysWhyWhenItHasNoFigures() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx(AppLanguage.EN)) {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    TargetStatusContent(CardLoad(null, failure = TsoFailure.OFFLINE), TargetTab.MONTHLY, Bands.DEFAULT, false, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Not available offline yet. Pull down to try again.").assertExists()
    }

    /** s8 row 42: switching the tab keeps the node and the category filter; a bar filters the table and the card, again clears it. */
    @Test fun detailsFilterByCategoryAndTheTabKeepsNodeAndFilter() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx(AppLanguage.EN)) {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    var tab by remember { mutableStateOf(TargetTab.MONTHLY) }
                    var filter by remember { mutableStateOf<String?>(null) }
                    TargetDetailsContent(CardLoad(TargetStatus.parse(details())), tab, Bands.DEFAULT, filter, false, { tab = it }, { filter = it }, {})
                }
            }
        }
        compose.onNodeWithText("Territory: Apsis").assertExists()
        listOf("cigarette", "bidi", "lighter", "match").forEach { compose.onNodeWithTag(TargetTags.bar(it)).assertExists() }
        compose.onNodeWithTag(TargetTags.row(300)).assertExists() // Aster
        assertEquals(emptyList<String>(), compose.a11yProblems())
        compose.onNodeWithTag(TargetTags.bar("lighter")).performClick()
        // screenshot 41 right: the card shows the Lighter bar only, the table Aster only
        compose.onNodeWithTag(TargetTags.bar("cigarette")).assertDoesNotExist()
        compose.onNodeWithTag(TargetTags.row(100)).assertDoesNotExist()
        compose.onNodeWithTag(TargetTags.row(300)).assertExists()
        compose.onNodeWithText("85/100").assertExists()
        compose.onNodeWithText("85.00%").assertExists()
        // the tab keeps the node and the filter (screenshot 41 right: Till Date, 85/81, 104.94%, remaining 0)
        compose.onNodeWithTag(TsoKitTags.segment(1)).performClick()
        compose.onNodeWithText("Territory: Apsis").assertExists()
        compose.onNodeWithTag(TargetTags.row(100)).assertDoesNotExist()
        compose.onNodeWithText("85/81").assertExists()
        compose.onNodeWithText("104.94%").assertExists()
        compose.onNodeWithText("100%").assertExists()
        // tapping the bar again clears the filter
        compose.onNodeWithTag(TargetTags.bar("lighter")).performClick()
        compose.onNodeWithTag(TargetTags.bar("cigarette")).assertExists()
        compose.onNodeWithTag(TargetTags.row(100)).assertExists()
        compose.onNodeWithText("43.42%").assertExists()
        // "Show all categories" clears it too
        compose.onNodeWithTag(TargetTags.bar("cigarette")).performClick()
        compose.onNodeWithTag(TargetTags.row(300)).assertDoesNotExist()
        compose.onNodeWithTag(TargetTags.SHOW_ALL).performClick()
        compose.onNodeWithTag(TargetTags.row(300)).assertExists()
    }

    @Test fun detailsInBangla() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx(AppLanguage.BN)) {
                TsoLoginTheme(AppLanguage.BN, loggedOut = false) {
                    TargetDetailsContent(CardLoad(TargetStatus.parse(details())), TargetTab.TILL_DATE, Bands.DEFAULT, null, false, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("টেরিটরি: Apsis").assertExists()
        compose.onNodeWithText("আজ পর্যন্ত টার্গেট").assertExists()
        compose.onNodeWithText("১০৩০/৩৩০৬৪").assertExists()
        compose.onNodeWithText("১০৪.৯৪%").assertExists()
    }
}

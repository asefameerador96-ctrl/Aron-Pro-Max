package com.aktcl.aron.feature.amo

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lead ruling 2026-11-11 06:00 UTC on the route level of `GET /v1/dashboards/targets`: a route the server refused (403) shows its own "not in your reach" row
 * while the zone and the other routes stay; a server without the route level (400) gives one line and the zone card, never an access banner.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TeamRouteStatesTest {
    @get:Rule val compose = createComposeRule()

    private val zone = TeamTargets.parse("""{"node":{"type":"zone","id":5,"name":"Gulshan"},"period_days":6,"rows":[{"name":"Marise","category_code":"cigarette","target_qty_base":50000,"achieved_qty_base":40999,"tilldate_target_qty_base":25000,"report_unit":"pack","target_qty_report":"2500.000","achieved_qty_report":"2199.950","tilldate_target_qty_report":"1250.000","remaining_qty_report":"300.050","remaining_tilldate_qty_report":"0.000"}]}""")!!
    private val route = TeamTargets.parse("""{"node":{"type":"route","id":2,"name":"Banani"},"period_days":6,"rows":[{"name":"Marise","category_code":"cigarette","target_qty_base":1000,"achieved_qty_base":500,"tilldate_target_qty_base":500}]}""")!!

    @Test fun aRefusedRouteSaysSoOnItsOwnRowAndTheZoneAndTheOtherRoutesStay() {
        compose.setContent {
            AronTheme(AppLanguage.EN, amo = true) {
                TeamPerformanceListContent(PerfCards(zone, listOf(route), listOf(RouteOutOfReach(9, "Dhanmondi"))), PerfTab.MONTHLY, {}, {}, stale = false, failed = false)
            }
        }
        compose.onNodeWithTag(TeamPerfTags.OUT_OF_REACH + 9).assertExists()
        compose.onNodeWithText("Not in your reach").assertExists()
        compose.onNodeWithText("Route: Dhanmondi").assertExists()
        compose.onNodeWithText("Route: Banani").assertExists() // the other route stays
        compose.onNodeWithText("2,199.95/2,500", substring = true).assertExists() // the zone card stays, in packets
        compose.onNodeWithText("Ask your office to check your access.", substring = true).assertDoesNotExist() // no screen-level refusal banner
        compose.onNodeWithTag(TeamPerfTags.ROUTE_NOTE).assertDoesNotExist()
    }

    @Test fun aServerWithoutTheRouteLevelGivesOneLineAndTheZoneCardNoAccessBanner() {
        compose.setContent {
            AronTheme(AppLanguage.EN, amo = true) {
                TeamPerformanceListContent(PerfCards(zone, emptyList(), emptyList(), routeLevelUnavailable = true), PerfTab.MONTHLY, {}, {}, stale = false, failed = false)
            }
        }
        compose.onNodeWithTag(TeamPerfTags.ROUTE_NOTE).assertExists()
        compose.onNodeWithText("Route detail unavailable").assertExists()
        compose.onNodeWithText("2,199.95/2,500", substring = true).assertExists()
        compose.onNodeWithText("Ask your office to check your access.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Not in your reach").assertDoesNotExist()
    }

    @Test fun theBanglaWordsAreInTheLocalizationLayer() {
        val ctx = com.aktcl.aron.core.ui.AppLocale.wrap(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), AppLanguage.BN)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides ctx) {
                AronTheme(AppLanguage.BN, amo = true) {
                    TeamPerformanceListContent(PerfCards(zone, emptyList(), listOf(RouteOutOfReach(9, "ধানমন্ডি")), routeLevelUnavailable = true), PerfTab.MONTHLY, {}, {}, stale = false, failed = false)
                }
            }
        }
        compose.onNodeWithText("আপনার আওতার বাইরে").assertExists(); compose.onNodeWithText("রুটের বিস্তারিত পাওয়া যাচ্ছে না").assertExists()
    }

    @Test fun theTillNowCardsCarryTheSameTwoStates() {
        val cards = listOf(TillCard("Route: Dhanmondi", false, 9, emptyList(), outOfReach = true))
        compose.setContent { AronTheme(AppLanguage.EN, amo = true) { TillNowContent(cards, {}, false, false, routeLevelUnavailable = true) } }
        compose.onNodeWithText("Not in your reach").assertExists(); compose.onNodeWithText("Route detail unavailable").assertExists()
    }
}

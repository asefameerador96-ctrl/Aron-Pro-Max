package com.aktcl.aron.feature.tso.periphery

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun outlet(id: Long, phone: String? = null) = NearbyOutlet(id, "DHK-$id", "Shop $id", 23.78, 90.40, 12.0 * id, 1, "Cluster", "GT", phone)
private val member = TeamMember(7, "Rahim", listOf(1), 23.78, 90.41, 1_760_000_000_000, "visit")

private class Src(var nearby: Stamped<Nearby>? = Stamped(Nearby("10:00", 100, false, listOf(outlet(1), outlet(2))), "10:00", false), var team: Stamped<List<TeamMember>>? = Stamped(listOf(member, member.copy(userId = 8, name = "Karim", routeIds = listOf(2), lat = null, lng = null, atMs = null)), "10:05", false)) : PeripherySource {
    var asked: Triple<Double, Double, Int>? = null
    override suspend fun zones() = listOf(10L)
    override suspend fun nearby(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): Stamped<Nearby>? { asked = Triple(lat, lng, radiusM); return nearby }
    override suspend fun team(zoneId: Long?, date: String) = team
    override suspend fun routeCodes() = mapOf(1L to "R1", 2L to "R2")
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PeripheryLogicTest {
    @Test fun radiusOptionsAreTheThreeOfTheManual() = assertEquals(listOf(50, 100, 300), RADIUS_OPTIONS_M)

    @Test fun findTakesOneFixAndSendsOnlyItsCoordinatesAndTheRadius() = runBlocking {
        val src = Src(); var fixes = 0
        val s = RetailerState(src) { fixes++; FixResult.Ok(23.7, 90.4, 8.0, false) }
        s.loadZones(); s.pickZone(10); s.pickRadius(300); s.find()
        assertEquals(1, fixes); assertEquals(Triple(23.7, 90.4, 300), src.asked)
        assertTrue(s.ui.value.phase is RetailerUi.Shown)
    }

    @Test fun aFixOutsideBangladeshIsNeverSent() = runBlocking {
        for ((lat, lng) in listOf(0.0 to 0.0, 19.9 to 90.0, 23.7 to 87.9, 27.1 to 90.0)) {
            val src = Src(); val s = RetailerState(src) { FixResult.Ok(lat, lng, 5.0, false) }; s.find()
            assertEquals(RetailerUi.OutOfArea, s.ui.value.phase); assertNull(src.asked)
        }
    }

    @Test fun changingTheRadiusWhileLoadingDropsTheOldAnswer() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = object : PeripherySource by Src() { override suspend fun nearby(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): Stamped<Nearby>? { gate.await(); return Stamped(Nearby("x", radiusM, false, emptyList()), "x", false) } }
        val s = RetailerState(src) { FixResult.Ok(23.7, 90.4, 5.0, false) }
        val job = kotlinx.coroutines.GlobalScope.launch { s.find() }
        kotlinx.coroutines.delay(100); s.pickRadius(300); gate.complete(Unit); job.join()
        assertEquals(RetailerUi.Pick, s.ui.value.phase); assertEquals(300, s.ui.value.radiusM)
    }

    @Test fun positionKeyDiffersBetweenPlacesAndMatchesNearbyOnes() {
        assertEquals(positionKey(23.78101, 90.40001), positionKey(23.78102, 90.40002))
        assertTrue(positionKey(23.78, 90.40) != positionKey(23.80, 90.40))
    }

    @Test fun refusingThePermissionOffersTheSettings() = runBlocking {
        val s = RetailerState(Src()) { FixResult.PermissionNeeded }; s.permissionRefused()
        assertTrue(s.ui.value.permissionDenied); assertEquals(RetailerUi.NeedsPermission, s.ui.value.phase)
    }

    @Test fun anInvalidRadiusIsIgnored() = runBlocking { val s = RetailerState(Src()) { FixResult.Failed }; s.pickRadius(75); assertEquals(50, s.ui.value.radiusM) }

    @Test fun permissionLocationOffAndNoFixAreWordsAndNothingIsRequested() = runBlocking {
        for ((f, expect) in listOf(FixResult.PermissionNeeded to RetailerUi.NeedsPermission, FixResult.LocationOff to RetailerUi.LocationOff, FixResult.Failed to RetailerUi.NoFix)) {
            val src = Src(); val s = RetailerState(src) { f }; s.find()
            assertEquals(expect, s.ui.value.phase); assertNull(src.asked)
        }
    }

    @Test fun aThrowingFixIsNoFixNotACrash() = runBlocking { val s = RetailerState(Src()) { error("boom") }; s.find(); assertEquals(RetailerUi.NoFix, s.ui.value.phase) }

    @Test fun offlineWithNoCopyIsUnavailableAndAMockFixIsFlagged() = runBlocking {
        val s = RetailerState(Src(nearby = null)) { FixResult.Ok(23.7, 90.4, 8.0, true) }; s.find()
        assertEquals(RetailerUi.Unavailable, s.ui.value.phase); assertTrue(s.ui.value.mockWarning)
    }

    @Test fun markersAreCappedAndPhonesAppearOnlyWhenTheServerSent() {
        val many = Nearby("x", 300, true, (1L..400L).map { outlet(it) })
        assertEquals(MAX_SHOWN, nearbyPoints(many).size)
        val j = """{"as_of":"2026-10-17T10:00:00Z","radius_m":100,"truncated":false,"items":[{"outlet_id":1,"code":"A","name":"N","lat":23.7,"lng":90.4,"distance_m":10.5,"route_id":2,"cluster_name":null,"channel":"GT","contact_number":null},{"outlet_id":2,"code":"B"}]}"""
        val n = PeripheryJson.nearby(j)!!
        assertEquals(1, n.items.size); assertNull(n.items[0].phone)
        assertEquals(n, PeripheryJson.nearby(PeripheryJson.nearbyToCache(n))); assertNull(PeripheryJson.nearby("<html>"))
    }

    @Test fun teamJsonKeepsMembersWithoutAFixAndLegendGroupsByRoute() {
        val j = """{"items":[{"user_id":7,"full_name":"Rahim","route_ids":[1],"last_fix":{"lat":23.7,"lng":90.4,"at":"2026-10-17T05:00:00Z","source":"visit"}},{"user_id":8,"full_name":"Karim","route_ids":[2],"last_fix":null}]}"""
        val t = PeripheryJson.team(j)!!
        assertEquals(2, t.size); assertNull(t[1].lat); assertEquals(1_792_000_000_000L - 1_792_000_000_000L + t[0].atMs!!, t[0].atMs)
        assertEquals(listOf("R1" to 1, "R2" to 1), legend(t, mapOf(1L to "R1", 2L to "R2")))
        val pts = teamPoints(t, mapOf(1L to "R1"))
        assertEquals("Rahim (R1)", pts[0].label); assertTrue(pts[0].located); assertTrue(!pts[1].located)
        assertEquals(t, PeripheryJson.team(PeripheryJson.teamToCache(t)))
    }

    @Test fun teamStateShowsCachedWhenOfflineAndUnavailableWhenNothing() = runBlocking {
        val s = TeamState(Src(team = Stamped(listOf(member), "09:00", true))) { "2026-10-17" }; s.load()
        assertTrue((s.ui.value.phase as TeamUi.Shown).result.fromCache)
        val s2 = TeamState(Src(team = null)) { "2026-10-17" }; s2.load(); assertEquals(TeamUi.Unavailable, s2.ui.value.phase)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PeripheryScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    @Test fun teamOfflineShowsTheLegendTheAsOfLineAndTheCachedBanner() {
        val st = TeamState(Src(team = Stamped(listOf(member), "09:00", true))) { "2026-10-17" }
        show(AppLanguage.EN) { TeamScreen(st, online = { false }, nowMs = { 1_760_000_600_000 }, userKey = "t1", onBack = {}) }
        compose.onNodeWithTag(PeripheryTags.CACHED).assertExists(); compose.onNodeWithTag(PeripheryTags.LEGEND).assertExists(); compose.onNodeWithText("As of 09:00").assertExists()
    }

    @Test fun banglaAsOfUsesBengaliDigits() {
        val st = TeamState(Src(team = Stamped(listOf(member), "09:05", false))) { "2026-10-17" }
        show(AppLanguage.BN) { TeamScreen(st, online = { false }, nowMs = { 1_760_000_600_000 }, userKey = "t1", onBack = {}) }
        compose.onNodeWithText("০৯:০৫ পর্যন্ত").assertExists()
    }

    @Test fun retailerNeedsPermissionShowsTheAllowButton() {
        val st = RetailerState(Src()) { FixResult.PermissionNeeded }
        // the real activity context (an activity result registry owner) is needed for the permission launcher
        compose.setContent { AronTheme(AppLanguage.EN) { RetailerScreen(st, online = { false }, nowMs = { 0 }, userKey = "t1", onBack = {}) } }
        compose.waitForIdle(); runBlocking { st.find() }; compose.waitForIdle()
        compose.onNodeWithTag(PeripheryTags.ALLOW).assertExists()
    }
}

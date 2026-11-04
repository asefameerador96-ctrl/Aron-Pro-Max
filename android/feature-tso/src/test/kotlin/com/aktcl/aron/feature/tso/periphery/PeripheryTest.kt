package com.aktcl.aron.feature.tso.periphery

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.aktcl.aron.feature.tso.a11yProblems

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

    /**
     * Checker fix (medium): the rep picks a radius while the opening fix is still running (a balanced fix takes seconds). The
     * pick's own find comes in while the first is busy; the first drops its answer and takes exactly one more fix for the
     * new radius, so the screen ends on the map of the new choice, never on a "Loading" no answer ends (D-TSO-MAN-34).
     */
    @Test fun aPickDuringTheOpeningFixTakesOneMoreFixAndShowsTheNewRadius() = runBlocking {
        val opening = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = Src(); var fixes = 0
        val s = RetailerState(src, fix = object : PeripheryFix { override suspend fun current(): FixResult { fixes++; if (fixes == 1) opening.await(); return FixResult.Ok(23.7, 90.4, 8.0, false) } })
        s.loadZones()
        val job = launch { s.find() } // the screen opens: one fix, still running
        kotlinx.coroutines.yield()
        assertEquals(RetailerUi.Locating, s.ui.value.phase)
        s.pickRadius(300); s.find() // the pick and its find, as the dropdown does
        assertEquals(1, fixes)
        opening.complete(Unit); job.join()
        assertEquals(2, fixes)
        val shown = s.ui.value.phase as RetailerUi.Shown
        assertEquals(300, shown.radiusM); assertEquals(300, src.asked!!.third)
    }

    @Test fun aZonePickWithoutAFindOfItsOwnLeavesPickNotLoading() = runBlocking {
        val opening = kotlinx.coroutines.CompletableDeferred<Unit>()
        var fixes = 0
        val s = RetailerState(Src(), fix = object : PeripheryFix { override suspend fun current(): FixResult { fixes++; opening.await(); return FixResult.Ok(23.7, 90.4, 8.0, false) } })
        s.loadZones()
        val job = launch { s.find() }
        kotlinx.coroutines.yield()
        s.pickZone(10)
        opening.complete(Unit); job.join()
        assertEquals(1, fixes); assertEquals(RetailerUi.Pick, s.ui.value.phase)
    }

    @Test fun aSecondRefreshDuringARunTakesNoExtraFix() = runBlocking {
        val opening = kotlinx.coroutines.CompletableDeferred<Unit>()
        var fixes = 0
        val s = RetailerState(Src(), fix = object : PeripheryFix { override suspend fun current(): FixResult { fixes++; opening.await(); return FixResult.Ok(23.7, 90.4, 8.0, false) } })
        val job = launch { s.find() }
        kotlinx.coroutines.yield()
        s.find() // a second tap on refresh: the running fix answers it
        opening.complete(Unit); job.join()
        assertEquals(1, fixes); assertTrue(s.ui.value.phase is RetailerUi.Shown)
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
        val pts = teamPoints(t, mapOf(1L to "R1")).associateBy { it.id }
        assertEquals("Rahim (R1)", pts.getValue("7").label); assertTrue(pts.getValue("7").located); assertTrue(!pts.getValue("8").located)
        assertEquals(t, PeripheryJson.team(PeripheryJson.teamToCache(t)))
    }

    // ---- tso-manual A3 step 2 (D-TSO-MAN-34)

    @Test fun radiiAndStaleAgeComeFromTheConfigWithinTheRegistryBounds() {
        assertEquals(PeripheryConfig(listOf(50, 100, 300), 60), PeripheryConfig.of(listOf(300, 50, 100, 50), 60))
        assertEquals(PeripheryConfig(), PeripheryConfig.of(null, null))
        assertEquals(RADIUS_OPTIONS_M, PeripheryConfig.of(listOf(5, 100), 120).radiiM) // 5 m is below the registry bound
        assertEquals(RADIUS_OPTIONS_M, PeripheryConfig.of((1..7).map { it * 100 }, 120).radiiM) // seven values: over the bound
        assertEquals(120, PeripheryConfig.of(null, 10).staleAfterMin)
        assertEquals(720, PeripheryConfig.of(null, 720).staleAfterMin)
    }

    @Test fun retailerOffersTheConfiguredRadiiAndPreselectsTheFirstZone() = runBlocking {
        val src = object : PeripherySource by Src() {
            override suspend fun zones() = listOf(12L, 10L)
            override suspend fun config() = PeripheryConfig(listOf(100, 200), 120)
        }
        val s = RetailerState(src) { FixResult.Failed }
        s.loadZones()
        assertEquals(listOf(100, 200), s.ui.value.radii); assertEquals(100, s.ui.value.radiusM); assertEquals(10L, s.ui.value.zoneId)
        s.pickRadius(50); assertEquals(100, s.ui.value.radiusM) // not offered: the server would refuse it
        s.pickRadius(200); assertEquals(200, s.ui.value.radiusM)
    }

    private val centre = 23.7937 to 90.4066
    private fun north(m: Double) = centre.first + Math.toDegrees(m / com.aktcl.aron.rules.Geo.EARTH_RADIUS_M)
    private fun east(m: Double) = centre.second + Math.toDegrees(m / (com.aktcl.aron.rules.Geo.EARTH_RADIUS_M * Math.cos(Math.toRadians(centre.first))))
    private fun b(id: Long, lat: Double?, lng: Double?, route: Long? = 1, zone: Long? = 10, active: Boolean = true, confirmed: Boolean = true) =
        BundleOutlet(id, "DHK-$id", "Shop $id", lat, lng, route, zone, active, confirmed, "Cluster", "GT", null)

    @Test fun offlineNearbyFollowsTheServerRule() {
        val r = 100
        val outlets = listOf(
            b(1, north(0.9995 * r), centre.second),            // inside, just under the radius (north)
            b(2, centre.first, east(0.9995 * r)),              // inside, just under the radius (east)
            b(3, north(1.0005 * r), centre.second),            // just outside
            b(4, north(10.0), centre.second, active = false),  // inactive
            b(5, north(10.0), centre.second, confirmed = false), // coordinates not confirmed
            b(6, north(10.0), centre.second, route = null),    // no route
            b(7, null, null),                                  // no coordinates
            b(8, north(10.0), centre.second, zone = 11),       // another zone
            b(10, north(20.0), centre.second), b(9, north(20.0), centre.second), // a tie: by id
            b(11, centre.first, centre.second),                // at the centre
        )
        val n = NearbyRule.compute(outlets, centre.first, centre.second, r, 10, "10:00")
        assertEquals(listOf(11L, 9L, 10L), n.items.take(3).map { it.id })
        assertEquals(setOf(1L, 2L), n.items.drop(3).map { it.id }.toSet()) // both just inside the radius, north and east
        assertEquals(5, n.items.size)
        // each distance is the server's: the shared haversine rounded to 0.1 m, and the list is ordered by it
        n.items.forEach { assertEquals(NearbyRule.roundTenth(com.aktcl.aron.rules.Geo.haversineM(centre.first, centre.second, it.lat, it.lng)), it.distanceM, 0.0) }
        assertEquals(n.items.map { it.distanceM }.sorted(), n.items.map { it.distanceM })
        assertEquals(listOf(0.0, 20.0, 20.0), n.items.take(3).map { it.distanceM })
        assertTrue(!n.truncated); assertEquals(100, n.radiusM); assertEquals("10:00", n.asOf)
        // every zone when none is chosen; the cap sets truncated
        val all = NearbyRule.compute(outlets, centre.first, centre.second, r, null, "x", maxMarkers = 3)
        assertEquals(listOf(11L, 8L, 9L), all.items.map { it.id }); assertTrue(all.truncated)
    }

    @Test fun distancesRoundAsTheServerAndPinsShowWholeMetres() {
        assertEquals(10.3, NearbyRule.roundTenth(10.25), 0.0)
        assertEquals(10.2, NearbyRule.roundTenth(10.249), 0.0)
        assertEquals(13, shownMetres(12.5)); assertEquals(12, shownMetres(12.4)); assertEquals(0, shownMetres(0.4))
        val pts = nearbyPoints(Nearby("x", 100, false, listOf(outlet(1).copy(distanceM = 12.5))), { "$it m" }, 23.7 to 90.4, "You")
        assertEquals("Cluster  ·  13 m", pts[0].note); assertEquals(com.aktcl.aron.core.map.MapPointKind.OUTLET, pts[0].kind)
        assertEquals(com.aktcl.aron.core.map.MapPointKind.SELF, pts.last().kind); assertEquals("You", pts.last().label)
    }

    @Test fun retailerOfflineWorksTheAnswerOutFromTheBundle() = runBlocking {
        val bundle = listOf(b(1, north(30.0), centre.second), b(2, north(500.0), centre.second))
        val src = object : PeripherySource by Src(nearby = null) { override suspend fun bundleOutlets(zoneId: Long?) = bundle }
        val s = RetailerState(src, { "11:20" }) { FixResult.Ok(centre.first, centre.second, 5.0, false) }
        s.loadZones(); s.find()
        val shown = s.ui.value.phase as RetailerUi.Shown
        assertTrue(shown.result.computedOnPhone); assertEquals("11:20", shown.result.asOf); assertEquals(listOf(1L), shown.result.value.items.map { it.id })
    }

    @Test fun aFreshServerAnswerWinsAndTheBundleBeatsAnOldCopy() = runBlocking {
        val bundle = listOf(b(1, north(30.0), centre.second))
        val fresh = Stamped(Nearby("10:00", 50, false, listOf(outlet(7))), "10:00", false)
        val old = fresh.copy(fromCache = true)
        suspend fun run(server: Stamped<Nearby>?, outlets: List<BundleOutlet>?): Stamped<Nearby>? {
            val src = object : PeripherySource by Src(nearby = server) { override suspend fun bundleOutlets(zoneId: Long?) = outlets }
            val s = RetailerState(src, { "11:20" }) { FixResult.Ok(centre.first, centre.second, 5.0, false) }
            s.find(); return (s.ui.value.phase as? RetailerUi.Shown)?.result
        }
        assertEquals(fresh, run(fresh, bundle))
        assertTrue(run(old, bundle)!!.computedOnPhone)
        assertEquals(old, run(old, null)) // nothing in the bundle: the last copy of this very view
        assertEquals(null, run(null, emptyList()))
    }

    @Test fun teamIsGroupedByRouteAndTheLegendCountsEachRoute() {
        val m = listOf(
            member.copy(userId = 1, name = "Zia", routeIds = listOf(2)), member.copy(userId = 2, name = "Abul", routeIds = listOf(2)),
            member.copy(userId = 3, name = "Karim", routeIds = listOf(1)), member.copy(userId = 4, name = "Nobody", routeIds = emptyList()),
        )
        val g = teamGroups(m, mapOf(1L to "R1", 2L to "R2"))
        assertEquals(listOf("R1", "R2", "-"), g.map { it.label })
        assertEquals(listOf("Abul", "Zia"), g[1].members.map { it.name })
        assertEquals(listOf("R1" to 1, "R2" to 2, "-" to 1), legend(m, mapOf(1L to "R1", 2L to "R2")))
        assertEquals(listOf("3", "2", "1", "4"), teamPoints(m, mapOf(1L to "R1", 2L to "R2")).map { it.id })
    }

    @Test fun anOldFixIsGreyedAfterTheConfiguredAge() {
        val at = 1_760_000_000_000L
        val m = member.copy(atMs = at)
        assertTrue(!isStale(m, at + 119 * 60_000, PeripheryConfig()))
        assertTrue(isStale(m, at + 120 * 60_000, PeripheryConfig()))
        assertTrue(isStale(m, at + 30 * 60_000, PeripheryConfig(staleAfterMin = 30)))
        assertTrue(isStale(m.copy(atMs = null), at, PeripheryConfig())) // no fix at all
    }

    @Test fun theMockChipIsOnlyOnSrsWithMockFixesToday() {
        val j = """{"as_of":"2026-10-17T05:00:00Z","items":[{"user_id":7,"full_name":"Rahim","route_ids":[1],"mock_fixes_today":2,"last_fix":null},{"user_id":8,"full_name":"Karim","route_ids":[1],"last_fix":null},{"user_id":9,"full_name":"Selim","route_ids":[1],"mock_fixes_today":-3,"last_fix":null}]}"""
        val t = PeripheryJson.team(j)!!
        assertEquals(listOf(2, 0, 0), t.map { it.mockFixesToday })
        assertEquals(t, PeripheryJson.team(PeripheryJson.teamToCache(t)))
        val notes = teamPoints(t, emptyMap()) { "mock $it" }.associate { it.id to it.note }
        assertEquals(mapOf("7" to "mock 2", "8" to null, "9" to null), notes)
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

    @Test fun teamUnavailableOffersTryAgainAndRecoversWhenTheListArrives() {
        val src = Src(team = null)
        val st = TeamState(src) { "2026-10-17" }
        show(AppLanguage.EN) { TeamScreen(st, online = { false }, nowMs = { 1_760_000_600_000 }, userKey = "t2", onBack = {}) }
        compose.onNodeWithTag(PeripheryTags.STATUS).assertExists(); compose.onNodeWithText("Try again").assertExists()
        src.team = Stamped(listOf(member), "09:00", false)
        compose.onNodeWithText("Try again").performClick(); compose.waitForIdle()
        compose.onNodeWithTag(PeripheryTags.LEGEND).assertExists()
    }

    @Test fun banglaAsOfUsesBengaliDigits() {
        val st = TeamState(Src(team = Stamped(listOf(member), "09:05", false))) { "2026-10-17" }
        show(AppLanguage.BN) { TeamScreen(st, online = { false }, nowMs = { 1_760_000_600_000 }, userKey = "t1", onBack = {}) }
        compose.onNodeWithText("০৯:০৫ পর্যন্ত").assertExists()
    }

    @Test fun myTeamShowsTheZoneDropdownTheRefreshAndTheMockChip() {
        val src = Src(team = Stamped(listOf(member.copy(mockFixesToday = 2)), "09:00", false))
        var reads = 0
        val counted = object : PeripherySource by src { override suspend fun team(zoneId: Long?, date: String): Stamped<List<TeamMember>>? { reads++; return src.team } }
        val st = TeamState(counted) { "2026-10-17" }
        show(AppLanguage.EN) { TeamScreen(st, online = { false }, nowMs = { 1_760_000_600_000 }, userKey = "t3", onBack = {}) }
        compose.onNodeWithTag(PeripheryTags.ZONE).assertExists()
        compose.onNodeWithText("Mock GPS today: 2", substring = true).assertExists()
        compose.onNodeWithText("My Teams").assertExists()
        assertEquals(1, reads)
        compose.onNodeWithTag(PeripheryTags.REFRESH).performClick(); compose.waitForIdle()
        assertEquals("Refresh reads once more; nothing polls", 2, reads)
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun retailerOpensWithOneFixAndOfflineShowsTheOutletsWorkedOutOnThePhone() {
        var fixes = 0
        val c = 23.7937 to 90.4066
        val bundle = listOf(BundleOutlet(1, "DHK-1", "Shop One", c.first + 0.0002, c.second, 1, 10, true, true, "Cluster A", "GT", null))
        val src = object : PeripherySource by Src(nearby = null) { override suspend fun bundleOutlets(zoneId: Long?) = bundle }
        val st = RetailerState(src, { "11:20" }) { fixes++; FixResult.Ok(c.first, c.second, 5.0, false) }
        compose.setContent { AronTheme(AppLanguage.EN) { RetailerScreen(st, online = { false }, nowMs = { 0 }, userKey = "t4", onBack = {}, findOnOpen = true) } }
        compose.waitForIdle()
        assertEquals(1, fixes)
        compose.onNodeWithTag(PeripheryTags.COMPUTED).assertExists()
        compose.onNodeWithText("Shop One  ·  DHK-1").assertExists()
        compose.onNodeWithText("Cluster A  ·  22 m").assertExists()
        compose.onNodeWithText("Outlets").assertExists()
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun pickingARadiusTakesOneNewFixAndBanglaShowsBengaliDigits() {
        var fixes = 0
        val st = RetailerState(Src()) { fixes++; FixResult.Ok(23.7, 90.4, 5.0, false) }
        compose.setContent { AronTheme(AppLanguage.BN) { RetailerScreen(st, online = { false }, nowMs = { 0 }, userKey = "t5", onBack = {}) } }
        compose.waitForIdle()
        assertEquals("no fix until the rep picks (findOnOpen off here)", 0, fixes)
        compose.onNodeWithTag(PeripheryTags.RADIUS).performClick(); compose.waitForIdle()
        compose.onNodeWithText("৩০০").performClick(); compose.waitForIdle()
        assertEquals(300, st.ui.value.radiusM); assertEquals(1, fixes)
        assertTrue(st.ui.value.phase is RetailerUi.Shown)
    }

    @Test fun retailerNeedsPermissionShowsTheAllowButton() {
        val st = RetailerState(Src()) { FixResult.PermissionNeeded }
        // the real activity context (an activity result registry owner) is needed for the permission launcher
        compose.setContent { AronTheme(AppLanguage.EN) { RetailerScreen(st, online = { false }, nowMs = { 0 }, userKey = "t1", onBack = {}) } }
        compose.waitForIdle(); runBlocking { st.find() }; compose.waitForIdle()
        compose.onNodeWithTag(PeripheryTags.ALLOW).assertExists()
    }
}

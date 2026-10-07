package com.aktcl.aron.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-008, F-SR-009, F-SR-064. */
class HomeModelTest {
    @Test fun headerShowsRoleNameUsernameRouteAndIsoDate() {
        val h = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis Route (Sun, Tue, Thu)", "3f", "2026-10-05")
        assertEquals("SR - Testing Banani (sr334001)", h.title)
        assertEquals("Apsis Route (Sun, Tue, Thu), 2026-10-05", h.subtitle)
        assertEquals(RouteKind.THREE_F, h.routeKind)
    }

    @Test fun routeLabelCoversDailyThreeFTwoFAndUnknown() {
        assertEquals(RouteKind.DAILY, RouteKind.of("daily")); assertEquals(RouteKind.TWO_F, RouteKind.of("2f")); assertNull(RouteKind.of(null)); assertNull(RouteKind.of("x"))
    }

    @Test fun headerWithoutARouteShowsOnlyTheDate() = assertEquals("2026-10-05", HomeModel.header("SR", "A", "a", null, null, "2026-10-05").subtitle)

    @Test fun tilesInScreenshotOrderWithoutLoyaltyOrPhotoCaptureByDefault() {
        val keys = HomeTiles.resolve(emptySet(), 0).map { it.tile.key }
        assertEquals(listOf("attendance", "stock", "sale", "memo", "summary", "sales_submit", "outlet", "tutorial", "tasks", "sales_journey", "kpi"), keys)
        assertFalse("loyalty" in keys)
    }

    @Test fun photoCaptureAppearsOnlyWhenEnabledForTheUserAndKeepsItsPlace() {
        val keys = HomeTiles.resolve(setOf("photo_capture"), 0).map { it.tile.key }
        assertEquals(keys.indexOf("tasks") + 1, keys.indexOf("photo_capture"))
        assertTrue(HomeTiles.resolve(setOf("loyalty"), 0).none { it.tile.key == "loyalty" })
    }

    @Test fun taskBadgeEqualsTheOpenCountAndIsHiddenAtZero() {
        assertEquals(3, HomeTiles.resolve(emptySet(), 3).first { it.tile == HomeTile.TASKS }.badge)
        assertNull(HomeTiles.resolve(emptySet(), 0).first { it.tile == HomeTile.TASKS }.badge)
        assertTrue(HomeTiles.resolve(emptySet(), 3).filter { it.tile != HomeTile.TASKS }.all { it.badge == null })
    }

    @Test fun healthWarningsAtTheThresholds() {
        val ok = DeviceHealthModel.of(80, 4000, 3, 30)
        assertFalse(ok.anyWarning)
        assertTrue(DeviceHealthModel.of(39, 4000, 3, 30).batteryWarn)
        assertFalse(DeviceHealthModel.of(40, 4000, 3, 30).batteryWarn)
        assertTrue(DeviceHealthModel.of(80, 499, 3, 30).storageWarn)
        assertFalse(DeviceHealthModel.of(80, 500, 3, 30).storageWarn)
        assertTrue(DeviceHealthModel.of(80, 4000, 201, 30).pendingWarn)
        assertTrue(DeviceHealthModel.of(80, 4000, 3, 24 * 60 + 1).syncWarn)
        assertTrue(DeviceHealthModel.of(80, 4000, 3, null).syncWarn) // never synced
        assertTrue(runCatching { DeviceHealthModel.of(101, 1, 1, 1) }.isFailure)
    }
}

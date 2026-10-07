package com.aktcl.aron.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-065. */
class RoutePickerTest {
    private val a = PlannedRoute(1, "A", true, 1); private val b = PlannedRoute(2, "B", true, 2); private val c = PlannedRoute(3, "C", false, null)

    @Test fun twoPlannedRoutesAskTheSrAndNamingFollowsTheChoice() {
        assertTrue(RoutePicker.needsChoice(listOf(a, b, c), null)); assertNull(RoutePicker.inUse(listOf(a, b, c), null))
        assertFalse(RoutePicker.needsChoice(listOf(a, b, c), 2)); assertEquals("B", RoutePicker.inUse(listOf(a, b, c), 2)?.name)
    }

    @Test fun onePlannedRouteNeedsNoPrompt() {
        assertFalse(RoutePicker.needsChoice(listOf(a, c), null)); assertEquals("A", RoutePicker.inUse(listOf(a, c), null)?.name)
    }

    @Test fun savedChoiceThatIsNoLongerPlannedAsksAgain() = assertTrue(RoutePicker.needsChoice(listOf(a, b), 3))
}

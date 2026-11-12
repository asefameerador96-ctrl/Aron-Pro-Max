package com.aktcl.aron.sr

import com.aktcl.aron.feature.home.HomeTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SRM-A-13: the programme tiles have a screen to open (never a dead tile) and the new screens sit under the right parents. */
class SrComingSoonTest {
    @Test fun everyProgrammeTileHasATitleOfItsOwn() {
        val titles = HomeTile.entries.filter { it.isProgramme }.map { soonTitle(it) }
        assertEquals(4, titles.distinct().size)
        assertTrue(HomeTile.entries.filter { it.isProgramme }.all { it.alwaysShown })
    }

    @Test fun clustersAndTheSoonScreenReturnToTheirParents() {
        assertEquals(SrScreen.OUTLET_MENU, SrNav.parent(SrScreen.CLUSTERS))
        assertEquals(SrScreen.HOME, SrNav.parent(SrScreen.COMING_SOON))
        assertEquals(SrScreen.OUTLET_MENU, SrNav.parent(SrScreen.REQUEST_FORM))
    }
}

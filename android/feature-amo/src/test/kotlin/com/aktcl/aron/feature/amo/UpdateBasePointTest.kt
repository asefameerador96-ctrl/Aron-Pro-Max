package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateBasePointTest {
    @Test fun movingByTenMetresMovesTheRightDistanceInTheRightDirection() {
        val (lat, lng) = UpdateBasePoint.move(23.79, 90.40, 10, 0)
        assertEquals(10.0, Geo.haversineM(23.79, 90.40, lat, lng), 0.5); assertTrue(lat > 23.79)
        val (lat2, lng2) = UpdateBasePoint.move(23.79, 90.40, 0, -30)
        assertEquals(30.0, Geo.haversineM(23.79, 90.40, lat2, lng2), 0.5); assertTrue(lng2 < 90.40)
    }

    @Test fun offsetIsStraightLineAndTheLimitStopsFurtherSteps() {
        assertEquals(50, UpdateBasePoint.offsetM(30, 40))
        assertTrue(UpdateBasePoint.canMove(0, 90, 0, 10, 100))
        assertFalse(UpdateBasePoint.canMove(0, 100, 0, 10, 100))
        assertTrue(UpdateBasePoint.canMove(0, 100, 0, -10, 100)) // back towards the AMO is always allowed
    }
}

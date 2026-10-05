package com.aktcl.aron.rules

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** N-004: haversine against 20 references from shared/rules/reference/haversine_reference.py (chord and cross/dot formulas). */
class GeoTest {
    private data class Ref(val name: String, val lat1: Double, val lng1: Double, val lat2: Double, val lng2: Double, val metres: Double)

    private val refs = listOf(
        Ref("same point", 23.7808, 90.4, 23.7808, 90.4, 0.0000),
        Ref("10 m north", 23.7808, 90.4, 23.78088993, 90.4, 9.9998),
        Ref("10 m east", 23.7808, 90.4, 23.7808, 90.4000979, 9.9617),
        Ref("63 m diagonal", 23.7808, 90.4, 23.78045, 90.40049, 63.2504),
        Ref("geofence edge ~100 m", 23.8103, 90.4125, 23.8112, 90.4125, 100.0756),
        Ref("458 m east", 23.75, 90.39, 23.75, 90.3945, 458.0016),
        Ref("4.9 km across Dhaka", 23.73, 90.41, 23.77, 90.39, 4891.4689),
        Ref("Gulshan-Motijheel", 23.7925, 90.4078, 23.733, 90.4172, 6684.9083),
        Ref("Dhaka-Narayanganj", 23.8103, 90.4125, 23.6238, 90.5, 22570.0963),
        Ref("Dhaka-Chittagong", 23.8103, 90.4125, 22.3569, 91.7832, 213952.4867),
        Ref("Dhaka-Sylhet", 23.8103, 90.4125, 24.8949, 91.8687, 190537.1088),
        Ref("Dhaka-Khulna", 23.8103, 90.4125, 22.8456, 89.5403, 139418.5103),
        Ref("Dhaka-Rajshahi", 23.8103, 90.4125, 24.3745, 88.6042, 193980.7663),
        Ref("Teknaf-Panchagarh", 20.862, 92.305, 26.3411, 88.5542, 719072.2566),
        Ref("equator 1 degree", 0.0, 0.0, 0.0, 1.0, 111195.0802),
        Ref("meridian 1 degree", 0.0, 0.0, 1.0, 0.0, 111195.0802),
        Ref("across antimeridian", 10.0, 179.9, 10.0, -179.9, 21901.1551),
        Ref("pole to near pole", 90.0, 0.0, 89.99, 123.0, 1111.9508),
        Ref("southern hemisphere", -33.8688, 151.2093, -37.8136, 144.9631, 713428.4661),
        Ref("near antipodal", 23.0, 90.0, -22.9, -90.1, 19999998.6332),
    )

    @Test
    fun haversineMatchesTwentyReferencePairsWithinHalfAMetre() {
        assertEquals(20, refs.size)
        for (r in refs) {
            val d = Geo.haversineM(r.lat1, r.lng1, r.lat2, r.lng2)
            assertTrue(abs(d - r.metres) <= 0.5, "${r.name}: got $d expected ${r.metres}")
            val rev = Geo.haversineM(r.lat2, r.lng2, r.lat1, r.lng1)
            assertTrue(abs(d - rev) <= 1e-6, "${r.name}: not symmetric")
        }
    }

    @Test
    fun haversineAgreesWithTheVectorFormulaOnRandomPairs() {
        val rnd = kotlin.random.Random(42)
        repeat(2000) {
            val a = rnd.nextDouble(-90.0, 90.0); val b = rnd.nextDouble(-180.0, 180.0)
            val c = rnd.nextDouble(-90.0, 90.0); val d = rnd.nextDouble(-180.0, 180.0)
            assertTrue(abs(Geo.haversineM(a, b, c, d) - Geo.vectorDistanceM(a, b, c, d)) < 0.01, "$a,$b,$c,$d")
        }
    }

    @Test
    fun speedAndTeleport() {
        assertEquals(36.0, Geo.impliedSpeedKmh(1000.0, 100_000), 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, Geo.impliedSpeedKmh(10.0, 0))
        assertEquals(0.0, Geo.impliedSpeedKmh(0.0, 0))
        val a = GeoPoint(23.78, 90.40, 0)
        val farNorth = GeoPoint(23.7926, 90.40, 60_000)          // ~1.4 km in 1 min = 84 km/h
        assertTrue(Geo.isTeleport(a, farNorth))
        assertFalse(Geo.isTeleport(a, GeoPoint(23.7926, 90.40, 600_000)))   // same hop in 10 min = 8 km/h
        assertFalse(Geo.isTeleport(a, GeoPoint(23.7810, 90.40, 1_000)))     // fast but under 500 m
        assertTrue(Geo.isTeleport(a, GeoPoint(23.7926, 90.40, 0)))          // same instant, apart: impossible
    }

    @Test
    fun routeSinglePoint() {
        val same = List(10) { GeoPoint(23.78 + it * 1e-6, 90.40, it.toLong()) }
        assertTrue(Geo.isRouteSinglePoint(same))
        assertFalse(Geo.isRouteSinglePoint(same.take(7)), "fewer than 8 visits")
        val spread = List(10) { GeoPoint(23.78 + it * 0.001, 90.40, it.toLong()) }
        assertFalse(Geo.isRouteSinglePoint(spread))
        val eightOfTen = List(8) { GeoPoint(23.78, 90.40) } + List(2) { GeoPoint(23.9 + it, 90.5) }
        assertTrue(Geo.isRouteSinglePoint(eightOfTen))
        assertFalse(Geo.isRouteSinglePoint(List(7) { GeoPoint(23.78, 90.40) } + List(3) { GeoPoint(23.9 + it, 90.5) }))
    }

    @Test
    fun clampRadius() {
        assertEquals(20, Geo.clampRadius(5, 20, 2000))
        assertEquals(2000, Geo.clampRadius(9999, 20, 2000))
        assertEquals(100, Geo.clampRadius(100, 20, 2000))
    }
}

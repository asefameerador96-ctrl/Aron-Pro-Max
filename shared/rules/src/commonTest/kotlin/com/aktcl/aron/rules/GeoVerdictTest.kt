package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** N-004: radius resolution order and the s11.2 verdict. */
class GeoVerdictTest {
    private val outlet = OutletGeo(true, 23.8103, 90.4125)
    // ~ 100.08 m north of the outlet (reference table, "geofence edge")
    private val at = { lat: Double, acc: Double?, mock: Boolean -> FixInput(true, lat, 90.4125, acc, mock) }
    private val pol = GeoPolicy(MockPolicy.BLOCK_SALE, NoLocationPolicy.FORCE_SALE_REQUIRED, false, 0, 3)

    @Test
    fun radiusResolvesFromTheMostSpecificScope() {
        val all = ConfigScope.entries.map { ScopedValue(it, it.precedence + 1000) }
        assertEquals(ConfigScope.DEVICE.precedence + 1000, RadiusResolver.resolve(all, 100))
        val order = listOf(ConfigScope.OUTLET, ConfigScope.ROUTE, ConfigScope.ZONE, ConfigScope.GEO_CLASS, ConfigScope.HOUSE,
            ConfigScope.TERRITORY, ConfigScope.DIVISION, ConfigScope.WING, ConfigScope.GLOBAL)
        // remove the most specific one at a time: the next one must win
        for (i in order.indices) {
            val chain = order.drop(i).map { ScopedValue(it, it.name) }.shuffled(kotlin.random.Random(i))
            assertEquals(order[i].name, RadiusResolver.resolve(chain, "default"))
        }
        assertEquals("default", RadiusResolver.resolve(emptyList(), "default"))
        assertEquals(150, RadiusResolver.resolve(listOf(ScopedValue(ConfigScope.WING, 150), ScopedValue(ConfigScope.GLOBAL, 100)), 100))
        assertFailsWith<IllegalArgumentException> {
            RadiusResolver.resolve(listOf(ScopedValue(ConfigScope.ZONE, 1), ScopedValue(ConfigScope.ZONE, 2)), 0)
        }
    }

    @Test
    fun mockedFixNeverValidatesUnderAnyPolicyOrDistance() {
        for (mp in MockPolicy.entries) for (tol in listOf(false, true)) for (lat in listOf(23.8103, 23.8104, 24.5)) {
            val r = GeoVerdicts.verdict(at(lat, 5.0, true), outlet, 100, 100, pol.copy(mockPolicy = mp, accuracyTolerant = tol))
            assertEquals(GeoVerdict.MOCKED, r.verdict)
            assertNotEquals(GeoAction.SALE_ALLOWED, r.action)
        }
        assertEquals(GeoAction.BLOCKED, GeoVerdicts.verdict(at(23.8103, 5.0, true), outlet, 100, 100, pol).action)
        val warn = GeoVerdicts.verdict(at(23.8103, 5.0, true), outlet, 100, 100, pol.copy(mockPolicy = MockPolicy.WARN_REP))
        assertTrue(warn.warnRep); assertEquals(GeoAction.FORCE_SALE, warn.action)
        val silent = GeoVerdicts.verdict(at(23.8103, 5.0, true), outlet, 100, 100, pol.copy(mockPolicy = MockPolicy.SILENT_FLAG))
        assertEquals(GeoAction.FORCE_SALE, silent.action); assertEquals(false, silent.warnRep)
    }

    @Test
    fun accuracyWorseThanTheLimitIsNotAProof() {
        val r = GeoVerdicts.verdict(at(23.8103, 100.5, false), outlet, 100, 100, pol)
        assertEquals(GeoVerdict.ACCURACY_TOO_LOW, r.verdict)
        assertEquals(GeoAction.REFRESH_OFFERED, r.action)
        assertEquals(GeoAction.FORCE_SALE, GeoVerdicts.verdict(at(23.8103, 100.5, false), outlet, 100, 100, pol.copy(refreshCount = 3)).action)
        assertEquals(GeoVerdict.IN_RANGE, GeoVerdicts.verdict(at(23.8103, 100.0, false), outlet, 100, 100, pol).verdict)
        assertEquals(GeoVerdict.ACCURACY_TOO_LOW, GeoVerdicts.verdict(at(23.8103, null, false), outlet, 100, 100, pol).verdict)
        assertEquals(GeoVerdict.ACCURACY_TOO_LOW, GeoVerdicts.verdict(at(23.8103, Double.NaN, false), outlet, 100, 100, pol).verdict)
    }

    @Test
    fun rangeBoundaryAndTolerance() {
        // reference: 23.8103 -> 23.8112 is 100.0756 m
        val edge = at(23.8112, 10.0, false)
        assertEquals(GeoVerdict.OUT_OF_RANGE, GeoVerdicts.verdict(edge, outlet, 100, 100, pol).verdict)
        assertEquals(GeoVerdict.IN_RANGE, GeoVerdicts.verdict(edge, outlet, 101, 100, pol).verdict)
        assertEquals(GeoVerdict.IN_RANGE, GeoVerdicts.verdict(edge, outlet, 100, 100, pol.copy(accuracyTolerant = true)).verdict)
        val far = GeoVerdicts.verdict(at(23.8203, 10.0, false), outlet, 100, 100, pol)
        assertEquals(GeoVerdict.OUT_OF_RANGE, far.verdict); assertEquals(GeoAction.REFRESH_OFFERED, far.action)
        assertTrue(far.distanceM!! > 1000)
    }

    @Test
    fun noFixAndNoOutletLocation() {
        val none = GeoVerdicts.verdict(FixInput(false, 0.0, 0.0, null, false), outlet, 100, 100, pol)
        assertEquals(GeoVerdict.NO_FIX, none.verdict); assertEquals(GeoAction.REFRESH_OFFERED, none.action)
        assertEquals(GeoAction.FORCE_SALE, GeoVerdicts.verdict(FixInput(false, 0.0, 0.0, null, false), outlet, 100, 100, pol.copy(refreshCount = 3)).action)
        assertEquals(GeoVerdict.NO_FIX, GeoVerdicts.verdict(FixInput(true, Double.NaN, 90.0, 5.0, false), outlet, 100, 100, pol).verdict)
        val noLoc = OutletGeo(false, 0.0, 0.0)
        assertEquals(GeoAction.FORCE_SALE, GeoVerdicts.verdict(at(23.8, 5.0, false), noLoc, 100, 100, pol).action)
        assertEquals(GeoAction.SALE_ALLOWED, GeoVerdicts.verdict(at(23.8, 5.0, false), noLoc, 100, 100, pol.copy(noLocationPolicy = NoLocationPolicy.ALLOW_UNVALIDATED)).action)
        assertEquals(GeoAction.BLOCKED, GeoVerdicts.verdict(at(23.8, 5.0, false), noLoc, 100, 100, pol.copy(noLocationPolicy = NoLocationPolicy.BLOCK)).action)
        // a mocked fix is judged before the outlet has a location
        assertEquals(GeoVerdict.MOCKED, GeoVerdicts.verdict(at(23.8, 5.0, true), noLoc, 100, 100, pol).verdict)
    }
}

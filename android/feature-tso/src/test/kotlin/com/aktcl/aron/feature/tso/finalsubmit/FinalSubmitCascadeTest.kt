package com.aktcl.aron.feature.tso.finalsubmit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * The five-level cascade of Final Submit (D-TSO-MAN-32, screenshots 23 and 24): Wing > Division > Territory > House > Zone
 * from `GET /v1/dashboards/national/filter-options` (FilterNode parent ids; the server cuts the lists to the token's reach,
 * the phone sends no ids). A level with one option is preselected; Get Sales Data needs all five.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FinalSubmitCascadeTest {
    companion object {
        /** Screenshot 24's tree: one wing, division, territory and house, and the house's two zones (Banani - Test, Gulshan - Test). */
        val screenshot23 = FsFilterOptions(
            wings = listOf(FsFilterNode(1, "Apsis Wing", null)),
            divisions = listOf(FsFilterNode(11, "Apsis Division", 1)),
            territories = listOf(FsFilterNode(21, "Apsis Territory", 11)),
            houses = listOf(FsFilterNode(31, "Apsis House", 21, 21)),
            zones = listOf(FsFilterNode(41, "Banani - Test", 31, 21), FsFilterNode(42, "Gulshan - Test", 31, 21)),
        )

        /** Two wings; the second wing's territory has two houses and one zone without a house. */
        val wide = FsFilterOptions(
            wings = listOf(FsFilterNode(1, "W1", null), FsFilterNode(2, "W2", null)),
            divisions = listOf(FsFilterNode(11, "D1", 1), FsFilterNode(12, "D2", 2)),
            territories = listOf(FsFilterNode(21, "T1", 11), FsFilterNode(22, "T2", 12)),
            houses = listOf(FsFilterNode(31, "H1", 21, 21), FsFilterNode(32, "H2", 22, 22), FsFilterNode(33, "H3", 22, 22)),
            zones = listOf(
                FsFilterNode(41, "Z1", 31, 21), FsFilterNode(42, "Z2", 32, 22), FsFilterNode(43, "Z3", 32, 22),
                FsFilterNode(44, "Z4", 33, 22), FsFilterNode(45, "Z5", null, 22),
            ),
        )
    }

    private class Tree(val o: FsFilterOptions?, val flat: List<Long>? = listOf(50L)) : FinalSubmitApi {
        var reads = 0
        override suspend fun zones() = flat
        override suspend fun filterOptions(): FsFilterOptions? { reads++; return o }
        override suspend fun preview(zoneId: Long, date: String) = FsPreviewOutcome.Failed(FsFailure.SERVER)
        override suspend fun submit(clientUuid: String, zoneId: Long, date: String) = FsSubmitOutcome.Failed(FsFailure.SERVER)
    }
    private fun state(t: Tree) = FinalSubmitState(t, 7) { "2026-10-26" }

    @Test fun singleOptionLevelsArePreselectedDownToTheChoice() = runBlocking {
        val t = Tree(screenshot23); val s = state(t); s.loadCascade()
        assertTrue(s.ui.value.cascade)
        assertEquals(mapOf(0 to 1L, 1 to 11L, 2 to 21L, 3 to 31L), s.ui.value.picks)
        assertEquals(listOf(41L, 42L), s.ui.value.options[4]!!.map { it.id })
        assertNull("two zones: the TSO chooses, Get Sales Data stays off", s.ui.value.zoneId)
        s.pickLevel(4, 41); assertEquals(41L, s.ui.value.zoneId)
        assertEquals(1, t.reads) // one read for the whole cascade, no request per level
    }

    @Test fun aSingleZoneIsPreselectedTooSoGetIsReadyAtOnce() = runBlocking {
        val one = screenshot23.copy(zones = screenshot23.zones.take(1))
        val s = state(Tree(one)); s.loadCascade()
        assertEquals(41L, s.ui.value.zoneId)
        assertEquals(5, s.ui.value.picks.size)
    }

    @Test fun eachLevelListsTheChildrenOfThePickAbove() = runBlocking {
        val s = state(Tree(wide)); s.loadCascade()
        assertTrue(s.ui.value.picks.isEmpty()); assertNull(s.ui.value.zoneId)
        assertEquals(listOf(1L, 2L), s.ui.value.options[0]!!.map { it.id })
        s.pickLevel(0, 2)
        assertEquals(mapOf(0 to 2L, 1 to 12L, 2 to 22L), s.ui.value.picks) // D2 and T2 were the only options
        // the territory's houses, plus "No house" for its zone without one
        assertEquals(listOf(32L, 33L, FsGeo.NO_HOUSE), s.ui.value.options[3]!!.map { it.id })
        s.pickLevel(3, 32); assertEquals(listOf(42L, 43L), s.ui.value.options[4]!!.map { it.id })
        s.pickLevel(3, 33); assertEquals(44L, s.ui.value.zoneId) // H3 has one zone: picked
        s.pickLevel(3, FsGeo.NO_HOUSE); assertEquals(45L, s.ui.value.zoneId)
    }

    @Test fun changingAnUpperLevelClearsEverythingBelow() = runBlocking {
        val s = state(Tree(wide)); s.loadCascade()
        s.pickLevel(0, 2); s.pickLevel(3, 32); s.pickLevel(4, 43)
        assertEquals(43L, s.ui.value.zoneId)
        s.pickLevel(0, 1)
        assertEquals(mapOf(0 to 1L, 1 to 11L, 2 to 21L, 3 to 31L, 4 to 41L), s.ui.value.picks) // W1's chain has one option per level
        assertEquals(41L, s.ui.value.zoneId)
        s.pickLevel(0, 2)
        assertNull(s.ui.value.zoneId); assertFalse(s.ui.value.picks.containsKey(3)); assertFalse(s.ui.value.options.containsKey(4))
    }

    @Test fun anIdOutsideTheOfferedOptionsIsIgnored() = runBlocking {
        val s = state(Tree(wide)); s.loadCascade()
        s.pickLevel(0, 99); s.pickLevel(4, 41); s.pickLevel(2, 21)
        assertTrue(s.ui.value.picks.isEmpty()); assertNull(s.ui.value.zoneId)
    }

    @Test fun unreadableFilterOptionsFallBackToTheFlatZoneList() = runBlocking {
        val s = state(Tree(null)); s.loadCascade()
        assertFalse(s.ui.value.cascade); assertTrue(s.ui.value.cascadeTried)
        s.loadZones(); s.pickZone(50); assertEquals(50L, s.ui.value.zoneId)
        val empty = state(Tree(FsFilterOptions(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))); empty.loadCascade()
        assertFalse(empty.ui.value.cascade)
    }

    @Test fun filterOptionsJsonIsReadWithParentsAndBadNodesDropped() {
        val body = """{"date":"2026-10-26","till_time":true,"as_of":"2026-10-26T09:00:00Z","last_updated_at":null,
          "wings":[{"id":1,"code":"W-A","name":"Apsis Wing","wing_id":null,"division_id":null,"territory_id":null,"house_id":null},{"id":0,"code":"bad","name":"x"}],
          "divisions":[{"id":11,"code":"D-A","name":"Apsis Division","wing_id":1}],
          "territories":[{"id":21,"code":"T-A","name":null,"division_id":11}],
          "houses":[{"id":31,"code":"H-A","name":"Apsis House","territory_id":21}],
          "zones":[{"id":41,"code":"Z-B","name":"Banani - Test","house_id":31,"territory_id":21},{"id":45,"code":"Z-X","name":"Loose","house_id":null,"territory_id":21}],
          "categories":[],"segments":[],"brands":[],"variants":[]}"""
        val o = FsGeo.parseFilterOptions(body)!!
        assertEquals(listOf(1L), o.wings.map { it.id })
        assertEquals(1L, o.divisions.single().parentId)
        assertEquals("T-A", o.territories.single().name) // a null name falls back to the code
        assertEquals(21L, o.houses.single().parentId)
        assertEquals(listOf(31L, null), o.zones.map { it.parentId }); assertEquals(listOf(21L, 21L), o.zones.map { it.territoryId })
        assertEquals(listOf(31L, FsGeo.NO_HOUSE), FsGeo.children(o, 3, mapOf(0 to 1L, 1 to 11L, 2 to 21L)).map { it.id })
        assertNull(FsGeo.parseFilterOptions("not json")); assertNull(FsGeo.parseFilterOptions("""{"wings":[]}"""))
    }
}

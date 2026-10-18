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

/** The five-level cascade (Wing, Division, Territory, House, Zone) of F-TSO-010. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FinalSubmitCascadeTest {
    // parent id -> children, per level; ids are unique across the tree
    private class Tree(val kids: Map<Pair<String, Long?>, List<FsGeoNode>>, var broken: Boolean = false) : FinalSubmitApi {
        override suspend fun zones() = listOf(50L)
        override suspend fun geo(level: String, parentId: Long?): List<FsGeoNode>? = if (broken && parentId != null) null else kids[level to parentId].orEmpty()
        override suspend fun preview(zoneId: Long, date: String) = FsPreviewOutcome.Failed(FsFailure.SERVER)
        override suspend fun submit(clientUuid: String, zoneId: Long, date: String) = FsSubmitOutcome.Failed(FsFailure.SERVER)
    }
    private fun n(id: Long, name: String) = FsGeoNode(id, name, null)
    private fun state(t: Tree) = FinalSubmitState(t, 7) { "2026-10-17" }

    private val tree = mapOf(
        ("wing" to null) to listOf(n(1, "W1"), n(2, "W2")),
        ("division" to 1L) to listOf(n(11, "D1")),
        ("territory" to 11L) to listOf(n(21, "T1")),
        ("house" to 21L) to listOf(n(31, "H1"), n(32, "H2")),
        ("zone" to 31L) to listOf(n(41, "Z1"), n(42, "Z2")),
        ("zone" to 32L) to listOf(n(43, "Z3")),
    )

    @Test fun singleChildLevelsArePickedAndTheZoneIsChosenLast() = runBlocking {
        val s = state(Tree(tree)); s.loadCascade()
        assertTrue(s.ui.value.cascade); assertNull(s.ui.value.zoneId)
        s.pickLevel(0, 1)
        assertEquals(mapOf(0 to 1L, 1 to 11L, 2 to 21L), s.ui.value.picks)   // division and territory had one node each
        assertEquals(listOf(31L, 32L), s.ui.value.options[3]!!.map { it.id })
        s.pickLevel(3, 31); assertNull(s.ui.value.zoneId)
        s.pickLevel(4, 42); assertEquals(42L, s.ui.value.zoneId)
    }

    @Test fun changingAnUpperLevelClearsEverythingBelow() = runBlocking {
        val s = state(Tree(tree)); s.loadCascade()
        s.pickLevel(0, 1); s.pickLevel(3, 31); s.pickLevel(4, 41)
        s.pickLevel(3, 32)   // house with a single zone: picked for the TSO
        assertEquals(43L, s.ui.value.zoneId); assertEquals(listOf(43L), s.ui.value.options[4]!!.map { it.id })
        s.pickLevel(0, 2)
        assertNull(s.ui.value.zoneId); assertEquals(setOf(0), s.ui.value.picks.keys); assertEquals(emptyList<FsGeoNode>(), s.ui.value.options[1])   // W2 has no divisions; nothing deeper is offered
    }

    @Test fun anIdOutsideTheOfferedOptionsIsIgnored() = runBlocking {
        val s = state(Tree(tree)); s.loadCascade()
        s.pickLevel(0, 99); s.pickLevel(4, 41)
        assertTrue(s.ui.value.picks.isEmpty()); assertNull(s.ui.value.zoneId)
    }

    @Test fun anUnreadableTreeFallsBackToTheFlatZoneList() = runBlocking {
        val nothing = state(Tree(emptyMap())); nothing.loadCascade(); assertFalse(nothing.ui.value.cascade)
        val broken = state(Tree(tree, broken = true)); broken.loadCascade(); broken.pickLevel(0, 1)
        assertFalse(broken.ui.value.cascade); assertNull(broken.ui.value.zoneId)
        broken.loadZones(); broken.pickZone(50); assertEquals(50L, broken.ui.value.zoneId)
    }

    @Test fun nodePageParsingDropsBadRows() {
        val p = FsGeo.parse("""{"items":[{"id":1,"name":"A","name_bn":"ক"},{"id":0,"name":"x"},{"id":3,"name":""},{"id":4,"name":"D","name_bn":null}]}""")!!
        assertEquals(listOf(1L, 4L), p.map { it.id })
        assertEquals("ক", p[0].label(true)); assertEquals("A", p[0].label(false)); assertEquals("D", p[1].label(true))
        assertNull(FsGeo.parse("not json"))
    }
}

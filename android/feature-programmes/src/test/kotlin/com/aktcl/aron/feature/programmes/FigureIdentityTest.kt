package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The identity rule (docs/requests/sr-programmes-phone-rule-b.md): a server MODEL answers the probe from ONE snapshot (the way the real endpoint does), the phone merges by the answer, and what it shows must be
 * exactly the truth: the sum over every memo live in the snapshot plus every phone-live memo the snapshot does not hold, each counted once. No acknowledgement, resolution, quarantine state or clock is read anywhere.
 */
class FigureIdentityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val month = "2026-11"; private val through = "2026-11-17"; private val sku = 5L

    /** The server's stored memo. [stored] is what the server kept (a refused line is missing); [active] false = voided/superseded/admin-voided. */
    private data class Stored(val uuid: String, val date: String, val stored: List<Long>, val active: Boolean = true)
    /** One snapshot S: the figure (sum of stored sale quantities of active memos dated in the month up to through) and the answer for the probe. */
    private class Snapshot(val stored: List<Stored>, val through: String, val month: String) {
        val figure get() = stored.filter { it.active && it.date.take(7) == month && it.date <= through }.let { FigureRuleB.ServerFigure(it.sumOf { s -> s.stored.sum() }, it.size.toLong(), 0L, emptyMap(), "informational") }
        fun answer(probe: List<FigureProbe.Entry>): FiguresAnswer {
            val inF = LinkedHashMap<String, FigureIn>(); val voided = LinkedHashSet<String>()
            probe.forEach { e ->
                val s = stored.firstOrNull { it.uuid == e.memoUuid } ?: return@forEach
                if (s.active && s.date.take(7) == month && s.date <= through) inF[e.memoUuid] = FigureIn(s.stored.size, if (e.removedLocally) s.stored.map { StoredLine(5, it, "sale") } else null)
                else if (!s.active) voided += e.memoUuid
            }
            return FiguresAnswer(null, through, inF, voided, emptySet(), kotlinx.serialization.json.JsonArray(emptyList()), null, "")
        }
    }

    private fun lm(uuid: String, date: String, qty: Long, state: String = "pending", supersedes: String? = null, no: String? = null) =
        LocalMemo(uuid, 1, date, supersedes, state, null, listOf(LocalMemoLine(sku, "sale", qty, 20, qty * 100)), 7, memoNo = no ?: "n-$uuid")

    private fun shown(snapshot: Snapshot, phone: List<LocalMemo>, confirmed: Set<String> = emptySet()): FigureRuleB.Shown {
        val probe = FigureProbe.build(phone, month, snapshot.through, confirmed)
        val answer = snapshot.answer(probe.entries)
        return FigureIdentity.show(snapshot.figure, phone, answer, month, snapshot.through, { u -> answer.inFigure[u]?.storedLines }, confirmedIn = confirmed)!!
    }
    private fun truth(snapshot: Snapshot, phone: List<LocalMemo>): Long {
        // phone-live memos (not superseded by a live memo, not refused) counted once; a memo the server holds counts as the server stored it; an inactive stored memo counts zero
        val gone = phone.filter { it.state != "rejected" }.mapNotNull { it.supersedes }.toSet()
        return phone.filter { it.state != "rejected" && it.uuid !in gone && it.businessDate.take(7) == month }.sumOf { m ->
            val st = snapshot.stored.firstOrNull { it.uuid == m.uuid }
            when { st == null -> m.lines.sumOf { it.qtyBase }; !st.active -> 0L; st.date > snapshot.through -> m.lines.sumOf { it.qtyBase }; else -> st.stored.sum() }
        }
    }

    @Test fun everyCommitInterleavingShowsTheTruthWithNoAckAnywhere() {
        val phone = listOf(lm("a", "2026-11-03", 10), lm("b", "2026-11-03", 20), lm("c", "2026-11-04", 30), lm("d", "2026-11-17", 5), lm("t", "2026-11-18", 7))
        // the server committed a prefix of the uploads when the snapshot was read (the phone does not know which)
        for (k in 0..phone.size) {
            val committed = phone.take(k).map { Stored(it.uuid, it.businessDate, listOf(it.lines.sumOf { l -> l.qtyBase })) }
            val s = Snapshot(committed, through, month)
            assertEquals("after $k commits", truth(s, phone), shown(s, phone).qtyBase)
        }
    }

    @Test fun aMemoHeldForReviewThenReleasedBeforeThePhoneHearsIsINOnceReleasedAndNotAddedTwice() {
        val m = lm("m", "2026-11-05", 12, state = "quarantined")
        val held = Snapshot(emptyList(), through, month) // still held: NOT IN, the phone's own copy is added... it is NOT in the figure
        assertEquals(12L, shown(held, listOf(m)).qtyBase)
        val released = Snapshot(listOf(Stored("m", "2026-11-05", listOf(12))), through, month) // released by a reviewer before the phone heard: IN
        assertEquals(12L, shown(released, listOf(m)).qtyBase) // counted once
    }

    @Test fun aHeldEditIsNotLostAndTheOldMemoIsSubtractedWhileItIsStillInAndNeverTwice() {
        val old = lm("old", "2026-11-05", 10, state = "acked"); val edit = lm("edit", "2026-11-05", 14, state = "quarantined", supersedes = "old")
        val held = Snapshot(listOf(Stored("old", "2026-11-05", listOf(10))), through, month) // the edit is held: the server still has the old one
        assertEquals(14L, shown(held, listOf(old, edit)).qtyBase) // 10 (figure) - 10 (old, IN, stored lines) + 14 (edit NOT IN)
        val released = Snapshot(listOf(Stored("old", "2026-11-05", listOf(10), active = false), Stored("edit", "2026-11-05", listOf(14))), through, month)
        assertEquals(14L, shown(released, listOf(old, edit)).qtyBase) // old VOIDED, edit IN: nothing added or subtracted
    }

    @Test fun aLineTheServerRefusedForGoodIsInWithFewerLinesAndNothingDoublesOnEveryBundle() {
        val m = lm("m", "2026-11-05", 12).copy(lines = listOf(LocalMemoLine(sku, "sale", 8, 20, 800), LocalMemoLine(sku, "sale", 4, 20, 400)))
        val s = Snapshot(listOf(Stored("m", "2026-11-05", listOf(8))), through, month) // the 4 was refused for good (a zero price)
        val shown = shown(s, listOf(m))
        assertEquals(8L, shown.qtyBase); assertEquals(1L, shown.memoCount) // the figure holds what the server stored; the phone adds nothing
    }

    @Test fun anAdminDataVoidOfTodayDropsTheMemoAndAnOfficeVoidOfAConfirmedOneLeavesByItself() {
        val today = lm("t", "2026-11-18", 7)
        val voided = Snapshot(listOf(Stored("t", "2026-11-18", listOf(7), active = false)), through, month)
        assertEquals(0L, shown(voided, listOf(today)).qtyBase) // VOIDED: dropped
        // confirmed IN earlier, voided by the office since: not probed again, absent from the figure, nothing added
        val old = lm("old", "2026-11-05", 9, state = "acked")
        val after = Snapshot(listOf(Stored("old", "2026-11-05", listOf(9), active = false)), through, month)
        assertEquals(0L, shown(after, listOf(old), confirmed = setOf("old")).qtyBase)
    }

    @Test fun aPaperTwinAnswerOfINMakesThePhonesOwnCopyNotBeAddedOnTop() {
        // the server answers for the phone's uuid with the paper twin's state: IN
        val m = lm("phoneCopy", "2026-11-06", 15, no = "SR-1001")
        val answer = FiguresAnswer(null, through, mapOf("phoneCopy" to FigureIn(1, null)), emptySet(), emptySet(), kotlinx.serialization.json.JsonArray(emptyList()), null, "")
        assertEquals(15L, FigureIdentity.show(FigureRuleB.ServerFigure(15, 1), listOf(m), answer, month, through)!!.qtyBase)
        assertEquals("SR-1001", FigureProbe.build(listOf(m), month, through, emptySet()).entries.single().memoNo) // the memo number is always sent
    }

    @Test fun aBundleOnTheFirstOfTheMonthAddsEveryMemoOfTheMonthNotVoided() {
        val phone = listOf(lm("a", "2026-11-01", 4), lm("b", "2026-11-01", 6, state = "acked"))
        val s = Snapshot(emptyList(), "2026-10-31", month)
        assertEquals(10L, shown(s, phone).qtyBase)
        assertEquals(2, FigureProbe.build(phone, month, "2026-10-31", setOf("b")).entries.size) // dated after through_date: asked on every request
    }

    @Test fun twoRoutesAreKeptApartByTheMemoFilter() {
        val a = lm("a", "2026-11-05", 4); val b = lm("b", "2026-11-05", 6).copy(routeId = 9)
        val s = Snapshot(emptyList(), through, month)
        val answer = s.answer(emptyList())
        assertEquals(4L, FigureIdentity.show(FigureRuleB.ServerFigure(0, 0), listOf(a, b), answer, month, through, memoIn = { it.routeId == 7L })!!.qtyBase)
    }

    @Test fun moreThanThreeHundredUnconfirmedMemosSaysItIsStillSyncing() {
        val many = (1..301).map { lm("m$it", "2026-11-05", 1) }
        val p = FigureProbe.build(many, month, through, emptySet())
        assertTrue(p.tooMany); assertEquals(FigureProbe.CAP, p.entries.size)
        assertFalse(FigureProbe.build(many.take(300), month, through, emptySet()).tooMany)
        // confirmed ones are not asked again
        assertEquals(0, FigureProbe.build(many.take(10), month, through, many.take(10).map { it.uuid }.toSet()).entries.size)
    }

    @Test fun aMemoConfirmedInIsAskedAgainOnlyWhenThePhoneVoidsOrEditsItAndTodaysIsAlwaysAsked() {
        val m = lm("m", "2026-11-05", 5, state = "acked"); val today = lm("t", "2026-11-18", 3)
        assertEquals(listOf("t"), FigureProbe.build(listOf(m, today), month, through, setOf("m")).entries.map { it.memoUuid })
        val edit = lm("e", "2026-11-05", 6, supersedes = "m")
        val p = FigureProbe.build(listOf(m, edit, today), month, through, setOf("m")).entries
        assertEquals(setOf("m", "e", "t"), p.map { it.memoUuid }.toSet())
        assertTrue(p.single { it.memoUuid == "m" }.removedLocally); assertFalse(p.single { it.memoUuid == "e" }.removedLocally)
    }

    @Test fun anOldServerWithoutAnAnswerUsesTheBundleFiguresAndAddsOnlyNeverSentAndAfterThroughDate() {
        val phone = listOf(lm("never", "2026-11-05", 5), lm("acked", "2026-11-05", 7, state = "acked"), lm("today", "2026-11-18", 3, state = "acked"))
        val d = FigureRuleB.delta(phone, null, month, through)!!
        assertEquals(setOf("never", "today"), d.add.map { it.uuid }.toSet()) // an acknowledged memo of an earlier day may or may not be in the figure: not added
    }

    @Test fun theUnknownSourceAddsNothingBeforeThroughDateAndTheAckedSetMapsToTheInterface() {
        val phone = listOf(lm("p", "2026-11-05", 5), lm("a", "2026-11-05", 7, state = "acked"), lm("q", "2026-11-05", 2, state = "quarantined"))
        val h = AckedSetHolds(setOf("a"), phone)
        assertEquals(Holds.NOT_IN, h.of("p")); assertEquals(Holds.IN, h.of("a")); assertEquals(Holds.UNKNOWN, h.of("q"))
        val d = FigureIdentity.delta(phone, h, month, through)!!
        assertEquals(listOf("p"), d.add.map { it.uuid }); assertEquals(listOf("q"), d.unknown.map { it.uuid })
    }

    // ---- the answer, the request and the store ----

    @Test fun theAnswerIsReadAndTheRequestIsBuiltToTheContract() {
        val a = FiguresAnswerParser.parse("""{"business_date":"2026-11-18","through_date":"2026-11-17","routes":[{"route_id":7}],"figure_probe":{"in_figure":[{"memo_client_uuid":"u1","lines":2,"stored_lines":[{"sku_id":5,"qty_base":8,"line_kind":"sale"}]},{"memo_client_uuid":"u2","lines":1}],"voided":["u3"],"twin_ambiguous":["u4"]}}""")!!
        assertEquals(Holds.IN, a.of("u1")); assertEquals(Holds.IN, a.of("u2")); assertEquals(Holds.VOIDED, a.of("u3")); assertEquals(Holds.NOT_IN, a.of("u4")); assertEquals(Holds.NOT_IN, a.of("nobody"))
        assertEquals(listOf(StoredLine(5, 8, "sale")), a.inFigure["u1"]!!.storedLines); assertNull(a.inFigure["u2"]!!.storedLines); assertEquals(setOf("u4"), a.twinAmbiguous)
        assertNull(FiguresAnswerParser.parse("<html>")); assertNull(FiguresAnswerParser.parse("""{"routes":[]}"""))
        val json = FigureProbe.requestJson(listOf(FigureProbe.Entry("11111111-1111-4111-8111-111111111111", "SR-1", "2026-11-05", true), FigureProbe.Entry("22222222-2222-4222-8222-222222222222", null, "2026-11-06", false)), "2026-11-18", includeAstha = true)
        assertEquals("""{"for":"2026-11-18","probe":[{"memo_client_uuid":"11111111-1111-4111-8111-111111111111","memo_no":"SR-1","business_date":"2026-11-05","removed_locally":true},{"memo_client_uuid":"22222222-2222-4222-8222-222222222222","business_date":"2026-11-06"}],"include_astha":true}""", json)
    }

    @Test fun theStoreKeepsTheNewestAnswerAndWhatIsConfirmedInAcrossARelaunch() {
        val dir = File(tmp.root, "f"); val store = FiguresStore(dir)
        assertNull(store.loadAnswer()); assertEquals(emptySet<String>(), store.confirmedIn())
        val raw = """{"business_date":"2026-11-18","through_date":"2026-11-17","routes":[],"figure_probe":{"in_figure":[{"memo_client_uuid":"u1","lines":1},{"memo_client_uuid":"u2","lines":1}],"voided":["u3"]}}"""
        val answer = FiguresAnswerParser.parse(raw)!!
        store.saveConfirmedIn(setOf("u3"))
        store.record(answer, listOf(FigureProbe.Entry("u1", null, "2026-11-05", false), FigureProbe.Entry("u2", null, "2026-11-05", true), FigureProbe.Entry("u3", null, "2026-11-05", false)))
        val again = FiguresStore(dir)
        assertEquals(setOf("u1"), again.confirmedIn()) // u2 was removed locally: not confirmed; u3 VOIDED leaves the set
        assertEquals(Holds.IN, again.loadAnswer()!!.of("u1"))
        again.clear(); assertNull(FiguresStore(dir).loadAnswer())
    }

    // ---- the refresher: capability check, no timer, tolerant of a server without the endpoint ----
    private val okBody = """{"business_date":"2026-11-18","through_date":"2026-11-17","routes":[],"figure_probe":{"in_figure":[{"memo_client_uuid":"a","lines":1}],"voided":[]}}"""

    @Test fun anOldServerWithNoEndpointIsRememberedAndNeverAskedAgainThisSession() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val r = FiguresRefresher(FiguresStore(File(tmp.root, "g"))) { calls++; FiguresCall.NoEndpoint }
        assertEquals(FiguresRefresher.Outcome.NoEndpoint, r.refresh(listOf(lm("a", "2026-11-05", 5)), month, through))
        assertEquals(FiguresRefresher.Outcome.NoEndpoint, r.refresh(listOf(lm("a", "2026-11-05", 5)), month, through))
        assertEquals(1, calls); assertEquals(false, r.supported)
    }

    @Test fun anAnswerIsKeptAndWhatItHoldsIsConfirmedSoTheNextCallDoesNotAskAgain() = kotlinx.coroutines.runBlocking {
        val bodies = ArrayList<String>(); val store = FiguresStore(File(tmp.root, "h"))
        val r = FiguresRefresher(store) { bodies += it; FiguresCall.Ok(okBody) }
        val phone = listOf(lm("a", "2026-11-05", 5, state = "acked"))
        assertTrue(r.refresh(phone, month, through, "2026-11-18") is FiguresRefresher.Outcome.Updated)
        assertTrue(bodies[0].contains("\"memo_client_uuid\":\"a\"") && bodies[0].contains("\"for\":\"2026-11-18\""))
        assertEquals(setOf("a"), store.confirmedIn()); assertEquals(Holds.IN, store.loadAnswer()!!.of("a")); assertEquals(true, r.supported)
        r.refresh(phone, month, through)
        assertEquals("""{"probe":[]}""", bodies[1]) // confirmed IN: not asked again
    }

    @Test fun offlineAFailureOrAnUnreadableAnswerChangesNothing() = kotlinx.coroutines.runBlocking {
        val store = FiguresStore(File(tmp.root, "i")); store.saveConfirmedIn(setOf("keep"))
        for ((call, expected) in listOf(FiguresCall.Offline to FiguresRefresher.Outcome.Offline, FiguresCall.Failed to FiguresRefresher.Outcome.Failed, FiguresCall.Ok("<html>") to FiguresRefresher.Outcome.Failed)) {
            val r = FiguresRefresher(store) { call }
            assertEquals(expected, r.refresh(listOf(lm("a", "2026-11-05", 5)), month, through)); assertNull(r.supported)
        }
        assertEquals(setOf("keep"), store.confirmedIn()); assertNull(store.loadAnswer())
    }

    @Test fun moreThanThreeHundredUnconfirmedMemosMakesNoCallAndSaysTooMany() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val r = FiguresRefresher(FiguresStore(File(tmp.root, "j"))) { calls++; FiguresCall.Ok(okBody) }
        assertEquals(FiguresRefresher.Outcome.TooMany, r.refresh((1..301).map { lm("m$it", "2026-11-05", 1) }, month, through)); assertEquals(0, calls)
    }
}

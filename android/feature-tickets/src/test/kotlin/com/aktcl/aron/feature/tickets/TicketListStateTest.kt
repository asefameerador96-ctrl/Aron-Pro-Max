package com.aktcl.aron.feature.tickets

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All Tickets (screenshots 12, 13): local search, the five tabs (All also Cancelled), the unsent merge, offline copy. */
class TicketListStateTest {
    private val list = listOf(
        ticket("a", "INC010700", "resolved", "Login OTP issue", TYPES[0], at = T0 - 4 * DAY),
        ticket("b", "INC010576", "closed", "Final Submission", TYPES[2], at = T0 - 11 * DAY),
        ticket("c", "INC010519", "resolved", "For test", TYPES[3], at = T0 - 14 * DAY),
        ticket("d", "INC010393", "cancelled", "Final Submission", TYPES[2], at = T0 - 35 * DAY),
        ticket("e", "INC010357", "in_progress", "Final submit nicche na", TYPES[2], at = T0 - 39 * DAY),
        ticket("f", "INC010356", "new", "dd", TYPES[1], at = T0 - 40 * DAY),
    )

    private fun uuids(l: List<Ticket>) = l.map { it.uuid }

    @Test fun tabsFilterByStatusAndAllAlsoShowsCancelled() {
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), uuids(TicketLists.filter(list, TicketTab.ALL, "")))
        assertEquals(listOf("f"), uuids(TicketLists.filter(list, TicketTab.NEW, "")))
        assertEquals(listOf("e"), uuids(TicketLists.filter(list, TicketTab.IN_PROGRESS, "")))
        assertEquals(listOf("a", "c"), uuids(TicketLists.filter(list, TicketTab.RESOLVED, "")))
        assertEquals(listOf("b"), uuids(TicketLists.filter(list, TicketTab.CLOSED, "")))
        // cancelled is only under All
        assertTrue(TicketTab.entries.filter { it != TicketTab.ALL }.none { tab -> TicketLists.filter(list, tab, "").any { it.uuid == "d" } })
        // a status this version does not know still shows under All
        assertEquals(listOf("x"), uuids(TicketLists.filter(listOf(ticket("x", "INC1", "on_hold")), TicketTab.ALL, "")))
    }

    @Test fun searchMatchesNumberTitleAndIssueLabelCaseInsensitive() {
        assertEquals(listOf("a"), uuids(TicketLists.filter(list, TicketTab.ALL, "inc010700")))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), uuids(TicketLists.filter(list, TicketTab.ALL, "INC010")))
        assertEquals(listOf("e"), uuids(TicketLists.filter(list, TicketTab.ALL, "NICCHE")))
        assertEquals(listOf("b", "d", "e"), uuids(TicketLists.filter(list, TicketTab.ALL, "final sub")))
        assertEquals(listOf("f"), uuids(TicketLists.filter(list, TicketTab.ALL, "outlet related")))
        assertEquals(listOf("c"), uuids(TicketLists.filter(list, TicketTab.ALL, "রিপোর্টিং"))) // the Bangla issue label
        assertEquals(listOf("a"), uuids(TicketLists.filter(list, TicketTab.ALL, "০৭০০"))) // Bengali digits find the number
        assertEquals(emptyList<String>(), uuids(TicketLists.filter(list, TicketTab.ALL, "zzz")))
        assertEquals(listOf("c"), uuids(TicketLists.filter(list, TicketTab.RESOLVED, "test")))
        assertEquals(6, TicketLists.filter(list, TicketTab.ALL, "   ").size)
    }

    @Test fun unsentRowsMergeByUuidTheServerRowWinsNewestFirst() {
        val unsent = ticket("u", null, title = "Printer", type = TYPES[1], at = T0, delivery = Delivery.Pending)
        val acked = ticket("A", null, title = "local copy of a", at = T0, delivery = Delivery.Sent)
        val merged = TicketLists.merge(list, listOf(unsent, acked))
        assertEquals(listOf("u", "a", "b", "c", "d", "e", "f"), uuids(merged))
        assertEquals("INC010700", merged.first { it.uuid == "a" }.number)
        assertNull(merged.first().number)
        // an unsent ticket is New: it shows under New and is found by its title and issue label
        assertEquals(listOf("u", "f"), uuids(TicketLists.filter(merged, TicketTab.NEW, "")))
        assertEquals(listOf("u"), uuids(TicketLists.filter(merged, TicketTab.ALL, "printer")))
    }

    @Test fun recentIsTheTenNewest() {
        val many = (1..14).map { ticket("t$it", "INC$it", at = T0 - it * HOUR) }
        assertEquals((1..10).map { "t$it" }, uuids(TicketLists.recent(TicketLists.merge(many.shuffled(java.util.Random(7)), emptyList()))))
    }

    @Test fun theStateLoadsTheCopyThenTheServerAndKeepsTabAndQuery() = runTest {
        val server = FakeServer()
        val device = FakeDevice(server).apply { online = true }
        val d = deps(device)
        // three tickets on the server, one unsent on the phone
        device.ticket("s1", TicketRules.payload(TicketRules.newDraft("s1", TicketOrigin.QUICK, "login_otp"), TYPES[0]), emptyList(), TicketRules.local(TicketRules.newDraft("s1", TicketOrigin.QUICK, "login_otp"), TYPES[0], T0 - DAY))
        device.ticket("s2", TicketRules.payload(TicketRules.newDraft("s2", TicketOrigin.QUICK, "reporting"), TYPES[3]), emptyList(), TicketRules.local(TicketRules.newDraft("s2", TicketOrigin.QUICK, "reporting"), TYPES[3], T0 - 2 * DAY))
        device.sync()
        server.resolve("s2", "Checked.", T0 - HOUR)
        device.online = false
        device.ticket("u1", TicketRules.payload(TicketRules.newDraft("u1", TicketOrigin.QUICK, "outlet_related"), TYPES[1]), emptyList(), TicketRules.local(TicketRules.newDraft("u1", TicketOrigin.QUICK, "outlet_related"), TYPES[1], T0))

        val st = TicketListState(d, TicketTab.RESOLVED, "")
        st.load()
        // offline, never read: only the phone's own tickets, flagged offline
        assertTrue(st.ui.offline)
        assertFalse(st.ui.loading)
        assertEquals(listOf("u1", "s1", "s2"), uuids(st.ui.all))
        assertEquals(emptyList<String>(), uuids(st.ui.shown)) // the acked ones are New on the phone until read back
        assertEquals(TicketTab.RESOLVED, st.ui.tab)

        device.online = true
        st.load()
        assertFalse(st.ui.offline)
        assertEquals(listOf("s2"), uuids(st.ui.shown))
        assertEquals("INC100002", st.ui.shown.single().number)
        assertEquals(T0, st.ui.asOfMs)
        st.setTab(TicketTab.ALL)
        assertEquals(listOf("u1", "s1", "s2"), uuids(st.ui.shown))
        assertEquals(Delivery.Pending, st.ui.shown.first().delivery)
        st.setQuery("inc100001")
        assertEquals(listOf("s1"), uuids(st.ui.shown))

        // back offline, pulled to refresh (an open within the fresh window would not read at all): the copy stays, flagged
        device.online = false
        st.setQuery("")
        st.load(refresh = true)
        assertTrue(st.ui.offline)
        assertEquals(T0, st.ui.asOfMs)
        assertEquals(listOf("u1", "s1", "s2"), uuids(st.ui.shown))
    }

    @Test fun aRefusedTicketStaysVisibleWithItsReasonCode() = runTest {
        val server = FakeServer(types = TYPES.filter { it.code != "reporting" }) // the server no longer offers "reporting"
        val device = FakeDevice(server).apply { online = true }
        val dr = TicketRules.newDraft("r1", TicketOrigin.QUICK, "reporting")
        device.ticket("r1", TicketRules.payload(dr, TYPES[3]), emptyList(), TicketRules.local(dr, TYPES[3], T0))
        device.sync()
        val st = TicketListState(deps(device))
        st.load()
        assertEquals(Delivery.Refused("issue_type_unknown"), st.ui.all.single().delivery)
        val dash = TicketDashboardState(deps(device))
        dash.load()
        assertEquals(0L, dash.ui.counts.total) // a refused ticket is not a ticket
        assertEquals(listOf("r1"), dash.ui.recent.map { it.uuid })
    }
}

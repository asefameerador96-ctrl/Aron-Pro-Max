package com.aktcl.aron.feature.tickets

import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.rules.TicketCounts
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation, payloads, counts (s4.4, R-F-14), wire and bundle mapping, the mirror rows and the Dhaka 12-hour clock. */
class TicketModelTest {
    private val otp = TYPES[0]
    private val outlet = TYPES[1]
    private val u = "11111111-1111-4111-8111-111111111111"

    // ---- validation and payloads

    @Test fun quickNeedsOnlyTheIssueTypeAndTakesItsEnglishLabelAsTitle() {
        val d = TicketRules.newDraft(u, TicketOrigin.QUICK, outlet.code)
        assertEquals(emptyList<TicketFormError>(), TicketRules.validate(d, TYPES, 3))
        val p = TicketRules.payload(d.copy(title = "ignored", description = "  DHK-344-011, route 3  "), outlet)
        assertEquals("Outlet Related Issue", p.title)
        assertEquals("DHK-344-011, route 3", p.description)
        assertEquals("quick", p.source)
        assertEquals("outlet_related", p.issueTypeCode)
        assertNull(p.affectedUsername)
    }

    @Test fun quickDescriptionIsOptionalButBounded() {
        val d = TicketRules.newDraft(u, TicketOrigin.QUICK, outlet.code)
        assertEquals(listOf(TicketFormError.DESCRIPTION_LONG), TicketRules.validate(d.copy(description = "x".repeat(2001)), TYPES, 3))
        assertEquals(emptyList<TicketFormError>(), TicketRules.validate(d.copy(description = "x".repeat(2000)), TYPES, 3))
    }

    @Test fun createNeedsIssueTypeTitleAndDescription() {
        val d = TicketRules.newDraft(u, TicketOrigin.MANUAL)
        assertEquals(listOf(TicketFormError.ISSUE_TYPE, TicketFormError.TITLE_EMPTY, TicketFormError.DESCRIPTION_EMPTY), TicketRules.validate(d, TYPES, 3))
        assertEquals(
            listOf(TicketFormError.TITLE_EMPTY, TicketFormError.DESCRIPTION_EMPTY),
            TicketRules.validate(d.copy(issueTypeCode = otp.code, title = "   ", description = "\n"), TYPES, 3),
        )
        assertEquals(
            listOf(TicketFormError.TITLE_LONG, TicketFormError.DESCRIPTION_LONG),
            TicketRules.validate(d.copy(issueTypeCode = otp.code, title = "t".repeat(121), description = "d".repeat(2001)), TYPES, 3),
        )
        val ok = d.copy(issueTypeCode = otp.code, title = " OTP does not come ", description = " Since morning ")
        assertEquals(emptyList<TicketFormError>(), TicketRules.validate(ok, TYPES, 3))
        val p = TicketRules.payload(ok, otp)
        assertEquals("OTP does not come", p.title)
        assertEquals("Since morning", p.description)
        assertEquals("manual", p.source)
    }

    @Test fun anUnknownIssueTypeIsRefusedBeforeTheQueue() {
        val d = TicketRules.newDraft(u, TicketOrigin.QUICK, "no_such_type")
        assertEquals(listOf(TicketFormError.ISSUE_TYPE), TicketRules.validate(d, TYPES, 3))
    }

    @Test fun theAffectedUsernameIsSentOnlyForAccountTypesAndMustBeValid() {
        val d = TicketRules.newDraft(u, TicketOrigin.QUICK, otp.code)
        assertTrue(otp.asksAffectedUser)
        assertFalse(outlet.asksAffectedUser)
        val login = TYPES.first { it.code == "user_login" }
        assertTrue(login.asksAffectedUser)
        val dl = d.copy(issueTypeCode = login.code)
        assertNull(TicketRules.payload(dl, login).affectedUsername)
        assertEquals("sr1001", TicketRules.payload(dl.copy(affectedUsername = " sr১০০১ "), login).affectedUsername)
        assertEquals(listOf(TicketFormError.AFFECTED_USER), TicketRules.validate(dl.copy(affectedUsername = "ab"), TYPES, 3))
        assertEquals(listOf(TicketFormError.AFFECTED_USER), TicketRules.validate(dl.copy(affectedUsername = "sr 1001"), TYPES, 3))
        // a type that does not ask ignores whatever is typed
        val o = d.copy(issueTypeCode = outlet.code, affectedUsername = "x")
        assertEquals(emptyList<TicketFormError>(), TicketRules.validate(o, TYPES, 3))
        assertNull(TicketRules.payload(o, outlet).affectedUsername)
    }

    @Test fun photosAreBoundedByTheConfiguredLimit() {
        val d = TicketRules.newDraft(u, TicketOrigin.QUICK, outlet.code).copy(photoUuids = listOf("a", "b"))
        assertEquals(emptyList<TicketFormError>(), TicketRules.validate(d, TYPES, 2))
        assertEquals(listOf(TicketFormError.TOO_MANY_PHOTOS), TicketRules.validate(d, TYPES, 1))
        assertEquals(listOf(TicketFormError.TOO_MANY_PHOTOS), TicketRules.validate(d.copy(photoUuids = listOf("a", "b", "c", "d")), TYPES, 9))
    }

    @Test fun theLocalRowIsNewUnsentWithoutANumber() {
        val t = TicketRules.local(TicketRules.newDraft(u, TicketOrigin.QUICK, outlet.code).copy(photoUuids = listOf("p")), outlet, T0)
        assertNull(t.number)
        assertEquals("new", t.status)
        assertEquals(Delivery.Pending, t.delivery)
        assertEquals(T0, t.capturedAtMs)
        assertEquals("p1", t.priority)
        assertEquals(1, t.attachmentCount)
    }

    @Test fun commentsAre1To2000Characters() {
        assertEquals(CommentError.EMPTY, TicketRules.validateComment(CommentDraft(u, "  "), 3))
        assertEquals(CommentError.TOO_LONG, TicketRules.validateComment(CommentDraft(u, "c".repeat(2001)), 3))
        assertEquals(CommentError.TOO_MANY_PHOTOS, TicketRules.validateComment(CommentDraft(u, "ok", listOf("a", "b")), 1))
        assertNull(TicketRules.validateComment(CommentDraft(u, "c".repeat(2000)), 3))
        assertEquals("ok", TicketRules.commentPayload("t", CommentDraft(u, " ok ")).body)
        assertNull(TicketRules.closePayload("t").body)
        assertEquals("close_review", TicketRules.closePayload("t").kind)
    }

    @Test fun theCloseUuidIsOnePerTicketAndAValidV4() {
        val a = TicketRules.closeUuid(u)
        assertEquals(a, TicketRules.closeUuid(u.uppercase()))
        assertFalse(a == TicketRules.closeUuid("22222222-2222-4222-8222-222222222222"))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a))
    }

    // ---- counts (s4.4 fixtures through TicketMath, R-F-14)

    @Test fun theDashboardFixtureGives2PercentResolvedAnd0Open() {
        val list = (1..705).map { ticket("c$it", "INC$it", status = "closed") } + (1..18).map { ticket("r$it", "INR$it", status = "resolved") }
        val c = TicketLists.counts(TicketSnapshot(list, complete = true, summary = null, asOfMs = T0), TicketOutbox.EMPTY)
        assertEquals(723L, c.total); assertEquals(0L, c.new); assertEquals(0L, c.inProgress); assertEquals(18L, c.resolved)
        assertEquals("2%", c.resolvedPctText(UiLocale.EN))
        assertEquals("0%", c.openPctText(UiLocale.EN))
        assertEquals("২%", c.resolvedPctText(UiLocale.BN))
    }

    @Test fun noTicketsPrints0PercentNotADash() {
        val c = TicketLists.counts(null, TicketOutbox.EMPTY)
        assertEquals(0L, c.total)
        assertEquals("0%", c.resolvedPctText(UiLocale.EN))
        assertEquals("0%", c.openPctText(UiLocale.EN))
    }

    @Test fun unsentTicketsCountAsNewMergedByUuidAndRefusedOnesDoNotCount() {
        val copy = TicketSnapshot(listOf(ticket("a", "INC100001", "resolved"), ticket("b", "INC100002", "in_progress")), true, null, T0)
        val outbox = TicketOutbox(
            listOf(
                ticket("c", null, delivery = Delivery.Pending),
                ticket("B", null, delivery = Delivery.Sent), // acked, the copy already lists it (case differs): once
                ticket("d", null, delivery = Delivery.Refused("issue_type_unknown")),
            ),
        )
        val c = TicketLists.counts(copy, outbox)
        assertEquals(TicketCounts(total = 3, new = 1, inProgress = 1, resolved = 1, closed = 0, cancelled = 0), c)
        assertEquals("33%", c.resolvedPctText(UiLocale.EN))
        assertEquals("67%", c.openPctText(UiLocale.EN))
    }

    @Test fun aListCutAtThePageCapTakesTheServerCountsPlusTheUnlistedUnsent() {
        val summary = TicketCounts(total = 723, new = 0, inProgress = 0, resolved = 18, closed = 700, cancelled = 5)
        val cut = TicketSnapshot(listOf(ticket("a", "INC1", "resolved")), complete = false, summary = summary, asOfMs = T0)
        val outbox = TicketOutbox(listOf(ticket("a", null, delivery = Delivery.Sent), ticket("n", null, delivery = Delivery.Pending)))
        val c = TicketLists.counts(cut, outbox)
        assertEquals(724L, c.total)
        assertEquals(1L, c.new)
        assertEquals(18L, c.resolved)
        assertEquals("2%", c.resolvedPctText(UiLocale.EN))
    }

    // ---- the thread rules (D-TSO-MAN-20, 21)

    @Test fun closeOnlyFromResolvedAndCommentsOnlyWhileOpen() {
        val resolved = ticket("a", "INC100001", "resolved")
        assertTrue(TicketThread.canClose(resolved, null))
        assertFalse(TicketThread.canComment(resolved, null))
        for (s in listOf("new", "in_progress")) {
            assertFalse(TicketThread.canClose(ticket("a", "INC1", s), null))
            assertTrue(TicketThread.canComment(ticket("a", "INC1", s), null))
        }
        for (s in listOf("closed", "cancelled")) {
            assertFalse(TicketThread.canClose(ticket("a", "INC1", s), null))
            assertFalse(TicketThread.canComment(ticket("a", "INC1", s), null))
        }
        // an unsent ticket is never closed from the phone; it can take comments (it is New)
        assertFalse(TicketThread.canClose(ticket("u", null, "resolved", delivery = Delivery.Pending), null))
        assertTrue(TicketThread.canComment(ticket("u", null, delivery = Delivery.Pending), null))
        assertFalse(TicketThread.canComment(ticket("u", null, delivery = Delivery.Refused("issue_type_unknown")), null))
    }

    @Test fun aPendingCloseShowsClosedAndARefusedOneLeavesTheServerStatus() {
        val t = ticket("a", "INC100001", "resolved")
        val pending = LocalEvent("e", "a", EventKind.CLOSE_REVIEW, null, T0)
        assertEquals(TicketStatus.CLOSED, TicketThread.status(t, pending))
        assertFalse(TicketThread.canClose(t, pending))
        val refused = pending.copy(delivery = Delivery.Refused("ticket_state_invalid"))
        assertEquals(TicketStatus.RESOLVED, TicketThread.status(t, refused))
        assertFalse(TicketThread.canClose(t, refused))
    }

    @Test fun theThreadIsServerEventsThenTheUnreturnedLocalOnesOldestFirst() {
        val server = listOf(TicketEvent("s1", EventKind.RESOLUTION, "r", true, "Support", T0 + 2 * MIN), TicketEvent("c1", EventKind.COMMENT, "x", false, "Me", T0))
        val local = listOf(LocalEvent("c1", "a", EventKind.COMMENT, "x", T0), LocalEvent("c2", "a", EventKind.COMMENT, "y", T0 + MIN))
        assertEquals(listOf("c1", "c2", "s1"), TicketThread.events(server, local).map { it.uuid })
        assertFalse(TicketThread.events(server, local).first { it.uuid == "c1" }.mine)
        assertTrue(TicketThread.events(server, local).first { it.uuid == "c2" }.mine)
    }

    // ---- wire, bundle, mirror

    private val pageJson = """
        {"items":[
          {"ticket_uuid":"a","ticket_no":"INC100001","issue_type_code":"login_otp","issue_type_label_en":"Login OTP issue","issue_type_label_bn":null,
           "category_label_en":"Tickets","title":"Login OTP issue","description":"","source":"quick","priority":"p1","status":"resolved",
           "requester":{"user_id":7,"username":"tso1","full_name":"TSO One","role":"TSO"},"affected_username":null,
           "business_date":"2026-10-21","captured_at":"2026-10-21T11:34:00.000Z","created_at":"2026-10-21T11:35:00.000Z","updated_at":"2026-10-25T11:34:00.000Z",
           "first_response_at":null,"resolved_at":"2026-10-25T11:34:00.000Z","closed_at":null,"cancelled_at":null,"resolution_code":"resolved",
           "resolution_label_en":"Resolved","resolution_notes":"Checked.","cancel_reason":null,"version":3,"attachment_count":1},
          {"ticket_uuid":"broken"}
        ],"next_cursor":"c2"}
    """.trimIndent()

    @Test fun aPageMapsToTheModelAndDropsABadRowOnly() {
        val (items, cursor) = TicketWire.page(pageJson)!!
        assertEquals("c2", cursor)
        assertEquals(1, items.size)
        val t = items.single()
        assertEquals("INC100001", t.number)
        assertEquals("resolved", t.status)
        assertEquals(1_792_582_440_000L, t.capturedAtMs) // captured_at, not created_at
        assertEquals(3, t.version)
        assertEquals(Delivery.Sent, t.delivery)
        assertNull(TicketWire.page("not json"))
    }

    @Test fun theSummaryHasNoPercentagesAndMapsToTheSharedCounts() {
        val (c, asOf) = TicketWire.summary("""{"as_of":"2026-10-25T11:34:00Z","total":723,"new":0,"in_progress":0,"resolved":18,"closed":700,"cancelled":5,"open":0}""")!!
        assertEquals(TicketCounts(723, 0, 0, 18, 700, 5), c)
        assertEquals(T0, asOf)
    }

    @Test fun aDetailKeepsTheThreadInTimeOrder() {
        val first = kotlinx.serialization.json.Json.parseToJsonElement(pageJson).let { (it as kotlinx.serialization.json.JsonObject)["items"] as kotlinx.serialization.json.JsonArray }[0]
        val body = """{"ticket":$first,
          "events":[
            {"event_uuid":"e2","kind":"resolution","body":"[TicketID: INC100001] - Resolved:\nResolution Code: Resolved\nResolution Notes:\n\nChecked.","actor_is_support":true,"actor_display":"Support","from_status":"new","to_status":"resolved","resolution_code":"resolved","created_at":"2026-10-25T11:34:00.000Z","attachments":[]},
            {"event_uuid":"e1","kind":"created","body":null,"actor_is_support":false,"actor_display":"TSO One","from_status":null,"to_status":"new","resolution_code":null,"created_at":"2026-10-21T11:35:00.000Z","attachments":[]}
          ],"attachments":["m1"]}"""
        val d = TicketWire.detail(body)!!
        assertEquals(listOf("e1", "e2"), d.events.map { it.uuid })
        assertTrue(d.events[1].actorIsSupport)
        assertEquals(listOf("m1"), d.attachments)
    }

    @Test fun issueTypesComeFromTheBundleInSortOrder() {
        val section = """[
          {"list_key":"feedback_category","items":[{"code":"suggestion","label_en":"Suggestion","label_bn":null,"sort":1,"attrs":{}}]},
          {"list_key":"ticket_issue_type","items":[
            {"code":"web_entry","label_en":"Web Entry","label_bn":null,"sort":31,"attrs":{"priority":"p1","category":"tickets","urgency":"high","icon":6,"hint_en":"Problems with Web Entry in the portal."}},
            {"code":"app_crash_order_submit","label_en":"App crashes on order submit","label_bn":"অ্যাপ ক্র্যাশ","sort":1,"attrs":{"priority":"p2","icon":0}},
            {"code":"Bad Code","label_en":"x","sort":2,"attrs":{}},
            {"code":"no_label","label_en":"","sort":3,"attrs":{}},
            {"code":"no_icon","label_en":"No icon","sort":12,"attrs":{"icon":99}}
          ]}]"""
        val types = IssueTypes.parse(section)
        assertEquals(listOf("app_crash_order_submit", "no_icon", "web_entry"), types.map { it.code })
        assertEquals(TicketPriority.P2, types[0].priority)
        assertEquals("অ্যাপ ক্র্যাশ", types[0].label(AppLanguage.BN))
        assertEquals("Web Entry", types[2].label(AppLanguage.BN)) // label_bn null: English
        assertEquals(6, types[2].icon)
        assertEquals(3, types[1].icon) // (12 - 1) mod 8
        assertEquals("high", types[2].urgency)
        assertEquals(emptyList<IssueType>(), IssueTypes.parse(null))
        assertEquals(emptyList<IssueType>(), IssueTypes.parse("[]"))
        assertEquals(emptyList<IssueType>(), IssueTypes.parse("{oops"))
    }

    @Test fun mirrorRowsRoundTrip() {
        val t = TicketRules.local(TicketRules.newDraft(u, TicketOrigin.MANUAL, otp.code).copy(title = "OTP", description = "since 9"), otp, T0)
        assertEquals(t, TicketMirror.ticketFromJson(TicketMirror.ticketToJson(t)))
        assertEquals(Delivery.Refused("x"), TicketMirror.ticketFromJson(TicketMirror.ticketToJson(t), Delivery.Refused("x"))!!.delivery)
        val e = LocalEvent("e", u, EventKind.COMMENT, "hello", T0, 2)
        assertEquals(e, TicketMirror.eventFromJson(TicketMirror.eventToJson(e)))
        val close = LocalEvent("c", u, EventKind.CLOSE_REVIEW, null, T0)
        assertEquals(close, TicketMirror.eventFromJson(TicketMirror.eventToJson(close)))
        assertNull(TicketMirror.ticketFromJson("{}"))
    }

    // ---- times: Asia/Dhaka, 12-hour clock (screenshot 14 "5:34 PM")

    @Test fun timesAreDhaka12Hour() {
        assertEquals("5:34 PM", TicketTime.text(T0, T0, AppLanguage.EN, "AM", "PM"))
        assertEquals("৫:৩৪ অপরাহ্ণ", TicketTime.text(T0, T0, AppLanguage.BN, "পূর্বাহ্ণ", "অপরাহ্ণ"))
        // 18:00 UTC is 00:00 in Dhaka the next day: midnight is 12:00 AM; 06:00 UTC is noon
        val midnight = T0 + 6 * HOUR + 26 * MIN
        assertEquals("12:00 AM", TicketTime.text(midnight, midnight, AppLanguage.EN, "AM", "PM"))
        val noon = T0 - 5 * HOUR - 34 * MIN
        assertEquals("12:00 PM", TicketTime.text(noon, noon, AppLanguage.EN, "AM", "PM"))
        // another day carries its Dhaka date
        assertEquals("2026-10-21 5:34 PM", TicketTime.text(T0 - 4 * DAY, T0, AppLanguage.EN, "AM", "PM"))
    }

    @Test fun agesFollowDTsoMan25() {
        assertEquals("4d ago", com.aktcl.aron.rules.TicketMath.ageText(T0, T0 - 4 * DAY, UiLocale.EN))
        assertEquals("23h ago", com.aktcl.aron.rules.TicketMath.ageText(T0, T0 - 23 * HOUR - 59 * MIN, UiLocale.EN))
        assertEquals("just now", com.aktcl.aron.rules.TicketMath.ageText(T0, T0 - 59_000, UiLocale.EN))
    }
}

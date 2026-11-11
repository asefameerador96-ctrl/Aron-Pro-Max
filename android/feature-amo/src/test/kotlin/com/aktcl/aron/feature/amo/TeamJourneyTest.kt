package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamJourneyTest {
    @Test fun durationsInBanglaAndEnglish() {
        assertEquals("৭ মিনিট ৪৬ সেকেন্ড", TeamJourneys.duration(466, "মিনিট", "সেকেন্ড", UiLocale.BN))
        assertEquals("7 min 46 s", TeamJourneys.duration(466, "min", "s", UiLocale.EN))
        assertEquals("৪৫ সেকেন্ড", TeamJourneys.duration(45, "মিনিট", "সেকেন্ড", UiLocale.BN)); assertEquals("0 s", TeamJourneys.duration(-3, "min", "s", UiLocale.EN))
        assertEquals("১২ মিনিট ০ সেকেন্ড", TeamJourneys.duration(720, "মিনিট", "সেকেন্ড", UiLocale.BN))
    }

    @Test fun metresTwoDecimalsHalfUp() {
        assertEquals("২৭৭.৩২", TeamJourneys.metresText(277.32, UiLocale.BN)); assertEquals("391.54", TeamJourneys.metresText(277.32 + 114.22, UiLocale.EN))
        assertEquals("0.13", TeamJourneys.metresText(0.125, UiLocale.EN)); assertEquals("5.00", TeamJourneys.metresText(5.0, UiLocale.EN))
    }

    @Test fun unknownStatusIsNotASaleAndNonFiniteDistanceDoesNotCrash() {
        val j = TeamJourneys.journey("""{"calls":[{"visit_uuid":"a","outlet":{"id":1,"name":"x","code":"1"},"status":"void","opened_at":"2026-11-06T03:43:04Z"}]}""")!!
        assertEquals(CallStatus.NO_SALE, j.calls[0].status); assertEquals("0.00", TeamJourneys.metresText(Double.NaN, UiLocale.EN)); assertEquals("0.00", TeamJourneys.metresText(Double.POSITIVE_INFINITY, UiLocale.EN))
    }

    @Test fun clockIs12HourDhakaLatinDigits() {
        assertEquals("9:43:04 AM", TeamJourneys.clock12("2026-11-06T03:43:04Z")); assertEquals("10:06:15 PM", TeamJourneys.clock12("2026-11-06T16:06:15Z"))
        assertEquals("garbage", TeamJourneys.clock12("garbage"))
    }

    private val ans = """{"user":{"user_id":7,"full_name":"Rahim"},"route":{"route_id":3,"name":"Gulshan-1"},"business_date":"2026-11-06","distance_m":391.54,"calls":[
      {"visit_uuid":"a","outlet":{"id":1,"name":"Unite Tea Store","code":"2672261","phone_masked":"017*****123","cluster":"Fojle Rabbi Park"},"status":"new_sale","opened_at":"2026-11-06T03:43:04Z","closed_at":"2026-11-06T03:48:47Z","duration_s":343,"distance_from_prev_m":null,"distance_so_far_m":0.0,"gap_to_next_s":536},
      {"visit_uuid":"b","outlet":{"id":2,"name":"Rana","code":"2645667","cluster":null},"status":"edited_sale","opened_at":"2026-11-06T03:57:43Z","closed_at":null,"duration_s":null,"distance_from_prev_m":277.32,"distance_so_far_m":277.32,"gap_to_next_s":null},
      {"visit_uuid":"c","outlet":{"id":3,"name":"K","code":"1","phone":"01711000000"},"status":"no_sale","opened_at":"2026-11-06T04:10:00Z"}]}"""

    @Test fun journeyParsesStatusesAndOutletLine() {
        val j = TeamJourneys.journey(ans)!!
        assertEquals(listOf(CallStatus.NEW_SALE, CallStatus.EDITED_SALE, CallStatus.NO_SALE), j.calls.map { it.status })
        assertEquals("Unite Tea Store (2672261-017*****123-Fojle Rabbi Park)", TeamJourneys.outletLine(j.calls[0].outlet))
        assertEquals("Rana (2645667)", TeamJourneys.outletLine(j.calls[1].outlet)); assertEquals("K (1-01711000000)", TeamJourneys.outletLine(j.calls[2].outlet))
        assertEquals("9:43:04 AM - 9:48:47 AM", TeamJourneys.range(j.calls[0])); assertEquals("9:57:43 AM", TeamJourneys.range(j.calls[1]))
        assertEquals(null, j.calls[0].distanceFromPrevMetres); assertEquals(277.32, j.calls[1].distanceFromPrevMetres!!, 0.0); assertEquals(391.54, j.runningMetres, 0.0)
        assertTrue(TeamJourneys.journey("{") == null)
    }

    @Test fun membersVacantAndPhones() {
        val m = TeamJourneys.members("""{"items":[
          {"user_id":7,"full_name":"A","route_id":1,"route_name":"Tal Tola","route_days":[7,2,4],"phone":"01611826244","phone_masked":"016*****244","total_outlets":91,"vacant":false},
          {"user_id":null,"full_name":null,"route_id":2,"route_name":"Baridhara","route_days":[1,2,3,4,5,6],"total_outlets":58,"vacant":true},{"bad":1}]}""")
        assertEquals(2, m.size); assertTrue(m[1].vacant); assertEquals("01611-826244", TeamJourneys.phoneText(m[0].phone)); assertEquals("***********", TeamJourneys.phoneText(m[1].phone))
        assertEquals("016*****244", TeamJourneys.phoneText(m[0].phoneMasked))
        val dn = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
        assertEquals("Tal Tola (Sun, Tue, Thu)", TeamJourneys.routeLabel("Tal Tola", m[0].routeDays, "Daily") { dn.getValue(it) })
        assertEquals("Baridhara Daily", TeamJourneys.routeLabel("Baridhara", m[1].routeDays, "Daily") { dn.getValue(it) })
        assertEquals("Kuptola (Sat, Mon, Wed)", TeamJourneys.routeLabel("Kuptola", listOf(1, 3, 6), "Daily") { dn.getValue(it) })
    }
}

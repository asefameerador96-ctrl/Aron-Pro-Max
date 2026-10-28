package com.aktcl.aron.feature.tso

import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Qty
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every s4.2 fixture of contract/fixtures/kpi-format-corpus.json (the independent Python oracle of tools/ci/kpi-format-corpus.py)
 * through the TSO Dashboard's own path: a national answer is built with the fixture's integers, parsed by [TsoDashboards] and printed
 * the way the card prints it, in English and Bangla. A new s4.2 fixture without a check here fails [everyS42FixtureIsChecked], so
 * the corpus cannot grow past the phone.
 */
class TsoDashboardCardsTest {
    private val corpusFile = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "contract/fixtures/kpi-format-corpus.json") }.first { it.isFile }
    private val corpus = Json.parseToJsonElement(corpusFile.readText()).jsonObject
    private val locales = mapOf("en" to UiLocale.EN, "bn" to UiLocale.BN)

    private fun section(name: String) = corpus.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.note() = this["note"]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.long(k: String): Long? = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonObject.text(vararg path: String): String {
        var o: JsonObject = this
        path.dropLast(1).forEach { o = o.getValue(it).jsonObject }
        return o.getValue(path.last()).jsonPrimitive.content
    }
    private fun lit(v: Long?) = v?.toString() ?: "null"
    private val asOf = "\"as_of\":\"2026-10-29T08:30:00Z\",\"date\":\"2026-10-29\",\"till_time\":true,\"last_updated_at\":null"

    private val checked = mutableSetOf<String>()

    private fun salesFixtures() {
        for (f in section("sales_card")) {
            val (a, t, w) = Triple(f.long("a"), f.long("t"), f.long("w")!!)
            val json = """{$asOf,"working_days":$w,"cards":[{"category_code":"cigarette","base_unit":"stick","total_sales":${lit(a)},"daily_target":null,
                "sales_so_far":${lit(a)},"remaining":null,"achievement_pct_bp":null,"target_routes":5,"monthly_target_qty":${lit(t)}}]}"""
            val card = TsoDashboards.sales(json)!!
            val row = card.rows.single()
            assertEquals(f.note(), f.long("ring_tenths"), TsoDashboards.ringTenths(row, card.workingDays))
            locales.forEach { (k, loc) ->
                assertEquals("${f.note()} total sales $k", f.text("total_sales", k), TsoDashboards.totalSales(row, loc))
                assertEquals("${f.note()} total target $k", f.text("total_target", k), TsoDashboards.totalTarget(row, card.workingDays, loc))
                assertEquals("${f.note()} ring $k", f.text("ring", k), TsoDashboards.ring(row, card.workingDays, loc))
            }
            checked += f.note()
        }
    }

    private fun percentFixtures() {
        for (f in section("percent").filter { it.note().startsWith("s4.2") }) {
            val n = f.long("n")!!
            val d = f.long("d")!!
            val note = f.note()
            when {
                note.startsWith("s4.2 Geo Fencing") -> {
                    val g = TsoDashboards.geo("""{$asOf,"total_calls":$d,"geo_validated":$n,"geo_pct_bp":null}""")!!
                    assertEquals(note, f.long("tenths"), TsoDashboards.geoTenths(g))
                    locales.forEach { (k, loc) -> assertEquals("$note $k", f.text("text", "tenths", k), Percent.text(TsoDashboards.geoTenths(g), 1, loc)) }
                }
                note.startsWith("s4.2 By Channel STD") -> {
                    val c = TsoDashboards.channels("""{$asOf,"channels":[{"channel":"Astha","value":$n,"share_pct_bp":null}],"total":$d,"unit":"stick"}""")!!
                    assertEquals(note, f.long("bp"), TsoDashboards.channelBp(c.channels.single(), c.total))
                    locales.forEach { (k, loc) -> assertEquals("$note $k", f.text("text", "bp", k), Percent.text(TsoDashboards.channelBp(c.channels.single(), c.total), 2, loc)) }
                }
                note.startsWith("s4.2 CPR") -> {
                    val c = TsoDashboards.cpr("""{$asOf,"target_outlets":$d,"successful_calls":$n,"cpr_pct_bp":null}""")!!
                    assertEquals(note, f.long("tenths"), TsoDashboards.cprTenths(c))
                    locales.forEach { (k, loc) -> assertEquals("$note $k", f.text("text", "tenths", k), Percent.text(TsoDashboards.cprTenths(c), 1, loc)) }
                }
                note.startsWith("s4.2 Brand CPR") -> {
                    // the default denominator: successful calls (D-TSO-MAN-06), 119 target outlets as screenshot 7
                    val b = TsoDashboards.brandCpr(
                        """{$asOf,"target_outlets":119,"successful_calls":$d,"denominator":"successful_calls","brands":[
                        {"brand_code":"MX","brand_name":"Maxim","outlets_bought":$n,"cpr_bp":null,"cpr_of_successful_bp":null,"cpr_of_target_bp":null}]}""",
                    )!!
                    assertEquals(note, f.long("whole"), TsoDashboards.brandWhole(b.brands.single(), b))
                    locales.forEach { (k, loc) -> assertEquals("$note $k", f.text("text", "whole", k), Percent.text(TsoDashboards.brandWhole(b.brands.single(), b), 0, loc)) }
                }
                note.startsWith("s4.2 Login Status") -> {
                    val l = TsoDashboards.loginSubmit("""{$asOf,"target_routes":$d,"successful_login":$n,"login_pct_bp":null,"submitted":0,"submit_pct_bp":null}""")!!
                    assertEquals(note, f.long("whole"), TsoDashboards.loginWhole(l))
                    locales.forEach { (k, loc) -> assertEquals("$note $k", f.text("text", "whole", k), Percent.text(TsoDashboards.loginWhole(l), 0, loc)) }
                }
                note.startsWith("s4.2 Bikroy Joma") -> {
                    // over TARGET routes (D-TSO-MAN-07), never over the logins
                    val l = TsoDashboards.loginSubmit("""{$asOf,"target_routes":$d,"successful_login":2,"login_pct_bp":null,"submitted":$n,"submit_pct_bp":null,"submit_of_target_pct_bp":null}""")!!
                    assertEquals(note, f.long("whole"), TsoDashboards.bikroyWhole(l))
                    locales.forEach { (k, loc) -> assertEquals("$note $k", f.text("text", "whole", k), Percent.text(TsoDashboards.bikroyWhole(l), 0, loc)) }
                }
                note.startsWith("s4.2 Segment") -> {
                    val s = TsoDashboards.segments(
                        """{$asOf,"total_value_mtk":$d,"families":[{"base_unit":"stick","total_qty_base":10}],"segments":[
                        {"segment_code":"S1","segment_name":"Bidi","category_code":"bidi","base_unit":"stick","value_mtk":$n,"value_share_bp":null,"qty_base":5,"volume_share_bp":null}]}""",
                    )!!
                    assertEquals(note, f.long("bp"), TsoDashboards.valueBp(s.segments.single(), s))
                    assertEquals(note, f.long("whole"), TsoDashboards.valueWhole(s.segments.single(), s))
                }
                else -> throw AssertionError("an s4.2 percent fixture the dashboard does not check: $note")
            }
            checked += note
        }
    }

    private fun qtyFixtures() {
        for (f in section("qty").filter { it.note().startsWith("s4.2") }) {
            val n = f.long("n")
            val d = f.long("d")!!
            // Total Target prints Qty.text(T, W): the card's own call with T = n and W = d
            val row = SalesRow("cigarette", "stick", null, n, null)
            locales.forEach { (k, loc) -> assertEquals("${f.note()} $k", f.text("text", k), TsoDashboards.totalTarget(row, d, loc)) }
            checked += f.note()
        }
    }

    private fun workingDayFixtures() {
        // W is the server's (WorkingDays at the scope's node, B2); the phone prints the daily target with the W it is sent
        for (f in section("working_days").filter { it.note().startsWith("s4.2") }) {
            val w = f.long("month_days")!!
            val card = TsoDashboards.sales("""{$asOf,"working_days":$w,"cards":[{"category_code":"cigarette","base_unit":"stick","total_sales":40,"monthly_target_qty":42000}]}""")!!
            assertEquals(f.note(), w, card.workingDays)
            assertEquals(f.note(), Qty.text(42_000L, w, UiLocale.EN), TsoDashboards.totalTarget(card.rows.single(), card.workingDays, UiLocale.EN))
            checked += f.note()
        }
    }

    @Test fun everyS42FixtureIsChecked() {
        salesFixtures(); percentFixtures(); qtyFixtures(); workingDayFixtures()
        val all = corpus.values.filterIsInstance<kotlinx.serialization.json.JsonArray>().flatMap { it.map { e -> e.jsonObject.note() } }.filter { it.startsWith("s4.2") }.toSet()
        assertEquals("every s4.2 fixture of the corpus goes through the dashboard path", all, all intersect checked)
        assertTrue(all.size >= 26)
    }

    @Test fun screenshot6Figures() {
        val json = """{$asOf,"working_days":25,"cards":[
            {"category_code":"match","base_unit":"dozen","total_sales":0,"monthly_target_qty":175,"achievement_pct_bp":0},
            {"category_code":"cigarette","base_unit":"stick","total_sales":40,"monthly_target_qty":42000,"achievement_pct_bp":238},
            {"category_code":"lighter","base_unit":"piece","total_sales":0,"monthly_target_qty":100,"achievement_pct_bp":0},
            {"category_code":"bidi","base_unit":"stick","total_sales":2991,"monthly_target_qty":26000,"achievement_pct_bp":28760}]}"""
        val c = TsoDashboards.sales(json)!!
        // the screenshot's order, whatever the server's
        assertEquals(listOf("cigarette", "bidi", "lighter", "match"), c.rows.map { it.category })
        assertEquals(listOf("40.0", "3.0 K", "0.0", "0.0"), c.rows.map { TsoDashboards.totalSales(it, UiLocale.EN) })
        assertEquals(listOf("1.7 K", "1.0 K", "4.0", "7.0"), c.rows.map { TsoDashboards.totalTarget(it, 25, UiLocale.EN) })
        assertEquals(listOf("2.4%", "287.6%", "0.0%", "0.0%"), c.rows.map { TsoDashboards.ring(it, 25, UiLocale.EN) })
        assertEquals("৪০.০", TsoDashboards.totalSales(c.rows[0], UiLocale.BN))
    }

    @Test fun theRingNeverReadsTheServersBasisPointsAndDecodesThemAsLong() {
        // F1 ask 15 / R-F-16: achievement_pct_bp is int64; a value past Int decodes, and the ring is still computed from A, T and W
        val c = TsoDashboards.sales("""{$asOf,"working_days":25,"cards":[{"category_code":"bidi","base_unit":"stick","total_sales":2991,"monthly_target_qty":26000,"achievement_pct_bp":3000000000}]}""")!!
        assertEquals(3_000_000_000L, c.rows.single().achievementPctBp)
        assertEquals(2876L, TsoDashboards.ringTenths(c.rows.single(), 25))
    }

    @Test fun workingDaysZeroPrintsDashes() {
        // R-F-13
        val c = TsoDashboards.sales("""{$asOf,"working_days":0,"cards":[{"category_code":"cigarette","base_unit":"stick","total_sales":40,"monthly_target_qty":42000}]}""")!!
        assertEquals("—", TsoDashboards.totalTarget(c.rows.single(), 0, UiLocale.EN))
        assertEquals("—", TsoDashboards.ring(c.rows.single(), 0, UiLocale.BN))
        assertEquals("40.0", TsoDashboards.totalSales(c.rows.single(), UiLocale.EN))
    }

    @Test fun brandCprFollowsTheConfiguredDenominator() {
        val json = { den: String -> """{$asOf,"target_outlets":119,"successful_calls":3,"denominator":"$den","brands":[
            {"brand_code":"MX","brand_name":"Maxim","outlets_bought":2},{"brand_code":"BG","brand_name":"Bidi Gold","outlets_bought":1}]}""" }
        val byCalls = TsoDashboards.brandCpr(json("successful_calls"))!!
        assertEquals(listOf(67L, 33L), byCalls.brands.map { TsoDashboards.brandWhole(it, byCalls) })
        val byTarget = TsoDashboards.brandCpr(json("target_outlets"))!!
        assertEquals(listOf(2L, 1L), byTarget.brands.map { TsoDashboards.brandWhole(it, byTarget) })
    }

    @Test fun theBrandAxisIsScreenshot7() {
        // the top point is 2 of 3 = 6667 bp; six steps print 0, 11, 22, 33, 44, 56, 67
        assertEquals(listOf(0L, 11L, 22L, 33L, 44L, 56L, 67L), TsoDashboards.axisTicks(6667, 6))
    }

    @Test fun volumeStaysInsideItsUnitFamily() {
        val s = TsoDashboards.segments(
            """{$asOf,"total_value_mtk":1000,"families":[{"base_unit":"stick","total_qty_base":400},{"base_unit":"dozen","total_qty_base":10}],"segments":[
            {"segment_code":"S1","segment_name":"Bidi","category_code":"bidi","base_unit":"stick","value_mtk":750,"qty_base":100},
            {"segment_code":"S2","segment_name":"Match","category_code":"match","base_unit":"dozen","value_mtk":250,"qty_base":10}]}""",
        )!!
        assertEquals(listOf(7500L, 2500L), s.segments.map { TsoDashboards.valueBp(it, s) })
        // sticks over sticks (100 of 400) and dozens over dozens (10 of 10): never added across families
        assertEquals(listOf(2500L, 10000L), s.segments.map { TsoDashboards.volumeBp(it, s) })
    }

    @Test fun loginSubmitUsersKeepTheServerOrderAndShowVacantRoutes() {
        val u = TsoDashboards.users(
            """{$asOf,"tab":"not_logged_in","route_count":2,"truncated":false,"items":[
            {"route_id":2,"route_code":"R2","route_name":"Apsis Route 2","zone_id":9,"dep_name":"Banani - Test","user_id":null,"username":null,"display_name":null,"role":"SR"},
            {"route_id":2,"route_code":"R2","route_name":"Apsis Route 2","zone_id":9,"dep_name":"Banani - Test","user_id":7,"username":"amo6334","display_name":"AMO-6334","role":"AMO"}]}""",
        )!!
        assertEquals(listOf(null, "AMO-6334"), u.items.map { it.displayName })
        assertEquals("Banani - Test", u.items[0].depName)
        assertEquals(2L, u.routeCount)
    }

    @Test fun malformedAnswersAreNull() {
        assertNull(TsoDashboards.sales("["))
        assertNull(TsoDashboards.geo(null))
        assertNull(TsoDashboards.cpr("""{"target_outlets":1}"""))
    }

    @Test fun theRequestCarriesOnlyTheDate() {
        assertEquals("""{"date":"2026-10-29"}""", TsoDashboards.filterBody("2026-10-29"))
    }

    @Test fun asOfIsDhakaTime() {
        assertEquals("14:30", TsoDashboards.asOfClock("2026-10-29T08:30:00Z"))
        assertNull(TsoDashboards.asOfClock("not a time"))
    }

    @Test fun theStateNamesTheOldestStampAndKeptCopies() {
        val cpr = TsoDashboards.cpr("""{"as_of":"2026-10-29T08:30:00Z","target_outlets":119,"successful_calls":3}""")
        val geo = TsoDashboards.geo("""{"as_of":"2026-10-29T08:10:00Z","total_calls":4,"geo_validated":4}""")
        val s = TsoDashboardState(cpr = CardLoad(cpr), geo = CardLoad(geo, kept = true, failure = TsoFailure.OFFLINE))
        assertEquals("2026-10-29T08:10:00Z", s.asOf)
        assertTrue(s.anyKept)
    }
}

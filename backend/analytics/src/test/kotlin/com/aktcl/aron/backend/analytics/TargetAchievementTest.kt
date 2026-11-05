package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.contract.TargetAchievement
import com.aktcl.aron.contract.TargetAchievementRow
import com.aktcl.aron.rules.Band
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Targets
import com.aktcl.aron.rules.UiLocale
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Target Status (`GET /v1/dashboards/targets`, contract 1.10.0; build contract s4.3, screenshots 39-42, D-TSO-MAN-05/39/40, lead
 * rulings R-F-01, R-F-15, R-F-18, R-F-24) over target_status.sql: every s4.3 fixture through the API's SQL on a migrated
 * PostgreSQL. Expectations are the build contract's numbers (and, where it has none, hand computed with an independent
 * Python-integer oracle: round half up of T x e / n per item, basis points (20000 a + t) div 2t). D = 2026-10-25 (the clock is
 * 12:00 Dhaka that day): calendar basis e = 25, n = 31.
 * Callers: 21 TSO (territory Apsis: zones Banani and Gulshan), 22 TSO holding house "House A" (the same two zones), 23 AMO
 * (zone Banani), 24 ANALYST (national), 25 TSO holding only route R1 (route-keyed), 26 SR, 27 SUPPORT.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetAchievementTest {
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-25T06:00:00Z")
    private val clock = AronClock { now }
    private val day = LocalDate.parse("2026-10-25")
    private val ids = mutableMapOf<String, Long>()
    private val reaches = mutableMapOf<Long, Reach>()

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("target_status.sql")!!.bufferedReader().readText()) } }
        fresh.db.jdbi.useHandle<Exception> { h ->
            Aggregator.refreshDimensions(h)
            for (t in listOf("zone", "territory", "division", "house", "route")) {
                h.createQuery("SELECT code, id FROM app.$t").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().forEach { (code, id) -> ids[code] = id }
            }
        }
        val z1 = ids.getValue("Z1"); val z2 = ids.getValue("Z2")
        reaches[21] = Reach(21, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("territory", ids.getValue("T1"), "T1", "Apsis")))
        reaches[22] = Reach(22, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("house", ids.getValue("H1"), "H1", "House A")))
        reaches[23] = Reach(23, Role.AMO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
        reaches[24] = Reach(24, Role.ANALYST, day, true, emptySet(), emptySet(), false, emptyList())
        reaches[25] = Reach(25, Role.TSO, day, false, emptySet(), setOf(ids.getValue("R1")), false, listOf(ReachNode("route", ids.getValue("R1"), "R1", "Route R1")))
        reaches[26] = Reach(26, Role.SR, day, false, emptySet(), setOf(ids.getValue("R1")), true, emptyList())
        reaches[27] = Reach(27, Role.SUPPORT, day, true, emptySet(), emptySet(), false, emptyList())
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }

    private fun config(overrides: Map<String, String> = emptyMap()) = object : ServerConfig {
        private val d = RegistryDefaults()
        override fun value(key: String): JsonElement = overrides[key]?.let(::JsonPrimitive) ?: if (key == "cfg.api.rl.user_per_min") JsonPrimitive(5000) else d.value(key)
        override fun configVersion() = 1L
    }

    private val working = mapOf(TargetAchievementService.BASIS_TSO to "working_days")

    private fun svc(overrides: Map<String, String> = emptyMap()) = TargetAchievementService(fresh.db, config(overrides), clock)

    private fun ask(uid: Long, month: String = "2026-10", level: String? = null, node: Long? = null, product: String? = "category", children: String? = null,
                    cfg: Map<String, String> = emptyMap()): TargetAchievement {
        val r = reaches.getValue(uid)
        return svc(cfg).achievement(r.role, r, month, level, node, product, children)
    }

    private fun List<TargetAchievementRow>.of(name: String) = single { it.name == name }
    private fun pct(bp: Long?) = Percent.text(bp, 2, UiLocale.EN)

    private fun holiday(date: String, scope: String = "global", scopeId: Long = 0, kind: String = "holiday") =
        sql("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES ('$date', '$scope', $scopeId, '$kind', ${kind == "makeup_day"}, 'B2 test')")

    private fun clearHolidays() = sql("DELETE FROM app.calendar_holiday")

    // ---------------------------------------------------------------- screenshots 39 and 40: the cards

    @Test
    fun theCardsOfScreenshots39And40OnTheCalendarBasis() {
        val a = ask(21, children = "zone")
        assertEquals("territory", a.node.type); assertEquals(ids.getValue("T1"), a.node.id); assertEquals("Apsis", a.node.name)
        assertEquals("2026-10", a.month); assertEquals("2026-10-25", a.day); assertEquals("calendar_days", a.tilldateBasis)
        assertEquals(25, a.elapsedDays); assertEquals(31, a.periodDays)
        assertEquals("2026-10-25T05:30:00Z", a.lastUpdatedAt)
        val r = a.rows
        assertEquals(listOf("Cigarette", "Bidi", "Lighter", "Match"), r.map { it.name })
        assertEquals(listOf("cigarette", "bidi", "lighter", "match"), r.map { it.categoryCode })
        assertEquals(listOf("stick", "stick", "piece", "dozen"), r.map { it.unit })
        // s4.3: monthly T (card) Cigarette 40999 = the eight items; A 1030 (the September sale, the free quantity and the out-of-reach route never count).
        assertEquals(listOf(40999L, 11000L, 100L, 170L), r.map { it.targetQtyBase })
        assertEquals(listOf(1030L, 50L, 85L, 72L), r.map { it.achievedQtyBase })
        // Till-date card = sum of the item rows: 806 + 806 + 8065 + 8065 + 4032 + 4839 + 2419 + 4032 = 33064; 8871, 81, 137.
        assertEquals(listOf(33064L, 8871L, 81L, 137L), r.map { it.tilldateTargetQtyBase })
        // Card % (the app prints Targets.cardPct from these integers): monthly 3, 0, 85, 42; till date 3, 1, 100 (capped), 53.
        assertEquals(listOf(3L, 0L, 85L, 42L), r.map { Targets.cardPct(it.achievedQtyBase, it.targetQtyBase) })
        assertEquals(listOf(3L, 1L, 100L, 53L), r.map { Targets.cardPct(it.achievedQtyBase, it.tilldateTargetQtyBase!!) })
        assertEquals(listOf(Band.RED, Band.RED, Band.GREEN, Band.AMBER), r.map { Targets.band(Targets.cardPct(it.achievedQtyBase, it.tilldateTargetQtyBase!!)!!) })
        assertEquals(listOf(Band.RED, Band.RED, Band.GREEN, Band.AMBER), r.map { Targets.band(Targets.cardPct(it.achievedQtyBase, it.targetQtyBase)!!) })
        // The server's bp, rounded once from the integers (uncapped): Lighter till date 104.94 %.
        assertEquals(listOf(251L, 45L, 8500L, 4235L), r.map { it.achievementPctBp })
        assertEquals(listOf(312L, 56L, 10494L, 5255L), r.map { it.tilldateAchievementPctBp })
        assertEquals(listOf(39969L, 10950L, 15L, 98L), r.map { it.remainingQtyBase })
        assertEquals(listOf(32034L, 8821L, 0L, 65L), r.map { it.remainingTilldateQtyBase })
        // The AMO app's Decimal3 members carry the same integers; achievement_pct is the same bp with two decimals.
        val cig = r.of("Cigarette")
        assertEquals("40999.000", cig.targetQty); assertEquals("1030.000", cig.achievedQty); assertEquals("33064.000", cig.tilldateTargetQty); assertEquals("39969.000", cig.remainingQty)
        assertEquals(2.51, cig.achievementPct); assertNull(cig.memoTarget); assertNull(cig.memoAchieved)
        // One card per zone in the reach, by name; Banani holds every figure, so its card is the territory's (screenshot 40).
        val kids = a.children!!
        assertEquals(listOf("Banani - Test", "Gulshan - Test"), kids.map { it.node.name })
        assertEquals(listOf(ids.getValue("Z1"), ids.getValue("Z2")), kids.map { it.node.id }); assertTrue(kids.all { it.node.type == "zone" })
        assertEquals(r, kids[0].rows)
        assertEquals(listOf(25, 25), kids.map { it.elapsedDays }); assertEquals(listOf(31, 31), kids.map { it.periodDays })
        assertEquals(listOf("Cigarette", "Bidi", "Lighter", "Match"), kids[1].rows.map { it.name })   // four bars even with nothing in them
        assertTrue(kids[1].rows.all { it.targetQtyBase == 0L && it.achievedQtyBase == 0L && it.tilldateTargetQtyBase == 0L && it.achievementPctBp == null && it.tilldateAchievementPctBp == null })
        assertNull(ask(21).children, "children only with children=zone")
    }

    // ---------------------------------------------------------------- screenshots 41 and 42: the details

    @Test
    fun theDetailsOfScreenshots41And42AreVariantsInCatalogueOrder() {
        val a = ask(21, level = "territory", node = ids.getValue("T1"), product = "variant")
        val r = a.rows
        assertEquals(
            listOf("Maxim Regular", "Maxim Bolt", "Maxim Double Burst", "Marise Special Blend", "Black Diamond Double Filter", "Black Diamond Advanced",
                "Sunmoon American", "Sunmoon Lights", "Abul Bidi", "Flame Lighter", "Dhaka Match"),
            r.map { it.name },
        )
        assertTrue(r.all { it.productLevel == "variant" })
        assertEquals(listOf(1000L, 1000L, 10000L, 10000L, 5000L, 6000L, 2999L, 5000L, 11000L, 100L, 170L), r.map { it.targetQtyBase })
        assertEquals(listOf(350L, 330L, 20L, 200L, 30L, 30L, 40L, 30L, 50L, 85L, 72L), r.map { it.achievedQtyBase })
        // s4.3 till-date items: 1000 -> 806, 10000 -> 8065, 5000 -> 4032, 6000 -> 4839, 2999 -> 2419, 100 -> 81, 170 -> 137, 11000 -> 8871.
        assertEquals(listOf(806L, 806L, 8065L, 8065L, 4032L, 4839L, 2419L, 4032L, 8871L, 81L, 137L), r.map { it.tilldateTargetQtyBase })
        assertEquals(listOf(456L, 476L, 8045L, 7865L, 4002L, 4809L, 2379L, 4002L, 8821L, 0L, 65L), r.map { it.remainingTilldateQtyBase })
        assertEquals(listOf(650L, 670L, 9980L, 9800L, 4970L, 5970L, 2959L, 4970L, 10950L, 15L, 98L), r.map { it.remainingQtyBase })
        // Details % (2 dp, uncapped): 350/1000 -> 35.00 %; 20/10000 -> 0.20 %; 40/2999 -> 1.33 %; 350/806 -> 43.42 %; 20/8065 -> 0.25 %; 85/81 -> 104.94 %.
        assertEquals("35.00%", pct(r.of("Maxim Regular").achievementPctBp)); assertEquals("0.20%", pct(r.of("Maxim Double Burst").achievementPctBp))
        assertEquals("1.33%", pct(r.of("Sunmoon American").achievementPctBp)); assertEquals("43.42%", pct(r.of("Maxim Regular").tilldateAchievementPctBp))
        assertEquals("0.25%", pct(r.of("Maxim Double Burst").tilldateAchievementPctBp)); assertEquals("104.94%", pct(r.of("Flame Lighter").tilldateAchievementPctBp))
        // The rest of screenshot 42: 40.94 %, 2.48 %, 0.74 %, 0.62 %, 1.65 %.
        assertEquals(listOf("43.42%", "40.94%", "0.25%", "2.48%", "0.74%", "0.62%", "1.65%"), r.take(7).map { pct(it.tilldateAchievementPctBp) })
        // R-F-01: an item is rounded once at the node. Maxim Regular sits on two routes (600 + 400): 806, never 484 + 323 = 807;
        // Marise sums its 20s and 10s (7000 + 3000) first: 8065, never 5645 + 2419 = 8064.
        assertEquals(806L, r.of("Maxim Regular").tilldateTargetQtyBase); assertEquals(8065L, r.of("Marise Special Blend").tilldateTargetQtyBase)
        // Each card figure is the sum of its item rows.
        val cards = ask(21, level = "territory", node = ids.getValue("T1")).rows
        for (c in cards) {
            val items = r.filter { it.categoryCode == c.categoryCode }
            assertEquals(c.tilldateTargetQtyBase, items.sumOf { it.tilldateTargetQtyBase!! }, c.name)
            assertEquals(c.targetQtyBase, items.sumOf { it.targetQtyBase }, c.name); assertEquals(c.achievedQtyBase, items.sumOf { it.achievedQtyBase }, c.name)
        }
        // Brand rows sum their items (Maxim 806 + 806 + 8065); a SKU row is its own item (Marise 20s 5645, 10s 2419).
        val brands = ask(21, product = "brand").rows
        assertEquals(listOf("Maxim", "Marise", "Black Diamond", "Sunmoon", "Abul", "Flame", "Dhaka Match"), brands.map { it.name })
        assertEquals(9677L, brands.of("Maxim").tilldateTargetQtyBase)
        val skus = ask(21, product = "sku").rows
        assertEquals(5645L, skus.of("Marise Special Blend 20s").tilldateTargetQtyBase); assertEquals(2419L, skus.of("Marise Special Blend 10s").tilldateTargetQtyBase)
        assertEquals(listOf(7000L, 3000L), listOf(skus.of("Marise Special Blend 20s").targetQtyBase, skus.of("Marise Special Blend 10s").targetQtyBase))
    }

    // ---------------------------------------------------------------- basis switch (D-TSO-MAN-05, R-F-01)

    @Test
    fun theTillDateBasisFollowsTheConfigOfTheCallersRole() {
        clearHolidays()
        try {
            holiday("2026-10-21")
            // Working basis with the holiday of the 21st: e = 20, n = 25; 40999 -> 32799 (every item rounds exactly).
            val w = ask(21, cfg = working)
            assertEquals("working_days", w.tilldateBasis); assertEquals(20, w.elapsedDays); assertEquals(25, w.periodDays)
            assertEquals(32799L, w.rows.of("Cigarette").tilldateTargetQtyBase)
            // Without the holiday 21/26: the card is the sum of the item rows 808+808+8077+8077+4038+4846+2422+4038 = 33114, never 33115.
            clearHolidays()
            val w2 = ask(21, product = "variant", cfg = working)
            assertEquals(21, w2.elapsedDays); assertEquals(26, w2.periodDays)
            assertEquals(listOf(808L, 808L, 8077L, 8077L, 4038L, 4846L, 2422L, 4038L), w2.rows.filter { it.categoryCode == "cigarette" }.map { it.tilldateTargetQtyBase })
            assertEquals(33114L, ask(21, cfg = working).rows.of("Cigarette").tilldateTargetQtyBase)
            assertEquals(33115L, Targets.tillDate(40999L, 21L, 26L), "tillDate of the card's own total, which the card never shows")
            // The calendar default whatever the calendar holds; an unknown value is the default too.
            assertEquals(33064L, ask(21).rows.of("Cigarette").tilldateTargetQtyBase)
            assertEquals("calendar_days", ask(21, cfg = mapOf(TargetAchievementService.BASIS_TSO to "fortnights")).tilldateBasis)
            // The AMO reads its own key (`.amo_team`): the TSO's working basis does not move the AMO's figures, and the other way round.
            val amo = ask(23, cfg = working)
            assertEquals("zone", amo.node.type); assertEquals("calendar_days", amo.tilldateBasis); assertEquals(33064L, amo.rows.of("Cigarette").tilldateTargetQtyBase)
            val amoWorking = ask(23, cfg = mapOf(TargetAchievementService.BASIS_AMO to "working_days"))
            assertEquals("working_days", amoWorking.tilldateBasis); assertEquals(33114L, amoWorking.rows.of("Cigarette").tilldateTargetQtyBase)
            assertEquals("calendar_days", ask(21, cfg = mapOf(TargetAchievementService.BASIS_AMO to "working_days")).tilldateBasis)
        } finally { clearHolidays() }
    }

    // ---------------------------------------------------------------- per-node days (R-F-24, D-TSO-MAN-08)

    @Test
    fun eachZoneCardCountsTheWorkingDaysOfItsOwnPath() {
        clearHolidays()
        try {
            holiday("2026-10-21"); holiday("2026-10-12", "zone", ids.getValue("Z1"))
            val a = ask(21, children = "zone", cfg = working)
            // The territory ignores its zone's holiday (s4.2: zone 24, territory 25); Banani counts it: e = 19, n = 24.
            assertEquals(20, a.elapsedDays); assertEquals(25, a.periodDays); assertEquals(32799L, a.rows.of("Cigarette").tilldateTargetQtyBase)
            val (banani, gulshan) = a.children!!
            assertEquals(19, banani.elapsedDays); assertEquals(24, banani.periodDays)
            assertEquals(32458L, banani.rows.of("Cigarette").tilldateTargetQtyBase)   // 792+792+7917+7917+3958+4750+2374+3958
            assertEquals(20, gulshan.elapsedDays); assertEquals(25, gulshan.periodDays)
            // On the calendar basis every node has the parent's pair.
            val c = ask(21, children = "zone")
            assertEquals(listOf(25 to 31, 25 to 31), c.children!!.map { it.elapsedDays to it.periodDays })
        } finally { clearHolidays() }
    }

    @Test
    fun aZoneWithNoWorkingDayHasNullTillDateMembersWhileItsTerritoryKeepsThem() {
        clearHolidays()
        try {
            // Every day of November is off in Gulshan; November is a future month on 25 October (e = 0, day = the 1st).
            sql("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) SELECT d::date, 'zone', ${ids.getValue("Z2")}, 'emergency_off', false, 'B2 off' FROM generate_series(DATE '2026-11-01', DATE '2026-11-30', interval '1 day') d")
            val a = ask(21, month = "2026-11", children = "zone", cfg = working)
            assertEquals("2026-11-01", a.day); assertEquals(0, a.elapsedDays); assertEquals(26, a.periodDays)
            val terr = a.rows.of("Cigarette")
            assertEquals(6100L, terr.targetQtyBase); assertEquals(0L, terr.tilldateTargetQtyBase); assertEquals(0L, terr.remainingTilldateQtyBase)
            val (banani, gulshan) = a.children!!
            assertEquals(26, banani.periodDays); assertEquals(0L, banani.rows.of("Cigarette").tilldateTargetQtyBase)
            assertEquals(0, gulshan.periodDays)
            for (row in gulshan.rows) {
                assertNull(row.tilldateTargetQtyBase, row.name); assertNull(row.remainingTilldateQtyBase, row.name); assertNull(row.tilldateAchievementPctBp, row.name)
                assertEquals("0.000", row.tilldateTargetQty, row.name)
            }
            assertEquals(3000L, gulshan.rows.of("Cigarette").targetQtyBase)
            // Gulshan alone has n = 0 at its own node.
            val z = ask(21, month = "2026-11", level = "zone", node = ids.getValue("Z2"), cfg = working)
            assertEquals(0, z.periodDays); assertNull(z.rows.of("Cigarette").tilldateTargetQtyBase)
            // Over HTTP the till-date members are explicit nulls (R7) and the Decimal3 member is "0.000".
            http(working) {
                val g = get(21, "/v1/dashboards/targets?month=2026-11&children=zone").obj()["children"]!!.jsonArray[1].jsonObject
                val row = g["rows"]!!.jsonArray[0].jsonObject
                for (k in listOf("tilldate_target_qty_base", "remaining_tilldate_qty_base", "tilldate_achievement_pct_bp")) assertTrue(row[k] is JsonNull, k)
                assertEquals("0.000", row["tilldate_target_qty"]!!.jsonPrimitive.content); assertEquals(0L, g["period_days"]!!.jsonPrimitive.long)
            }
        } finally { clearHolidays() }
    }

    @Test
    fun aPastMonthIsCompleteAndAFutureMonthHasNotStarted() {
        val past = ask(21, month = "2026-09")
        assertEquals("2026-09-30", past.day); assertEquals(30, past.elapsedDays); assertEquals(30, past.periodDays)
        assertEquals(500L, ask(21, month = "2026-09", product = "variant").rows.of("Maxim Regular").achievedQtyBase)   // the September sale counts in September
        val future = ask(21, month = "2026-12")
        assertEquals("2026-12-01", future.day); assertEquals(0, future.elapsedDays); assertEquals(31, future.periodDays)
    }

    // ---------------------------------------------------------------- scope (constraint 4) and R-F-18

    @Test
    fun aNodeOutsideTheReachIs403AndTheZoneCardsComeFromTheReach() = http {
        // Another territory, another division's zone and the national node are 403 ERR_OUT_OF_SCOPE, with nothing of them in the body.
        for (q in listOf("level=territory&node_id=${ids.getValue("T2")}", "level=zone&node_id=${ids.getValue("Z3")}", "level=national", "level=division&node_id=${ids.getValue("D2")}")) {
            val r = get(21, "/v1/dashboards/targets?month=2026-10&$q&children=zone")
            assertEquals(403, r.status.value, q); assertEquals("ERR_OUT_OF_SCOPE", r.obj()["code"]!!.jsonPrimitive.content, q)
            assertFalse(r.bodyAsText().contains("Agrabad") || r.bodyAsText().contains("Other"), q)
        }
        // The zone cards are the reach's zones (never another zone), and the figures leave out the other division's route R4.
        val own = get(21, "/v1/dashboards/targets?month=2026-10&product_level=variant&children=zone").obj()
        assertEquals(listOf("Banani - Test", "Gulshan - Test"), own["children"]!!.jsonArray.map { it.jsonObject["node"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(1000L, own["rows"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "Maxim Regular" }["target_qty_base"]!!.jsonPrimitive.long)
        assertFalse(own.toString().contains("Agrabad"))
        // The national caller sees R4 too: Maxim Regular T 1000 + 5000, A 350 + 999.
        val nat = ask(24, product = "variant").rows.of("Maxim Regular")
        assertEquals("national", ask(24).node.type); assertEquals(6000L, nat.targetQtyBase); assertEquals(1349L, nat.achievedQtyBase); assertEquals(4839L, nat.tilldateTargetQtyBase)
        // A route-only TSO reads its own route, never the zone's other route (D-BC-DASH-ROUTE-KEYED-01): Maxim Regular 600 of R1 only.
        val ro = ask(25, product = "variant", children = "zone")
        assertEquals("route", ro.node.type)
        assertEquals(600L, ro.rows.of("Maxim Regular").targetQtyBase); assertEquals(200L, ro.rows.of("Maxim Regular").achievedQtyBase); assertEquals(484L, ro.rows.of("Maxim Regular").tilldateTargetQtyBase)
        assertEquals(listOf("Banani - Test"), ro.children!!.map { it.node.name }); assertEquals(ro.rows, ro.children!!.single().rows)
        assertFalse(ro.rows.any { it.name == "Maxim Double Burst" }, "R2's variant never reaches R1's TSO")
        // The AMO's zone node: a zone answers itself as its one child.
        val amo = get(23, "/v1/dashboards/targets?month=2026-10&children=zone").obj()
        assertEquals("zone", amo["node"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf(ids.getValue("Z1")), amo["children"]!!.jsonArray.map { it.jsonObject["node"]!!.jsonObject["id"]!!.jsonPrimitive.long })
        assertEquals(403, get(23, "/v1/dashboards/targets?month=2026-10&level=zone&node_id=${ids.getValue("Z2")}").status.value, "the AMO's sibling zone")
    }

    @Test
    fun childrenAreAllowedOnlyAtATerritoryOrBelow() {
        fun bad(uid: Long, level: String? = null, node: Long? = null) =
            assertFailsWith<ApiProblem> { ask(uid, level = level, node = node, children = "zone") }
        // R-F-18: national, division (in reach: Dhaka holds only the TSO's zones) -> 400 query.children.
        for (p in listOf(bad(24), bad(21, "division", ids.getValue("D1")), bad(24, "wing", wingId()))) {
            assertEquals(ProblemCode.ERR_VALIDATION, p.code); assertEquals("query.children", p.errors.single().pointer)
        }
        assertEquals(1, ask(21, level = "division", node = ids.getValue("D1")).rows.count { it.name == "Cigarette" }, "the division itself answers without children")
        // A house node (the default top node of a house TSO) answers its zones (R-F-24, D-F1-24).
        val house = ask(22, children = "zone")
        assertEquals("house", house.node.type); assertEquals(listOf("Banani - Test", "Gulshan - Test"), house.children!!.map { it.node.name })
        assertEquals(33064L, house.rows.of("Cigarette").tilldateTargetQtyBase)
        // An explicit level=house stays 400 query.level (GeoLevelQuery), with or without children.
        assertEquals("query.level", assertFailsWith<ApiProblem> { ask(22, level = "house", node = ids.getValue("H1"), children = "zone") }.errors.single().pointer)
        assertEquals("query.level", assertFailsWith<ApiProblem> { ask(22, level = "house", node = ids.getValue("H1")) }.errors.single().pointer)
        // A zone node answers itself as its one child.
        assertEquals(listOf(ids.getValue("Z2")), ask(21, level = "zone", node = ids.getValue("Z2"), children = "zone").children!!.map { it.node.id })
    }

    private fun wingId(): Long = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.wing WHERE code = 'W1'").mapTo(Long::class.java).one() }

    // ---------------------------------------------------------------- HTTP: contract shape, validation, roles

    @Test
    fun theHttpAnswerFollowsTheContract() = http {
        val r = get(21, "/v1/dashboards/targets?month=2026-10&product_level=category&children=zone")
        assertEquals(200, r.status.value)
        val o = r.obj()
        for (k in listOf("month", "as_of", "node", "tilldate_basis", "rows", "day", "elapsed_days", "period_days", "last_updated_at", "children")) assertTrue(k in o, k)
        val row = o["rows"]!!.jsonArray[0].jsonObject
        for (k in listOf("product_level", "product_id", "name", "unit", "target_qty", "achieved_qty", "tilldate_target_qty", "remaining_qty", "category_code",
            "target_qty_base", "achieved_qty_base", "tilldate_target_qty_base", "remaining_qty_base", "remaining_tilldate_qty_base", "achievement_pct_bp", "tilldate_achievement_pct_bp")) assertTrue(k in row, k)
        assertEquals(33064L, row["tilldate_target_qty_base"]!!.jsonPrimitive.long)
        // `children` is absent (never null) without children=zone; the AMO app's call (month + product_level=category) answers 200.
        assertFalse("children" in get(21, "/v1/dashboards/targets?month=2026-10").obj())
        assertEquals(200, get(23, "/v1/dashboards/targets?month=2026-10&product_level=category").status.value)
        // The default product level is category.
        assertEquals("category", get(21, "/v1/dashboards/targets?month=2026-10").obj()["rows"]!!.jsonArray[0].jsonObject["product_level"]!!.jsonPrimitive.content)
        // Validation (400 ERR_VALIDATION with the field).
        for ((q, field) in listOf("" to "query.month", "month=2026-13" to "query.month", "month=2026-10&product_level=outlet" to "query.product_level",
            "month=2026-10&children=route" to "query.children", "month=2026-10&level=territory" to "query.node_id", "month=2026-10&level=house&node_id=${ids.getValue("H1")}" to "query.level",
            "month=2026-10&level=zone&node_id=x" to "query.node_id")) {
            val b = get(21, "/v1/dashboards/targets?$q")
            assertEquals(400, b.status.value, q); assertEquals(field, b.obj()["errors"]!!.jsonArray[0].jsonObject["pointer"]!!.jsonPrimitive.content, q)
        }
        // Roles: SR (targets ride its bundle) and SUPPORT (sync health only) are refused before any figure is read.
        for (uid in listOf(26L, 27L)) {
            val f = get(uid, "/v1/dashboards/targets?month=2026-10")
            assertEquals(403, f.status.value); assertEquals("ERR_FORBIDDEN", f.obj()["code"]!!.jsonPrimitive.content)
        }
        assertEquals(200, get(24, "/v1/dashboards/targets?month=2026-10").status.value)
    }

    // ---------------------------------------------------------------- HTTP harness

    private fun http(cfg: Map<String, String> = emptyMap(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val config = config(cfg)
        val reach = ReachResolver { uid, _, _, _ -> reaches.getValue(uid) }
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            routing { route("/v1") { targetAchievementRoutes(TargetAchievementDeps(TargetAchievementService(fresh.db, config, clock), reach, TestTokens.guard(config), clock)) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(uid: Long, path: String): HttpResponse =
        client.get(path) { bearerAuth(TestTokens.web(uid, reaches.getValue(uid).role)) }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
}

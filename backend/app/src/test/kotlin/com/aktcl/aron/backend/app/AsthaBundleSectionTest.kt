package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.sync.BundleProgrammes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bundle `programmes` section (lane portal-loyalty-api, push B; docs/requests/contract-1.15-loyalty-field.md): programmes, ledger balances as of the previous
 * business day, Astha targets with server-side achievement (voided, superseded and non-sale lines excluded; base units and integer milli-taka), gift assignments,
 * the reach cut and the flags.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsthaBundleSectionTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var today: LocalDate
    private var userId = 0L
    private var route = 0L
    private var outletA = 0L
    private var outletB = 0L
    private var outletOther = 0L
    private var brand = 0L
    private var programme = 0L
    private var cigaretteSku = 0L
    private var pack = 0

    private val v1 = "00000000-0000-4000-8000-0000000a5001"
    private val mA = "00000000-0000-4000-8000-0000000a5101"
    private val mBold = "00000000-0000-4000-8000-0000000a5102"
    private val mBnew = "00000000-0000-4000-8000-0000000a5103"

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        with(world) {
            today = today()
            userId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val o = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 2")
            outletA = o[0]; outletB = o[1]
            outletOther = one("SELECT id FROM app.outlet WHERE route_id <> $route AND status = 'active' ORDER BY id LIMIT 1")
            cigaretteSku = one("SELECT id FROM app.sku WHERE entry_unit_default = 'pack' AND category_code = 'cigarette' ORDER BY id LIMIT 1")
            pack = one("SELECT base_per_pack FROM app.sku WHERE id = $cigaretteSku").toInt()
            brand = one("SELECT b.id FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id WHERE s.id = $cigaretteSku")
            val first = today.withDayOfMonth(1)
            programme = one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'ASTHA-T', 'Astha test', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active') RETURNING id")
            exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($programme, $outletA, 'gold', DATE '${today.minusDays(30)}')")
            exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($programme, $outletB, 'silver', DATE '${today.minusDays(30)}')")
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target, value_target_mtk) VALUES ($programme, DATE '$first', $route, $outletA, $brand, 120.5, 0, 500000)")
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$first', $route, $outletA, NULL, 0, 10)")
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$first', $route, $outletB, NULL, 0, 4)")
            exec("INSERT INTO app.gift (programme_id, code, name_en, tier_codes) VALUES ($programme, 'gift_a', 'Gift A', '{gold}')")
            val q = BundleProgrammes.Quarter.of(today, 1).label
            exec("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id, tier_code, chosen_by_user_id, chosen_at) SELECT $programme, '$q', $outletA, $route, id, 'gold', $userId, now() FROM app.gift WHERE code = 'gift_a'")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) VALUES ($outletA, $programme, 'adjustment', 'a1', 100, DATE '${today.minusDays(2)}', DATE '${today.plusDays(3)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date) VALUES ($outletA, $programme, 'adjustment', 'a2', 50, DATE '$today')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date) VALUES ($outletA, $programme, 'redemption', 'r1', -30, DATE '${today.minusDays(1)}')")
            sales()
        }
    }

    @AfterAll fun tearDown() = world.close()

    private fun memo(uuid: String, outlet: Long, no: String, status: String = "active", supersedes: String? = null, supersededBy: String? = null) = world.exec(
        """
        INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at,
                              price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit,
                              line_count, discount_line_count, qc_line_count, status, supersedes_client_uuid, edit_reason_code, superseded_by_client_uuid, received_at)
        VALUES (CAST('$uuid' AS uuid), CAST('$uuid' AS uuid), DATE '$today', $userId, $route, TIMESTAMPTZ '$today 10:05:00+06', 0, CAST('$v1' AS uuid), $outlet, '$no', 'sale', TIMESTAMPTZ '$today 10:05:00+06',
                DATE '$today', 'outlet', 6250, 0, 0, 0, 0, 6250, 6250, 0, false, 1, 0, 0, '$status', ${supersedes?.let { "CAST('$it' AS uuid)" } ?: "NULL"}, ${if (supersedes != null) "'wrong_quantity'" else "NULL"},
                ${supersededBy?.let { "CAST('$it' AS uuid)" } ?: "NULL"}, now())
        """.trimIndent(),
    )

    private fun line(memo: String, no: Int, packs: Int, gross: Long, voided: Boolean = false, kind: String = "sale") = world.exec(
        """
        INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id, line_kind, qty_entered, unit_entered,
                                   pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk, voided_at)
        VALUES (gen_random_uuid(), gen_random_uuid(), DATE '$today', $userId, $route, TIMESTAMPTZ '$today 10:05:00+06', 0, CAST('$memo' AS uuid), $no, $cigaretteSku, '$kind', $packs, 'pack', $pack, ${packs * pack},
                'outlet', DATE '$today', ${if (kind == "sale") gross / (packs * pack) else 0}, 1, ${if (kind == "sale") gross else 0}, ${if (voided) "now()" else "NULL"})
        """.trimIndent(),
    )

    private fun sales() {
        world.exec(
            """
            INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned,
                                   verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action)
            VALUES (CAST('$v1' AS uuid), CAST('$v1' AS uuid), DATE '$today', $userId, $route, TIMESTAMPTZ '$today 10:00:00+06', 0, 'sr_call', $outletA, TIMESTAMPTZ '$today 10:00:00+06', 1, false, 'in_range', 100, 50, 'master', 'sale_allowed')
            """.trimIndent(),
        )
        memo(mA, outletA, "sr1001-270103-001")
        line(mA, 1, 3, 3 * pack * 100L)                       // counts: 3 packs, 3 x pack x 100 milli-taka
        line(mA, 2, 9, 9 * pack * 100L, voided = true)        // voided line: out
        line(mA, 3, 1, 0, kind = "promo_free")                // free line: out
        memo(mBold, outletB, "sr1001-270103-002", status = "superseded", supersededBy = mBnew)
        memo(mBnew, outletB, "sr1001-270103-003", supersedes = mBold)
        line(mBold, 1, 5, 5 * pack * 100L)                    // belongs to a superseded memo: out
        line(mBnew, 1, 2, 2 * pack * 100L)
    }

    private fun section(outlets: Set<Long>, flags: BundleProgrammes.Flags = BundleProgrammes.Flags(true, true, true)): JsonObject? =
        world.fresh.db.jdbi.withHandle<JsonObject?, Exception> { h -> BundleProgrammes.section(h, listOf(route), outlets, today, java.time.Instant.parse("2026-01-01T00:00:00Z"), flags, 1) }

    private fun JsonObject.str(k: String) = this[k]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun theSectionCarriesTheProgrammeItsBalancesTargetsAndGift() {
        val s = assertNotNull(section(setOf(outletA, outletB)))
        assertEquals(listOf("ASTHA-T"), s["programmes"]!!.jsonArray.map { it.jsonObject.str("code") })
        assertEquals("active", s["programmes"]!!.jsonArray[0].jsonObject.str("status"))
        // Balance as of the previous business day: +100 (two days ago) -30 (yesterday); today's +50 is not in it; 100 expire within 7 days, capped by the balance 70.
        val a = s["loyalty"]!!.jsonArray.map { it.jsonObject }.single { it.str("outlet_id") == outletA.toString() }
        assertEquals("70", a.str("balance_points")); assertEquals("70", a.str("expiring_points")); assertEquals(today.plusDays(3).toString(), a.str("expiry_date"))
        assertEquals(today.minusDays(1).toString(), a.str("as_of_date"))
        assertEquals("0", s["loyalty"]!!.jsonArray.map { it.jsonObject }.single { it.str("outlet_id") == outletB.toString() }.str("balance_points"))
        assertEquals(listOf("gift_a"), s["gifts"]!!.jsonArray.map { it.jsonObject.str("code") })
        assertEquals(listOf("gold"), s["gift_assignments"]!!.jsonArray.map { it.jsonObject.str("tier_code") })
        assertEquals("false", s["gift_assignments"]!!.jsonArray[0].jsonObject.str("locked"))
        assertTrue(s["pending_gift_verifications"]!!.jsonArray.isEmpty())
    }

    @Test
    fun achievementIsServerSideInBaseUnitsAndMilliTakaWithVoidedSupersededAndFreeLinesOut() {
        val rows = assertNotNull(section(setOf(outletA, outletB)))["astha_targets"]!!.jsonArray.map { it.jsonObject }
        val brandRow = rows.single { it.str("outlet_id") == outletA.toString() && it.str("brand_id") == brand.toString() }
        assertEquals("120.500", brandRow.str("std_target")); assertEquals((3 * pack).toString() + ".000", brandRow.str("std_achieved"))
        assertEquals("500000", brandRow.str("value_target_mtk")); assertEquals((3 * pack * 100).toString(), brandRow.str("value_achieved_mtk"))
        assertEquals(today.toString().substring(0, 7), brandRow.str("month")); assertNotNull(brandRow.str("as_of"))
        // The All Brand row counts active sale memos: A has one; B has two memos, one superseded, so one counts.
        assertEquals("1", rows.single { it.str("outlet_id") == outletA.toString() && it.str("brand_id") == null }.str("memo_achieved"))
        assertEquals("1", rows.single { it.str("outlet_id") == outletB.toString() && it.str("brand_id") == null }.str("memo_achieved"))
        // The value target is 0 until uploaded; an unset target never shows a negative or a percentage base.
        assertEquals("0", rows.single { it.str("outlet_id") == outletB.toString() }.str("value_target_mtk"))
    }

    @Test
    fun theReachCutAndTheFlagsDecideWhatIsSent() {
        // An outlet outside the caller's reach contributes nothing; with no enrolled outlet in reach there is no section at all.
        assertNull(section(setOf(outletOther)))
        val onlyA = assertNotNull(section(setOf(outletA)))
        assertEquals(listOf(outletA.toString()), onlyA["loyalty"]!!.jsonArray.map { it.jsonObject.str("outlet_id") })
        assertTrue(onlyA["astha_targets"]!!.jsonArray.none { it.jsonObject.str("outlet_id") == outletB.toString() })
        // The Astha tile hidden: a hidden programme sends no data.
        assertNull(section(setOf(outletA, outletB), BundleProgrammes.Flags(loyalty = true, astha = false, superstar = true)))
        assertNull(section(emptySet()))
    }

    @Test
    fun theQuarterFollowsTheConfiguredStartMonth() {
        fun q(date: String, start: Int) = BundleProgrammes.Quarter.of(LocalDate.parse(date), start)
        assertEquals("2026-Q4", q("2026-11-12", 1).label); assertEquals(listOf("2026-10-01", "2026-11-01", "2026-12-01"), q("2026-11-12", 1).months.map { it.toString() })
        assertEquals("2026-Q1", q("2026-11-12", 11).label); assertEquals("2026-11-01", q("2026-11-12", 11).months.first().toString())
        assertEquals("2027-Q2", q("2027-01-05", 10).label) // an October-start year: Oct-Dec is its Q1, Jan-Mar its Q2, labelled by the year the quarter starts in
        assertEquals(listOf("2026-10-01", "2026-11-01", "2026-12-01"), q("2026-12-31", 1).months.map { it.toString() })
    }
}

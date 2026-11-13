package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AsthaQuarter
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
    private var outletC = 0L
    private var outletD = 0L
    private var outletE = 0L
    private var outletOther = 0L
    private var brand = 0L
    private var programme = 0L
    private var cigaretteSku = 0L
    private var pack = 0

    private val v1 = "00000000-0000-4000-8000-0000000a5001"
    private val mA = "00000000-0000-4000-8000-0000000a5101"
    private val mBold = "00000000-0000-4000-8000-0000000a5102"
    private val mBnew = "00000000-0000-4000-8000-0000000a5103"
    private val mE1 = "00000000-0000-4000-8000-0000000a5106"
    private val mCold = "00000000-0000-4000-8000-0000000a5104"
    private val mCnew = "00000000-0000-4000-8000-0000000a5105"

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        with(world) {
            today = today()
            userId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val o = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 5")
            outletA = o[0]; outletB = o[1]; outletC = o[2]; outletD = o[3]; outletE = o[4]
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
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$first', $route, $outletC, NULL, 0, 4)")
            // The bundle's cut: an outlet with a target but no enrolment (D), an all-zero route row, and a row of ANOTHER month of the quarter are not sent; the quarter read still has the last.
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$first', $route, $outletD, NULL, 0, 9)")
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$first', $route, NULL, NULL, 0, 0)")
            val otherMonth = AsthaQuarter.of(today, 1).months.first { it != first }
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$otherMonth', $route, $outletA, NULL, 0, 77)")
            // Outlet E: the snapshot test's outlet (a memo target, no memo yet) and the expired-credit balance: +40 expired yesterday with its expiry debit row, +10 still valid.
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) VALUES ($programme, DATE '$first', $route, $outletE, NULL, 0, 3)")
            exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($programme, $outletE, 'silver', DATE '${today.minusDays(30)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) VALUES ($outletE, $programme, 'adjustment', 'e1', 40, DATE '${today.minusDays(5)}', DATE '${today.minusDays(1)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) VALUES ($outletE, $programme, 'adjustment', 'e2', 10, DATE '${today.minusDays(5)}', DATE '${today.plusDays(20)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date) VALUES ($outletE, $programme, 'expiry', 'e3', -40, DATE '${today.minusDays(1)}')")
            // Outlet C: 100 points expiring in 5 days, 80 spent: balance 20, so the expiring points are capped at 20 (debits are not matched to credits).
            exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) VALUES ($programme, $outletC, 'silver', DATE '${today.minusDays(30)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) VALUES ($outletC, $programme, 'adjustment', 'c1', 100, DATE '${today.minusDays(3)}', DATE '${today.plusDays(5)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date) VALUES ($outletC, $programme, 'redemption', 'c2', -80, DATE '${today.minusDays(1)}')")
            exec("INSERT INTO app.gift (programme_id, code, name_en, tier_codes) VALUES ($programme, 'gift_a', 'Gift A', '{gold}')")
            val q = AsthaQuarter.of(today, 1).label
            exec("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id, tier_code, chosen_by_user_id, chosen_at) SELECT $programme, '$q', $outletA, $route, id, 'gold', $userId, now() FROM app.gift WHERE code = 'gift_a'")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) VALUES ($outletA, $programme, 'adjustment', 'a1', 100, DATE '${today.minusDays(2)}', DATE '${today.plusDays(3)}')")
            exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) VALUES ($outletA, $programme, 'adjustment', 'a3', 40, DATE '${today.minusDays(2)}', DATE '${today.plusDays(30)}')")
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
        // Out-of-order edit: the edited memo arrived and names its original (supersedes_client_uuid) but the original still reads `active` (its status flip is not visible
        // yet, or never happened). The achievement follows supersedes_client_uuid: the original is out, the edit counts once.
        memo(mCold, outletC, "sr1001-270103-004")
        memo(mCnew, outletC, "sr1001-270103-005", supersedes = mCold)
        line(mCold, 1, 7, 7 * pack * 100L)
        line(mCnew, 1, 1, 1 * pack * 100L)
    }

    private fun section(outlets: Set<Long>, flags: BundleProgrammes.Flags = BundleProgrammes.Flags(true, true, true)): JsonObject? =
        world.fresh.db.jdbi.withHandle<JsonObject?, Exception> { h -> BundleProgrammes.section(h, listOf(route), outlets, today, flags, 1) }

    private fun JsonObject.str(k: String) = this[k]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun theSectionCarriesTheProgrammeItsBalancesTargetsAndGift() {
        val s = assertNotNull(section(setOf(outletA, outletB, outletC)))
        assertEquals(listOf("ASTHA-T"), s["programmes"]!!.jsonArray.map { it.jsonObject.str("code") })
        assertEquals("active", s["programmes"]!!.jsonArray[0].jsonObject.str("status"))
        // Balance as of the previous business day: +100 (expires in 3 days) +40 (expires in 30 days) -30 (yesterday); today's +50 is not in it. Expiring = the credits of the
        // EARLIEST upcoming expiry date (100, not the 30-day batch), capped by the balance; the expiry date is that date (D24-68: expiry_days counts from the period end).
        val a = s["loyalty"]!!.jsonArray.map { it.jsonObject }.single { it.str("outlet_id") == outletA.toString() }
        assertEquals("110", a.str("balance_points")); assertEquals("100", a.str("expiring_points")); assertEquals(today.plusDays(3).toString(), a.str("expiry_date"))
        assertEquals(today.minusDays(1).toString(), a.str("as_of_date"))
        val c = s["loyalty"]!!.jsonArray.map { it.jsonObject }.single { it.str("outlet_id") == outletC.toString() }
        assertEquals("20", c.str("balance_points")); assertEquals("20", c.str("expiring_points"), "capped by the balance, never above it"); assertEquals(today.plusDays(5).toString(), c.str("expiry_date"))
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
        assertEquals(today.toString().substring(0, 7), brandRow.str("month")); assertNull(brandRow["as_of"], "the bundle rows carry no as_of"); assertNotNull(assertNotNull(section(setOf(outletA, outletB, outletC)))["astha_as_of"])
        // by_pack: achievement per pack size in base units; the entries sum to the row total (never summed across pack sizes on the phone).
        val packs = brandRow["by_pack"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(pack.toString()), packs.map { it.str("pack_factor") })
        assertEquals(brandRow.str("std_achieved"), packs.fold(java.math.BigDecimal.ZERO) { a, p -> a + java.math.BigDecimal(p.str("std_achieved")!!) }.toPlainString(), "sum of by_pack equals the row total")
        // The All Brand row counts active sale memos: A has one; B has two memos, one superseded, so one counts.
        assertEquals("1", rows.single { it.str("outlet_id") == outletA.toString() && it.str("brand_id") == null }.str("memo_achieved"))
        assertEquals("1", rows.single { it.str("outlet_id") == outletB.toString() && it.str("brand_id") == null }.str("memo_achieved"))
        // The value target is 0 until uploaded; an unset target never shows a negative or a percentage base.
        assertEquals("0", rows.single { it.str("outlet_id") == outletB.toString() }.str("value_target_mtk"))
    }

    @Test
    fun anExpiredCreditIsOutOfTheBalanceAndItsExpiryDebitIsNotCountedTwice() {
        val e = assertNotNull(section(setOf(outletE)))["loyalty"]!!.jsonArray.map { it.jsonObject }.single()
        assertEquals("10", e.str("balance_points"), "the 40 expired yesterday are out whether or not the expiry debit is posted, and the debit row is not subtracted again")
        assertEquals("10", e.str("expiring_points")); assertEquals(today.plusDays(20).toString(), e.str("expiry_date"))
    }

    @Test
    fun timestampsAreMillisecondUtcAndTheCapsAreReported() {
        val s = assertNotNull(section(setOf(outletA, outletB)))
        assertTrue(Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z$").matches(s.str("astha_as_of")!!), s.str("astha_as_of"))
        assertEquals(0, s["truncated_sections"]!!.jsonArray.size); assertEquals("false", s["astha_truncated"]!!.toString())
        val chosen = s["gift_assignments"]!!.jsonArray[0].jsonObject.str("chosen_at")!!
        assertTrue(Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z$").matches(chosen), chosen)
    }

    /**
     * B1/B2 of the Opus pass: the section is ONE snapshot. A memo committed from another connection while the snapshot is open is not in it (both reads agree), and a request that
     * starts after the commit sees the memo exactly once: with the phone rule (add what was not acknowledged when the request was SENT) nothing is counted twice or missed.
     */
    @Test
    fun theSectionIsReadInOneRepeatableReadSnapshotAndALaterRequestSeesACommittedMemoOnce() {
        fun achieved(sec: JsonObject?) = assertNotNull(sec)["astha_targets"]!!.jsonArray.map { it.jsonObject }.single { it.str("outlet_id") == outletE.toString() && it.str("brand_id") == null }.str("memo_achieved")
        val jdbi = world.fresh.db.jdbi
        jdbi.inTransaction<Unit, Exception>(org.jdbi.v3.core.transaction.TransactionIsolationLevel.REPEATABLE_READ) { h ->
            h.execute("SET TRANSACTION READ ONLY")
            val first = BundleProgrammes.section(h, listOf(route), setOf(outletE), today, BundleProgrammes.Flags(true, true, true), 1)
            assertEquals("0", achieved(first))
            // committed on ANOTHER connection (another thread: Jdbi would reuse this thread's open handle) while the snapshot is open
            var failure: Throwable? = null
            kotlin.concurrent.thread { try { memo(mE1, outletE, "sr1001-270103-006") } catch (t: Throwable) { failure = t } }.join()
            failure?.let { throw it }
            val second = BundleProgrammes.section(h, listOf(route), setOf(outletE), today, BundleProgrammes.Flags(true, true, true), 1)
            assertEquals(first, second, "one snapshot: every read of the section sees the same database")
        }
        assertEquals("1", achieved(section(setOf(outletE))), "a request that starts after the commit counts the memo exactly once")
    }

    @Test
    fun theBundleCarriesOnlyTheCurrentMonthEnrolledOutletsAndNoAllZeroRow() {
        val rows = assertNotNull(section(setOf(outletA, outletB, outletC, outletD)))["astha_targets"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.none { it.str("outlet_id") == outletD.toString() }, "an outlet with no active enrolment sends no row")
        assertTrue(rows.none { it.str("outlet_id") == "null" || it["outlet_id"] is JsonNull }, "the all-zero route row is cut")
        assertTrue(rows.all { it.str("month") == today.toString().substring(0, 7) }, "only the month of the business date")
        assertTrue(rows.none { it.str("memo_target") == "77" })
        assertEquals("false", assertNotNull(section(setOf(outletA)))["astha_truncated"]!!.toString())
        // The quarter read (listAsthaTargets) keeps every month and every row.
        val q = AsthaQuarter.of(today, 1)
        val all = world.fresh.db.jdbi.withHandle<List<JsonObject>, Exception> { h -> BundleProgrammes.asthaTargets(h, listOf(route), listOf(outletA, outletD), q, java.time.Instant.EPOCH) }
        assertTrue(all.any { it.str("memo_target") == "77" } && all.any { it.str("outlet_id") == outletD.toString() })
    }

    @Test
    fun anEditedMemoReplacesItsOriginalByTheSupersedesLinkEvenBeforeTheOriginalsStatusFlips() {
        val rows = assertNotNull(section(setOf(outletC)))["astha_targets"]!!.jsonArray.map { it.jsonObject }
        assertEquals("1", rows.single { it.str("outlet_id") == outletC.toString() && it.str("brand_id") == null }.str("memo_achieved"), "only the superseding memo counts")
    }

    @Test
    fun asOfIsContentDerivedSoRebuildingTheBundleGivesTheSameBytesUntilAMemoArrives() {
        val a = assertNotNull(section(setOf(outletA, outletB, outletC))); Thread.sleep(20); val b = assertNotNull(section(setOf(outletA, outletB, outletC)))
        assertEquals(a.toString(), b.toString(), "a wall-clock as_of would give every build a new digest and a new bundle snapshot")
    }

    @Test
    fun theGiftCarriesItsStableAssetIdAndNeverASignedUrl() {
        val g = assertNotNull(section(setOf(outletA)))["gifts"]!!.jsonArray[0].jsonObject
        assertNull(g.str("image_url")); assertTrue(g.containsKey("image_asset_id"))
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
    fun aQuarterLabelParsesBackToItsMonths() {
        fun p(l: String, start: Int) = AsthaQuarter.parse(l, start)?.months?.map { it.toString() }
        assertEquals(listOf("2026-10-01", "2026-11-01", "2026-12-01"), p("2026-Q4", 1))
        assertEquals(listOf("2026-11-01", "2026-12-01", "2027-01-01"), p("2026-Q1", 11))
        assertEquals(listOf("2027-01-01", "2027-02-01", "2027-03-01"), p("2027-Q2", 10))
        assertNull(p("2026-Q5", 1)); assertNull(p("26-Q1", 1))
        for (start in 1..12) for (d in listOf("2026-01-01", "2026-05-17", "2026-11-12", "2027-02-28")) {   // parse(of(date)) is the same quarter
            val q = AsthaQuarter.of(LocalDate.parse(d), start); assertEquals(q.months, AsthaQuarter.parse(q.label, start)?.months, "$d start $start")
        }
    }

    @Test
    fun theQuarterFollowsTheConfiguredStartMonth() {
        fun q(date: String, start: Int) = AsthaQuarter.of(LocalDate.parse(date), start)
        assertEquals("2026-Q4", q("2026-11-12", 1).label); assertEquals(listOf("2026-10-01", "2026-11-01", "2026-12-01"), q("2026-11-12", 1).months.map { it.toString() })
        assertEquals("2026-Q1", q("2026-11-12", 11).label); assertEquals("2026-11-01", q("2026-11-12", 11).months.first().toString())
        assertEquals("2027-Q2", q("2027-01-05", 10).label) // an October-start year: Oct-Dec is its Q1, Jan-Mar its Q2, labelled by the year the quarter starts in
        assertEquals(listOf("2026-10-01", "2026-11-01", "2026-12-01"), q("2026-12-31", 1).months.map { it.toString() })
    }
}

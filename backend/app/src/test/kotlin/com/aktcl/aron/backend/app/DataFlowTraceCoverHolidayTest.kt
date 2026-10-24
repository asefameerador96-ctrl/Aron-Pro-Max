package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test

/** The memos, upload and server count the two scenarios below share: three memos on the first outlets of the route, a credit memo with a partial collection, a void-free day. */
abstract class DataFlowTraceScenario(dayRoute: String) : DataFlowTraceSupport(dayRoute) {
    protected lateinit var recs: List<ObjectNode>
    protected var collected = 0L

    protected fun sellAndUpload(label: String, extraRecords: List<ObjectNode> = emptyList()) {
        val cig20 = pick("cigarette", 20); val cig10 = pick("cigarette", 10); val bidi = pick("bidi"); val lighter = pick("lighter"); val match = pick("match")
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted().take(3)
        sr.memos += Memo(outletByCode(codes[0]), codes[0], listOf(Line(cig20, 2), Line(cig10, 1), Line(bidi, 1), Line(match, 3)), paidShare = 100, minute = 0)
        sr.memos += Memo(outletByCode(codes[1]), codes[1], listOf(Line(cig20, 3), Line(lighter, 1)), paidShare = 50, minute = 40)
        sr.memos += Memo(outletByCode(codes[2]), codes[2], listOf(Line(bidi, 2)), paidShare = 100, minute = 80)
        val list = mutableListOf<ObjectNode>()
        list += extraRecords
        sr.memos.forEachIndexed { i, m -> val (r, a) = radiusOf(m.outlet); list += recordsOf(sr, m, gulPins.getValue(m.outlet), r, a, i + 1) }
        val credit = sr.memos[1]; val cu = uuid()
        collected = minOf(credit.due, 1_000L)
        list += envelope(sr, "due_collection", cu, cu, 0, 100, json.readTree(
            """{"outlet_id":${credit.outlet},"against_memo_client_uuid":"${credit.memo}","against_memo_no":"${list.first { it.path("client_uuid").asText() == credit.memo }.path("payload").path("memo_no").asText()}",
                "against_memo_business_date":"$day","amount_mtk":$collected,"is_full_settlement":${collected >= credit.due},"outstanding_before_mtk":${credit.due},"payment_mode":"cash"}"""))
        recs = list
        list.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts[t] = n }
        hop("$label: the day shuffled leaves only retryable parent_missing rejections", "other codes" to send(sr, list.shuffled(java.util.Random(5)), uuid()).body!!.path("acks").filter { it.path("status").asText() == "rejected" }.map { it.path("code").asText() + "/" + it.path("retryable").asText() }.distinct().filter { it != "parent_missing/true" }, "expected" to emptyList<String>())
        val inOrder = send(sr, list, uuid())
        hop("$label: the day in order leaves no rejected or quarantined row", "bad rows" to acks(inOrder).count { it == "rejected" || it == "quarantined" }, "expected" to 0)
        hop("$label: the day again is all duplicate", "statuses" to acks(send(sr, list, uuid())).distinct(), "expected" to listOf("duplicate"))
        fun count(table: String) = sqlLong("SELECT count(*) FROM $table t JOIN app.app_user u ON u.id = t.user_id WHERE u.username = '${sr.username}' AND t.business_date = DATE '$day'")
        for ((t, tbl) in listOf("visit" to "app.visit", "memo" to "app.memo", "memo_line" to "app.memo_line", "due_collection" to "app.due_collection"))
            hop("$label: $t rows by ${sr.username}, device vs $tbl", "device" to sr.counts[t], "server" to count(tbl))
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val money = send(sr, list, uuid()).body!!.path("server_totals").single { it.path("business_date").asText() == day }.path("money")
        hop("$label: server_totals gross / net / paid / due / collected", "server" to listOf("gross_mtk", "net_mtk", "paid_mtk", "due_mtk", "due_collected_mtk").map { money.path(it).asLong() },
            "device" to listOf(active.sumOf { it.gross }, active.sumOf { it.net }, active.sumOf { it.paid }, active.sumOf { it.due }, collected))
        sr.truthGross = active.sumOf { it.gross }
    }

    protected fun soldByCategory(): Map<String, Long> {
        val m = sortedMapOf<String, Long>(); for (x in sr.memos.filter { it.lines.isNotEmpty() && !it.voided }) for (l in x.lines) m.merge(l.sku.category, l.qtyBase, Long::plus); return m
    }

    protected fun salesCards(token: String): Map<String, Long> =
        jsonOf(postJson("/v1/dashboards/national/sales", token, """{"date":"$day"}""")).path("cards").associate { it.path("category_code").asText() to it.path("total_sales").asLong() }.filterValues { it != 0L }.toSortedMap()

    protected fun plannedRouteDays(): Long = sqlLong(
        "SELECT coalesce(count(*), 0) FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id WHERE a.business_date = DATE '$day' AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr'")

    protected fun workingDays(month: String): Long = sqlLong(
        """
        SELECT count(*) FROM generate_series(DATE '$month-01', (DATE '$month-01' + interval '1 month - 1 day')::date, interval '1 day') g(d)
         LEFT JOIN app.calendar_holiday c ON c.date = g.d::date AND c.scope_type = 'global' AND c.revoked_at IS NULL
         WHERE (extract(isodow FROM g.d) <> 5 AND coalesce(c.kind, '') NOT IN ('holiday', 'emergency_off')) OR c.kind = 'makeup_day'
        """.trimIndent(),
    )
}

/**
 * A cover day (docs/24 s4.9): sr1001 is away on a day of BAN-SR-11 (the first the seed plans, D-TEST-DATE-PIN-01), a stand-in SR (qacover1001, its own phone) holds BAN-SR-11 as a `cover` assignment
 * for that date only. The bundle plans the route for the stand-in, the route-day records the stand-in as the acting user, his sales and check-in
 * belong to him and to the route, and the Successful Login (D-REPORTS-LOGIN-01: logged in AND downloaded AND checked in) counts the route
 * once, through the stand-in's check-in; sr1001 has no record that day.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DataFlowTraceCoverDayTest : DataFlowTraceScenario("BAN-SR-11") {
    override val traceName get() = "data-flow-trace-cover"
    private val phone = "00000000-0000-4000-8000-0000000000c1"
    private var loginBefore = 0; private var targetRoutesBefore = 0

    private fun loginSubmit(): Pair<Int, Int> = jsonOf(postJson("/v1/dashboards/national/login-submit", login("superadmin1001", "web", null), """{"date":"$day"}""")).let { it.path("successful_login").asInt() to it.path("target_routes").asInt() }

    @Test
    @Order(1)
    fun theStandInGetsTheRouteAndIsTheActingUser() {
        h("1 Cover day $day: the bundle of the stand-in plans BAN-SR-11, the route-day names him")
        val hash = PasswordHasher().hash(world.password)
        db { hd ->
            hd.execute("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) SELECT 'qacover1001', 'QA Cover SR', role, designation, 'QACOVER1001', locale, home_zone_id, true, false, '$hash' FROM app.app_user WHERE username = 'sr1001'")
            hd.execute(
                "INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) " +
                    "SELECT '$phone', flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, 'qa-cover-0001', attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version FROM app.device WHERE device_uuid = '${ApiWorld.SR_PHONE}'",
            )
            hd.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 1, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '$phone' AND u.username = 'qacover1001'")
            hd.execute(
                "WITH tok AS (INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, used_count, release_id, expires_at, created_by, note) " +
                    "SELECT sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'smoke0', 'sr', 'dev', 1, 1, (SELECT id FROM app.app_release WHERE flavour = 'sr' AND status = 'published' ORDER BY version_code DESC LIMIT 1), " +
                    "now() + interval '1 hour', (SELECT id FROM app.app_user WHERE username = 'admin1001'), 'qa trace: cover phone enrolled' RETURNING id) " +
                    "UPDATE app.device d SET enrolment_token_id = tok.id FROM tok WHERE d.device_uuid = '$phone'",
            )
            hd.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to, reason) SELECT r.id, u.id, 'cover', DATE '$day', DATE '$day' + 1, 'qa trace: cover day' FROM app.route r, app.app_user u WHERE r.code = 'BAN-SR-11' AND u.username = 'qacover1001'")
        }
        prepareSrDay("qacover1001", phone)
        hop("routes planned in the stand-in's bundle", "planned" to bundle.path("routes").filter { it.path("planned_today").asBoolean() }.map { it.path("route").path("code").asText() }, "expected" to listOf("BAN-SR-11"))
        hop("the assignment kind of the route", "kind" to route.path("assignment_kind").asText(), "expected" to "cover")
        val coverId = sqlLong("SELECT id FROM app.app_user WHERE username = 'qacover1001'")
        hop("route-day: acting user is the stand-in, no assigned user", "acting/assigned" to sql("SELECT acting_user_id || '/' || coalesce(assigned_user_id::text, '-') FROM app.route_day WHERE route_id = $gulRouteId AND business_date = DATE '$day'"), "expected" to "$coverId/-")
        hop("one route-day for the route and date", "rows" to sqlLong("SELECT count(*) FROM app.route_day WHERE route_id = $gulRouteId AND business_date = DATE '$day'"), "expected" to 1L)
        runWorker()
        val (l, t) = loginSubmit(); loginBefore = l; targetRoutesBefore = t
        hop("before the check-in the route is not a Successful Login (bundle downloaded, nobody checked in)", "successful_login" to loginBefore, "expected" to 0)
        hop("Login/Submit target routes = planned normal SR route-days of the date", "dashboard" to targetRoutesBefore.toLong(), "dw oracle" to plannedRouteDays())
    }

    @Test
    @Order(2)
    fun theStandInSellsAndChecksIn() {
        h("2 Cover day: day_open and check-in, sales in packets, upload")
        val first = gulPins.getValue(sr.let { outletByCode(route.path("outlets").map { o -> o.path("code").asText() }.sorted().first()) })
        val morning = listOf(
            envelope(sr, "day_open", uuid(), uuid(), 0, -30, json.readTree("""{"route_ids":[$gulRouteId],"online":true,"bundle_valid_for":"$day","offline_start":false}""")),
            envelope(sr, "attendance_event", uuid(), uuid(), 0, -20, json.readTree("""{"kind":"check_in","fix":${fix("attendance_in", first.first, first.second)}}""")),
        )
        sellAndUpload("cover", morning)
        val coverId = sqlLong("SELECT id FROM app.app_user WHERE username = 'qacover1001'")
        hop("every memo of the day belongs to the stand-in and the route", "users/routes" to sql("SELECT string_agg(DISTINCT user_id || '/' || route_id, ',') FROM app.memo WHERE business_date = DATE '$day'"), "expected" to "$coverId/$gulRouteId")
        hop("sr1001 has no memo, visit or collection on the date", "rows" to sqlLong("SELECT (SELECT count(*) FROM app.memo m JOIN app.app_user u ON u.id = m.user_id WHERE u.username = 'sr1001' AND m.business_date = DATE '$day') + (SELECT count(*) FROM app.visit v JOIN app.app_user u ON u.id = v.user_id WHERE u.username = 'sr1001' AND v.business_date = DATE '$day')"), "expected" to 0L)
    }

    @Test
    @Order(3)
    fun warehouseAndLoginCountTheRouteOnce() {
        h("3 Cover day: warehouse, cards and the Successful Login")
        runWorker()
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val ar = rows("SELECT gross_mtk, net_mtk, paid_mtk, due_mtk, active_memo_count, planned FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").single()
        hop("dw.agg_daily_route gross / net / paid / due", "dw" to listOf("gross_mtk", "net_mtk", "paid_mtk", "due_mtk").map { (ar[it] as Number).toLong() }, "device" to listOf(active.sumOf { it.gross }, active.sumOf { it.net }, active.sumOf { it.paid }, active.sumOf { it.due }))
        hop("the route-day is planned", "planned" to ar["planned"], "expected" to true)
        hop("dw.v_daily_sr: the stand-in's gross, sr1001 nothing", "stand-in/sr1001" to "${sqlLong("SELECT coalesce(sum(s.gross_mtk), 0) FROM dw.v_daily_sr s JOIN app.app_user u ON u.id = s.user_id WHERE s.business_date = DATE '$day' AND u.username = 'qacover1001'")}/${sqlLong("SELECT coalesce(sum(s.gross_mtk), 0) FROM dw.v_daily_sr s JOIN app.app_user u ON u.id = s.user_id WHERE s.business_date = DATE '$day' AND u.username = 'sr1001'")}", "device" to "${sr.truthGross}/0")
        hop("national sales cards (SUPERADMIN) by category, base units", "dashboard" to salesCards(login("superadmin1001", "web", null)), "device" to soldByCategory())
        val (l, t) = loginSubmit()
        hop("Successful Login counts the route once, through the stand-in's check-in (D-REPORTS-LOGIN-01)", "successful_login" to l, "expected" to loginBefore + 1)
        hop("target routes unchanged by the check-in and the sales", "target_routes" to t, "expected" to targetRoutesBefore)
    }
}

/**
 * A selling day the calendar calls off (docs/24 s4.9, D-DASH-06): the first day the seed plans GUL-SR-2 on (D-TEST-DATE-PIN-01) is an emergency off day for the division of Gulshan (division scope), a
 * day GUL-SR-2 would otherwise sell. The route is not due (planned false); sr1001 sells anyway, the sale is stored and the sales cards count it, but
 * CPR and Login/Submit leave the route out; the national working days ignore a division-scoped day and drop by one for a global holiday.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DataFlowTraceHolidayTest : DataFlowTraceScenario("GUL-SR-2") {
    override val traceName get() = "data-flow-trace-holiday"
    private var wd0 = 0L

    @Test
    @Order(1)
    fun theRouteIsNotDueOnAnEmergencyOffDay() {
        h("1 Emergency off day $day (division of Gulshan): the route is not due")
        wd0 = workingDays(day.take(7))
        db { hd -> hd.execute("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en, reason) SELECT DATE '$day', 'division', d.id, 'emergency_off', false, 'QA emergency off', 'qa trace' FROM app.division d WHERE d.code = 'D-DHK'") }
        val mask = sqlLong("SELECT visit_days_mask FROM app.route WHERE code = 'GUL-SR-2'")
        val bit = com.aktcl.aron.backend.platform.DayPlan.bit(java.time.LocalDate.parse(day).dayOfWeek)
        hop("GUL-SR-2 visits on the day's weekday (mask bit $bit)", "bit" to ((mask shr bit) and 1L), "expected" to 1L)
        prepareSrDay(routeCode = "GUL-SR-2")
        hop("the bundle does not plan any route today", "planned" to bundle.path("routes").filter { it.path("planned_today").asBoolean() }.map { it.path("route").path("code").asText() }, "expected" to emptyList<String>())
        hop("route-day planned flag", "planned" to sql("SELECT planned::text FROM app.route_day WHERE route_id = $gulRouteId AND business_date = DATE '$day'"), "expected" to "false")
    }

    @Test
    @Order(2)
    fun theSrSellsAnyway() {
        h("2 Emergency off day: sr1001 sells anyway, the sale is stored under the business date")
        val first = gulPins.getValue(outletByCode(route.path("outlets").map { it.path("code").asText() }.sorted().first()))
        val morning = listOf(
            envelope(sr, "day_open", uuid(), uuid(), 0, -30, json.readTree("""{"route_ids":[$gulRouteId],"online":true,"bundle_valid_for":"$day","offline_start":false}""")),
            envelope(sr, "attendance_event", uuid(), uuid(), 0, -20, json.readTree("""{"kind":"check_in","fix":${fix("attendance_in", first.first, first.second)}}""")),
        )
        sellAndUpload("holiday", morning)
        hop("the stored memos carry the date of the off day", "business dates" to sql("SELECT string_agg(DISTINCT business_date::text, ',') FROM app.memo m JOIN app.app_user u ON u.id = m.user_id WHERE u.username = 'sr1001' AND m.business_date >= DATE '$day'"), "expected" to day)
    }

    @Test
    @Order(3)
    fun cardsCountTheSaleAndCprLeavesTheRouteOut() {
        h("3 Emergency off day: cards, CPR, Login/Submit and the working days")
        runWorker()
        val sup = login("superadmin1001", "web", null)
        val ar = rows("SELECT planned, gross_mtk, active_memo_count FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").single()
        hop("dw.agg_daily_route: not planned, the sale is there", "planned/gross" to "${ar["planned"]}/${ar["gross_mtk"]}", "device" to "false/${sr.truthGross}")
        hop("national sales cards (SUPERADMIN) count the sale by category, base units", "dashboard" to salesCards(sup), "device" to soldByCategory())
        val cpr = jsonOf(postJson("/v1/dashboards/national/cpr", sup, """{"date":"$day"}"""))
        val callsOracle = sqlLong("SELECT coalesce(sum(a.successful_calls), 0) FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id WHERE a.business_date = DATE '$day' AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr'")
        val targetOracle = sqlLong("SELECT coalesce(sum(a.target_outlets), 0) FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id WHERE a.business_date = DATE '$day' AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr'")
        hop("CPR target outlets and successful calls count planned route-days only", "dashboard" to "${cpr.path("target_outlets").asInt()}/${cpr.path("successful_calls").asInt()}", "dw oracle (planned)" to "$targetOracle/$callsOracle")
        hop("the unplanned route's calls are not in CPR", "successful calls" to cpr.path("successful_calls").asInt().toLong(), "expected" to 0L)
        val ls = jsonOf(postJson("/v1/dashboards/national/login-submit", sup, """{"date":"$day"}"""))
        hop("Login/Submit: the unplanned route is no target and no Successful Login", "target/login" to "${ls.path("target_routes").asInt()}/${ls.path("successful_login").asInt()}", "dw oracle" to "${sqlLong("SELECT coalesce(count(*), 0) FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id WHERE a.business_date = DATE '$day' AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr'")}/0")
        val wdDivision = jsonOf(postJson("/v1/dashboards/national/sales", sup, """{"date":"$day"}""")).path("working_days").asLong()
        hop("a division-scoped off day does not change the national working days (D-DASH-06)", "working days" to wdDivision, "before the declaration" to wd0)
        // Another working day of the same month (not the weekend, no global entry yet), from the calendar, not a constant.
        val other = sql(
            """
            SELECT g.d::date::text FROM generate_series(date_trunc('month', DATE '$day'), date_trunc('month', DATE '$day') + interval '1 month - 1 day', interval '1 day') g(d)
             WHERE g.d::date <> DATE '$day' AND extract(isodow FROM g.d) <> 5
               AND NOT EXISTS (SELECT 1 FROM app.calendar_holiday c WHERE c.date = g.d::date AND c.scope_type = 'global' AND c.revoked_at IS NULL)
             ORDER BY 1 LIMIT 1
            """.trimIndent(),
        )!!
        db { hd -> hd.execute("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en, reason) VALUES (DATE '$other', 'global', 0, 'holiday', false, 'QA holiday', 'qa trace')") }
        val wdGlobal = jsonOf(postJson("/v1/dashboards/national/sales", sup, """{"date":"$day"}""")).path("working_days").asLong()
        hop("a global holiday lowers the working days of the month by one", "working days" to wdGlobal, "expected" to wd0 - 1)
    }
}

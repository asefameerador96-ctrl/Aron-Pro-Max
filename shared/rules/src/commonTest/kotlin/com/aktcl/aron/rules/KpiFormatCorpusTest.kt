package com.aktcl.aron.rules

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text of contract/fixtures/kpi-format-corpus.json (platform part: KpiCorpusSource.kt in jvmTest). */
internal expect fun kpiFormatCorpusText(): String

/**
 * Numbers everywhere (build contract s8 "numbers everywhere"; D-TSO-MAN-10): Percent, Qty, Targets, WorkingDays and
 * TicketMath against contract/fixtures/kpi-format-corpus.json, the corpus that tools/ci/kpi-format-corpus.py computes
 * with an independent Python Fraction oracle. web/tests/kpi-format-corpus.test.ts (lane W1) checks the web's formatters
 * against the same file, so the phone, the server and the web print the same text for the same integers.
 */
class KpiFormatCorpusTest {
    private val corpus = MiniJson.parse(kpiFormatCorpusText()).obj()
    private val locales = listOf(UiLocale.EN to "en", UiLocale.BN to "bn")

    private fun section(name: String): List<Map<String, Any?>> = corpus.getValue(name).arr().map { it.obj() }

    /** Runs [check] on every entry of [name] and fails once with every mismatch listed. */
    private fun each(name: String, check: (Map<String, Any?>, MutableList<String>) -> Unit) {
        val bad = ArrayList<String>()
        val entries = section(name)
        assertTrue(entries.isNotEmpty(), "corpus section $name is empty")
        for (e in entries) {
            val before = bad.size
            runCatching { check(e, bad) }.onFailure { bad += "threw ${it::class.simpleName}: ${it.message}" }
            for (i in before until bad.size) bad[i] = "$name ${e["note"]} ${bad[i]}"
        }
        assertTrue(bad.isEmpty(), "${bad.size} mismatches in $name:\n" + bad.take(25).joinToString("\n"))
    }

    private fun MutableList<String>.eq(what: String, actual: Any?, expected: Any?) {
        if (actual != expected) add("$what: got $actual, expected $expected")
    }

    private fun MutableList<String>.texts(what: String, byLocale: Any?, render: (UiLocale) -> String) {
        val m = byLocale.obj()
        for ((loc, key) in locales) eq("$what/$key", render(loc), m[key])
    }

    @Test
    fun corpusHolds300SeededCasesAndEveryContractFixture() {
        val counts = corpus.getValue("counts").obj()
        assertEquals(300L, counts["seeded"])
        val sections = listOf("percent", "qty", "sales_card", "tilldate", "target_card", "details", "remaining", "band",
            "working_days", "ticket_counts", "ticket_age")
        val all = sections.flatMap { section(it) }
        assertEquals(300, all.count { it["note"] == "seeded" })
        assertEquals(counts["fixtures"], all.count { it["note"] != "seeded" }.toLong())
        assertEquals(Percent.DASH, corpus["dash"])
        assertEquals(Qty.K_SUFFIX, corpus["k_suffix"])
        // A few fixtures the contract names, so a regenerated corpus cannot silently drop them.
        val notes = all.map { it["note"] as String }.toSet()
        for (n in listOf("s4.2 CPR 29/1186 (double rounding: never 2.5)", "s4.2 K boundary 19999/20 (999.95 rounds to 1,000.0)",
                "s4.3 Target card Cigarette (screenshots 39, 40)", "s4.4 Ticket Dashboard 723 / 0 / 0 / 18 (screenshot 11)",
                "s4.2 Working days zone holiday 2026-10-12: territory", "s4.4 age 23 h 59 m",
                "s4.2 Sales Match (screenshot 6)", "s4.1 A null prints a dash",
                "s4.1 W = 0: daily target and ring print a dash (R-F-13)",
                "s4.3 Target card Cigarette working basis 21/26: card 33114 = sum of item rows (R-F-01)",
                "s4.4 whole % once from the counts: 1 of 201 is 0%, never 1% (R-F-14)")) {
            assertTrue(n in notes, "fixture missing from the corpus: $n")
        }
    }

    @Test
    fun percentMatchesTheCorpus() = each("percent") { e, bad ->
        val n = e.long("n")!!; val d = e.long("d")!!
        bad.eq("whole", Percent.whole(n, d), e.long("whole"))
        bad.eq("tenths", Percent.tenths(n, d), e.long("tenths"))
        bad.eq("bp", Percent.bp(n, d), e.long("bp"))
        bad.eq("whole_capped", Targets.cardPct(n, d), e.long("whole_capped"))
        val t = e.getValue("text").obj()
        bad.texts("text.whole", t["whole"]) { Percent.wholeText(n, d, it) }
        bad.texts("text.tenths", t["tenths"]) { Percent.tenthsText(n, d, it) }
        bad.texts("text.bp", t["bp"]) { Percent.bpText(n, d, it) }
        bad.texts("text.tenths via Formats", t["tenths"]) { Formats.percent(Percent.tenths(n, d), 1, it) }
    }

    @Test
    fun qtyMatchesTheCorpus() = each("qty") { e, bad ->
        val n = e.long("n"); val d = e.long("d")!!
        bad.texts("text", e["text"]) { Qty.text(n, d, it) }
        if (d == 1L) bad.texts("text via Formats", e["text"]) { Formats.qty(n, it) }
    }

    @Test
    fun salesCardMatchesTheCorpus() = each("sales_card") { e, bad ->
        val a = e.long("a"); val t = e.long("t"); val w = e.long("w")!!
        bad.texts("total_sales", e["total_sales"]) { Qty.text(a, it) }
        bad.texts("total_target", e["total_target"]) { Qty.text(t, w, it) }
        val ring = if (a == null || t == null) null else Targets.dailyRingTenths(a, t, w)
        bad.eq("ring_tenths", ring, e.long("ring_tenths"))
        bad.texts("ring", e["ring"]) { Percent.text(ring, 1, it) }
    }

    @Test
    fun tillDateMatchesTheCorpus() = each("tilldate") { e, bad ->
        bad.eq("value", Targets.tillDate(e.long("t")!!, e.long("e")!!, e.long("n")!!), e.long("value"))
    }

    @Test
    fun targetCardsMatchTheCorpus() = each("target_card") { e, bad ->
        val items = e.getValue("items").arr().map { it as Long }
        val a = e.long("achieved")!!; val el = e.long("e")!!; val n = e.long("n")!!
        val bands = Bands(e.long("lo")!!, e.long("hi")!!)
        val monthly = items.sum()
        val till = items.sumOf { Targets.tillDate(it, el, n)!! }
        bad.eq("monthly_target", monthly, e.long("monthly_target"))
        bad.eq("tilldate_target", till, e.long("tilldate_target"))
        val mp = Targets.cardPct(a, monthly)!!; val tp = Targets.cardPct(a, till)!!
        bad.eq("monthly_pct", mp, e.long("monthly_pct"))
        bad.eq("tilldate_pct", tp, e.long("tilldate_pct"))
        bad.eq("monthly_band", Targets.band(mp, bands).name.lowercase(), e["monthly_band"])
        bad.eq("tilldate_band", Targets.band(tp, bands).name.lowercase(), e["tilldate_band"])
        bad.texts("monthly_text", e["monthly_text"]) { Percent.text(mp, 0, it) }
        bad.texts("tilldate_text", e["tilldate_text"]) { Percent.text(tp, 0, it) }
    }

    @Test
    fun detailsRowsMatchTheCorpus() = each("details") { e, bad ->
        val a = e.long("a")!!; val t = e.long("t")!!
        bad.eq("bp", Targets.detailsBp(a, t), e.long("bp"))
        bad.texts("text", e["text"]) { Percent.text(Targets.detailsBp(a, t), 2, it) }
        bad.eq("remaining", Targets.remaining(t, a), e.long("remaining"))
    }

    @Test
    fun remainingMatchesTheCorpus() = each("remaining") { e, bad ->
        bad.eq("value", Targets.remaining(e.long("t")!!, e.long("a")!!), e.long("value"))
    }

    @Test
    fun bandsMatchTheCorpus() = each("band") { e, bad ->
        bad.eq("band", Targets.band(e.long("p")!!, Bands(e.long("lo")!!, e.long("hi")!!)).name.lowercase(), e["band"])
    }

    @Test
    fun workingDaysMatchTheCorpus() = each("working_days") { e, bad ->
        val (y, m) = (e["month"] as String).split("-").map { it.toInt() }
        val weekend = WorkingDays.weekendOf(e.getValue("weekend").arr().map { (it as Long).toInt() })
        val node = e.getValue("node").obj()
        val path = NodePath(node.long("wing_id"), node.long("division_id"), node.long("territory_id"), node.long("zone_id"))
        val marks = e.getValue("marks").arr().map { it.obj() }.map {
            CalendarMark(LocalDate.parse(it["date"] as String), CalendarLevel.fromWire(it["level"] as String)!!,
                it.long("scope_id")!!, DayKind.fromWire(it["kind"] as String)!!)
        }
        val cal = WorkingDays.resolve(path, weekend, marks)
        bad.eq("month_days", WorkingDays.month(YearMonth(y, m), cal).toLong(), e.long("month_days"))
        val day = e["day"] as String?
        if (day != null) bad.eq("elapsed_days", WorkingDays.elapsed(LocalDate.parse(day), cal).toLong(), e.long("elapsed_days"))
    }

    @Test
    fun ticketCountsMatchTheCorpus() = each("ticket_counts") { e, bad ->
        val statuses = listOf(TicketMath.NEW to "new", TicketMath.IN_PROGRESS to "in_progress", TicketMath.RESOLVED to "resolved",
            TicketMath.CLOSED to "closed", TicketMath.CANCELLED to "cancelled")
            .flatMap { (code, key) -> List(e.long(key)!!.toInt()) { code } }
        val c = TicketMath.counts(statuses)
        bad.eq("total", c.total, e.long("total"))
        bad.eq("open", c.open, e.long("open"))
        bad.eq("resolved_pct", c.resolvedPct, e.long("resolved_pct"))
        bad.eq("open_pct", c.openPct, e.long("open_pct"))
        bad.texts("resolved_text", e["resolved_text"]) { c.resolvedPctText(it) }
        bad.texts("open_text", e["open_text"]) { c.openPctText(it) }
    }

    @Test
    fun ticketAgesMatchTheCorpus() = each("ticket_age") { e, bad ->
        bad.texts("text", e["text"]) { TicketMath.ageText(e.long("now_ms")!!, e.long("captured_ms")!!, it) }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Any?.arr(): List<Any?> = this as List<Any?>

private fun Map<String, Any?>.long(key: String): Long? = this[key] as Long?

/**
 * A minimal JSON reader for the corpus (objects, arrays, strings with escapes, integers, true, false, null): shared:rules
 * has no JSON library and the corpus needs none. A fraction or exponent is rejected, because the corpus holds integers only.
 */
private class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; v }
    }

    private fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

    private fun value(): Any? {
        ws()
        require(i < s.length) { "unexpected end" }
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c in '0'..'9') num() else error("unexpected '$c' at $i")
        }
    }

    private fun word(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length; return v }

    private fun num(): Long {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        require(i == s.length || s[i] !in ".eE") { "the corpus holds integers only (at $start)" }
        return s.substring(start, i).toLong()
    }

    private fun str(): String {
        i++
        val b = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return b.toString()
                '\\' -> when (val e = s[i++]) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("bad escape \\$e at $i")
                }
                else -> b.append(c)
            }
        }
    }

    private fun arr(): List<Any?> {
        i++
        val out = ArrayList<Any?>()
        ws()
        if (s[i] == ']') { i++; return out }
        while (true) {
            out += value(); ws()
            when (s[i++]) { ',' -> continue; ']' -> return out; else -> error("expected , or ] at ${i - 1}") }
        }
    }

    private fun obj(): Map<String, Any?> {
        i++
        val out = LinkedHashMap<String, Any?>()
        ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws(); require(s[i] == '"') { "expected a key at $i" }
            val k = str(); ws()
            require(s[i++] == ':') { "expected : at ${i - 1}" }
            out[k] = value(); ws()
            when (s[i++]) { ',' -> continue; '}' -> return out; else -> error("expected , or } at ${i - 1}") }
        }
    }
}

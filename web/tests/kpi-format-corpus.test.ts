// Numbers gate (D-TSO-MAN-10, build contract docs/requests/tso-manual-build-2026-10.md s4; lane W1): the web's KPI formatter
// (src/lib/national/fmt.ts) against contract/fixtures/kpi-format-corpus.json, which tools/ci/kpi-format-corpus.py writes from an
// independent Python-integer oracle and shared/rules KpiFormatCorpusTest reads for the phone and the server. Every entry runs in
// en and bn, so the phone, the server and the web print the same text for the same integers (s8 "numbers everywhere").
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import type { Locale } from "@/lib/i18n";
import { band, cardPct, DASH, K_SUFFIX, pctBp, pctBpOf, pctTenths, pctText, pctWhole, qtyText, remaining, salesCard, targetCard, ticketAge, ticketCounts, tillDate } from "@/lib/national/fmt";

type Text = { en: string; bn: string };
interface Corpus {
  dash: string;
  k_suffix: string;
  counts: { seeded: number; fixtures: number };
  percent: { n: number; d: number; whole: number | null; tenths: number | null; bp: number | null; whole_capped: number | null; text: { whole: Text; tenths: Text; bp: Text }; note: string }[];
  qty: { n: number | null; d: number; text: Text; note: string }[];
  sales_card: { a: number | null; t: number | null; w: number; total_sales: Text; total_target: Text; ring_tenths: number | null; ring: Text; note: string }[];
  tilldate: { t: number; e: number; n: number; value: number | null; note: string }[];
  target_card: { items: number[]; achieved: number; e: number; n: number; lo: number; hi: number; monthly_target: number; tilldate_target: number; monthly_pct: number; tilldate_pct: number; monthly_band: string; tilldate_band: string; monthly_text: Text; tilldate_text: Text; note: string }[];
  details: { a: number; t: number; bp: number; text: Text; remaining: number; note: string }[];
  remaining: { t: number; a: number; value: number; note: string }[];
  band: { p: number; lo: number; hi: number; band: string; note: string }[];
  working_days: unknown[];
  ticket_counts: { new: number; in_progress: number; resolved: number; closed: number; cancelled: number; total: number; open: number; resolved_pct: number; open_pct: number; resolved_text: Text; open_text: Text; note: string }[];
  ticket_age: { now_ms: number; captured_ms: number; text: Text; note: string }[];
}

const corpus = JSON.parse(readFileSync(resolve(__dirname, "../../contract/fixtures/kpi-format-corpus.json"), "utf8")) as Corpus;
const LOCALES: Locale[] = ["en", "bn"];
const BENGALI_DIGIT = /[০-৯]/;
const LATIN_DIGIT = /[0-9]/;

/** Sections the web formats, and the one it does not: working days are counted by the server (WorkingDays, D-TSO-MAN-08) and
 *  arrive as `working_days` / `elapsed_days` / `period_days`; the web only divides by them. A new corpus section fails here until
 *  it is checked or listed. */
const CHECKED = ["percent", "qty", "sales_card", "tilldate", "target_card", "details", "remaining", "band", "ticket_counts", "ticket_age"];
const NOT_A_WEB_FORMATTER = ["working_days"];

describe("kpi format corpus: shape", () => {
  it("every section of the corpus is checked here (or named as not a web formatter)", () => {
    const sections = Object.keys(corpus).filter((k) => Array.isArray((corpus as unknown as Record<string, unknown>)[k]));
    expect(sections.sort()).toEqual([...CHECKED, ...NOT_A_WEB_FORMATTER].sort());
    for (const s of CHECKED) expect((corpus as unknown as Record<string, unknown[]>)[s]!.length, s).toBeGreaterThan(0);
  });
  it("the dash and the K suffix are the corpus's", () => {
    expect(DASH).toBe(corpus.dash);
    expect(K_SUFFIX).toBe(corpus.k_suffix);
  });
  it("holds the 300 seeded cases and the contract fixtures", () => {
    expect(corpus.counts.seeded).toBe(300);
    const all = CHECKED.flatMap((s) => (corpus as unknown as Record<string, { note: string }[]>)[s]!);
    expect(all.filter((e) => e.note === "seeded").length).toBeGreaterThan(0);
    expect(all.filter((e) => e.note !== "seeded").length).toBeGreaterThan(50);
  });
});

describe("kpi format corpus: percent (whole, tenths, bp; d = 0 prints a dash)", () => {
  it.each(corpus.percent.map((e) => [e.note, e] as const))("%s", (_note, e) => {
    expect(pctWhole(e.n, e.d)).toBe(e.whole);
    expect(pctTenths(e.n, e.d)).toBe(e.tenths);
    expect(pctBpOf(e.n, e.d)).toBe(e.bp);
    expect(cardPct(e.n, e.d)).toBe(e.whole_capped);
    for (const l of LOCALES) {
      expect(pctText(l, pctWhole(e.n, e.d), 0)).toBe(e.text.whole[l]);
      expect(pctText(l, pctTenths(e.n, e.d), 1)).toBe(e.text.tenths[l]);
      expect(pctText(l, pctBpOf(e.n, e.d), 2)).toBe(e.text.bp[l]);
      // a figure the server already sends as bp prints the same at 2 dp (pctBp from bp only at the target precision)
      expect(pctBp(l, e.bp, 2)).toBe(e.text.bp[l]);
    }
  });
});

describe("kpi format corpus: qty (one decimal, K from 1,000.0, null and d = 0 print a dash)", () => {
  it.each(corpus.qty.map((e) => [e.note, e] as const))("%s", (_note, e) => {
    for (const l of LOCALES) expect(qtyText(l, e.n, e.d)).toBe(e.text[l]);
  });
});

describe("kpi format corpus: sales card (W = 0 and a missing A or T print a dash, R-F-13)", () => {
  it.each(corpus.sales_card.map((e) => [e.note, e] as const))("%s", (_note, e) => {
    for (const l of LOCALES) {
      const c = salesCard(l, e.a, e.t, e.w);
      expect(c.totalSales).toBe(e.total_sales[l]);
      expect(c.totalTarget).toBe(e.total_target[l]);
      expect(c.ringTenths).toBe(e.ring_tenths);
      expect(c.ring).toBe(e.ring[l]);
    }
  });
});

describe("kpi format corpus: targets", () => {
  it.each(corpus.tilldate.map((e) => [e.note, e] as const))("till-date %s", (_note, e) => {
    expect(tillDate(e.t, e.e, e.n)).toBe(e.value);
  });
  it.each(corpus.target_card.map((e) => [e.note, e] as const))("card %s", (_note, e) => {
    const c = targetCard(e.items, e.achieved, e.e, e.n, e.lo, e.hi);
    expect(c.monthlyTarget).toBe(e.monthly_target);
    expect(c.tillDateTarget).toBe(e.tilldate_target);
    expect(c.monthlyPct).toBe(e.monthly_pct);
    expect(c.tillDatePct).toBe(e.tilldate_pct);
    expect(c.monthlyBand).toBe(e.monthly_band);
    expect(c.tillDateBand).toBe(e.tilldate_band);
    for (const l of LOCALES) {
      expect(pctText(l, c.monthlyPct, 0)).toBe(e.monthly_text[l]);
      expect(pctText(l, c.tillDatePct, 0)).toBe(e.tilldate_text[l]);
    }
  });
  it.each(corpus.details.map((e) => [e.note, e] as const))("details %s", (_note, e) => {
    expect(pctBpOf(e.a, e.t)).toBe(e.bp);
    expect(remaining(e.t, e.a)).toBe(e.remaining);
    for (const l of LOCALES) expect(pctText(l, pctBpOf(e.a, e.t), 2)).toBe(e.text[l]);
  });
  it.each(corpus.remaining.map((e) => [e.note, e] as const))("remaining %s", (_note, e) => {
    expect(remaining(e.t, e.a)).toBe(e.value);
  });
  it.each(corpus.band.map((e) => [e.note, e] as const))("band %s", (_note, e) => {
    expect(band(e.p, e.lo, e.hi)).toBe(e.band);
  });
});

describe("kpi format corpus: tickets (percent once from the counts, R-F-14; age, D-TSO-MAN-25)", () => {
  it.each(corpus.ticket_counts.map((e) => [e.note, e] as const))("counts %s", (_note, e) => {
    const c = ticketCounts(e);
    expect(c.total).toBe(e.total);
    expect(c.open).toBe(e.open);
    expect(c.resolvedPct).toBe(e.resolved_pct);
    expect(c.openPct).toBe(e.open_pct);
    for (const l of LOCALES) {
      expect(pctText(l, c.resolvedPct, 0)).toBe(e.resolved_text[l]);
      expect(pctText(l, c.openPct, 0)).toBe(e.open_text[l]);
    }
  });
  it.each(corpus.ticket_age.map((e) => [e.note, e] as const))("age %s", (_note, e) => {
    for (const l of LOCALES) expect(ticketAge(l, e.now_ms, e.captured_ms)).toBe(e.text[l]);
  });
});

describe("kpi format corpus: the rules the build contract names", () => {
  it("W = 0: the daily target and the ring print a dash, Total Sales still prints (R-F-13)", () => {
    const e = corpus.sales_card.find((x) => x.w === 0);
    expect(e, "the corpus pins a W = 0 case").toBeDefined();
    for (const l of LOCALES) {
      const c = salesCard(l, e!.a, e!.t, 0);
      expect([c.totalTarget, c.ring]).toEqual([DASH, DASH]);
      expect(c.totalSales).not.toBe(DASH);
    }
  });
  it("A null prints a dash for Total Sales and the ring", () => {
    const e = corpus.sales_card.find((x) => x.a === null);
    expect(e).toBeDefined();
    for (const l of LOCALES) expect(salesCard(l, null, e!.t, e!.w)).toMatchObject({ totalSales: DASH, ring: DASH, ringTenths: null });
  });
  it("K boundary: 999 -> 999.0, 19999/20 -> 1.0 K, 1234567 -> 1,234.6 K", () => {
    expect(qtyText("en", 999)).toBe("999.0");
    expect(qtyText("en", 19999, 20)).toBe("1.0 K");
    expect(qtyText("en", 1234567)).toBe("1,234.6 K");
    expect(qtyText("bn", 1234567)).toBe("১,২৩৪.৬ K");
  });
  it("Bengali digits in Bangla, Latin in English; K, %, comma and point stay Latin", () => {
    for (const e of corpus.qty.filter((x) => x.n !== null && x.d > 0)) {
      expect(BENGALI_DIGIT.test(qtyText("en", e.n, e.d)), e.note).toBe(false);
      expect(LATIN_DIGIT.test(qtyText("bn", e.n, e.d)), e.note).toBe(false);
    }
    expect(pctText("bn", 10494, 2)).toBe("১০৪.৯৪%");
    expect(pctText("bn", 31_000_000_000_000, 0)).toBe("৩১,০০০,০০০,০০০,০০০%"); // Western grouping, never lakh
  });
  it("one rounding per figure: 29 of 1,186 is 2.4%, never the 2.5% of 245 bp rounded again", () => {
    expect(pctText("en", pctTenths(29, 1186), 1)).toBe("2.4%");
    expect(pctBp("en", pctBpOf(29, 1186), 1)).toBe("2.5%"); // what a bp-only card would print: why cards with integers use pctTenths
  });
  it("a bad input prints a dash, never a wrong figure", () => {
    expect(pctWhole(-1, 5)).toBeNull();
    expect(pctTenths(1.5, 5)).toBeNull();
    expect(qtyText("en", Number.MAX_SAFE_INTEGER + 2)).toBe(DASH);
    expect(pctText("en", null, 1)).toBe(DASH);
  });
});

// tso-manual W1 (build contract s5 W1 "tso-daily-tracking shows the till-date %"; B2 DailyTrackingApi till-date %): each route of
// the TSO Daily Tracking page shows its till-date achievement at the server's two decimals, never rounded a second time, "—"
// while the server sends none, Bengali digits in Bangla.
// tso-manual X3 (R-W-13 (4), R-W-06 (2)): the figure now prints from the integer tilldate_target_achievement_bp at two decimals
// (pctBp(bp, 2), the corpus formatter); the float member is read only when the bp member is absent (an older server). The same
// figure in the Daily Tracking table's Achievement column. Pinned with the corpus's s4.2 / s4.3 fixtures in en and bn.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as ServerMod from "@/lib/dash/server";
import { TrackingTable } from "@/components/dash/tracking";
import { pct2dpText, pctBp, pctBpOf, pctText, tillDatePctText } from "@/lib/national/fmt";

vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", scope: { nodes: [] }, user: { role: "TSO" } }) }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => locale.v }));
const stub = vi.hoisted(() => ({ tracking: vi.fn() }));
vi.mock("@/lib/dash/server", async (orig) => ({ ...(await orig<typeof ServerMod>()), getDailyTracking: stub.tracking }));

import Page from "@/app/(dashboards)/tso-daily-tracking/page";

const row = (route_id: number, pct: number | null | undefined, bp?: number | null) => ({ route_id, route_name: `R${route_id}`, zone_id: 1, user_id: 7, user_name: "Rahim", state: "in_field" as const, target_outlets: 40, visited_outlets: 35, successful_calls: 14, active_memo_count: 14, net_mtk: 1, bucket: "from_80" as const, ...(pct === undefined ? {} : { tilldate_target_achievement_pct: pct }), ...(bp === undefined ? {} : { tilldate_target_achievement_bp: bp }) });
const render = async () => renderToStaticMarkup((await Page({ searchParams: Promise.resolve({}) })) as ReactElement);
const cell = (m: string, id: number) => /data-testid="tilldate-(\d+)"[^>]*>(.*?)<\/span>/g.exec(m.slice(m.indexOf(`tilldate-${id}"`) - 20))?.[2]?.replace(/<!-- -->/g, "");

beforeEach(() => {
  locale.v = "en";
  stub.tracking.mockResolvedValue({ ok: true, status: 200, data: { as_of: "2026-10-29T06:00:00Z", business_date: "2026-10-29", items: [row(1, 87.5), row(2, 104.94), row(3, null), row(4, undefined)], next_cursor: null } });
});

describe("the till-date % per route", () => {
  it("prints the server's two decimals as they are", () => {
    expect(pct2dpText("en", 87.5)).toBe("87.50%");
    expect(pct2dpText("en", 104.94)).toBe("104.94%");
    expect(pct2dpText("en", 2.45)).toBe("2.45%"); // not 2.5 (no second rounding to one decimal)
    expect(pct2dpText("en", 1234.5)).toBe("1,234.50%");
    expect(pct2dpText("en", null)).toBe("—");
    expect(pct2dpText("en", Number.NaN)).toBe("—");
    expect(pct2dpText("bn", 87.5)).toBe("৮৭.৫০%");
  });
  // status ask 8: until 1.10.x sends integer members (A and T', or the bp), the page depends on this wire form
  it("pins B2's wire form (exactly bp / 100): the page prints what the corpus formatter prints from the integers", () => {
    // DailyTrackingApi (tso-man/B2): Percent.bp(A, T') then BigDecimal.valueOf(bp, 2).toDouble(); the JSON number is that double
    const wire = (bp: number) => Number(`${Math.trunc(bp / 100)}.${String(bp % 100).padStart(2, "0")}`);
    for (let bp = 0; bp <= 1_000_000; bp += 7) expect(pct2dpText("en", wire(bp)), String(bp)).toBe(pctText("en", bp, 2));
    for (const bp of [0, 1, 5, 49, 50, 99, 100, 101, 4342, 10494, 999_999]) expect(pct2dpText("bn", wire(bp))).toBe(pctText("bn", bp, 2));
    // s4.3 fixtures through A and T': 350/806 -> 43.42 %, 85/81 -> 104.94 %, 20/8065 -> 0.25 %
    for (const [a, t] of [[350, 806], [85, 81], [20, 8065], [1030, 33064]] as const) expect(pct2dpText("en", wire(pctBpOf(a, t)!))).toBe(pctText("en", pctBpOf(a, t), 2));
    expect(pct2dpText("en", wire(pctBpOf(350, 806)!))).toBe("43.42%");
  });
  it("each route line shows visited / target and the till-date %; a route without one shows a dash", async () => {
    const m = await render();
    expect(cell(m, 1)).toBe("35/40 · Till-date 87.50%");
    expect(cell(m, 2)).toBe("35/40 · Till-date 104.94%");
    expect(cell(m, 3)).toBe("35/40 · Till-date —");
    expect(cell(m, 4)).toBe("35/40 · Till-date —");
  });
  it("bn: the label and the digits in Bangla", async () => {
    locale.v = "bn";
    expect(cell(await render(), 1)).toBe("৩৫/৪০ · আজ পর্যন্ত ৮৭.৫০%");
  });
});

// ------------------------------------------------------------------------------------------------------------- tso-manual X3
type Corpus = { percent: { n: number; d: number; bp: number | null; text: { bp: { en: string; bn: string } }; note?: string }[]; details: { a: number; t: number; bp: number; text: { en: string; bn: string }; note?: string }[] };
const corpus = JSON.parse(readFileSync(resolve(__dirname, "../../contract/fixtures/kpi-format-corpus.json"), "utf8")) as Corpus;
const fixtures = [
  ...corpus.percent.filter((e) => e.note?.startsWith("s4.2") && e.bp !== null).map((e) => ({ bp: e.bp!, en: e.text.bp.en, bn: e.text.bp.bn, note: e.note! })),
  ...corpus.details.map((e) => ({ bp: e.bp, en: e.text.en, bn: e.text.bn, note: e.note ?? "details" })),
];

describe("the till-date % from tilldate_target_achievement_bp (R-W-13 (4))", () => {
  it("the corpus has the s4.2 and s4.3 fixtures this pins", () => {
    expect(fixtures.filter((x) => x.note.startsWith("s4.2")).length).toBeGreaterThanOrEqual(5);
    expect(fixtures.some((x) => x.bp === 10494 && x.en === "104.94%")).toBe(true); // s4.3 85 / 81
  });
  it("prints the bp at two decimals exactly as the corpus does, en and bn; the float member is ignored when the bp is sent", () => {
    for (const x of fixtures) {
      expect(tillDatePctText("en", { tilldate_target_achievement_bp: x.bp, tilldate_target_achievement_pct: 999.99 }), x.note).toBe(x.en);
      expect(tillDatePctText("bn", { tilldate_target_achievement_bp: x.bp }), x.note).toBe(x.bn);
      expect(pctBp("en", x.bp, 2), x.note).toBe(x.en);
    }
  });
  it("a sent null bp prints a dash, also when a float is present; only an absent bp falls back to the float", () => {
    expect(tillDatePctText("en", { tilldate_target_achievement_bp: null, tilldate_target_achievement_pct: 87.5 })).toBe("—");
    expect(tillDatePctText("en", { tilldate_target_achievement_pct: 87.5 })).toBe("87.50%");
    expect(tillDatePctText("bn", { tilldate_target_achievement_pct: 87.5 })).toBe("৮৭.৫০%");
    expect(tillDatePctText("en", {})).toBe("—");
  });
  it("no second rounding: 4342 bp is 43.42% (one decimal would say 43.4%), 245 bp is 2.45%", () => {
    expect(tillDatePctText("en", { tilldate_target_achievement_bp: pctBpOf(350, 806) })).toBe("43.42%");
    expect(tillDatePctText("en", { tilldate_target_achievement_bp: 245 })).toBe("2.45%");
    expect(tillDatePctText("en", { tilldate_target_achievement_bp: 123_456 })).toBe("1,234.56%"); // uncapped, grouped
  });
  it("the TSO Daily Tracking page prints the bp; a route from an older server still prints its float", async () => {
    stub.tracking.mockResolvedValue({ ok: true, status: 200, data: { as_of: "2026-10-29T06:00:00Z", business_date: "2026-10-29", items: [row(1, 43.42, 4342), row(2, 999.99, 10494), row(3, 87.5, null), row(4, 87.5)], next_cursor: null } });
    const m = await render();
    expect(cell(m, 1)).toBe("35/40 · Till-date 43.42%");
    expect(cell(m, 2)).toBe("35/40 · Till-date 104.94%"); // from the bp, not the float beside it
    expect(cell(m, 3)).toBe("35/40 · Till-date —");
    expect(cell(m, 4)).toBe("35/40 · Till-date 87.50%"); // no bp member: the fallback
    locale.v = "bn";
    expect(cell(await render(), 2)).toBe("৩৫/৪০ · আজ পর্যন্ত ১০৪.৯৪%");
  });
  it("the Daily Tracking table's Achievement column is the same figure at two decimals (was the float at one)", () => {
    const items = [row(1, 43.42, 4342), row(2, 2.45, 245), row(3, null, null), row(4, 87.5)];
    const ach = (m: string, id: number) => new RegExp(`data-testid="achievement-${id}">([^<]*)<`).exec(m)?.[1];
    const en = renderToStaticMarkup(<TrackingTable locale="en" items={items} businessDate="2026-10-29" canAct={false} />);
    expect([1, 2, 3, 4].map((id) => ach(en, id))).toEqual(["43.42%", "2.45%", "—", "87.50%"]);
    const bn = renderToStaticMarkup(<TrackingTable locale="bn" items={items} businessDate="2026-10-29" canAct={false} />);
    expect(ach(bn, 2)).toBe("২.৪৫%");
  });
});

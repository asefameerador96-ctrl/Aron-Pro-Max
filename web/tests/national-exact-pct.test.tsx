// tso-manual W1, fix round (build contract s5 W1 "Web percentages from integers through the corpus", D-TSO-MAN-10, s4.2,
// s8 "numbers everywhere"): the national dashboard's rings and the route map print each percentage ONCE from the integers the
// payload carries (pctTenths / pctWhole / dailyRingTenths of fmt.ts, the corpus-checked formatters), never from a bp that was
// already rounded. 29 of 1,186 is 2.445 %: the phone and the web print 2.4 %, where the bp 245 at one decimal would print 2.5 %.
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { ChannelCallsCard, ChannelStdCard, CprCard, FinalSubmitCard, LoginSubmitCard, SalesCards } from "@/components/national/cards";
import { channelShareText, pctBp, pctBpOf, pctText, pctWhole, pctWholeOverDecimal3, pctWholeSalesCard, routeBarText } from "@/lib/national/fmt";
import type { ApiOutcome } from "@/lib/api/client";
import type { ChannelSlice, CprSlice, FinalSubmitSlice, LoginSubmitSlice, RouteMapSlice, SalesSlice } from "@/lib/national/types";
import { html } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {} }), notFound() { throw new Error("notFound"); } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", scope: null, user: { role: "NATIONAL" } }) }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => pageLocale.v }));
const routeMap = vi.hoisted(() => ({ current: null as unknown }));
const pageLocale = vi.hoisted(() => ({ v: "en" as "en" | "bn" })); // tso-manual X3: the route map page in bn
vi.mock("@/lib/national/server", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getRouteMap: async () => routeMap.current }));

const stamp = { business_date: "2026-10-29", as_of: "2026-10-29T06:00:00Z" };
const ok = <T,>(data: T): ApiOutcome<T> => ({ ok: true, status: 200, data, response: new Response(null) }) as ApiOutcome<T>;
/** The text of the element carrying this test id: the ring's aria-label ends with its text. */
const ringText = (m: string, id: string) => new RegExp(`aria-label="[^"]*: ([^"]*)" data-testid="${id}"`).exec(m)?.[1];
const pctOf = (m: string, id: string) => new RegExp(`data-testid="${id}-pct">([^<]*)<`).exec(m)?.[1];

describe("the bp would round twice: the cards use the counts", () => {
  it("29 of 1,186: the bp is 245 (2.45 %), one decimal from it is 2.5 %, from the counts 2.4 %", () => {
    expect(pctBpOf(29, 1186)).toBe(245);
    expect(pctBp("en", 245)).toBe("2.5%");
    expect(pctText("en", pctWhole(29, 1186), 0)).toBe("2%");
  });
});

describe("rendered national cards", () => {
  it("Live CPR: 29 calls of 1,186 target outlets prints 2.4% (the server's bp 245 only draws the ring)", () => {
    const d: CprSlice = { ...stamp, target_outlets: 1186, successful_calls: 29, cpr_pct_bp: 245 };
    const m = html(<CprCard slot={ok(d)} locale="en" />);
    expect(ringText(m, "cpr-ring")).toBe("2.4%");
    expect(m).toContain('data-bp="245"');
    expect(ringText(html(<CprCard slot={ok(d)} locale="bn" />, "bn"), "cpr-ring")).toBe("২.৪%");
    expect(ringText(html(<CprCard slot={ok({ ...d, target_outlets: 0, successful_calls: 0, cpr_pct_bp: null })} locale="en" />), "cpr-ring")).toBe("—");
  });
  it("Final Submit: 29 of 1,186 service zones prints 2.4%", () => {
    const d: FinalSubmitSlice = { ...stamp, total_zone: 1200, service_zone: 1186, submitted: 29, submit_pct_bp: 245 };
    expect(ringText(html(<FinalSubmitCard slot={ok(d)} locale="en" />), "final-ring")).toBe("2.4%");
  });
  it("Login / Submit: login = logged in of target routes, submit = Bikroy Joma of logged in, each from the counts", () => {
    const d: LoginSubmitSlice = { ...stamp, target_routes: 1186, logged_in: 29, submitted: 1, login_pct_bp: 245, submit_pct_bp: 345 };
    const m = html(<LoginSubmitCard slot={ok(d)} locale="en" />);
    expect(pctOf(m, "login-status")).toBe("2.4%");
    expect(pctOf(m, "submit-status")).toBe("3.4%"); // 1 of 29 = 3.448 %: the bp 345 at one decimal would be 3.5 %
  });
  it("Sales: ring tenths(total x W, monthly target) (s4.2); W = 0 or no target prints a dash (R-F-13)", () => {
    const card = { unit: "stick" as const, daily_target: 1186, remaining: 1157 };
    const d: SalesSlice = {
      ...stamp,
      working_days: 26,
      cards: [
        { category: "cigarette", ...card, total: 29, pct_bp: 245, monthly_target_qty: 1186 * 26 }, // 29 x 26 / 30,836 = 2.445 %
        { category: "bidi", ...card, total: 2991, pct_bp: 28760, monthly_target_qty: 26000 * 26 / 25 }, // s4.2 fixture scaled to W 26
        { category: "lighter", ...card, total: 0, pct_bp: 0, monthly_target_qty: 100 },
        { category: "match", ...card, total: 5, pct_bp: null, daily_target: null, remaining: null, monthly_target_qty: null },
      ],
    };
    const m = html(<SalesCards slot={ok(d)} locale="en" />);
    expect(ringText(m, "sales-ring-cigarette")).toBe("2.4%");
    expect(ringText(m, "sales-ring-bidi")).toBe("287.6%");
    expect(ringText(m, "sales-ring-lighter")).toBe("0.0%");
    expect(ringText(m, "sales-ring-match")).toBe("—");
    const noDay = html(<SalesCards slot={ok({ ...d, working_days: 0 })} locale="en" />);
    expect(ringText(noDay, "sales-ring-cigarette")).toBe("—");
  });
  it("Sales from a server without monthly_target_qty (before 1.10.0): the bp at one decimal, as before", () => {
    const d: SalesSlice = { ...stamp, cards: [{ category: "cigarette", unit: "stick", total: 29, daily_target: 1186, pct_bp: 245, remaining: 1157 }] };
    expect(ringText(html(<SalesCards slot={ok(d)} locale="en" />), "sales-ring-cigarette")).toBe("2.5%");
  });
});

describe("route map (Map Details): whole percent rounded once", () => {
  it("a bar is sales over the Decimal3 daily target, read exactly", () => {
    expect(pctWholeOverDecimal3(50, "2003.000")).toBe(2); // 2.496 %: the bp 250 at no decimal would print 3 %
    expect(pctWholeOverDecimal3(34, "1680.000")).toBe(2);
    expect(pctWholeOverDecimal3(1, "0.269")).toBe(372); // a small match target: 371.75 %
    expect(pctWholeOverDecimal3(3, "4")).toBe(75);
    expect(pctWholeOverDecimal3(3, "4.5")).toBe(67);
    expect(pctWholeOverDecimal3(0, "0.000")).toBeNull();
    expect(pctWholeOverDecimal3(5, null)).toBeNull();
    expect(pctWholeOverDecimal3(5, "1e3")).toBeNull();
    expect(pctWholeOverDecimal3(-1, "10.000")).toBeNull();
  });

  const slice = (o: Partial<RouteMapSlice> = {}): RouteMapSlice => ({
    ...stamp, sr_name: "SR - Testing Banani", territory_code: "T-334", territory_name: "Banani", zone: "Banani - Test", route: "Apsis Route",
    start: null, last_position: null, path: [], outlets: [], last_sale_place: null,
    target_vs_achievement: [
      { category: "cigarette", pct_bp: 250, sales: 50, daily_target: "2003.000" },
      { category: "bidi", pct_bp: null, sales: 0, daily_target: null },
      { category: "match", pct_bp: 37175, sales: 1, daily_target: "0.269" },
      { category: "lighter", pct_bp: 250 }, // an answer without the integers: the bp, as before
    ],
    cpr: { target_outlets: 2003, successful_calls: 50, geo_validation: 40, cpr_pct_bp: 250 },
    time: { first_sale: null, last_sale: null, total_hours: null, avg_hours: null, distance_m: 0 },
    ...o,
  });
  const render = async () => {
    const Page = (await import("@/app/(dashboards)/dashboard/route-map/[routeId]/page")).default;
    return renderToStaticMarkup((await Page({ params: Promise.resolve({ routeId: "10" }), searchParams: Promise.resolve({}) })) as ReactElement);
  };
  const bar = (m: string, cat: string) => new RegExp(`data-testid="tva-${cat}"[\\s\\S]*?tabular-nums">([^<]*)<`).exec(m)?.[1];

  it("bars and the CPR ring print from the payload's integers; the bp still draws them", async () => {
    routeMap.current = ok(slice());
    const m = await render();
    expect(bar(m, "cigarette")).toBe("2%");
    expect(bar(m, "bidi")).toBe("—");
    expect(bar(m, "match")).toBe("372%");
    expect(bar(m, "lighter")).toBe("3%");
    expect(ringText(m, "route-cpr-ring")).toBe("2%"); // 50 of 2,003 = 2.496 %: never the 3 % of the bp 250
    expect(m).toContain('data-bp="250"');
  });
});

// ------------------------------------------------------------------------------------------------------------- tso-manual X3
// R-W-13 (5): a route-map bar prints by the Sales-card rule from monthly_target_qty and working_days (contract 1.10.0, R-W-06 (3)),
// one rounding, BigInt inside; W1's sales-over-Decimal3-daily-target path and the bp stay as the fallbacks of an older server.
// R-W-13 (6): the By Channel legends print each share once from the counts over the card's denominator (calls: total +
// unclassified; STD: total), never from the rounded share_pct_bp.
type CorpusPercent = { n: number; d: number; whole: number | null }[];
const corpusPercent = (JSON.parse(readFileSync(resolve(__dirname, "../../contract/fixtures/kpi-format-corpus.json"), "utf8")) as { percent: CorpusPercent }).percent;

describe("route-map bars by the Sales-card rule (R-W-13 (5))", () => {
  it("round_half_up(100 x sales x W / monthly) once: 30 of 689 at W 24 is 104.4993 %, so 104", () => {
    expect(pctWholeSalesCard(30, "689.000", 24)).toBe(104);
    expect(pctWholeOverDecimal3(30, "28.708")).toBe(105); // the 3-dp daily target (689 / 24 = 28.7083) would say 105
    expect(pctBp("en", pctBpOf(30 * 24, 689), 0)).toBe("105%"); // and so would the exact bp 10450 at no decimal
    expect(pctWholeSalesCard(12, "101.000", 21)).toBe(250); // 249.505 %; the daily "4.810" would give 249
    expect(pctWholeOverDecimal3(12, "4.810")).toBe(249);
    expect(pctWholeSalesCard(50, "1991.000", 27)).toBe(68); // X1's case: bp 6781 exact
    expect(pctWholeSalesCard(0, "100.000", 25)).toBe(0);
  });
  it("W = 0, no target, a 0 target or a bad input print a dash (R-F-13)", () => {
    expect(pctWholeSalesCard(30, "689.000", 0)).toBeNull();
    expect(pctWholeSalesCard(30, null, 24)).toBeNull();
    expect(pctWholeSalesCard(30, "0.000", 24)).toBeNull();
    expect(pctWholeSalesCard(-1, "689.000", 24)).toBeNull();
    expect(pctWholeSalesCard(30, "6.89e2", 24)).toBeNull();
    expect(pctWholeSalesCard(30, "689.000", 1.5)).toBeNull();
  });
  it("agrees with the corpus's whole percent on every entry (W = 1, the target as a Decimal3)", () => {
    let n = 0;
    for (const e of corpusPercent) {
      if (e.d <= 0 || !Number.isSafeInteger(e.n) || !Number.isSafeInteger(e.d)) continue;
      expect(pctWholeSalesCard(e.n, `${e.d}.000`, 1), `${e.n}/${e.d}`).toBe(e.whole);
      n++;
    }
    expect(n).toBeGreaterThan(50);
  });
  it("routeBarText: the Sales-card rule when both members are sent, else the daily-target path, else the bp", () => {
    const x = { pct_bp: 10450, sales: 30, daily_target: "28.708" };
    expect(routeBarText("en", { ...x, monthly_target_qty: "689.000", working_days: 24 })).toBe("104%");
    expect(routeBarText("bn", { ...x, monthly_target_qty: "689.000", working_days: 24 })).toBe("১০৪%");
    expect(routeBarText("en", x)).toBe("105%"); // an older server: W1's path
    expect(routeBarText("en", { ...x, monthly_target_qty: "689.000" })).toBe("105%"); // W missing: not the exact rule
    expect(routeBarText("en", { pct_bp: 10450 })).toBe("105%"); // no integers at all: the bp
    expect(routeBarText("en", { pct_bp: null, sales: 30, daily_target: null, monthly_target_qty: "689.000", working_days: 0 })).toBe("—");
    expect(routeBarText("en", { pct_bp: null, sales: 30, daily_target: null, monthly_target_qty: null, working_days: 24 })).toBe("—");
  });

  const slice = (o: Partial<RouteMapSlice> = {}): RouteMapSlice => ({
    ...stamp, sr_name: "SR - Testing Banani", territory_code: "T-334", territory_name: "Banani", zone: "Banani - Test", route: "Apsis Route",
    start: null, last_position: null, path: [], outlets: [], last_sale_place: null,
    target_vs_achievement: [
      { category: "cigarette", pct_bp: 10450, sales: 30, daily_target: "28.708", monthly_target_qty: "689.000", working_days: 24 },
      { category: "bidi", pct_bp: 24950, sales: 12, daily_target: "4.810", monthly_target_qty: "101.000", working_days: 21 },
      { category: "lighter", pct_bp: null, sales: 3, daily_target: null, monthly_target_qty: "100.000", working_days: 0 },
      { category: "match", pct_bp: 250, sales: 50, daily_target: "2003.000" }, // an older answer: W1's exact path
    ],
    cpr: { target_outlets: 2003, successful_calls: 50, geo_validation: 40, cpr_pct_bp: 250 },
    time: { first_sale: null, last_sale: null, total_hours: null, avg_hours: null, distance_m: 0 },
    ...o,
  });
  const render = async () => {
    const Page = (await import("@/app/(dashboards)/dashboard/route-map/[routeId]/page")).default;
    return renderToStaticMarkup((await Page({ params: Promise.resolve({ routeId: "10" }), searchParams: Promise.resolve({}) })) as ReactElement);
  };
  const bar = (m: string, cat: string) => new RegExp(`data-testid="tva-${cat}"[\\s\\S]*?tabular-nums">([^<]*)<`).exec(m)?.[1];

  it("the page prints each bar once from the integers, en and bn; the bp still draws it", async () => {
    routeMap.current = ok(slice());
    const m = await render();
    expect(bar(m, "cigarette")).toBe("104%");
    expect(bar(m, "bidi")).toBe("250%");
    expect(bar(m, "lighter")).toBe("—");
    expect(bar(m, "match")).toBe("2%");
    pageLocale.v = "bn";
    try {
      const b = await render();
      expect(bar(b, "cigarette")).toBe("১০৪%");
      expect(bar(b, "bidi")).toBe("২৫০%");
      expect(bar(b, "lighter")).toBe("—");
    } finally {
      pageLocale.v = "en";
    }
  });
});

describe("channel legends from the counts (R-W-13 (6))", () => {
  // 29 of 1,186 is 2.445 %: the server's bp 245 at one decimal would print 2.5 %; over total alone (1,129) it would be 2.6 %
  const calls: ChannelSlice = { ...stamp, denominator: 1186, channels: [{ code: "GT", value: 29, share_bp: 245 }, { code: "DCC", value: 1100, share_bp: 9275 }] };
  /** The legend line's text (value and share) of one channel. */
  const legend = (m: string, id: string) => new RegExp(`data-testid="${id}"[^>]*><p[^>]*><span[^>]*></span>([^<]*(?:<!-- -->[^<]*)*)<`).exec(m)?.[1]?.replace(/<!-- -->/g, "");
  const pieOf = (m: string) => /data-testid="channel-pie"[\s\S]*?<\/svg>/.exec(m)?.[0] ?? "";
  it("channelShareText is tenths of value over the denominator, rounded once; a 0 denominator prints a dash", () => {
    expect(channelShareText("en", 29, 1186)).toBe("2.4%");
    expect(pctBp("en", 245)).toBe("2.5%");
    expect(channelShareText("en", 29, 1129)).toBe("2.6%");
    expect(channelShareText("bn", 29, 1186)).toBe("২.৪%");
    expect(channelShareText("en", 5, 5)).toBe("100.0%"); // s4.2 one channel
    expect(channelShareText("en", 0, 0)).toBe("—");
  });
  it("By Channel Successful Call: the share over total + unclassified, en and bn", () => {
    const m = html(<ChannelCallsCard slot={ok(calls)} locale="en" />);
    expect(legend(m, "ch-calls-GT")).toContain("(2.4%)");
    expect(legend(m, "ch-calls-DCC")).toContain("(92.7%)"); // 92.748 %: the bp 9275 at one decimal would print 92.8 %
    expect(pctBp("en", 9275)).toBe("92.8%");
    const b = html(<ChannelCallsCard slot={ok(calls)} locale="bn" />, "bn");
    expect(legend(b, "ch-calls-GT")).toContain("(২.৪%)");
  });
  it("By Channel STD: the share over total; the pie's slice text is the legend's", () => {
    const std: ChannelSlice = { ...stamp, denominator: 1186, channels: [{ code: "GT", value: 29, share_bp: 245 }, { code: "MT", value: 1157, share_bp: 9755 }] };
    const m = html(<ChannelStdCard slot={ok(std)} locale="en" />);
    expect(legend(m, "ch-std-GT")).toContain("(2.4%)");
    expect(legend(m, "ch-std-MT")).toContain("(97.6%)");
    expect(pieOf(m)).toContain(">97.6%<"); // a slice of 5 % and more carries the legend's text
    expect(pieOf(m)).not.toContain("2.4%"); // the small slice is in the legend only
    const one: ChannelSlice = { ...stamp, denominator: 5, channels: [{ code: "Astha", value: 5, share_bp: 10000 }] };
    const b = html(<ChannelStdCard slot={ok(one)} locale="bn" />, "bn");
    expect(legend(b, "ch-std-Astha")).toContain("(১০০.০%)");
    expect(pieOf(b)).toContain(">১০০.০%<");
  });
  it("a card with nothing to divide by prints a dash for each share", () => {
    const empty: ChannelSlice = { ...stamp, denominator: 0, channels: [{ code: "GT", value: 0, share_bp: null }] };
    expect(legend(html(<ChannelCallsCard slot={ok(empty)} locale="en" />), "ch-calls-GT")).toContain("(—)");
  });
});

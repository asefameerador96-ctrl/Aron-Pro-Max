// tso-manual W1, fix round (build contract s5 W1 "Web percentages from integers through the corpus", D-TSO-MAN-10, s4.2,
// s8 "numbers everywhere"): the national dashboard's rings and the route map print each percentage ONCE from the integers the
// payload carries (pctTenths / pctWhole / dailyRingTenths of fmt.ts, the corpus-checked formatters), never from a bp that was
// already rounded. 29 of 1,186 is 2.445 %: the phone and the web print 2.4 %, where the bp 245 at one decimal would print 2.5 %.
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { CprCard, FinalSubmitCard, LoginSubmitCard, SalesCards } from "@/components/national/cards";
import { pctBp, pctBpOf, pctText, pctWhole, pctWholeOverDecimal3 } from "@/lib/national/fmt";
import type { ApiOutcome } from "@/lib/api/client";
import type { CprSlice, FinalSubmitSlice, LoginSubmitSlice, RouteMapSlice, SalesSlice } from "@/lib/national/types";
import { html } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {} }), notFound() { throw new Error("notFound"); } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", scope: null, user: { role: "NATIONAL" } }) }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => "en" }));
const routeMap = vi.hoisted(() => ({ current: null as unknown }));
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

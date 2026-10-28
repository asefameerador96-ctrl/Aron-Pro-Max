// tso-manual W1 (build contract s5 W1 "tso-daily-tracking shows the till-date %"; B2 DailyTrackingApi till-date %): each route of
// the TSO Daily Tracking page shows its till-date achievement at the server's two decimals, never rounded a second time, "—"
// while the server sends none, Bengali digits in Bangla.
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as ServerMod from "@/lib/dash/server";
import { pct2dpText } from "@/lib/national/fmt";

vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", scope: { nodes: [] }, user: { role: "TSO" } }) }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => locale.v }));
const stub = vi.hoisted(() => ({ tracking: vi.fn() }));
vi.mock("@/lib/dash/server", async (orig) => ({ ...(await orig<typeof ServerMod>()), getDailyTracking: stub.tracking }));

import Page from "@/app/(dashboards)/tso-daily-tracking/page";

const row = (route_id: number, pct: number | null | undefined) => ({ route_id, route_name: `R${route_id}`, zone_id: 1, user_id: 7, user_name: "Rahim", state: "in_field", target_outlets: 40, visited_outlets: 35, successful_calls: 14, active_memo_count: 14, net_mtk: 1, bucket: "from_80", ...(pct === undefined ? {} : { tilldate_target_achievement_pct: pct }) });
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

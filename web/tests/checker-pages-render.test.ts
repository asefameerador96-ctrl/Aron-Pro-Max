// Checker: page-level defects of the dashboard page rows (daily tracking, routes, tutorial, tree) with stubbed API reads.
import type * as ServerMod from "@/lib/dash/server";
import type { ReactElement } from "react";

import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {} }), notFound() { throw new Error("notFound"); } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", user: { role: "TSO" } }) }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => "en" }));
const stub = vi.hoisted(() => ({ tracking: vi.fn(), routes: vi.fn(), assignments: vi.fn(), tutorials: vi.fn() }));
vi.mock("@/lib/dash/server", async (orig) => {
  const real = await orig<typeof ServerMod>();
  return { ...real, getDailyTracking: stub.tracking, listRoutes: stub.routes, listAssignments: stub.assignments, listTutorials: stub.tutorials };
});

import DailyTracking from "@/app/(dashboards)/daily-tracking/page";
import RoutesPage from "@/app/(dashboards)/routes/page";
import Tutorial from "@/app/(dashboards)/tutorial/page";
import { businessDate } from "@/lib/i18n";

const render = async (p: Promise<ReactElement>) => renderToStaticMarkup(await p);
const ok = <T,>(data: T) => ({ ok: true as const, status: 200, data });
const row = (o: Record<string, unknown> = {}) => ({ route_id: 1, route_name: "R1", zone_id: 1, user_id: 7, user_name: "Rahim", state: "in_field", target_outlets: 10, visited_outlets: 5, successful_calls: 1, active_memo_count: 1, net_mtk: 1000, bucket: "below_80", tilldate_target_achievement_pct: 50, ...o });
const page = (items: unknown[], date = businessDate()) => ok({ as_of: "2026-10-07T05:00:00Z", business_date: date, items, next_cursor: null });

beforeEach(() => vi.resetAllMocks());

describe("F-WEB-038 daily tracking page", () => {
  it("a calendar-invalid ?date= (2026-13-45) must not crash the page (previousDate throws RangeError)", async () => {
    stub.tracking.mockResolvedValue({ ok: false, status: 400, problem: { code: "ERR_VALIDATION" } });
    await expect(render(DailyTracking({ searchParams: Promise.resolve({ date: "2026-13-45" }) }) as Promise<ReactElement>)).resolves.toBeTypeOf("string");
  });
  it("for a past date the comparator label must not say 'Yesterday' relative to today", async () => {
    stub.tracking.mockResolvedValue(page([row()], "2026-10-01"));
    const h = await render(DailyTracking({ searchParams: Promise.resolve({ date: "2026-10-01" }) }) as Promise<ReactElement>);
    expect(h).not.toMatch(/Yesterday: /);
  });
  it("a future date offers no take-action column", async () => {
    stub.tracking.mockResolvedValue(page([row()], "2099-01-01"));
    const h = await render(DailyTracking({ searchParams: Promise.resolve({ date: "2099-01-01" }) }) as Promise<ReactElement>);
    expect(h).not.toContain("take-action-1");
  });
});

describe("F-WEB-010 browse routes page", () => {
  it("a primary assignee absent from today's tracking rows shows a name, never '#<id>'", async () => {
    stub.routes.mockResolvedValue(ok({ items: [{ id: 5, code: "C5", name: "Inactive SR route", kind: "sr", zone_id: 1, status: "inactive" }], next_cursor: null }));
    stub.assignments.mockResolvedValue(ok({ items: [{ id: 1, route_id: 5, user_id: 777, kind: "primary", valid_from: "2026-01-01", created_at: "x" }], next_cursor: null }));
    stub.tracking.mockResolvedValue(page([]));
    const h = await render(RoutesPage() as Promise<ReactElement>);
    expect(h).not.toContain("#777");
  });
});

describe("F-WEB-035 tutorial page", () => {
  it("never renders a javascript: URL as a link", async () => {
    stub.tutorials.mockResolvedValue(ok({ items: [{ tutorial_id: 1, kind: "manual", title_en: "x", url: "javascript:alert(1)", sort: 1 }] }));
    const h = await render(Tutorial() as Promise<ReactElement>);
    expect(h).not.toContain("javascript:alert(1)");
  });
});


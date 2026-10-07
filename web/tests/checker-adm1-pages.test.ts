// Checker ADM-1 (F-ADM-001/002/003/007): behaviour of the generic CRUD pages and the BFF that the builder's tests do not pin down.
// Each test states the rule it holds the code to; a failing test is a confirmed defect, not a style opinion.
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const calls: { path: string; query: Record<string, unknown> }[] = [];
let handler: (path: string, query: Record<string, unknown>) => unknown = () => ({ items: [], next_cursor: null });

vi.mock("@/lib/api/raw", () => ({
  rawRequest: vi.fn(async (req: { path: string; query?: Record<string, unknown> }) => {
    calls.push({ path: req.path, query: req.query ?? {} });
    const out = handler(req.path, req.query ?? {});
    if (out && typeof out === "object" && "__fail" in out) return { ok: false, status: 503, problem: { code: "ERR_SERVICE_UNAVAILABLE", status: 503, title: "x", type: "x", request_id: "r" }, response: new Response(null) };
    return { ok: true, status: 200, data: out, response: new Response(null) };
  }),
}));
let LOCALE: "bn" | "en" = "bn";
vi.mock("@/lib/auth/require", () => ({
  requireSession: async () => ({ user: { role: "ADMIN" }, at: "tok" }),
  currentPath: async () => "/admin/x",
}));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => LOCALE }));
// The form is a client component (needs the app router); the page-level markup is what these tests inspect.
vi.mock("@/components/admin/crud/entity-form", () => ({ EntityForm: () => null }));

import { routeAssignments } from "@/app/admin/_entities/routes";
import { users } from "@/app/admin/_entities/users";
import { routes } from "@/app/admin/_entities/routes";
import { EntityCreatePage, EntityListPage } from "@/components/admin/crud/pages";
import { loadRefOptions } from "@/components/admin/crud/server";

beforeEach(() => {
  calls.length = 0;
  LOCALE = "bn";
  handler = () => ({ items: [], next_cursor: null });
});

const key = (c: { path: string; query: Record<string, unknown> }) => `${c.path}?${JSON.stringify(c.query)}`;

describe("ref options (F-ADM-003 needs every route and every user in its selects)", () => {
  it("CA1: a referenced table with more than 500 rows is not silently cut to its first 500", async () => {
    const all = Array.from({ length: 1200 }, (_, i) => ({ id: i + 1, username: `u${i + 1}`, full_name: `User ${i + 1}` }));
    handler = (_p, q) => {
      const off = q.cursor ? Number(q.cursor) : 0;
      const lim = Number(q.limit ?? 100);
      return { items: all.slice(off, off + lim), next_cursor: off + lim < all.length ? String(off + lim) : null };
    };
    const opts = await loadRefOptions({ path: "/v1/admin/users", label: ["username", "full_name"] }, "tok");
    // 8,500 reps: an assignment form that can only offer the first 500 users cannot assign the rest.
    expect(opts.length).toBe(1200);
  });

  it("CA2: when the ref list call fails, the create form says so instead of rendering an empty required select", async () => {
    handler = (p) => (p === "/v1/admin/routes" || p === "/v1/admin/users" ? { __fail: true } : { items: [], next_cursor: null });
    const html = renderToStaticMarkup(await EntityCreatePage({ meta: routeAssignments }));
    expect(html).toMatch(/role="alert"/);
  });
});

describe("list page API cost (docs/24: no chatty calls)", () => {
  it("CA3: the users list loads the zone table once, not once per field and once per filter", async () => {
    renderToStaticMarkup(await EntityListPage({ meta: users, searchParams: {} }));
    const keys = calls.map(key);
    expect(keys.length).toBe(new Set(keys).size);
  });

  it("CA4: the assignments list does not fetch 500 routes and 500 users twice each", async () => {
    renderToStaticMarkup(await EntityListPage({ meta: routeAssignments, searchParams: {} }));
    const keys = calls.map(key);
    expect(keys.length).toBe(new Set(keys).size);
  });

  it("CA5: ref lookups are only made for what the page shows (a non-column ref field is not fetched on the list)", async () => {
    renderToStaticMarkup(await EntityListPage({ meta: users, searchParams: {} }));
    // users.home_zone_id is not a list column; the zone filter needs the zone table, so exactly one zone call is legitimate.
    expect(calls.filter((c) => c.path === "/v1/admin/geo/zone").length).toBeLessThanOrEqual(1);
  });
});

describe("list filters are validated before they reach the API (contract: Search minLength 2, Id integer)", () => {
  it("CA6: a one-character search is not forwarded (the API would answer 400 and the page shows a raw error)", async () => {
    renderToStaticMarkup(await EntityListPage({ meta: routes, searchParams: { q: "a" } }));
    const list = calls.find((c) => c.path === "/v1/admin/routes");
    expect(list?.query.q ?? undefined).toBeUndefined();
  });

  it("CA7: a non-integer zone_id filter is not forwarded", async () => {
    renderToStaticMarkup(await EntityListPage({ meta: routes, searchParams: { zone_id: "abc" } }));
    const list = calls.find((c) => c.path === "/v1/admin/routes");
    expect(list?.query.zone_id ?? undefined).toBeUndefined();
  });
});

describe("Bengali digits in list cells (docs/26 s5: Bengali digits per locale)", () => {
  it("CA8: assignment dates are shown with Bengali digits in the Bangla UI", async () => {
    handler = (p) => (p === "/v1/admin/route-assignments" ? { items: [{ id: 1, route_id: 1, user_id: 1001, kind: "primary", valid_from: "2026-09-01", valid_to: "2026-10-20" }], next_cursor: null } : { items: [], next_cursor: null });
    const html = renderToStaticMarkup(await EntityListPage({ meta: routeAssignments, searchParams: {} }));
    expect(html).not.toContain("2026-09-01");
    expect(html).not.toContain("2026-10-20");
  });
});

// Checker ADM-3 (F-ADM-010, F-ADM-070, F-ADM-056, F-WEB-003, F-WEB-032): behaviour of the outlet pages and BFF that the
// builder's tests do not pin down. A failing test here is a confirmed defect against the cited rule, not a style opinion.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";

const calls: { path: string; query: Record<string, unknown> }[] = [];
let handler: (path: string, query: Record<string, unknown>) => unknown = () => ({ items: [], next_cursor: null });
let ROLE = "ADMIN";

vi.mock("@/lib/api/raw", () => ({
  rawRequest: vi.fn(async (req: { path: string; query?: Record<string, unknown> }) => {
    calls.push({ path: req.path, query: req.query ?? {} });
    const out = handler(req.path, req.query ?? {});
    return { ok: true, status: 200, data: out, response: new Response(null) };
  }),
}));
vi.mock("@/lib/auth/require", () => ({
  requireSession: async () => ({ user: { role: ROLE }, at: "tok" }),
  currentPath: async () => "/admin/x",
}));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => "en" }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), notFound: () => { throw new Error("notFound"); }, redirect: () => { throw new Error("redirect"); } }));
// The form is a client component; the mock prints the labels of the fields the page hands it so the page's content is inspectable.
vi.mock("@/components/admin/crud/entity-form", async () => {
  const { createElement } = await import("react");
  return { EntityForm: (p: { fields: { name: string; label: string }[] }) => createElement("div", { "data-testid": "form" }, p.fields.map((f) => f.label).join("|")) };
});

import { outletRequests, outlets } from "@/app/admin/_entities/outlets";
import WholesaleMarkingPage from "@/app/admin/wholesale-marking/page";
import { EntityActionPage, EntityEditPage, EntityListPage } from "@/components/admin/crud/pages";
import { valuesSchema } from "@/components/admin/crud/validation";
import { renderToStaticMarkup } from "react-dom/server";
import { en } from "@/lib/i18n/messages-en";
import { html } from "./helpers/render";

const U = "22222222-2222-4222-8222-222222222222";

beforeEach(() => {
  calls.length = 0;
  ROLE = "ADMIN";
  handler = () => ({ items: [], next_cursor: null });
});

/** A paged mock of a table of `n` rows (500 per page like the BFF asks for). */
function paged(all: unknown[]) {
  return (q: Record<string, unknown>) => {
    const off = q.cursor ? Number(q.cursor) : 0;
    const lim = Number(q.limit ?? 100);
    return { items: all.slice(off, off + lim), next_cursor: off + lim < all.length ? String(off + lim) : null };
  };
}

describe("F-ADM-010 / F-WEB-003: retailer detail", () => {
  it("CA3-1: location_confirmed (named in the F-ADM-010 acceptance) is visible on the retailer detail", async () => {
    handler = (p) =>
      p === "/v1/admin/outlets/1"
        ? { id: 1, code: "DHK-334-001", name: "Banani Store", owner_name: "Md. Rahim", zone_id: 14, cluster_id: 1, channel: "GT", outlet_kind: "retail", price_type: "regular", status: "active", location_confirmed: true, version: 1 }
        : { items: [], next_cursor: null };
    const markup = renderToStaticMarkup(await EntityEditPage({ meta: outlets, id: "1", searchParams: {} }));
    // The edit form is built from writable fields only, so the read-only location_confirmed (section "additional") is never shown anywhere.
    expect(markup).toContain(en["entity.field.location_confirmed"]);
  });

  it("CA3-2: name and owner_name enforce the contract's minLength 2 (OutletWrite/OutletPatch) before the API call", () => {
    const create = valuesSchema(outlets, "create").safeParse({ name: "A", owner_name: "B", zone_id: "1", cluster_id: "1", channel: "GT", outlet_kind: "retail" });
    expect(create.success).toBe(false);
    expect(valuesSchema(outlets, "update").safeParse({ name: "A" }).success).toBe(false);
    expect(valuesSchema(outlets, "update").safeParse({ owner_name: "B" }).success).toBe(false);
  });

  it("CA3-3: the reopen page (no inputs but the reason) does not download the zone, cluster and route tables", async () => {
    handler = (p) => (p === "/v1/admin/outlets/4" ? { id: 4, code: "X", name: "Tea", status: "closed", version: 1, zone_id: 15, outlet_kind: "retail", channel: "GT", owner_name: "S M" } : { items: [], next_cursor: null });
    renderToStaticMarkup(await EntityActionPage({ meta: outlets, id: "4", actionKey: "reopen", searchParams: {} }));
    const refCalls = calls.filter((c) => c.path !== "/v1/admin/outlets/4");
    expect(refCalls.map((c) => c.path)).toEqual([]);
  });

  it("CA3-4: the retailer edit page does not fetch the zone table for a create-only field it does not render", async () => {
    handler = (p) => (p === "/v1/admin/outlets/1" ? { id: 1, code: "A", name: "Banani", status: "active", version: 1, zone_id: 14, cluster_id: 1, owner_name: "Md R", channel: "GT", outlet_kind: "retail" } : { items: [], next_cursor: null });
    renderToStaticMarkup(await EntityEditPage({ meta: outlets, id: "1", searchParams: {} }));
    expect(calls.some((c) => c.path === "/v1/admin/geo/zone")).toBe(false);
  });

  it("CA3-5: outlets.ts points at a request file that exists (docs/requests/web-admin-sub-channel-ids.md)", () => {
    const src = readFileSync(join(process.cwd(), "src/app/admin/_entities/outlets.ts"), "utf8");
    const m = /docs\/requests\/([a-z0-9-]+\.md)/.exec(src);
    expect(m).not.toBeNull();
    expect(existsSync(join(process.cwd(), "..", "docs", "requests", m![1]!))).toBe(true);
  });
});

describe("F-WEB-032: Outlet Approval Panel", () => {
  const users = Array.from({ length: 8500 }, (_, i) => ({ id: i + 1, username: `sr${i + 1}`, full_name: `SR ${i + 1}` }));
  const reqRow = {
    request_uuid: U, request_type: "new", status: "verified", outlet_id: null, outlet_name: "Verified Fresh Store", route_id: 1, cluster_id: 2,
    requested_by_user_id: 1001, verified_by_user_id: 1003, requested_at: "2026-10-04T04:00:00.000Z", rejection_reason: null, version: 1,
    proposed: { name: "Verified Fresh Store", owner_name: "Sumon Das Unique", address: "House 7 Unique Road", lat: 23.79, lng: 90.4 },
    flags: ["mocked_fix"], moved_m: null, requires_tso: false, photos: [], events: [],
  };

  it("CB3-1: the approve page shows what is being approved (the proposed owner and address), not only six list columns", async () => {
    ROLE = "DMO";
    handler = (p, q) => (p === `/v1/outlet-requests/${U}` ? reqRow : p === "/v1/admin/users" ? paged(users)(q) : { items: [], next_cursor: null });
    const markup = renderToStaticMarkup(await EntityActionPage({ meta: outletRequests, id: U, actionKey: "approve", searchParams: {} }));
    // An approver confirming a new outlet must see the proposal (owner, address, pin) and the integrity flags (mocked_fix), or the confirmation is blind.
    expect(markup).toContain("Sumon Das Unique");
  });

  it("CB3-2: the panel list does not page through all 8,500 users just to label the requester column", async () => {
    ROLE = "DMO";
    handler = (p, q) => (p === "/v1/admin/users" ? paged(users)(q) : { items: [], next_cursor: null });
    renderToStaticMarkup(await EntityListPage({ meta: outletRequests, searchParams: {} }));
    // 8,500 users at 500 per call is 17 sequential calls on every view of the panel; the label needs the users on the page only.
    expect(calls.filter((c) => c.path === "/v1/admin/users").length).toBeLessThanOrEqual(1);
  });

  it("CB3-3: a reject page (no ref input) does not page through all users either", async () => {
    ROLE = "DMO";
    handler = (p, q) => (p === `/v1/outlet-requests/${U}` ? { ...reqRow, status: "pending" } : p === "/v1/admin/users" ? paged(users)(q) : { items: [], next_cursor: null });
    renderToStaticMarkup(await EntityActionPage({ meta: outletRequests, id: U, actionKey: "reject", searchParams: {} }));
    expect(calls.filter((c) => c.path === "/v1/admin/users").length).toBeLessThanOrEqual(1);
  });
});

describe("F-ADM-056: wholesale marking page", () => {
  it("CC3-1: the wholesale filter finds wholesale outlets beyond the first 500 rows of the zone", async () => {
    const all = Array.from({ length: 1200 }, (_, i) => ({ id: i + 1, code: `O-${i + 1}`, name: `Outlet ${i + 1}`, owner_name: "Owner", outlet_kind: i === 700 ? "wholesale" : "retail", status: "active" }));
    handler = (p, q) => (p === "/v1/admin/outlets" ? paged(all)(q) : { items: [], next_cursor: null });
    const markup = html(await WholesaleMarkingPage({ searchParams: Promise.resolve({ kind: "wholesale" }) }), "en");
    // The list operation has no outlet_kind filter, so the page filters the first page only: this wholesale outlet is silently unreachable.
    expect(markup).toContain("O-701");
  });
});

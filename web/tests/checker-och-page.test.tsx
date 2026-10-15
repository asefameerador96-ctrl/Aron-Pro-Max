// CHECKER OCH (T1) for F-WEB-003: app/admin/outlet-change/page.tsx with the session and the API mocked.
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { html } from "./helpers/render";

const st = vi.hoisted(() => ({ role: "TSO", calls: [] as { path: string; query?: Record<string, unknown> }[], fail: new Set<string>(), outlets: [] as Record<string, unknown>[] }));
vi.mock("next/navigation", () => ({ redirect: (u: string) => { throw new Error(`redirect ${u}`); }, notFound: () => { throw new Error("notFound"); }, usePathname: () => "/admin/outlet-change", useRouter: () => ({ push() {}, refresh() {} }) }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", user: { role: st.role, user_id: 1 }, scope: { nodes: [] } }) }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => "en" }));
vi.mock("@/lib/api/raw", () => ({
  rawRequest: async (r: { path: string; query?: Record<string, unknown> }) => {
    st.calls.push(r);
    if (st.fail.has(r.path)) return { ok: false, status: 503, problem: { code: "ERR_SERVICE_UNAVAILABLE" } };
    if (r.path === "/v1/outlets") return { ok: true, status: 200, data: { items: st.outlets, next_cursor: null } };
    if (r.path === "/v1/admin/code-lists") return { ok: true, status: 200, data: { lists: [{ list_key: "sub_channel", items: [{ id: 11, code: "grocery", label_en: "Grocery" }] }, { list_key: "outlet_close_reason", items: [{ id: 1, code: "shop_shut", label_en: "Shut" }] }] } };
    if (r.path === "/v1/admin/clusters") return { ok: true, status: 200, data: { items: [{ id: 1, name: "C1" }], next_cursor: null } };
    return { ok: false, status: 404, problem: { code: "ERR_NOT_FOUND" } };
  },
}));

const Page = (await import("@/app/admin/outlet-change/page")).default as unknown as (p: { searchParams: Promise<Record<string, string | string[] | undefined>> }) => Promise<ReactElement>;
const OUT = { id: 1, code: "DHK-334-001", name: "Banani Store", owner_name: "Md. Rahim", zone_id: 14, cluster_id: 1, sub_channel_id: 11, lat: 23.79, lng: 90.4, address: "Road 11" };
const page = async (sp: Record<string, string | string[] | undefined>) => html(await Page({ searchParams: Promise.resolve(sp) }));

beforeEach(() => {
  st.role = "TSO";
  st.calls = [];
  st.fail = new Set();
  st.outlets = [OUT];
});

describe("role gate", () => {
  it("ADMIN, SUPERADMIN, SUPPORT, WM, ANALYST get Forbidden and no API call; TSO and DMO get the search", async () => {
    for (const r of ["ADMIN", "SUPERADMIN", "SUPPORT", "WM", "ANALYST", "TOP"]) {
      st.role = r;
      expect(await page({ q: "Banani", outlet: "1" }), r).toContain('data-testid="forbidden"');
    }
    expect(st.calls).toHaveLength(0);
    for (const r of ["TSO", "DMO"]) {
      st.role = r;
      expect(await page({ q: "Banani", outlet: "1" })).toContain('data-testid="outlet-change-form"');
    }
  });
});

describe("search params", () => {
  it("the client never sends a scope: only q and limit go to GET /v1/outlets", async () => {
    await page({ q: "Banani", outlet: "1", zone_id: "15" });
    const c = st.calls.find((x) => x.path === "/v1/outlets")!;
    expect(Object.keys(c.query ?? {}).sort()).toEqual(["limit", "q"]);
  });
  it("an outlet id not among the search results opens no form", async () => {
    expect(await page({ q: "Banani", outlet: "3" })).not.toContain("outlet-change-form");
    expect(await page({ outlet: "1" })).not.toContain("outlet-change-form");
  });
  it("q is encoded in the pick href (no parameter injection)", async () => {
    const h = await page({ q: "Ban&outlet=99#x" });
    expect(h).toContain("q=Ban%26outlet%3D99%23x&amp;outlet=1");
  });
  it("an over-long numeric outlet param is not truncated into a different id", async () => {
    st.outlets = [{ ...OUT, id: 123456789012345 }];
    expect(await page({ q: "Banani", outlet: "1234567890123456789" })).not.toContain("outlet-change-form");
  });
  it("array params take the first value; non-numeric outlet is ignored", async () => {
    expect(await page({ q: ["Banani", "x"], outlet: ["1", "2"] })).toContain("outlet-change-form");
    expect(await page({ q: "Banani", outlet: "1 OR 1=1" })).not.toContain("outlet-change-form");
  });
});

describe("XSS", () => {
  it("outlet name and owner are escaped in results and form", async () => {
    st.outlets = [{ ...OUT, name: '<img src=x onerror="alert(1)">', owner_name: "<script>alert(2)</script>", code: '"><svg onload=alert(3)>' }];
    const h = await page({ q: "Banani", outlet: "1" });
    expect(h).not.toMatch(/<img src=x|<script>alert|<svg onload/);
  });
});

describe("failures", () => {
  it("GET /v1/outlets failing shows an error, not a crash or an empty list", async () => {
    st.fail.add("/v1/outlets");
    const h = await page({ q: "Banani" });
    expect(h).toContain('data-testid="api-error"');
  });
  it("code lists failing are reported, not silently turned into free-text inputs", async () => {
    st.fail.add("/v1/admin/code-lists");
    const h = await page({ q: "Banani", outlet: "1" });
    expect(h).toMatch(/role="alert"[^>]*>[^<]*\S/);
  });
  it("clusters failing are reported, not silently turned into a free-text cluster id", async () => {
    st.fail.add("/v1/admin/clusters");
    const h = await page({ q: "Banani", outlet: "1" });
    expect(h).toMatch(/role="alert"[^>]*>[^<]*\S/);
  });
});

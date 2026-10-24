// Flagged memos page (D-WEB-FLAGGED-MEMOS-01): the view in every state, the query rules, the role gate and the menu, and the reads
// against the mock endpoint (scope, filters, paging, an unknown flag, the 92-day cap).
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { FlaggedMemosView, flagLabel, type FlaggedMemosViewProps } from "@/components/dash/flagged-memos-view";
import { FLAGGED_MEMO_ROLES, MAX_RANGE_DAYS, daysBetween, flaggedHref, getFlaggedMemos, parseFlaggedQuery, type FlaggedMemo } from "@/lib/dash/flagged-memos";
import { ALL_ROLES } from "@/lib/auth/roles";
import { menuFor } from "@/lib/menu/menu";
import { en } from "@/lib/i18n/messages-en";
import { bn } from "@/lib/i18n/messages-bn";
import { createMock } from "../mock/server";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

const memo = (n: number, over: Partial<FlaggedMemo> = {}): FlaggedMemo => ({
  memo_client_uuid: `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`, memo_no: `sr1001-261007-${n}`, business_date: "2026-10-07", status: "active", user_id: 1001, user_code: "sr1001", user_name: "Testing Banani", outlet_id: 5, outlet_name: "Banani Store",
  route_id: 10231, zone_id: 3341, territory_id: 334, division_id: 10, wing_id: 1, gross_mtk: 1_234_567, flags: ["zero_price_line"], stored_at: "2026-10-07T08:00:00.000Z", voided_at: null, ...over,
});
const query = { from: "2026-10-07", to: "2026-10-07", flag: undefined, cursor: undefined, rangeError: null } as const;
const base: FlaggedMemosViewProps = { locale: "en", today: "2026-10-07", query, scopeFilter: <span data-testid="scope-slot" />, result: { ok: true, status: 200, data: { items: [memo(1)], next_cursor: null } } as never, firstHref: null, nextHref: null, resetHref: "/flagged-memos" };
const withItems = (items: FlaggedMemo[], next: string | null = null): FlaggedMemosViewProps => ({ ...base, result: { ok: true, status: 200, data: { items, next_cursor: next } } as never });

describe("FlaggedMemosView", () => {
  it("a row per memo: number, date, status, rep, outlet, route, gross in the house money format, flags as chips", () => {
    const m = html(<FlaggedMemosView {...withItems([memo(1)])} />);
    const s = text(m);
    expect(s).toContain("sr1001-261007-1");
    expect(s).toContain("sr1001 · Testing Banani");
    expect(s).toContain("Banani Store");
    expect(s).toContain("1,234.57");
    expect(s).toContain("Zero-price line");
    expect(m).toContain('data-flag="zero_price_line"');
    expect(m).toContain('data-testid="scope-slot"');
  });
  it("shows an unknown flag exactly as the server sent it and never drops it", () => {
    const m = html(<FlaggedMemosView {...withItems([memo(1, { flags: ["price_mismatch", "future_check_x"] })])} />);
    expect(m).toContain('data-flag="future_check_x"');
    expect(text(m)).toContain("future_check_x");
    expect(text(m)).toContain("Price mismatch");
    expect(flagLabel("bn", "future_check_x")).toBe("future_check_x");
    expect(flagLabel("bn", "price_mismatch")).toBe(bn["flagged.flag.price_mismatch"]);
  });
  it("a flag in the URL that is not one of the four stays selectable", () => {
    const m = html(<FlaggedMemosView {...withItems([memo(1)])} query={{ ...query, flag: "future_check_x" }} />);
    expect(m).toContain('<option value="future_check_x" selected');
  });
  it("greys voided and superseded memos and says when an admin voided one; a route-less memo says so", () => {
    const m = html(<FlaggedMemosView {...withItems([memo(1, { status: "void", voided_at: "2026-10-07T09:00:00.000Z" }), memo(2, { status: "superseded" }), memo(3, { route_id: null }), memo(4)])} />);
    expect(m).toContain('data-row-state="void"');
    expect(m).toContain('data-row-state="superseded"');
    expect(m.match(/data-row-state=/g)).toHaveLength(2);
    expect(text(m)).toContain("voided");
    expect(m).toContain('data-testid="flagged-no-route"');
    expect(text(m)).toContain("No route");
  });
  it("empty: a plain empty state, no table", () => {
    const m = html(<FlaggedMemosView {...withItems([])} />);
    expect(m).toContain('data-testid="flagged-empty"');
    expect(m).not.toContain('data-testid="data-table"');
  });
  it("error (a 404 while the endpoint is not deployed, a 403, a network failure): an alert with retry, no table, no raw code", () => {
    for (const [status, code] of [[404, "ERR_NOT_FOUND"], [403, "ERR_FORBIDDEN"], [503, "ERR_SERVICE_UNAVAILABLE"], [500, "ERR_INTERNAL"]] as const) {
      const m = html(<FlaggedMemosView {...base} result={{ ok: false, status, problem: { code, title: "x", status } } as never} />);
      expect(m).toContain('data-testid="flagged-error"');
      expect(m.includes('data-testid="retry"')).toBe(status !== 403);
      expect(m).not.toContain('data-testid="data-table"');
      expect(text(m)).not.toContain("ERR_");
    }
  });
  it("a refused range says so and asks nothing (result null)", () => {
    const order = html(<FlaggedMemosView {...base} result={null} query={{ ...query, from: "2026-10-09", rangeError: "order" }} />);
    expect(order).toContain('data-testid="flagged-range-error"');
    expect(text(order)).toContain("before the start date");
    const long = html(<FlaggedMemosView {...base} result={null} query={{ ...query, rangeError: "too_long" }} />);
    expect(text(long)).toContain("longer than 92 days");
    expect(html(<FlaggedMemosView {...base} result={null} query={{ ...query, rangeError: "too_long" }} locale="bn" />, "bn")).toContain("৯২");
  });
  it("paging: next link only with a cursor, first-page link only on a later page, the end marker otherwise", () => {
    const first = html(<FlaggedMemosView {...withItems([memo(1)], "abc")} nextHref="/flagged-memos?cursor=abc" />);
    expect(first).toContain('data-testid="flagged-next"');
    expect(first).not.toContain('data-testid="flagged-first"');
    const later = html(<FlaggedMemosView {...withItems([memo(1)])} firstHref="/flagged-memos" />);
    expect(later).toContain('data-testid="flagged-first"');
    expect(later).toContain('data-testid="flagged-end"');
    expect(later).not.toContain('data-testid="flagged-next"');
  });
  it("Bangla first: labels, flags, status and digits in Bangla; a very long outlet name does not break the row", () => {
    const long = "অত্যন্ত দীর্ঘ আউটলেটের নাম ".repeat(25);
    const m = html(<FlaggedMemosView {...withItems([memo(1, { outlet_name: long, flags: ["price_mismatch", "zero_price_line", "arithmetic_mismatch", "pack_factor_mismatch"] })])} locale="bn" />, "bn");
    const s = text(m);
    expect(s).toContain(bn["menu.flagged_memos"]);
    expect(s).toContain("১,২৩৪.৫৭");
    for (const f of ["price_mismatch", "zero_price_line", "arithmetic_mismatch", "pack_factor_mismatch"]) expect(s).toContain(bn[`flagged.flag.${f}` as keyof typeof bn]);
    expect(s).toContain(long.trim());
    expect(m).toContain("break-words");
  });
  it("every flagged.* key has a Bangla twin that is not the English text", () => {
    for (const k of Object.keys(en).filter((x) => x.startsWith("flagged.") || x === "menu.flagged_memos")) {
      expect(bn[k as keyof typeof bn], k).toBeTruthy();
      expect(bn[k as keyof typeof bn], k).not.toBe(en[k as keyof typeof en]);
    }
  });
});

describe("query", () => {
  it("defaults to today; a lone date fills the other end; junk falls back; the flag is trimmed", () => {
    expect(parseFlaggedQuery({}, "2026-10-24")).toMatchObject({ from: "2026-10-24", to: "2026-10-24", flag: undefined, rangeError: null });
    expect(parseFlaggedQuery({ from: "2026-10-01" }, "2026-10-24")).toMatchObject({ from: "2026-10-01", to: "2026-10-24", rangeError: null });
    expect(parseFlaggedQuery({ to: "2026-10-30" }, "2026-10-24")).toMatchObject({ from: "2026-10-24", to: "2026-10-30" });
    expect(parseFlaggedQuery({ from: "2026-05-01" }, "2026-10-24").rangeError).toBe("too_long");
    expect(parseFlaggedQuery({ from: "nope", to: "2026-13-40" }, "2026-10-24")).toMatchObject({ from: "2026-10-24", to: "2026-10-24" });
    expect(parseFlaggedQuery({ flag: "  price_mismatch ", cursor: "c1" }, "2026-10-24")).toMatchObject({ flag: "price_mismatch", cursor: "c1" });
    expect(parseFlaggedQuery({ flag: "   " }, "2026-10-24").flag).toBeUndefined();
  });
  it("the 92-day cap and the order of the range", () => {
    expect(daysBetween("2026-07-01", "2026-10-01")).toBe(92);
    expect(parseFlaggedQuery({ from: "2026-07-01", to: "2026-10-01" }, "2026-10-24").rangeError).toBeNull();
    expect(parseFlaggedQuery({ from: "2026-06-30", to: "2026-10-01" }, "2026-10-24").rangeError).toBe("too_long");
    expect(parseFlaggedQuery({ from: "2026-10-05", to: "2026-10-04" }, "2026-10-24").rangeError).toBe("order");
    expect(MAX_RANGE_DAYS).toBe(92);
  });
  it("the paging link keeps the filters and the picked node", () => {
    expect(flaggedHref({ from: "2026-10-01", to: "2026-10-02", flag: "price_mismatch" }, { wing: "1", territory: "334", zone: undefined }, "c2")).toBe("/flagged-memos?from=2026-10-01&to=2026-10-02&flag=price_mismatch&wing=1&territory=334&cursor=c2");
  });
});

describe("role gate and menu", () => {
  it("SUPERADMIN, ADMIN, NATIONAL, WM, DMO and TSO; never BRAND, SR, AMO, SUPPORT, ANALYST or TOP", () => {
    expect([...FLAGGED_MEMO_ROLES].sort()).toEqual(["ADMIN", "DMO", "NATIONAL", "SUPERADMIN", "TSO", "WM"]);
    for (const r of ALL_ROLES) {
      const shown = menuFor(r).flatMap((s) => s.items).some((i) => i.id === "flagged-memos");
      expect(shown, r).toBe((FLAGGED_MEMO_ROLES as readonly string[]).includes(r));
    }
  });
  it("an ADMIN without the reports grant does not see it", () => {
    const sees = (grants: Record<string, "r" | "w">) => menuFor("ADMIN", undefined, null, grants as never).flatMap((s) => s.items).some((i) => i.id === "flagged-memos");
    expect(sees({ reports: "r" })).toBe(true);
    expect(sees({ outlets: "w" })).toBe(false);
  });
});

describe("reads against the mock endpoint", () => {
  const mock = createMock();
  let base = "";
  beforeAll(async () => {
    await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
    base = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
    process.env.ARON_API_BASE_URL = base;
  });
  afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
  beforeEach(() => mock.reset());
  const token = async (username: string, password: string) => ((await (await fetch(`${base}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username, password, client: "web" }) })).json()) as { access_token: string }).access_token;
  const day = { from: "2026-10-07", to: "2026-10-07" };

  it("a TSO sees only the memos of its territory; every row carries a flag; voided, route-less and unknown-flag rows are listed", async () => {
    const r = await getFlaggedMemos(await token("tso334", "tso-pass-1"), day);
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    const items = r.data.items;
    expect(items.length).toBeGreaterThan(0);
    expect(items.every((m) => m.territory_id === 334 && m.flags.length > 0)).toBe(true);
    expect(items.some((m) => m.status === "void" && m.voided_at)).toBe(true);
    expect(items.some((m) => m.route_id === null)).toBe(true);
    const other = await getFlaggedMemos(await token("tso335", "tso-pass-2"), day);
    expect(other.ok && other.data.items.some((m) => m.flags.includes("future_check_x"))).toBe(true);
  });
  it("a BRAND token is refused with 403", async () => {
    const r = await getFlaggedMemos(await token("brand1", "brand-pass-1"), day);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.status).toBe(403);
  });
  it("the flag filter narrows; an unknown flag answers an empty page", async () => {
    const t = await token("wm1", "wm-pass-1");
    const r = await getFlaggedMemos(t, { ...day, flag: "pack_factor_mismatch" });
    expect(r.ok && r.data.items.map((m) => m.status)).toEqual(["void"]);
    const none = await getFlaggedMemos(t, { ...day, flag: "no_such_flag" });
    expect(none.ok && none.data.items).toEqual([]);
  });
  it("a node outside the reach is 403; a node inside narrows", async () => {
    const t = await token("tso334", "tso-pass-1");
    const out = await getFlaggedMemos(t, { ...day, level: "territory", node_id: 335 });
    expect(out.ok).toBe(false);
    if (!out.ok) expect(out.status).toBe(403);
    const inside = await getFlaggedMemos(t, { ...day, level: "zone", node_id: 3342 });
    expect(inside.ok && inside.data.items.every((m) => m.zone_id === 3342)).toBe(true);
  });
  it("a node inside the reach with no flagged memo is an empty page, not a 403", async () => {
    const t = await token("wm1", "wm-pass-1");
    const r = await getFlaggedMemos(t, { ...day, level: "zone", node_id: 3351, flag: "pack_factor_mismatch" });
    expect(r.ok && r.data.items).toEqual([]);
  });
  it("cursor paging follows next_cursor to the end without a repeat or a gap", async () => {
    const t = await token("wm1", "wm-pass-1");
    const seen: string[] = [];
    let cursor: string | undefined;
    for (let i = 0; i < 5; i++) {
      const r = await getFlaggedMemos(t, { from: "2026-10-05", to: "2026-10-05", flag: "price_mismatch", cursor });
      expect(r.ok).toBe(true);
      if (!r.ok) return;
      seen.push(...r.data.items.map((m) => m.memo_client_uuid));
      if (!r.data.next_cursor) break;
      cursor = r.data.next_cursor;
    }
    expect(seen).toHaveLength(55);
    expect(new Set(seen).size).toBe(55);
  });
  it("a range over 92 days is a 400", async () => {
    const r = await getFlaggedMemos(await token("wm1", "wm-pass-1"), { from: "2026-01-01", to: "2026-10-01" });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.status).toBe(400);
  });
});

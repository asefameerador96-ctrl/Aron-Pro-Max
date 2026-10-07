// F-WEB-050 Web Entry, F-WEB-052 / F-WEB-060 QC entry, F-ADM-024 Data Entry and paper backfill.
import { describe, expect, it, vi } from "vitest";
import { POST as teamPost } from "@/app/api/bff/team-op/route";
import { DataEntryView } from "@/components/admin/config/data-entry-view";
import { QcEntryView } from "@/components/admin/config/qc-entry-view";
import { WebEntryView } from "@/components/admin/config/web-entry-view";
import { activeFaults, buildQcRows } from "@/lib/admin/qc-entry";
import { takaToMtk } from "@/lib/admin/taka";
import type { CodeItem, Sku, WebEntryRouteDay } from "@/lib/admin/types";
import { buildEntry, saleOf } from "@/lib/admin/web-entry";
import { admin, op, req, setupMock, support, tso } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

describe("web entry rules", () => {
  it("sale is issue minus return and never negative", () => {
    expect(saleOf({ sku_id: 1, issue: "10", ret: "3", memos: "" })).toBe(7);
    expect(saleOf({ sku_id: 1, issue: "3", ret: "10", memos: "" })).toBeNull();
    expect(saleOf({ sku_id: 1, issue: "", ret: "", memos: "" })).toBe(0);
  });
  it("builds only the lines with a quantity, refuses return above issue and calls above the target", () => {
    const ok = buildEntry([{ sku_id: 1, issue: "10", ret: "2", memos: "4" }, { sku_id: 2, issue: "", ret: "", memos: "" }], "5", 8);
    expect(ok.errors).toEqual([]);
    expect(ok.lines).toEqual([{ sku_id: 1, issue_qty_base: 10, return_qty_base: 2, memo_count: 4 }]);
    expect(buildEntry([{ sku_id: 1, issue: "2", ret: "5", memos: "" }], "0", 8).errors[0]).toMatchObject({ code: "return_exceeds_issue" });
    expect(buildEntry([], "9", 8).errors[0]).toMatchObject({ field: "calls", code: "too_big" });
    expect(buildEntry([{ sku_id: 1, issue: "1.5", ret: "", memos: "" }], "", 8).errors[0]).toMatchObject({ code: "invalid" });
    expect(buildEntry([{ sku_id: 1, issue: "99999999", ret: "", memos: "" }], "", 8).errors[0]).toMatchObject({ code: "too_big" });
  });
});

const sku = (id: number, name: string): Sku => ({ id, name, short_name: name } as unknown as Sku);
const entry = (over: Partial<WebEntryRouteDay> = {}): WebEntryRouteDay => ({ route_id: 9, business_date: "2026-10-06", lines: [{ sku_id: 1, issue_qty_base: 10, return_qty_base: 2, memo_count: 3 }], successful_calls: 4, target_outlets: 40, app_overlap: false, source: "web_entry", saved_at: null, client_uuid: null, saved_by_user_id: null, ...over });
const emptyOpts = { wing: [], division: [], territory: [], house: [], zone: [] };
const baseWe = { locale: "en" as const, options: emptyOpts, selection: { zone: "334" }, date: "2026-10-06", today: "2026-10-06", routes: [{ id: 9, code: "R9", name: "Banani" }] as never, routeId: "9", entry: entry(), skus: [sku(1, "Gold Leaf 10s"), sku(2, "Match")], canWrite: true };

describe("WebEntryView", () => {
  it("shows the grid with the saved quantities, a read-only sale and the call target", () => {
    const m = html(<WebEntryView {...baseWe} />);
    expect(m).toContain('data-testid="web-entry-grid"');
    expect(m).toContain('data-testid="sale-1">8<');
    expect(text(m)).toContain("At most the 40 target outlets");
    expect(m).not.toContain("backdate-banner");
    expect(text(m)).toContain("Astha channel");
  });
  it("flags an app overlap and asks for a reason when an entry already exists", () => {
    const m = html(<WebEntryView {...baseWe} entry={entry({ app_overlap: true, saved_at: "2026-10-06T05:00:00.000Z" })} />);
    expect(m).toContain("app-overlap");
    expect(text(m)).toContain("Saving replaces it");
    expect(m).toContain('name="reason"');
  });
  it("back-dated day gets the banner; no entry before a route is chosen", () => {
    expect(html(<WebEntryView {...baseWe} date="2026-10-04" />)).toContain("backdate-banner");
    expect(html(<WebEntryView {...baseWe} routeId="" entry={null} />)).not.toContain("web-entry-grid");
  });
  it("save goes to team-op with the uuid and lines; a first save needs no reason, a re-save needs one", async () => {
    h.stub({ method: "POST", path: "/v1/web-entry/route-day", fn: () => ({ status: 200, body: entry() }) });
    const body = { client_uuid: "99999999-9999-4999-8999-999999999999", route_id: 9, business_date: "2026-10-06", lines: [{ sku_id: 1, issue_qty_base: 10, return_qty_base: 2, memo_count: 3 }], successful_calls: 4 };
    const post = async (b: unknown) => teamPost(req("/api/bff/team-op", "POST", b, await tso()));
    expect((await post({ op: "web-entry.save", body })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual(body);
    expect((await post({ op: "web-entry.save", body, reason: "short" })).status).toBe(400);
    expect((await post({ op: "web-entry.save", body, reason: "Memo count corrected after the audit" })).status).toBe(200);
    expect(h.calls()[1]?.body).toEqual({ ...body, change_reason: "Memo count corrected after the audit" });
  });
});

const fault = (code: string, group: string, sort: number, over: Partial<CodeItem> = {}): CodeItem => ({ code, label_en: code, sort, valid_from: "2026-01-01", valid_to: null, attrs: { group, applies_to: "web" }, ...over });
const faults = [...["a", "b", "c", "d", "e"].map((c, i) => fault(`mfc_${c}`, "MFC", i)), ...["a", "b", "c", "d", "e"].map((c, i) => fault(`mkt_${c}`, "MKT", i))];

describe("QC entry", () => {
  it("collects non-zero cells only and refuses non-numbers", () => {
    expect(buildQcRows({ "1|mfc_a": "3", "1|mfc_b": "0", "2|mkt_a": "" })).toEqual({ rows: [{ sku_id: 1, fault_type_code: "mfc_a", qty_base: 3 }], errors: [] });
    expect(buildQcRows({ "1|mfc_a": "1.5" }).errors).toEqual(["1|mfc_a"]);
  });
  it("shows only faults in use", () => {
    expect(activeFaults([...faults, fault("old", "MFC", 9, { valid_to: "2026-02-01" })], "2026-10-06").map((f) => f.code)).not.toContain("old");
    expect(activeFaults(faults, "2026-10-06")).toHaveLength(10);
  });
  const baseQc = { locale: "en" as const, options: emptyOpts, selection: { zone: "334" }, date: "2026-10-06", today: "2026-10-06", routes: [{ id: 9, code: "R9", name: "Banani" }] as never, routeId: "9", faultItems: faults, skus: [sku(1, "Gold Leaf")] };
  it("market QC: ten fault columns in two groups, no reason box; warehouse QC: same grid with a reason", () => {
    const m = html(<QcEntryView {...baseQc} source="market" />);
    expect((m.match(/<th[^>]*title="M(FC|KT)"/g) ?? []).length).toBe(10);
    expect(m).not.toContain('name="reason"');
    const w = html(<QcEntryView {...baseQc} source="warehouse" routeId="" routes={null} />);
    expect(w).toContain('data-testid="qc-grid-warehouse"');
    expect(w).toContain('name="reason"');
  });
  it("says so when the fault types cannot be loaded", () => {
    expect(html(<QcEntryView {...baseQc} source="market" faultItems={null} />)).toContain("faults-unavailable");
  });
  it("save: market has route and no reason; warehouse needs a reason (403 from the API for others passes through)", async () => {
    h.stub({ method: "POST", path: "/v1/web-entry/qc", fn: (c) => ((c.body as { source: string }).source === "warehouse" && !(c.body as { reason?: string }).reason ? { status: 403, body: { type: "x", title: "x", status: 403, code: "ERR_FORBIDDEN", request_id: "00000000-0000-4000-8000-000000000000" } } : { status: 200, body: { client_uuid: "x", stored_rows: 1, replayed: false } }) });
    const rows = [{ sku_id: 1, fault_type_code: "mfc_a", qty_base: 3 }];
    const base = { client_uuid: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", business_date: "2026-10-06", zone_id: 334, rows };
    const post = async (b: unknown) => teamPost(req("/api/bff/team-op", "POST", b, await tso()));
    expect((await post({ op: "qc-entry.save", body: { ...base, source: "market", route_id: 9 } })).status).toBe(200);
    expect((await post({ op: "qc-entry.save", body: { ...base, source: "warehouse" }, reason: "Damaged pallets at the warehouse" })).status).toBe(200);
    expect((await post({ op: "qc-entry.save", body: { ...base, source: "warehouse" } })).status).toBe(403);
    expect((await post({ op: "qc-entry.save", body: { ...base, source: "warehouse" }, reason: "tiny" })).status).toBe(400);
  });
});

describe("data entry and paper backfill", () => {
  it("groups the entry tools and shows the backfill form to roles allowed", () => {
    const m = html(<DataEntryView locale="en" canBackfill />);
    expect(m).toContain('data-testid="entry-groups"');
    for (const href of ["/entry/web", "/entry/qc", "/entry/warehouse-qc", "/final-submit/submit"]) expect(m).toContain(`href="${href}"`);
    expect(m).not.toContain("Astha");
    expect(m).toContain('data-testid="backfill-form"');
    expect(html(<DataEntryView locale="en" canBackfill={false} />)).not.toContain("backfill-form");
  });
  it("taka parsing is exact: no float error", () => {
    expect(takaToMtk("1.005")).toBe(1005);
    expect(takaToMtk("0.1")).toBe(100);
    expect(takaToMtk("1,250.5")).toBe(1_250_500);
    expect(takaToMtk("12.0005")).toBeNull();
    expect(takaToMtk("abc")).toBeNull();
    expect(takaToMtk("-0")).toBe(0);
    expect(takaToMtk("9".repeat(14))).toBeNull();
  });
  it("backfill passes through the proxy as manual source; duplicate memo is a success status; TSO cannot", async () => {
    h.stub({ method: "POST", path: "/v1/admin/data-entry", fn: () => ({ status: 200, body: { memo_client_uuid: "x", status: "duplicate_memo_no", net_mtk: 0 } }) });
    const body = { client_uuid: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", memo_no: "sr334001-261005-017", business_date: "2026-10-05", user_id: 5, route_id: 9, outlet_id: 55, lines: [{ sku_id: 1, qty_base: 10, line_kind: "sale" }], paid_mtk: 500000 };
    expect((await op(await support(), { op: "paper-backfill.create", body, reason: "Phone died in the field" })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ ...body, reason: "Phone died in the field" });
    expect((await op(await admin(), { op: "paper-backfill.create", body, reason: "Phone died in the field" })).status).toBe(200);
    expect((await op(await tso(), { op: "paper-backfill.create", body, reason: "Phone died in the field" })).status).toBe(403);
  });
});

describe("web entry class split (cfg.web.entry_classes)", () => {
  const row = { sku_id: 1, issue: "10", ret: "2", memos: "3" };
  it("one class: the whole sale goes to it", () => {
    expect(buildEntry([row], "", 8, [7]).lines[0]?.class_qty_base).toEqual({ "7": 8 });
  });
  it("several classes: typed split must add up to the sale; an empty split sends none", () => {
    expect(buildEntry([{ ...row, cls: { "7": "5", "9": "3" } }], "", 8, [7, 9]).lines[0]?.class_qty_base).toEqual({ "7": 5, "9": 3 });
    expect(buildEntry([{ ...row, cls: { "7": "5", "9": "2" } }], "", 8, [7, 9]).errors[0]).toMatchObject({ field: "classes", code: "class_sum" });
    const none = buildEntry([row], "", 8, [7, 9]);
    expect(none.errors).toEqual([]);
    expect(none.lines[0]?.class_qty_base).toBeUndefined();
    expect(buildEntry([{ ...row, cls: { "7": "x" } }], "", 8, [7, 9]).errors[0]).toMatchObject({ code: "invalid" });
  });
  it("the grid shows one column per class when there are several", () => {
    const m = text(html(<WebEntryView {...baseWe} classes={[7, 9]} />));
    expect(m).toContain("Class 7");
    expect(m).toContain("Class 9");
    expect(text(html(<WebEntryView {...baseWe} classes={[7]} />))).not.toContain("Class 7");
  });
});

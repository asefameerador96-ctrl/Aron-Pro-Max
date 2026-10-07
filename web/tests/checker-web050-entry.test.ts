// Checker (F-WEB-050 / F-WEB-052 / F-WEB-060 / F-ADM-024): pure rules and the team-op gate.
import { describe, expect, it } from "vitest";
import { POST as teamPost } from "@/app/api/bff/team-op/route";
import { activeFaults, buildQcRows } from "@/lib/admin/qc-entry";
import { takaToMtk } from "@/lib/admin/taka";
import type { CodeItem } from "@/lib/admin/types";
import { buildEntry } from "@/lib/admin/web-entry";
import { req, setupMock, support } from "./helpers/harness";

const h = setupMock();
const fault = (code: string, group: string, sort: number, over: Partial<CodeItem> = {}): CodeItem => ({ code, label_en: code, sort, valid_from: "2026-01-01", valid_to: null, attrs: { group, applies_to: "web" }, ...over });

describe("checker QC fault columns", () => {
  it("DEFECT: manufacturing (MFC) columns come before marketing (MKT) as activeFaults' own doc says", () => {
    const out = activeFaults([fault("mkt_a", "MKT", 1), fault("mfc_a", "MFC", 1)], "2026-10-07").map((f) => String(f.attrs?.group));
    expect(out).toEqual(["MFC", "MKT"]);
  });
  it("DEFECT: a fault retired today (the code-list editor's Retire sets valid_to = today) is no longer offered today", () => {
    // code-list-editor.tsx Retire -> valid_to: today and shows 'retired'; dash/routes.ts treats valid_to as exclusive.
    const out = activeFaults([fault("mfc_a", "MFC", 1, { valid_to: "2026-10-07" })], "2026-10-07").map((f) => f.code);
    expect(out).toEqual([]);
  });
  it("held: a fault starting today is shown; one starting tomorrow is not", () => {
    expect(activeFaults([fault("a_b", "MFC", 1, { valid_from: "2026-10-07" }), fault("c_d", "MFC", 2, { valid_from: "2026-10-08" })], "2026-10-07").map((f) => f.code)).toEqual(["a_b"]);
  });
  it("held: only non-zero cells, leading zeros are integers", () => {
    expect(buildQcRows({ "1|a_b": "0", "1|c_d": "007" }).rows).toEqual([{ sku_id: 1, fault_type_code: "c_d", qty_base: 7 }]);
  });
});

describe("checker web entry rules (held)", () => {
  it("calls cap with target 0 and return > issue", () => {
    expect(buildEntry([], "1", 0).errors[0]).toMatchObject({ field: "calls", code: "too_big" });
    expect(buildEntry([], "0", 0).errors).toEqual([]);
    expect(buildEntry([{ sku_id: 1, issue: "10000000", ret: "10000000", memos: "" }], "", 0).errors).toEqual([]);
    expect(buildEntry([{ sku_id: 1, issue: "10000001", ret: "", memos: "" }], "", 0).errors[0]).toMatchObject({ code: "too_big" });
  });
  it("taka to mtk: negative parses negative (form refuses), exact thirds", () => {
    expect(takaToMtk("-5")).toBe(-5000);
    expect(takaToMtk("0.001")).toBe(1);
  });
  it("team-op refuses SUPPORT for web entry and QC", async () => {
    const c = await support();
    const body = { client_uuid: "99999999-9999-4999-8999-999999999999", route_id: 9, business_date: "2026-10-06", lines: [], successful_calls: 0 };
    expect((await teamPost(req("/api/bff/team-op", "POST", { op: "web-entry.save", body }, c))).status).toBe(403);
    expect((await teamPost(req("/api/bff/team-op", "POST", { op: "qc-entry.save", body: {} }, c))).status).toBe(403);
    expect(h.calls()).toHaveLength(0);
  });
});

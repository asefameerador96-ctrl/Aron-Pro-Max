// F-ADM-034 / F-ADM-053: filter handling, the before/after diff, CSV export, same rows on both pages.
import { describe, expect, it, vi } from "vitest";
import { GET as exportGet } from "@/app/api/bff/admin-export/audit/route";
import { AuditView } from "@/components/admin/config/audit-view";
import { auditCsvRow, cleanAuditFilter, diffLines } from "@/lib/admin/audit";
import { csvCell, toCsv } from "@/lib/admin/csv";
import type { AuditEntry } from "@/lib/admin/types";
import { admin, req, setupMock } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const entry = (id: number, over: Partial<AuditEntry> = {}): AuditEntry => ({ id, at: "2026-10-06T04:00:00.000Z", actor_user_id: 3001, actor_username: "admin1", actor_role: "ADMIN", via: "web", entity: "config", entity_id: "cfg.geo.radius_m", action: "config.change", before: { value: 50 }, after: { value: 100 }, reason: "Wider radius for the market zone", request_id: null, row_hash: "a".repeat(64), ...over });

describe("audit filter", () => {
  it("keeps only values the contract accepts", () => {
    expect(cleanAuditFilter({ entity: "config", actor_user_id: "3001", from: "2026-10-01", action: "config.change" })).toEqual({ entity: "config", actor_user_id: "3001", from: "2026-10-01", action: "config.change" });
    expect(cleanAuditFilter({ entity: "Bad Entity!", actor_user_id: "-1", from: "yesterday", action: "X" })).toEqual({});
  });
});

describe("diffLines", () => {
  it("lists only changed members with old and new value", () => {
    expect(diffLines({ a: 1, b: "x" }, { a: 1, b: "y", c: true })).toEqual([{ key: "b", from: "x", to: "y" }, { key: "c", from: "", to: "true" }]);
    expect(diffLines(null, null)).toEqual([]);
  });
});

describe("AuditView", () => {
  const props = { locale: "en" as const, entries: [entry(2), entry(1, { before: null, after: { name: "A" }, actor_user_id: null, actor_username: null, via: "job", action: "cluster.create" })], filter: { entity: "config" }, nextCursor: "abc" };
  it("shows actor, key, action, before and after, reason", () => {
    const m = text(html(<AuditView {...props} basePath="/admin/audit" />));
    expect(m).toContain("admin1");
    expect(m).toContain("config #cfg.geo.radius_m");
    expect(m).toContain("value: 50 → 100");
    expect(m).toContain("Wider radius for the market zone");
    expect(m).toContain("System"); // actor-less row
  });
  it("the export link and the next link carry the filter", () => {
    const m = html(<AuditView {...props} basePath="/admin/audit" />);
    expect(m).toContain('href="/api/bff/admin-export/audit?entity=config"');
    expect(m).toContain("/admin/audit?entity=config&amp;cursor=abc");
  });
  it("P16 shows the same rows as the audit page for the same filter", () => {
    const rows = (m: string) => m.match(/<tbody>[\s\S]*<\/tbody>/)?.[0];
    expect(rows(html(<AuditView {...props} basePath="/admin/config/audit" titleKey="cfgp.audit.title" />))).toBe(rows(html(<AuditView {...props} basePath="/admin/audit" />)));
  });
});

describe("CSV", () => {
  it("quotes, and neutralises spreadsheet formulas", () => {
    expect(csvCell('say "hi", ok')).toBe('"say ""hi"", ok"');
    expect(csvCell("=HYPERLINK(1)")).toBe("'=HYPERLINK(1)");
    expect(csvCell("+1")).toBe("'+1");
    expect(csvCell(-5)).toBe("-5");
    expect(csvCell(null)).toBe("");
    expect(toCsv(["a"], [["x"]])).toBe("﻿a\r\nx\r\n");
    expect(auditCsvRow(entry(1))).toHaveLength(14);
  });
});

describe("GET /api/bff/admin-export/audit", () => {
  it("exports every page of the filter as CSV", async () => {
    h.stub({ method: "GET", path: "/v1/admin/audit", fn: (c) => (c.query.cursor ? { status: 200, body: { items: [entry(1, { reason: "=cmd|' /C calc'!A0 sample reason" })], next_cursor: null } } : { status: 200, body: { items: [entry(2)], next_cursor: "p2" } }) });
    const res = await exportGet(req("/api/bff/admin-export/audit?entity=config&actor_user_id=3001&bogus=1", "GET", undefined, await admin()));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/csv");
    const body = await res.text();
    expect(body.split("\r\n").filter(Boolean)).toHaveLength(3); // header + 2 rows
    expect(body).toContain("'=cmd");
    expect(h.calls()[0]?.query).toMatchObject({ entity: "config", actor_user_id: "3001", limit: "500" });
    expect(h.calls()[0]?.query.bogus).toBeUndefined();
  });
  it("is refused without a session", async () => {
    expect((await exportGet(req("/api/bff/admin-export/audit", "GET", undefined))).status).toBe(401);
  });
});

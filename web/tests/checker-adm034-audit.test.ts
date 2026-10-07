// Checker (F-ADM-034 / F-ADM-053): defects found by the independent checker. Each test states the expected behaviour.
import { describe, expect, it } from "vitest";
import { GET as exportGet } from "@/app/api/bff/admin-export/audit/route";
import { one } from "@/components/admin/kit/page";
import { AUDIT_EXPORT_MAX_ROWS, cleanAuditFilter } from "@/lib/admin/audit";
import type { AuditEntry } from "@/lib/admin/types";
import { admin, req, setupMock, tso } from "./helpers/harness";

const h = setupMock();
const entry = (id: number): AuditEntry => ({ id, at: "2026-10-06T04:00:00.000Z", actor_user_id: 3001, actor_username: "admin1", actor_role: "ADMIN", via: "web", entity: "config", entity_id: "cfg.geo.radius_m", action: "config.change", before: { value: 50 }, after: { value: 100 }, reason: null, request_id: null, row_hash: "a".repeat(64) });

describe("checker: audit export vs page filter", () => {
  it("an over-long entity_id is not silently dropped by the export while the page filters by it", async () => {
    const long = "k".repeat(70);
    // What the page (AuditPageContent) sends: one() truncates to 64, then cleanAuditFilter keeps it.
    const pageFilter = cleanAuditFilter({ entity_id: one(long, 64) });
    expect(pageFilter.entity_id).toBe("k".repeat(64));
    h.stub({ method: "GET", path: "/v1/admin/audit", fn: () => ({ status: 200, body: { items: [entry(1)], next_cursor: null } }) });
    const res = await exportGet(req(`/api/bff/admin-export/audit?entity_id=${long}`, "GET", undefined, await admin()));
    // Either refuse the bad filter, or export the same filter the page shows; never export the unfiltered log.
    if (res.status === 200) expect(h.calls().at(-1)?.query.entity_id).toBe(pageFilter.entity_id);
    else expect(res.status).toBe(400);
  });
});

describe("checker: export cap", () => {
  it("a capped export tells the user in the file itself (a header is invisible to an <a href> download)", async () => {
    let n = 0;
    h.stub({ method: "GET", path: "/v1/admin/audit", fn: () => ({ status: 200, body: { items: Array.from({ length: 500 }, () => entry(++n)), next_cursor: "more" } }) });
    const res = await exportGet(req("/api/bff/admin-export/audit", "GET", undefined, await admin()));
    const body = await res.text();
    expect(res.headers.get("x-export-truncated")).toBe("1");
    const visible = /truncat|first 10,?000|সীমা/i.test(body) || /truncat|partial/i.test(res.headers.get("content-disposition") ?? "");
    expect(visible).toBe(true);
    expect(body.split("\r\n").filter(Boolean).length).toBeGreaterThanOrEqual(AUDIT_EXPORT_MAX_ROWS + 1);
  });

  it("an API that keeps returning a cursor with empty pages cannot spin the export loop", async () => {
    let calls = 0;
    h.stub({ method: "GET", path: "/v1/admin/audit", fn: () => (++calls > 200 ? { status: 500, body: { code: "ERR_INTERNAL", status: 500, title: "x", type: "x", request_id: crypto.randomUUID() } } : { status: 200, body: { items: [], next_cursor: "same" } }) });
    await exportGet(req("/api/bff/admin-export/audit", "GET", undefined, await admin()));
    // At most cap/page-size + 1 requests are needed; an unbounded loop only ends here because the stub fails at 201.
    expect(calls).toBeLessThanOrEqual(AUDIT_EXPORT_MAX_ROWS / 500 + 1);
  }, 30_000);
});

describe("checker: held", () => {
  it("a TSO is refused the export (403)", async () => {
    expect((await exportGet(req("/api/bff/admin-export/audit", "GET", undefined, await tso()))).status).toBe(403);
  });
});

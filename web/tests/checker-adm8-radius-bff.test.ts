// CHECKER ADM-8 (T2) for F-TSO-025: BFF and rules of radius.propose, plus message coverage.
import { describe, expect, it } from "vitest";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { masterOpRules } from "@/components/admin/master-op-rules";
import { problemMessage, t } from "@/lib/i18n";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Dense market, 80 percent of outlets share a 55 m cell";
const ch = (b: Record<string, unknown> = {}) => [{ key: "cfg.geo.radius_m", scope_type: "territory", scope_id: 6, value: 120, ...b }];
const call = (body: unknown, c: Record<string, string>, reason: unknown = REASON) => opPost(req("/api/bff/master-op", "POST", { op: "radius.propose", body, reason }, c));

describe("radius.propose rules", () => {
  it("a node of another type with the same id is not the territory", async () => {
    const e = await masterOpRules("radius.propose", { changes: ch(), reason: REASON }, {}, "t", "TSO", [{ type: "zone", id: 6 }]);
    expect(e.some((x) => x.code === "forbidden_scope")).toBe(true);
  });
  it("no nodes at all means forbidden, never accepted", async () => {
    const e = await masterOpRules("radius.propose", { changes: ch(), reason: REASON }, {}, "t", "TSO", []);
    expect(e.some((x) => x.code === "forbidden_scope")).toBe(true);
  });
  it("huge, negative and float ids are refused", async () => {
    for (const id of [1e21, Number.MAX_SAFE_INTEGER + 2, -6, 6.5, null, true, [6]]) {
      const e = await masterOpRules("radius.propose", { changes: ch({ scope_id: id }), reason: REASON }, {}, "t", "TSO", [{ type: "territory", id: 6 }]);
      expect(e.length, String(id)).toBeGreaterThan(0);
    }
  });
});

describe("radius.propose BFF", () => {
  it("unauthenticated is 401, ADMIN, SUPPORT and DMO are 403, nothing audited", async () => {
    const n = mock.state.audit.length;
    expect((await call({ changes: ch() }, {})).status).toBe(401);
    for (const u of ["madmin1", "msupport1", "dmo1"] as const) expect((await call({ changes: ch() }, await signIn(u))).status, u).toBe(403);
    expect(mock.state.audit.length).toBe(n);
  });
  it("reason: zero-width padding, whitespace, non-string, 501 code points", async () => {
    const c = await signIn("mtso1");
    const n = mock.state.audit.length;
    for (const r of ["​".repeat(12), " ".repeat(12), `​${"a".repeat(5)}​${"b".repeat(4)}`, 12345678901234, null, "😀".repeat(501)]) expect((await call({ changes: ch() }, c, r)).status, String(r).slice(0, 8)).toBe(400);
    expect(mock.state.audit.length).toBe(n);
    expect((await call({ changes: ch() }, c, "😀".repeat(10))).status).toBe(201); // 10 code points, 20 UTF-16 units
  });
  it("members: break_glass false, effective_to, a second key, a null value and odd numbers are refused", async () => {
    const c = await signIn("mtso1");
    const n = mock.state.audit.length;
    expect((await call({ changes: ch(), break_glass: false }, c)).status).toBe(400);
    for (const m of [{ effective_to: null }, { effective_from: null }, { value: null }, { key: "cfg.geo.tso_radius_mode", value: "apply" }, { scope_type: "global", scope_id: 0 }, { scope_id: 1e21 }, { scope_id: -6 }, { scope_id: 6.5 }, { value: 1000.5 }, { value: -100 }, { value: Number.MAX_SAFE_INTEGER }]) expect((await call({ changes: ch(m) }, c)).status, JSON.stringify(m)).toBe(400);
    expect(mock.state.audit.length).toBe(n);
  });
  it("a body that is not an object, or changes that are not an array, is 400", async () => {
    const c = await signIn("mtso1");
    for (const b of [null, [], "x", { changes: {} }, { changes: [] }, { changes: [null] }, { changes: ["x"] }]) expect((await call(b, c)).status, JSON.stringify(b)).toBe(400);
  });
});

describe("radius messages", () => {
  it("every ConfigChangeStatus has its own radius.status text in both languages", () => {
    for (const s of ["pending_approval", "scheduled", "applied", "rejected", "cancelled", "expired", "reverted"]) {
      for (const l of ["en", "bn"] as const) expect(typeof t(l, `radius.status.${s}` as never), `${l} ${s}`).toBe("string");
    }
  });
  it("registry refusals show their own text, not the generic retry message", () => {
    for (const code of ["ERR_CFG_OUT_OF_BOUNDS", "ERR_CFG_SCOPE_NOT_ALLOWED", "ERR_CFG_FREEZE_WINDOW", "ERR_CFG_REASON_REQUIRED"]) {
      for (const l of ["en", "bn"] as const) expect(problemMessage(l, code), `${l} ${code}`).not.toBe(t(l, "error.generic"));
    }
  });
});

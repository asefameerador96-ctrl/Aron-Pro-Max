import { describe, expect, it } from "vitest";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { accessFor } from "@/lib/auth/roles";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Dense market, 80 percent of outlets share a 55 m cell";
const call = (changes: unknown, c: Record<string, string>, reason = REASON, extra: Record<string, unknown> = {}) => opPost(req("/api/bff/master-op", "POST", { op: "radius.propose", body: { changes, ...extra }, reason }, c));
const one = (b: Record<string, unknown> = {}) => [{ key: "cfg.geo.radius_m", scope_type: "territory", scope_id: 6, value: 120, ...b }];

describe("radius proposal (F-TSO-025)", () => {
  it("a TSO proposes a radius for its own territory: it waits for approval and is audited with the reason", async () => {
    const c = await signIn("mtso1");
    const r = await call(one(), c);
    expect(r.status).toBe(201);
    expect(((await r.json()) as { data: { status: string } }).data.status).toBe("pending_approval");
    expect(mock.state.audit.at(-1)).toMatchObject({ action: "config.propose", reason: REASON });
  });
  it("an increase above 150 m escalates (risk class 3)", async () => {
    const c = await signIn("mtso1");
    expect(((await (await call(one({ value: 200 }), c)).json()) as { data: { risk_class: number } }).data.risk_class).toBe(3);
  });
  it("refuses another territory (403, from the token), other keys or scope levels, bad values, extra members and a short reason", async () => {
    const c = await signIn("mtso1");
    const n = mock.state.audit.length;
    expect((await call(one({ scope_id: 7 }), c)).status).toBe(403);
    for (const b of [{ key: "cfg.geo.max_accuracy_m" }, { scope_type: "zone" }, { value: 9 }, { value: 5001 }, { value: 100.5 }, { value: "100" }, { scope_id: 0 }, { scope_id: "6" }, { effective_from: "2026-10-10T00:00:00Z" }]) expect((await call(one(b), c)).status, JSON.stringify(b)).toBe(400);
    expect((await call([...one(), ...one()], c)).status).toBe(400);
    expect((await call(one(), c, "short")).status).toBe(400);
    expect((await call(one(), c, REASON, { break_glass: true })).status).toBe(400);
    expect(mock.state.audit.length).toBe(n);
  });
  it("only a TSO may use it, and only a TSO sees the page", async () => {
    expect((await call(one(), await signIn("msupport1"))).status).toBe(403);
    expect((await call(one(), await signIn("madmin1"))).status).toBe(403);
    expect(accessFor("TSO", "/admin/radius")).toBe("ok");
    expect(accessFor("ADMIN", "/admin/radius")).toBe("forbidden");
    expect(accessFor("DMO", "/admin/radius")).toBe("forbidden");
  });
});

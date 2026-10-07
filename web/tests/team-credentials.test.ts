import { describe, expect, it } from "vitest";
import { accessFor } from "@/lib/auth/roles";
import { ADMIN_MENU } from "@/app/admin/menu";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const callOp = (op: string, args: Record<string, unknown>, c: Record<string, string>) => opPost(req("/api/bff/master-op", "POST", { op, ...args }, c));
const REASON = "Phone was replaced, password forgotten";

describe("team credentials (F-TSO-023)", () => {
  it("a TSO resets an SR of its own zone: temporary password once, 24 hours, audited with the reason", async () => {
    const c = await signIn("mtso1");
    const r = await callOp("credential.manage", { params: { id: 1001 }, body: { action: "reset_password" }, reason: REASON }, c);
    expect(r.status).toBe(200);
    const d = ((await r.json()) as { data: { temporary_password: string; temporary_password_expires_at: string } }).data;
    expect(d.temporary_password.length).toBeGreaterThanOrEqual(12);
    expect(Date.parse(d.temporary_password_expires_at) - Date.now()).toBeGreaterThan(23 * 3600_000);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "user", entity_id: "1001", action: "user.reset_password", reason: REASON });
  });
  it("a TSO unlocks an SR: no password in the answer, audited", async () => {
    const c = await signIn("mtso1");
    const r = await callOp("credential.manage", { params: { id: 1002 }, body: { action: "unlock" }, reason: REASON }, c);
    expect(r.status).toBe(200);
    expect(((await r.json()) as { data: { temporary_password: unknown } }).data.temporary_password).toBeNull();
    expect(mock.state.audit.at(-1)).toMatchObject({ action: "user.unlock" });
  });
  it("a TSO cannot reach a user of another zone (404), another TSO or an administrator, nor use other actions; nothing is audited", async () => {
    const c = await signIn("mtso1");
    const n = mock.state.audit.length;
    expect((await callOp("credential.manage", { params: { id: 1003 }, body: { action: "reset_password" }, reason: REASON }, c)).status).toBe(404); // zone 15
    expect((await callOp("credential.manage", { params: { id: 2001 }, body: { action: "reset_password" }, reason: REASON }, c)).status).toBe(404);
    expect((await callOp("credential.manage", { params: { id: 3001 }, body: { action: "reset_password" }, reason: REASON }, c)).status).toBe(404);
    for (const action of ["force_logout", "reset_mfa", "nope"]) expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action }, reason: REASON }, c)).status).toBe(400);
    expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock", extra: 1 }, reason: REASON }, c)).status).toBe(400);
    expect(mock.state.audit.length).toBe(n);
  });
  it("a reason is mandatory (10 characters)", async () => {
    const c = await signIn("mtso1");
    expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: "short" }, c)).status).toBe(400);
    expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: "" }, c)).status).toBe(400);
  });
  it("DMO and other web roles get 403; SUPPORT may reset any role in reach", async () => {
    const dmo = await signIn("dmo1");
    expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: REASON }, dmo)).status).toBe(403);
    const support = await signIn("msupport1");
    expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: REASON }, support)).status).toBe(200);
  });
  it("the team page is open to TSO, SUPPORT, ADMIN and SUPERADMIN only, and is in their menu", () => {
    for (const r of ["TSO", "SUPPORT", "ADMIN", "SUPERADMIN"] as const) expect(accessFor(r, "/admin/team")).toBe("ok");
    for (const r of ["DMO", "WM", "TOP", "ANALYST"] as const) expect(accessFor(r, "/admin/team")).toBe("forbidden");
    expect(accessFor("TSO", "/admin/users")).toBe("forbidden");
    expect(ADMIN_MENU.find((m) => m.id === "admin-team")?.roles).toContain("TSO");
  });
});

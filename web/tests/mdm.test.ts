// D-MDM-01 MDM section: access levels, route gating, menu, schedule grid <-> block windows, policy validation, command params,
// the whitelisted operations through the BFF and the mock endpoints (idempotent create, second approver, optimistic version).
import { describe, expect, it } from "vitest";
import { commandParams } from "@/components/admin/mdm/command-panel";
import { OPS } from "@/lib/admin/ops";
import { SLOTS, emptyGrid, gridToWindows, mdmLevel, mdmLevelOf, packageWindows, validatePolicy, windowsToGrid, type MdmBlockWindow } from "@/lib/admin/mdm";
import { accessFor } from "@/lib/auth/roles";
import { menuFor } from "@/lib/menu/menu";
import { en } from "@/lib/i18n/messages-en";
import { bn } from "@/lib/i18n/messages-bn";
import { admin, op, signIn, support, tso, setupMock } from "./helpers/harness";

const h = setupMock();
const REASON = "Phone reported lost by the SR";

describe("access level", () => {
  it("SUPERADMIN writes, ADMIN follows the mdm grant (writes while no grant claim exists), others have none", () => {
    expect(mdmLevel("SUPERADMIN")).toBe("w");
    expect(mdmLevel("SUPERADMIN", { mdm: "r" })).toBe("w");
    expect(mdmLevel("ADMIN")).toBe("w");
    expect(mdmLevel("ADMIN", { mdm: "w" })).toBe("w");
    expect(mdmLevel("ADMIN", { mdm: "r" })).toBe("r");
    expect(mdmLevel("ADMIN", { reports: "w" })).toBe("w"); // a claim without mdm is not read here: absent key = no grant claim for the module
    expect(mdmLevel("ADMIN", { mdm: "x" })).toBe("none");
    for (const r of ["SUPPORT", "ANALYST", "TOP", "DMO", "WM", "TSO", "SR", "AMO"] as const) expect(mdmLevel(r)).toBe("none");
    expect(mdmLevelOf({ role: "ADMIN", modules: { mdm: "r" } })).toBe("r");
  });
  it("the route group and the menu allow ADMIN and SUPERADMIN only", () => {
    expect(accessFor("ADMIN", "/admin/mdm")).toBe("ok");
    expect(accessFor("SUPERADMIN", "/admin/mdm/devices/3")).toBe("ok");
    expect(accessFor("SUPPORT", "/admin/mdm")).toBe("forbidden");
    expect(accessFor("TSO", "/admin/mdm/policies")).toBe("forbidden");
    const ids = (r: Parameters<typeof menuFor>[0]) => menuFor(r).flatMap((s) => s.items.map((i) => i.id));
    expect(ids("ADMIN")).toContain("admin-mdm");
    expect(ids("SUPERADMIN")).toContain("admin-mdm");
    expect(ids("SUPPORT")).not.toContain("admin-mdm");
  });
  it("the write operations are ADMIN and SUPERADMIN only, and the reason travels in `reason`", () => {
    for (const k of ["mdm.command", "mdm.decide", "mdm.assign", "mdm.policy.create", "mdm.policy.update", "mdm.enrolment.create", "mdm.enrolment.revoke"] as const) {
      expect(OPS[k].roles).toEqual(["ADMIN", "SUPERADMIN"]);
      expect(OPS[k].path.startsWith("/v1/admin/mdm/")).toBe(true);
    }
    expect(OPS["mdm.command"].reason).toBe("reason");
    expect(OPS["mdm.decide"].reason).toBe("reason");
  });
  it("every MDM string exists in Bangla and English", () => {
    const keys = Object.keys(en).filter((k) => k.startsWith("mdm.") || k === "menu.admin.mdm");
    expect(keys.length).toBeGreaterThan(80);
    for (const k of keys) expect(bn[k as keyof typeof bn], k).toBeTruthy();
  });
});

describe("schedule grid", () => {
  it("joins weekdays with the same hours into one window", () => {
    const g = emptyGrid();
    for (const d of [0, 1, 2]) for (let s = 18; s < 36; s++) g[d]![s] = true; // Mon-Wed 09:00-18:00
    for (let s = 20; s < 24; s++) g[4]![s] = true; // Fri 10:00-12:00
    expect(gridToWindows(g)).toEqual([
      { days: [1, 2, 3], start: "09:00", end: "18:00" },
      { days: [5], start: "10:00", end: "12:00" },
    ]);
  });
  it("a block that reaches midnight ends at 00:00 and a full day is 00:00-00:00", () => {
    const g = emptyGrid();
    for (let s = 44; s < SLOTS; s++) g[5]![s] = true; // Sat 22:00-24:00
    g[6]!.fill(true); // Sun all day
    expect(gridToWindows(g)).toEqual([
      { days: [6], start: "22:00", end: "00:00" },
      { days: [7], start: "00:00", end: "00:00" },
    ]);
  });
  it("round-trips: windows -> grid -> windows is the same", () => {
    const w: MdmBlockWindow[] = [
      { days: [1, 2, 3, 4, 5], start: "09:00", end: "13:30" },
      { days: [6], start: "00:00", end: "00:00" },
    ];
    expect(gridToWindows(windowsToGrid(w))).toEqual(w);
  });
  it("a window that runs past midnight continues on the next weekday; windows naming packages stay off the grid", () => {
    const g = windowsToGrid([{ days: [7], start: "22:00", end: "06:00" }]);
    expect(g[6]!.slice(44).every(Boolean)).toBe(true); // Sunday evening
    expect(g[0]!.slice(0, 12).every(Boolean)).toBe(true); // Monday early morning
    expect(g[0]!.slice(12).some(Boolean)).toBe(false);
    const named: MdmBlockWindow = { days: [1], start: "09:00", end: "10:00", packages: ["com.facebook.katana"] };
    expect(gridToWindows(windowsToGrid([named]))).toEqual([]);
    expect(packageWindows([named, { days: [2], start: "01:00", end: "02:00" }])).toEqual([named]);
  });
  it("an empty grid has no windows", () => expect(gridToWindows(emptyGrid())).toEqual([]));
});

describe("policy validation and command params", () => {
  const ok = { name: "Field day", blockWindows: [], kioskOn: false, kioskPackages: [], samplingMode: "on_demand", intervalMin: "15" };
  it("checks name, window count, kiosk packages and the interval", () => {
    expect(validatePolicy(ok)).toEqual({});
    expect(validatePolicy({ ...ok, name: " a " }).name).toBe("required");
    expect(validatePolicy({ ...ok, name: "x".repeat(81) }).name).toBe("too_long");
    expect(validatePolicy({ ...ok, blockWindows: Array.from({ length: 15 }, () => ({ days: [1], start: "01:00", end: "02:00" })) }).schedule).toBe("too_many");
    expect(validatePolicy({ ...ok, kioskOn: true }).kiosk).toBe("required");
    expect(validatePolicy({ ...ok, samplingMode: "interval", intervalMin: "4" }).interval).toBe("invalid");
    expect(validatePolicy({ ...ok, samplingMode: "interval", intervalMin: "30" })).toEqual({});
  });
  it("message needs a text, lock may carry one, the others carry none", () => {
    expect(commandParams("message", { bn: " বার্তা ", en: "" })).toEqual({ text_bn: "বার্তা" });
    expect(commandParams("message", { bn: "", en: "" })).toEqual({});
    expect(commandParams("lock", { bn: "", en: "Call the office" })).toEqual({ message: "Call the office" });
    expect(commandParams("lock", { bn: "", en: "" })).toBeUndefined();
    expect(commandParams("reboot", { bn: "x", en: "y" })).toBeUndefined();
  });
});

describe("through the BFF and the mock", () => {
  const cid = () => crypto.randomUUID();
  it("a command is idempotent by command_id: same body = same command, a different body = 409", async () => {
    const c = await admin();
    const body = { command_id: cid(), type: "lock" };
    const a = await op(c, { op: "mdm.command", params: { device_id: "1" }, body, reason: REASON });
    expect(a.status).toBe(201);
    const first = (await a.json()) as { data: { command_id: string; state: string } };
    expect(first.data.state).toBe("queued");
    const again = await op(c, { op: "mdm.command", params: { device_id: "1" }, body, reason: REASON });
    expect(again.status).toBe(200);
    expect(((await again.json()) as { data: { command_id: string } }).data.command_id).toBe(first.data.command_id);
    const clash = await op(c, { op: "mdm.command", params: { device_id: "1" }, body, reason: "A different reason text here" });
    expect(clash.status).toBe(409);
    expect(h.mock.state.mdm.commands.filter((x) => x.command_id === body.command_id)).toHaveLength(1);
  });
  it("a command needs a reason of 10 characters and a known type", async () => {
    const c = await admin();
    expect((await op(c, { op: "mdm.command", params: { device_id: "1" }, body: { command_id: cid(), type: "lock" }, reason: "short" })).status).toBe(400);
    expect((await op(c, { op: "mdm.command", params: { device_id: "1" }, body: { command_id: cid(), type: "format_sdcard" }, reason: REASON })).status).toBe(400);
    expect((await op(c, { op: "mdm.command", params: { device_id: "1" }, body: { command_id: cid(), type: "message", params: {} }, reason: REASON })).status).toBe(400);
  });
  it("a factory reset waits for a second approver who is not the requester", async () => {
    const c = await admin();
    const id = cid();
    const made = await op(c, { op: "mdm.command", params: { device_id: "3" }, body: { command_id: id, type: "factory_reset" }, reason: REASON });
    expect(((await made.json()) as { data: { state: string } }).data.state).toBe("pending_approval");
    const self = await op(c, { op: "mdm.decide", params: { command_id: id }, body: { decision: "approve" }, reason: REASON });
    expect(self.status).toBe(409);
    const other = await signIn("madmin1", "admin-pass-1", true);
    const done = await op(other, { op: "mdm.decide", params: { command_id: id }, body: { decision: "approve" }, reason: REASON });
    expect(done.status).toBe(200);
    expect(((await done.json()) as { data: { state: string; approved_by: string } }).data).toMatchObject({ state: "queued", approved_by: "madmin1" });
  });
  it("SUPPORT and TSO cannot run MDM operations", async () => {
    const body = { command_id: cid(), type: "lock" };
    expect((await op(await support(), { op: "mdm.command", params: { device_id: "1" }, body, reason: REASON })).status).toBe(403);
    expect((await op(await tso(), { op: "mdm.command", params: { device_id: "1" }, body, reason: REASON })).status).toBe(403);
    expect(h.mock.state.mdm.commands.some((x) => x.command_id === body.command_id)).toBe(false);
  });
  it("a policy update needs the current version; the second writer with the old version gets 409", async () => {
    const c = await admin();
    const write = { name: "Field day", description: null, allowed_packages: ["com.whatsapp"], block_windows: [{ days: [1], start: "09:00", end: "18:00" }], kiosk: { enabled: false }, location_sampling: { mode: "on_demand" } };
    const ok = await op(c, { op: "mdm.policy.update", params: { policy_id: "1" }, body: { ...write, version: 1 }, reason: REASON });
    expect(ok.status).toBe(200);
    expect(((await ok.json()) as { data: { version: number } }).data.version).toBe(2);
    const stale = await op(c, { op: "mdm.policy.update", params: { policy_id: "1" }, body: { ...write, version: 1 }, reason: REASON });
    expect(stale.status).toBe(409);
  });
  it("assigning a policy changes the device and the activity log records it", async () => {
    const c = await admin();
    const res = await op(c, { op: "mdm.assign", params: { device_id: "4" }, body: { policy_id: 2 }, reason: REASON });
    expect(res.status).toBe(200);
    expect(h.mock.state.mdm.devices.find((d) => d.device_id === 4)!.policy_id).toBe(2);
    expect(h.mock.state.mdm.activity[0]).toMatchObject({ kind: "policy", device_id: 4, actor: "admin1" });
  });
  it("an enrolment code is created once and revoked", async () => {
    const c = await admin();
    const made = await op(c, { op: "mdm.enrolment.create", body: { flavour: "sr", lockdown_level: "prod", max_uses: 3, expires_in_h: 24 } });
    expect(made.status).toBe(201);
    const { data } = (await made.json()) as { data: { token: { token_id: number }; qr_text: string } };
    expect(data.qr_text).toContain("aron.enrolment_token");
    const gone = await op(c, { op: "mdm.enrolment.revoke", params: { token_id: String(data.token.token_id) }, noBody: true });
    expect(gone.status).toBe(200);
  });
});

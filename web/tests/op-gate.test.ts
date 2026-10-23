import { describe, expect, it, vi } from "vitest";

const me = vi.hoisted(() => ({ modules: null as Record<string, "r" | "w"> | null }));
vi.mock("@/lib/api/raw", () => ({ rawRequest: async () => ({ ok: true, status: 200, data: { modules: me.modules } }) }));

import { loadOpGate } from "@/lib/admin/access";
import { OPS, type OpKey } from "@/lib/admin/ops";
import { moduleOfApiPath } from "@/lib/admin/modules";

describe("write controls follow the ADMIN module grant (D-WEB-ADMIN-MENU-01)", () => {
  it("every portal operation path belongs to a module, so a read-only grant can hide it", () => {
    const orphans = (Object.keys(OPS) as OpKey[]).filter((k) => moduleOfApiPath(OPS[k].path.replace(/\{[^}]+\}/g, "1")) === null);
    expect(orphans).toEqual([]);
  });
  it("an ADMIN with masterdata at read cannot start a holiday, one at write can", async () => {
    me.modules = { masterdata: "r", config: "w" };
    const gate = await loadOpGate("t-read", "ADMIN");
    expect(gate("holiday.create")).toBe(false);
    expect(gate("config.change")).toBe(true);
    expect(gate("device.state")).toBe(false); // mdm: no grant
  });
  it("no claim (grant tables not deployed) or another role keeps the role rule only", async () => {
    me.modules = null;
    expect((await loadOpGate("t-null", "ADMIN"))("holiday.create")).toBe(true);
    expect((await loadOpGate("t-super", "SUPERADMIN"))("holiday.create")).toBe(true);
    expect((await loadOpGate("t-support", "SUPPORT"))("holiday.create")).toBe(false); // SUPPORT is not in the op's roles
  });
});

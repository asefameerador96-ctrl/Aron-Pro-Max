import { describe, expect, it } from "vitest";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { formatPriceMtk, mtkToTaka, parseTakaToMtk } from "@/lib/admin/price-money";
import { ALLOWED_SCOPE_TYPES, scopeNodeAllowed } from "@/lib/admin/scope-rules";
import { ALL_ROLES } from "@/lib/auth/roles";
import { businessDate } from "@/lib/i18n";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Reason that is long enough";
const plus = (n: number) => businessDate(new Date(Date.now() + n * 86_400_000));
const op = (body: unknown, c: Record<string, string>) => opPost(req("/api/bff/master-op", "POST", body, c));
const BATCH = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

describe("money (integer milli-taka, three decimals, no floats)", () => {
  it("parses taka text to integer mtk, Bengali digits included", () => {
    expect(parseTakaToMtk("12")).toBe(12000);
    expect(parseTakaToMtk("12.5")).toBe(12500);
    expect(parseTakaToMtk("12.505")).toBe(12505);
    expect(parseTakaToMtk("১২.৫০৫")).toBe(12505);
    expect(parseTakaToMtk("0.001")).toBe(1);
    expect(parseTakaToMtk("0.1")).toBe(100); // no 0.1 + 0.2 style drift
    expect(parseTakaToMtk("12.5051")).toBeNull();
    expect(parseTakaToMtk("-1")).toBeNull();
    expect(parseTakaToMtk("1e3")).toBeNull();
    expect(parseTakaToMtk("")).toBeNull();
    expect(parseTakaToMtk("12,5")).toBeNull();
    expect(parseTakaToMtk("1".repeat(13))).toBeNull();
  });
  it("formats with exactly three decimals and Bengali digits for bn", () => {
    expect(mtkToTaka(12500)).toBe("12.500");
    expect(mtkToTaka(7)).toBe("0.007");
    expect(formatPriceMtk("bn", 12505)).toBe("১২.৫০৫");
    expect(formatPriceMtk("en", 12505)).toBe("12.505");
  });
});

describe("role-node consistency", () => {
  it("AMO zone, TSO territory, DMO division, WM wing, national roles national, SR none", () => {
    expect(ALLOWED_SCOPE_TYPES.AMO).toEqual(["zone"]);
    expect(ALLOWED_SCOPE_TYPES.TSO).toEqual(["territory"]);
    expect(ALLOWED_SCOPE_TYPES.DMO).toEqual(["division"]);
    expect(ALLOWED_SCOPE_TYPES.WM).toEqual(["wing"]);
    expect(ALLOWED_SCOPE_TYPES.SR).toEqual([]);
    for (const r of ["TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN"] as const) expect(ALLOWED_SCOPE_TYPES[r]).toEqual(["national"]);
    expect(Object.keys(ALLOWED_SCOPE_TYPES).sort()).toEqual([...ALL_ROLES].sort());
    expect(scopeNodeAllowed("TSO", "zone")).toBe(false);
  });
});

describe("user scope (F-ADM-008)", () => {
  const put = (id: number, nodes: unknown, c: Record<string, string>, extra: Record<string, unknown> = {}) => op({ op: "user-scope.put", params: { id }, body: { valid_from: plus(1), nodes, ...extra }, reason: REASON }, c);
  it("replaces a TSO's territories from a date and audits the reason", async () => {
    const c = await signIn("madmin1");
    const res = await put(2001, [{ node_type: "territory", node_id: 7 }, { node_type: "territory", node_id: 6 }], c);
    expect(res.status).toBe(200);
    expect(mock.state.custom.scopes[2001]!.nodes).toHaveLength(2);
    expect(mock.state.custom.scopes[2001]!.version).toBe(8);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "user_scope", reason: REASON });
  });
  it("refuses a node type the role may not hold, an empty scope for a supervisor, past dates, unknown members", async () => {
    const c = await signIn("madmin1");
    const bad = await put(2001, [{ node_type: "zone", node_id: 14 }], c);
    expect(bad.status).toBe(400);
    expect((await bad.json()).errors[0]).toMatchObject({ code: "role_node_mismatch" });
    expect((await put(2001, [], c)).status).toBe(400);
    expect((await put(2001, [{ node_type: "territory", node_id: 0 }], c)).status).toBe(400); // only national is 0
    expect((await op({ op: "user-scope.put", params: { id: 2001 }, body: { valid_from: "2020-01-01", nodes: [{ node_type: "territory", node_id: 6 }] }, reason: REASON }, c)).status).toBe(400);
    expect((await put(2001, [{ node_type: "territory", node_id: 6, extra: 1 }], c)).status).toBe(400);
    expect((await put(2001, [{ node_type: "territory", node_id: 6 }], c, { surprise: true })).status).toBe(400);
    expect(mock.state.audit).toHaveLength(0);
  });
  it("an SR has no scope; an admin-role user can only be changed by a SUPERADMIN", async () => {
    const c = await signIn("madmin1");
    expect((await put(1001, [{ node_type: "zone", node_id: 14 }], c)).status).toBe(400);
    const admin = await put(3001, [{ node_type: "national", node_id: 0 }], c);
    expect(admin.status).toBe(400);
    expect((await admin.json()).errors[0]).toMatchObject({ code: "forbidden_target" });
  });
  it("support cannot write; the user id is validated", async () => {
    const s = await signIn("msupport1");
    expect((await put(2001, [{ node_type: "territory", node_id: 6 }], s)).status).toBe(403);
    const c = await signIn("madmin1");
    expect((await op({ op: "user-scope.put", params: { id: "../x" }, body: { valid_from: plus(1), nodes: [] }, reason: REASON }, c)).status).toBe(400);
  });
});

describe("sales plan (F-ADM-006)", () => {
  const put = (zone: number, ids: unknown, c: Record<string, string>, from = plus(1)) => op({ op: "sales-plan.put", params: { zone_id: zone }, body: { valid_from: from, sku_ids: ids }, reason: REASON }, c);
  it("saves a zone's SKUs atomically with the reason; the old plan is replaced from the date", async () => {
    const c = await signIn("madmin1");
    expect((await put(14, [1, 2], c)).status).toBe(200);
    expect(mock.state.custom.salesPlans[14]!.sku_ids).toEqual([1, 2]);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "sales_plan", entity_id: "14", reason: REASON });
  });
  it("refuses unknown SKUs (nothing saved), duplicates, too many, past dates, a short reason", async () => {
    const c = await signIn("madmin1");
    expect((await put(14, [1, 999], c)).status).toBe(400);
    expect(mock.state.custom.salesPlans[14]!.sku_ids).toEqual([1]);
    expect((await put(14, [1, 1], c)).status).toBe(400);
    expect((await put(14, Array.from({ length: 501 }, (_, i) => i + 1), c)).status).toBe(400);
    expect((await put(14, [1], c, "2020-01-01")).status).toBe(400);
    expect((await op({ op: "sales-plan.put", params: { zone_id: 14 }, body: { valid_from: plus(1), sku_ids: [1] }, reason: "short" }, c)).status).toBe(400);
    expect((await put(14, [1.5], c)).status).toBe(400);
  });
});

describe("prices (F-ADM-005)", () => {
  const rows = (amount: number) => [{ sku_id: 1, price_type: "outlet", amount_mtk: amount }];
  const call = (which: "price.preview" | "price.publish", prices: unknown, c: Record<string, string>, batch = BATCH) => op({ op: which, body: { batch_uuid: batch, valid_from: plus(2), prices }, reason: REASON }, c);
  it("previews the effect (skus, outlets, devices, largest change) without writing", async () => {
    const c = await signIn("madmin1");
    const res = await call("price.preview", rows(1_260_000), c);
    expect(res.status).toBe(200);
    expect((await res.json()).data).toMatchObject({ skus_affected: 1, max_change_pct: 5, approval_required: false });
    expect(mock.state.custom.priceBatches.size).toBe(0);
  });
  it("publishes once per batch_uuid (a retry replays) and closes the old row at the effective date", async () => {
    const c = await signIn("madmin1");
    expect((await (await call("price.publish", rows(1_260_000), c)).json()).data).toMatchObject({ status: "applied", replayed: false });
    expect((await (await call("price.publish", rows(1_260_000), c)).json()).data).toMatchObject({ replayed: true });
    const open = mock.state.custom.prices.filter((p) => p.sku_id === 1 && p.price_type === "outlet");
    expect(open.filter((p) => p.valid_to === null)).toHaveLength(1);
    expect(open.find((p) => p.valid_to !== null)).toMatchObject({ valid_to: plus(2) });
    expect(mock.state.audit.filter((a) => a.entity === "price_batch")).toHaveLength(1);
  });
  it("a large change asks for a second approver", async () => {
    const c = await signIn("madmin1");
    expect((await (await call("price.preview", rows(2_000_000), c)).json()).data).toMatchObject({ approval_required: true });
  });
  it("amounts must be non-negative safe integers (mtk), types known, no duplicates, no floats", async () => {
    const c = await signIn("madmin1");
    for (const bad of [rows(1.5), rows(-1), rows(Number.MAX_SAFE_INTEGER + 2), [{ sku_id: 1, price_type: "gold", amount_mtk: 1 }], [...rows(1), ...rows(2)], [], [{ sku_id: 0, price_type: "cc", amount_mtk: 1 }], [{ sku_id: 1, price_type: "cc", amount_mtk: 1, per_base_qty: 0 }], [{ sku_id: 1, price_type: "cc", amount_mtk: "1000" }]]) {
      expect((await call("price.preview", bad, c)).status).toBe(400);
    }
    expect((await call("price.preview", rows(1), c, "not-a-uuid")).status).toBe(400);
    expect((await op({ op: "price.preview", body: { batch_uuid: BATCH, valid_from: "2020-01-01", prices: rows(1) }, reason: REASON }, c)).status).toBe(400);
  });
  it("support cannot publish", async () => {
    const s = await signIn("msupport1");
    expect((await call("price.publish", rows(1_300_000), s)).status).toBe(403);
  });
});

describe("assignment ops (SR transfer)", () => {
  it("a transfer is a new primary from the date plus the old one ending at it; both audited", async () => {
    const c = await signIn("madmin1");
    const create = await op({ op: "assignment.create", body: { route_id: 4, user_id: 1001, kind: "primary", valid_from: plus(5) }, reason: REASON }, c);
    expect(create.status).toBe(201);
    const end = await op({ op: "assignment.end", params: { id: 1 }, body: { valid_to: plus(5) }, reason: REASON }, c);
    expect(end.status).toBe(200);
    expect(mock.state.audit.filter((a) => a.entity === "route_assignment")).toHaveLength(2);
  });
  it("past dates, unknown members, a reason over 300 and support are refused", async () => {
    const c = await signIn("madmin1");
    const base = { route_id: 4, user_id: 1001, kind: "primary", valid_from: plus(5) };
    expect((await op({ op: "assignment.create", body: { ...base, valid_from: "2020-01-01" }, reason: REASON }, c)).status).toBe(400);
    expect((await op({ op: "assignment.create", body: { ...base, extra: 1 }, reason: REASON }, c)).status).toBe(400);
    expect((await op({ op: "assignment.create", body: base, reason: "x".repeat(301) }, c)).status).toBe(400);
    const s = await signIn("msupport1");
    expect((await op({ op: "assignment.create", body: base, reason: REASON }, s)).status).toBe(403);
  });
});

describe("master-op whitelist", () => {
  it("rejects unknown operations and anything but an object", async () => {
    const c = await signIn("madmin1");
    expect((await op({ op: "users.delete", params: { id: 1 } }, c)).status).toBe(400);
    expect((await op("x", c)).status).toBe(400);
    expect((await op({ op: "constructor" }, c)).status).toBe(400);
    expect((await opPost(req("/api/bff/master-op", "POST", { op: "sales-plan.put" }))).status).toBe(401);
  });
});

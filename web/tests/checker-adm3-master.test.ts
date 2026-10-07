import { describe, expect, it } from "vitest";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { businessDate } from "@/lib/i18n";
import { req, setupMock } from "./helpers/bff";

const { signIn } = setupMock();
const REASON = "Reason that is long enough";
const plus = (n: number) => businessDate(new Date(Date.now() + n * 86_400_000));
const op = (body: unknown, c: Record<string, string>) => opPost(req("/api/bff/master-op", "POST", body, c));
const BATCH = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const scope = (id: number, nodes: unknown, c: Record<string, string>) => op({ op: "user-scope.put", params: { id }, body: { valid_from: plus(1), nodes }, reason: REASON }, c);

describe("checker-adm3 user scope (F-ADM-008)", () => {
  it("D1 an unknown user is 404, not a 400 validation error", async () => {
    const c = await signIn("madmin1");
    expect((await scope(999999, [{ node_type: "territory", node_id: 6 }], c)).status).toBe(404);
  });
  it("D2 the same node twice is refused (duplicate)", async () => {
    const c = await signIn("madmin1");
    expect((await scope(2001, [{ node_type: "territory", node_id: 6 }, { node_type: "territory", node_id: 6 }], c)).status).toBe(400);
  });
  it("D3 national scope only has node id 0", async () => {
    const c = await signIn("madmin1");
    const res = await scope(2001, [{ node_type: "national", node_id: 5 }], c);
    expect(res.status).toBe(400);
  });
  it("D4 node ids beyond int64 are refused", async () => {
    const c = await signIn("madmin1");
    expect((await scope(2001, [{ node_type: "territory", node_id: 1e300 }], c)).status).toBe(400);
    expect((await scope(2001, [{ node_type: "territory", node_id: Number.MAX_SAFE_INTEGER + 2 }], c)).status).toBe(400);
  });
  it("nodes as non-array / string node_id / null entries are 400", async () => {
    const c = await signIn("madmin1");
    for (const n of ["x", null, {}, [null], [{ node_type: "territory", node_id: "6" }], [{ node_type: "__proto__", node_id: 1 }]]) expect((await scope(2001, n, c)).status).toBe(400);
  });
});

describe("checker-adm3 sales plan", () => {
  const put = (zone: unknown, ids: unknown, c: Record<string, string>) => op({ op: "sales-plan.put", params: { zone_id: zone }, body: { valid_from: plus(1), sku_ids: ids }, reason: REASON }, c);
  it("D6 an unknown zone is 404", async () => {
    const c = await signIn("madmin1");
    expect((await put(999999, [1], c)).status).toBe(404);
  });
  it("zone id path tricks are 400", async () => {
    const c = await signIn("madmin1");
    for (const z of ["../x", "1/2", "0", -1, "", "1?x=1"]) expect([400, 404]).toContain((await put(z, [1], c)).status);
  });
  it("D7 a blank reason is refused; a Bengali reason counts by code point", async () => {
    const c = await signIn("madmin1");
    const r = (reason: string) => op({ op: "sales-plan.put", params: { zone_id: 14 }, body: { valid_from: plus(1), sku_ids: [1] }, reason }, c);
    expect((await r(" ".repeat(12))).status).toBe(400);
    expect((await r("বাংলা কারণ লিখুন")).status).toBe(200);
  });
});

describe("checker-adm3 prices", () => {
  const call = (which: string, body: Record<string, unknown>, c: Record<string, string>) => op({ op: which, body: { batch_uuid: BATCH, valid_from: plus(2), prices: [{ sku_id: 1, price_type: "outlet", amount_mtk: 1_260_000 }], ...body }, reason: REASON }, c);
  it("D8 contract: publish is from a FUTURE date; today is refused", async () => {
    const c = await signIn("madmin1");
    expect((await call("price.publish", { valid_from: businessDate() }, c)).status).toBe(400);
  });
  it("D9 upper-case uuid is refused (contract: lower case)", async () => {
    const c = await signIn("madmin1");
    expect((await call("price.preview", { batch_uuid: BATCH.toUpperCase() }, c)).status).toBe(400);
  });
});

describe("checker-adm3 assignments (SR transfer)", () => {
  const create = (b: Record<string, unknown>, c: Record<string, string>) => op({ op: "assignment.create", body: { route_id: 4, user_id: 1001, kind: "primary", valid_from: plus(5), ...b }, reason: REASON }, c);
  it("D14 valid_to not after valid_from is refused", async () => {
    const c = await signIn("madmin1");
    expect((await create({ valid_to: plus(3) }, c)).status).toBe(400);
    expect((await create({ valid_to: plus(5) }, c)).status).toBe(400);
  });
  it("D15 ending a nonexistent assignment is 404", async () => {
    const c = await signIn("madmin1");
    expect((await op({ op: "assignment.end", params: { id: 999999 }, body: { valid_to: plus(5) }, reason: REASON }, c)).status).toBe(404);
  });
  it("D16 end date before the assignment's own start is refused", async () => {
    const c = await signIn("madmin1");
    const made = await create({ valid_from: plus(20) }, c);
    expect(made.status).toBe(201);
    const id = (await made.json()).data?.id ?? 2;
    expect((await op({ op: "assignment.end", params: { id }, body: { valid_to: plus(2) }, reason: REASON }, c)).status).toBeGreaterThanOrEqual(400);
  });
  it("D18 support is 403 on every op", async () => {
    const s = await signIn("msupport1");
    for (const o of ["sales-plan.put", "user-scope.put", "price.preview", "price.publish", "assignment.create", "assignment.end"]) expect((await op({ op: o, params: { id: 1, zone_id: 14 }, body: {}, reason: REASON }, s)).status).toBe(403);
  });
});

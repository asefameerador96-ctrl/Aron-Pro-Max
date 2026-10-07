import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as createPost } from "@/app/api/bff/admin/[entity]/route";
import { PATCH as updatePatch } from "@/app/api/bff/admin/[entity]/[id]/route";
import { geoEntities } from "@/app/admin/_entities/geo";
import { entityBySlug } from "@/app/admin/_entities/registry";
import { fillItemPath, collectionPath, getRow, loadRefOptions } from "@/components/admin/crud/server";
import { isWritable } from "@/components/admin/crud/meta";
import { valuesSchema } from "@/components/admin/crud/validation";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { createMock } from "../mock/server";

const mock = createMock();
let token = "";
let cookies: Record<string, string> = {};
const ORIGIN = "http://localhost:3000";
const req = (path: string, method: string, body: unknown, c: Record<string, string> = {}) =>
  new NextRequest(`${ORIGIN}${path}`, { method, body: JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, cookie: Object.entries(c).map(([k, v]) => `${k}=${v}`).join("; ") } });
const jar = (res: Response) => Object.fromEntries(res.headers.getSetCookie().map((l) => [l.split("=")[0]!, l.split(";")[0]!.split("=").slice(1).join("=")]));

beforeAll(async () => {
  await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
  process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  process.env.ARON_COOKIE_INSECURE = "1";
});
afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
beforeEach(async () => {
  mock.reset();
  const c1 = jar(await loginPost(req("/api/bff/login", "POST", { username: "madmin1", password: "admin-pass-1" })));
  const c2 = jar(await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: c1[MFA_COOKIE]! })));
  cookies = { [SESSION_COOKIE]: c2[SESSION_COOKIE]!, [RT_COOKIE]: c2[RT_COOKIE]! };
  token = [...mock.state.access.keys()].pop()!;
});

const params = (entity: string) => ({ params: Promise.resolve({ entity }) });
const idParams = (entity: string, id: string) => ({ params: Promise.resolve({ entity, id }) });

describe("geography entities (one factory, five levels)", () => {
  it("registers wing, division, territory, house and zone with their level path parameter", () => {
    expect(geoEntities.map((e) => e.slug)).toEqual(["wings", "divisions", "territories", "houses", "zones"]);
    const zones = entityBySlug("zones")!;
    expect(collectionPath(zones)).toBe("/v1/admin/geo/zone");
    expect(fillItemPath(zones, "14")).toBe("/v1/admin/geo/zone/14");
  });
  it("only wings have no parent; zones carry the depot name; code is create-only", () => {
    const names = (slug: string, mode: "create" | "update") => entityBySlug(slug)!.fields.filter((f) => isWritable(f, mode)).map((f) => f.name);
    expect(names("wings", "create")).not.toContain("parent_id");
    expect(names("divisions", "create")).toContain("parent_id");
    expect(names("zones", "create")).toContain("dep_name");
    expect(names("divisions", "create")).not.toContain("dep_name");
    expect(names("zones", "create")).toContain("code");
    expect(names("zones", "update")).not.toContain("code");
  });
  it("validates code, email and phone like the contract", () => {
    const s = valuesSchema(entityBySlug("zones")!, "create");
    const ok = { code: "Z-1", name: "Zone", parent_id: "10" };
    expect(s.safeParse(ok)).toMatchObject({ success: true, data: { parent_id: 10 } });
    expect(s.safeParse({ ...ok, code: "-bad" }).success).toBe(false);
    expect(s.safeParse({ ...ok, code: "has space" }).success).toBe(false);
    expect(s.safeParse({ ...ok, email: "nope" }).success).toBe(false);
    expect(s.safeParse({ ...ok, email: "a@b.co" }).success).toBe(true);
    expect(s.safeParse({ ...ok, pda_contact_no: "12" }).success).toBe(false);
    expect(s.safeParse({ ...ok, pda_contact_no: "+8801700000000" }).success).toBe(true);
    expect(s.safeParse({ ...ok, pda_contact_no: "" })).toMatchObject({ success: true, data: { pda_contact_no: null } });
  });
  it("ref options come from the parent level's list", async () => {
    const parent = entityBySlug("zones")!.fields.find((f) => f.name === "parent_id")!;
    const opts = await loadRefOptions(parent.ref!, token);
    expect(opts.map((o) => o.label)).toContain("H-1 · Banani House");
    expect(opts).toHaveLength(4);
  });
  it("create sends the reason to the API (change_reason) and the audit row keeps it; code is unique", async () => {
    const reason = "New division for the Sylhet expansion";
    const res = await createPost(req("/api/bff/admin/divisions", "POST", { values: { code: "D-9", name: "Sylhet", parent_id: "2" }, reason }, cookies), params("divisions"));
    expect(res.status).toBe(201);
    expect((await res.json()).row).toMatchObject({ level: "division", code: "D-9", parent_id: 2, status: "active" });
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "geo_node", action: "geo_node.create", reason });
    const dup = await createPost(req("/api/bff/admin/divisions", "POST", { values: { code: "D-9", name: "Again", parent_id: "2" }, reason }, cookies), params("divisions"));
    expect(dup.status).toBe(409);
    expect((await dup.json()).code).toBe("ERR_MASTER_DUPLICATE_CODE");
  });
  it("update by id uses GET for the version, refuses code changes, soft-deletes by status with the reason", async () => {
    const meta = entityBySlug("zones")!;
    const got = await getRow(meta, token, "14");
    expect(got.ok && got.data).toMatchObject({ code: "Z-334-1", version: 1 });
    const reason = "Zone closed after territory merge";
    const bad = await updatePatch(req("/api/bff/admin/zones/14", "PATCH", { values: { code: "NEW" }, reason, version: 1 }, cookies), idParams("zones", "14"));
    expect(bad.status).toBe(400);
    const ok = await updatePatch(req("/api/bff/admin/zones/14", "PATCH", { values: { status: "inactive" }, reason, version: 1 }, cookies), idParams("zones", "14"));
    expect(ok.status).toBe(200);
    expect((await ok.json()).row).toMatchObject({ status: "inactive", version: 2 });
    expect(mock.state.audit.at(-1)).toMatchObject({ action: "geo_node.update", reason, entity_id: "14", before: { status: "active" }, after: { status: "inactive" } });
  });
  it("a wing id is not reachable through the zones path", async () => {
    const got = await getRow(entityBySlug("zones")!, token, "1");
    expect(got.ok).toBe(false);
  });
});

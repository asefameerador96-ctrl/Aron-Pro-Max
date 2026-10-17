// CHECKER OCH (T1, scope/security) for F-WEB-003: the outlet-request.create master op through POST /api/bff/master-op and its rules.
import { randomUUID } from "node:crypto";
import { describe, expect, it } from "vitest";
import { NextRequest } from "next/server";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { masterOpRules } from "@/components/admin/master-op-rules";
import { accessFor } from "@/lib/auth/roles";
import { SESSION_COOKIE, RT_COOKIE } from "@/lib/auth/cookies";
import { jar, req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Owner name is spelt wrong on the memo header";
const body = (b: Record<string, unknown> = {}) => ({ request_uuid: randomUUID(), request_type: "info", outlet_id: 1, proposed: { name: "Banani Store Ltd" }, ...b });
const call = (b: unknown, c: Record<string, string>, reason: unknown = REASON) => opPost(req("/api/bff/master-op", "POST", { op: "outlet-request.create", body: b, reason }, c));
const raw = (json: string, c: Record<string, string>) =>
  opPost(new NextRequest("http://localhost:3000/api/bff/master-op", { method: "POST", body: json, headers: { "content-type": "application/json", host: "localhost:3000", origin: "http://localhost:3000", cookie: Object.entries(c).map(([k, v]) => `${k}=${v}`).join("; ") } }));
async function login(username: string, password: string) {
  const c = jar(await loginPost(req("/api/bff/login", "POST", { username, password })));
  return { [SESSION_COOKIE]: c[SESSION_COOKIE]!, [RT_COOKIE]: c[RT_COOKIE]! };
}
const rows = () => mock.state.tables.outletRequests!;

describe("outlet-request.create: who may raise", () => {
  it("unauthenticated 401; ADMIN, SUPPORT, WM, ANALYST 403; nothing stored", async () => {
    expect((await call(body(), {})).status).toBe(401);
    const n = rows().length;
    for (const c of [await signIn("madmin1"), await signIn("msupport1"), await login("wm1", "wm-pass-1"), await login("analyst1", "analyst-pass-1")]) expect((await call(body(), c)).status).toBe(403);
    expect(rows().length).toBe(n);
  });
  it("TSO and DMO create; the page path is TSO and DMO only", async () => {
    expect((await call(body(), await signIn("mtso1"))).status).toBe(201);
    expect((await call(body(), await login("mdmo1", "dmo-pass-2"))).status).toBe(201);
    for (const r of ["TSO", "DMO"] as const) expect(accessFor(r, "/admin/outlet-change")).toBe("ok");
    for (const r of ["ADMIN", "SUPERADMIN", "SUPPORT", "WM", "TOP", "ANALYST"] as const) expect(accessFor(r, "/admin/outlet-change"), r).toBe("forbidden");
    expect(accessFor("ADMIN", "/admin/outlet-changes")).toBe("ok"); // a sibling prefix is not the override
    expect(accessFor("TSO", "/admin/outlet-changes")).toBe("forbidden");
  });
  it("scope is the server's: a TSO cannot raise on an outlet outside its zone, and no zone member is accepted", async () => {
    const c = await signIn("mtso1");
    expect((await call(body({ outlet_id: 3 }), c)).status).toBe(403);
    for (const extra of [{ zone_id: 15 }, { scope: [15] }, { territory_id: 6 }]) expect((await call(body(extra), c)).status, JSON.stringify(extra)).toBe(400);
  });
});

describe("outlet-request.create: body smuggling", () => {
  it("extra members, __proto__, constructor, arrays and non-objects are refused", async () => {
    const c = await signIn("mtso1");
    const n = rows().length;
    expect((await raw(`{"op":"outlet-request.create","reason":"${REASON}","body":{"request_uuid":"${randomUUID()}","request_type":"info","outlet_id":1,"proposed":{"name":"Banani X"},"__proto__":{"admin":true}}}`, c)).status).toBe(400);
    expect((await raw(`{"op":"outlet-request.create","reason":"${REASON}","body":{"request_uuid":"${randomUUID()}","request_type":"info","outlet_id":1,"proposed":{"name":"Banani X","__proto__":{"x":1}}}}`, c)).status).toBe(400);
    expect(({} as Record<string, unknown>).admin).toBeUndefined();
    for (const b of [null, [], "x", body({ proposed: [] }), body({ proposed: null }), body({ proposed: "x" }), body({ proposed: { geo_class: "Urban" } }), body({ proposed: { constructor: 1 } }), body({ proposed: {} }), body({ fix: {} }), body({ photo_uuids: [] })]) expect((await call(b, c)).status, JSON.stringify(b)).toBe(400);
    expect(rows().length).toBe(n);
  });
  it("types new, route_add, unknown and wrong-type members are refused", async () => {
    const c = await signIn("mtso1");
    for (const b of [body({ request_type: "new" }), body({ request_type: "route_add", proposed: { cluster_id: 1 } }), body({ request_type: "INFO" }), body({ request_type: ["info"] }), body({ request_type: "location", proposed: { name: "Banani X" } }), body({ request_type: "cluster", proposed: { cluster_id: 2, lat: 23 } }), body({ request_type: "close", proposed: { name: "xx" } })]) expect((await call(b, c)).status, JSON.stringify(b.request_type)).toBe(400);
  });
  it("outlet_id: negative, zero, float, huge, string, null are refused", async () => {
    const c = await signIn("mtso1");
    for (const id of [-1, 0, 1.5, 1e21, Number.MAX_SAFE_INTEGER + 2, "1", null, true]) expect((await call(body({ outlet_id: id }), c)).status, String(id)).toBe(400);
  });
  it("lat/lng: out of range, string, missing, null are refused; boundaries pass", async () => {
    const c = await signIn("mtso1");
    for (const p of [{ lat: 90.0000001, lng: 90 }, { lat: -90.0000001, lng: 90 }, { lat: 23, lng: 180.0000001 }, { lat: "23.8", lng: 90 }, { lat: 23.8 }, { lat: null, lng: 90 }, { lat: 1e309, lng: 90 }]) expect((await raw(JSON.stringify({ op: "outlet-request.create", reason: REASON, body: body({ request_type: "location", proposed: p }) }).replace("1e+309", "1e309"), c)).status, JSON.stringify(p)).toBe(400);
    expect((await call(body({ request_type: "location", proposed: { lat: 90, lng: -180 } }), c)).status).toBe(201);
  });
  it("huge strings and bad phones are refused by the BFF before the API", async () => {
    const c = await signIn("mtso1");
    for (const p of [{ name: "a".repeat(121) }, { name: "a" }, { owner_name: "😀".repeat(121) }, { address: "x".repeat(301) }, { name_bn: "" }, { contact_number: "০১৭১২৩৪৫৬৭৮" }, { contact_number: "01212345678" }, { contact_number: 1712345678 }, { sub_channel_id: 0 }, { sub_channel_id: "11" }]) expect((await call(body({ proposed: p }), c)).status, JSON.stringify(p).slice(0, 40)).toBe(400);
    expect((await call(body({ proposed: { name: "😀".repeat(120) } }), c)).status).toBe(201);
  });
  it("request_uuid must be a lower-case v4", async () => {
    const c = await signIn("mtso1");
    for (const u of [randomUUID().toUpperCase(), "00000000-0000-1000-8000-000000000000", "", null, 7, `${randomUUID()}x`]) expect((await call(body({ request_uuid: u }), c)).status, String(u)).toBe(400);
  });
});

describe("outlet-request.create: reason travels as note", () => {
  it("9 code points refused, 10 code points (emoji) pass, 501 refused, zero-width padding does not count", async () => {
    const c = await signIn("mtso1");
    for (const r of ["a".repeat(9), "​".repeat(12), `​${"a".repeat(5)}‍${"b".repeat(4)}﻿`, "😀".repeat(501), " ".repeat(20), 1234567890123, null]) expect((await call(body(), c, r)).status, String(r).slice(0, 6)).toBe(400);
    const r = await call(body(), c, "😀".repeat(10));
    expect(r.status).toBe(201);
    expect(rows().at(-1)!.note).toBe("😀".repeat(10));
  });
  it("a note smuggled in the body is replaced by the reason; zero-width characters are stripped", async () => {
    const c = await signIn("mtso1");
    expect((await call(body({ note: "x" }), c, `​ ${REASON} ​`)).status).toBe(201);
    expect(rows().at(-1)!.note).toBe(REASON);
    expect((await call(body({ note: REASON }), c, "short")).status).toBe(400);
  });
});

describe("outlet-request.create: idempotency by request_uuid", () => {
  it("a repeat returns the stored request and stores nothing; another type or outlet is 409", async () => {
    const c = await signIn("mtso1");
    const b = body();
    expect((await call(b, c)).status).toBe(201);
    const n = rows().length;
    expect((await call(b, c)).status).toBe(201);
    expect(rows().length).toBe(n);
    expect((await call({ ...b, request_type: "cluster", proposed: { cluster_id: 2 } }, c)).status).toBe(409);
    expect((await call({ ...b, outlet_id: 2 }, c)).status).toBe(409);
    expect((await call(b, await login("mdmo1", "dmo-pass-2"))).status).toBe(409);
  });
});

describe("outlet-request.create rules (pure)", () => {
  it("location accepts only finite numbers in range", async () => {
    for (const v of [Number.NaN, Number.POSITIVE_INFINITY, -Infinity, 90.0000001]) {
      const e = await masterOpRules("outlet-request.create", { request_uuid: randomUUID(), request_type: "location", outlet_id: 1, proposed: { lat: v, lng: 90 }, note: REASON }, {}, "t", "TSO");
      expect(e.length, String(v)).toBeGreaterThan(0);
    }
  });
  it("contract allows address null (OutletProposal.address is string|null); the BFF should not be stricter than the contract silently", async () => {
    const e = await masterOpRules("outlet-request.create", { request_uuid: randomUUID(), request_type: "info", outlet_id: 1, proposed: { address: null }, note: REASON }, {}, "t", "TSO");
    expect(e).toEqual([]);
  });
});

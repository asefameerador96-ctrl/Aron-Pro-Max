// Pure helpers and BFF handlers of the dashboard rows: password policy (F-WEB-033), routes (F-WEB-010), products (F-WEB-004..009),
// leave decision (F-WEB-046), risk review (F-WEB-057), tutorials (F-WEB-035), login remember-me (F-WEB-043), against the mock.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as leavePost } from "@/app/api/bff/leave-decision/route";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as passwordPost } from "@/app/api/bff/password/route";
import { POST as riskPost } from "@/app/api/bff/risk-review/route";
import { MFA_COOKIE, SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { checkPasswords, isValid } from "@/lib/auth/password-policy";
import { seal } from "@/lib/auth/seal";
import { joinRoutes } from "@/lib/dash/routes";
import { buildTree, countLeaves, levelRows, LEVELS, type Level, type Node } from "@/lib/dash/products";
import { listAssignments, listLeave, listProductNodes, listRiskSignals, listRoutes, listSkus, listTutorials } from "@/lib/dash/server";
import { createMock } from "../mock/server";
import { PRODUCT_NODES } from "../mock/seed";

const mock = createMock();
let base = "";
beforeAll(async () => {
  await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  process.env.ARON_API_BASE_URL = base;
  process.env.ARON_COOKIE_INSECURE = "1";
});
afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
beforeEach(() => mock.reset());

async function session(username: string, password: string) {
  const r = (await (await fetch(`${base}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username, password, client: "web" }) })).json()) as { access_token: string; user: never; scope: never };
  return { at: r.access_token, cookie: `${SESSION_COOKIE}=${seal({ at: r.access_token, atExp: Date.now() + 600_000, user: r.user, scope: r.scope }, SESSION_PURPOSE, 600)}` };
}
const bff = (path: string, cookie: string, body: unknown) => new NextRequest(`http://localhost:3000${path}`, { method: "POST", body: JSON.stringify(body), headers: { cookie, host: "localhost:3000", "content-type": "application/json" } });
const uuid = () => crypto.randomUUID();

describe("password policy (F-WEB-033)", () => {
  const good = "Correct-Horse-9";
  it("accepts a compliant password", () => expect(isValid(checkPasswords("old-pass", good, good))).toBe(true));
  it.each([
    ["short", "Ab1", "too_short"],
    ["no upper", "correct-horse-9x", "needs_upper"],
    ["no lower", "CORRECT-HORSE-9X", "needs_lower"],
    ["no digit", "Correct-Horse-Xx", "needs_digit"],
    ["same as old", good, "same_as_old"],
  ])("rejects %s", (_n, pw, issue) => expect(checkPasswords(issue === "same_as_old" ? good : "old", pw, pw).next).toBe(issue));
  it("flags a mismatch and missing fields", () => {
    expect(checkPasswords("old", good, "other").confirm).toBe("mismatch");
    expect(checkPasswords("", good, good).old).toBe("required");
    expect(checkPasswords("old", "", "").next).toBe("required");
    expect(checkPasswords("old", "A1" + "a".repeat(200), "A1" + "a".repeat(200)).next).toBe("too_long");
  });
  it("BFF refuses a weak password before calling the API and passes a good one; the wrong old password is a field error", async () => {
    const s = await session("tso334", "tso-pass-1");
    expect((await passwordPost(bff("/api/bff/password", s.cookie, { current_password: "tso-pass-1", new_password: "weak" }))).status).toBe(400);
    const wrong = await passwordPost(bff("/api/bff/password", s.cookie, { current_password: "not-it", new_password: "Correct-Horse-9" }));
    expect(wrong.status).toBe(400);
    expect(JSON.stringify(await wrong.json())).toContain("/current_password");
    const ok = await passwordPost(bff("/api/bff/password", s.cookie, { current_password: "tso-pass-1", new_password: "Correct-Horse-9" }));
    expect(ok.status).toBe(200);
    expect(await ok.json()).toEqual({ ok: true });
    expect((await passwordPost(bff("/api/bff/password", s.cookie, { current_password: "tso-pass-1", new_password: "Another-Pass-77" }))).status).toBe(400); // the old one no longer works
    expect((await passwordPost(bff("/api/bff/password", "", { current_password: "x", new_password: "y" }))).status).toBe(401);
  });
});

describe("login (F-WEB-043)", () => {
  const login = (body: unknown) => loginPost(new NextRequest("http://localhost:3000/api/bff/login", { method: "POST", body: JSON.stringify(body), headers: { host: "localhost:3000", origin: "http://localhost:3000", "content-type": "application/json" } }));
  const cookies = (res: Response) => res.headers.getSetCookie();
  it("takes the User ID case-insensitively", async () => {
    const res = await login({ username: "  TSO334 ", password: "tso-pass-1" });
    expect(res.status).toBe(200);
    expect((await res.json()).status).toBe("ok");
  });
  it("Remember me off (default): session cookies without Max-Age; on: persistent cookies", async () => {
    const off = cookies(await login({ username: "tso334", password: "tso-pass-1" }));
    expect(off.filter((c) => c.startsWith("aron_sess=") || c.startsWith("aron_rt="))).toHaveLength(2);
    for (const c of off.filter((c) => c.startsWith("aron_sess=") || c.startsWith("aron_rt="))) expect(c).not.toMatch(/Max-Age|Expires/i);
    const on = cookies(await login({ username: "tso334", password: "tso-pass-1", remember: true }));
    for (const c of on.filter((c) => c.startsWith("aron_sess=") || c.startsWith("aron_rt="))) expect(c).toMatch(/Max-Age=\d+/i);
    for (const c of [...off, ...on].filter((c) => c.startsWith("aron_sess=") || c.startsWith("aron_rt="))) expect(c).toMatch(/HttpOnly/i);
  });
  it("admin roles need the second step and never get a persistent session, even with Remember me", async () => {
    const first = await login({ username: "admin1", password: "admin-pass-1", remember: true });
    expect((await first.json()).status).toBe("mfa_required");
    expect(cookies(first).some((c) => c.startsWith("aron_sess="))).toBe(false);
    const mfaCookie = cookies(first).find((c) => c.startsWith(`${MFA_COOKIE}=`))!.split(";")[0]!.split("=").slice(1).join("=");
    const second = await mfaPost(new NextRequest("http://localhost:3000/api/bff/mfa/verify", { method: "POST", body: JSON.stringify({ code: "123456" }), headers: { host: "localhost:3000", origin: "http://localhost:3000", "content-type": "application/json", cookie: `${MFA_COOKIE}=${mfaCookie}` } }));
    expect(second.status).toBe(200);
    for (const c of cookies(second).filter((c) => c.startsWith("aron_sess=") || c.startsWith("aron_rt="))) expect(c).not.toMatch(/Max-Age|Expires/i);
  });
  it("rejects wrong credentials and unknown members", async () => {
    expect((await login({ username: "tso334", password: "nope" })).status).toBe(401);
    expect((await login({ username: "tso334", password: "tso-pass-1", extra: 1 })).status).toBe(400);
  });
});

describe("routes (F-WEB-010)", () => {
  it("an AMO route shows SR Not Set; an SR route shows its SR and the zone's AMO", async () => {
    const s = await session("tso334", "tso-pass-1");
    const [routes, assignments] = await Promise.all([listRoutes(s.at), listAssignments(s.at, "2026-10-07")]);
    if (!routes.ok || !assignments.ok) throw new Error("no data");
    const lines = joinRoutes(routes.data.items, assignments.data.items, new Map([[1003, "Jamal Uddin"], [1004, "Rafiq Amin"], [1001, "Testing Banani"], [1002, "Kamal Hossain"]]));
    const amo = lines.find((l) => l.route_id === 10234)!;
    expect(amo).toMatchObject({ kind: "amo", sr: null, amo: "Rafiq Amin" });
    const sr = lines.find((l) => l.route_id === 10233)!;
    expect(sr).toMatchObject({ kind: "sr", sr: "Jamal Uddin", amo: "Rafiq Amin" }); // same zone as the AMO route
    expect(lines.find((l) => l.route_id === 10231)!.amo).toBeNull(); // zone 3341 has no AMO route
    expect(lines).toHaveLength(4);
  });
});

describe("products (F-WEB-004..009)", () => {
  async function all() {
    const s = await session("tso334", "tso-pass-1");
    const pages = await Promise.all(LEVELS.map((l) => listProductNodes(s.at, l)));
    return { s, all: Object.fromEntries(LEVELS.map((l, i) => [l, pages[i]!.ok ? (pages[i] as { ok: true; data: { items: Node[] } }).data.items : []])) as Record<Level, Node[]> };
  }
  it("brand lists the 17 brands with category and segment", async () => {
    const { all: a } = await all();
    const rows = levelRows("brand", a);
    expect(rows).toHaveLength(17);
    expect(rows[0]).toMatchObject({ brand: "Sample", segment: "Premium", category: "Cigarette", status: "active" });
    expect(rows.filter((r) => r.status === "inactive")).toHaveLength(1);
  });
  it("variant rows carry all four ancestors; category rows only their own name; counts equal the seed", async () => {
    const { all: a } = await all();
    expect(levelRows("variant", a)[0]).toMatchObject({ variant: "King Size", brand: "Sample", segment: "Premium", category: "Cigarette" });
    expect(levelRows("category", a).map((r) => r.category)).toEqual(["Cigarette", "Bidi"]);
    for (const l of LEVELS) expect(levelRows(l, a)).toHaveLength(PRODUCT_NODES.filter((n) => n.level === l).length);
  });
  it("the tree expands from All Products down to SKU and holds every SKU under its variant", async () => {
    const { s, all: a } = await all();
    const skus = await listSkus(s.at);
    if (!skus.ok) throw new Error("no skus");
    const tree = buildTree(a, skus.data.items, "All Products");
    expect(tree.label).toBe("All Products");
    expect(tree.children.map((c) => c.label)).toEqual(["Cigarette", "Bidi"]);
    expect(countLeaves(tree)).toBe(skus.data.items.length);
    const king = tree.children[0]!.children[0]!.children[0]!.children[0]!; // category > segment > brand > variant
    expect(king.level).toBe("variant");
    expect(king.children.every((c) => c.level === "sku")).toBe(true);
  });
});

describe("leave decision (F-WEB-046)", () => {
  it("a DMO approves; the TSO sees the new status on the next list; a TSO cannot decide", async () => {
    const dmo = await session("dmo1", "dmo-pass-1");
    const tso = await session("tso334", "tso-pass-1");
    const pending = await listLeave(dmo.at, "pending");
    if (!pending.ok) throw new Error("no leave");
    expect(pending.data.items.map((l) => l.user_id).sort()).toEqual([2001, 2005]);
    const target = "11111111-1111-4111-8111-111111111111";
    expect((await leavePost(bff("/api/bff/leave-decision", tso.cookie, { leave_uuid: target, decision: "approve" }))).status).toBe(403);
    expect((await leavePost(bff("/api/bff/leave-decision", dmo.cookie, { leave_uuid: target, decision: "approve" }))).status).toBe(200);
    expect((await leavePost(bff("/api/bff/leave-decision", dmo.cookie, { leave_uuid: target, decision: "reject" }))).status).toBe(409); // already decided
    const mine = await listLeave(tso.at);
    if (!mine.ok) throw new Error("no leave");
    expect(mine.data.items).toHaveLength(1);
    expect(mine.data.items[0]).toMatchObject({ leave_uuid: target, status: "approved" });
  });
  it("rejects a malformed decision", async () => {
    const dmo = await session("dmo1", "dmo-pass-1");
    expect((await leavePost(bff("/api/bff/leave-decision", dmo.cookie, { leave_uuid: "x", decision: "maybe" }))).status).toBe(400);
  });
});

describe("exceptions (F-WEB-057)", () => {
  it("lists in-scope signals only, marks the re-sampled one, and a review closes it", async () => {
    const s = await session("tso334", "tso-pass-1");
    const open = await listRiskSignals(s.at, { status: "open" });
    if (!open.ok) throw new Error("no signals");
    expect(open.data.items.length).toBeGreaterThan(0);
    expect(open.data.items.every((x) => [10233].includes(x.route_id ?? 0))).toBe(true); // only Banani routes with suspicious visits
    expect(open.data.items[0]!.last_review?.action).toBe("dismissed"); // an AMO dismissal re-sampled to the TSO
    const id = open.data.items[0]!.signal_id;
    const res = await riskPost(bff("/api/bff/risk-review", s.cookie, { signal_id: id, request_uuid: uuid(), action: "confirmed", note: "Seen it" }));
    expect(res.status).toBe(200);
    expect(((await listRiskSignals(s.at, { status: "open" })) as { data: { items: unknown[] } }).data.items).toHaveLength(0);
    expect(((await listRiskSignals(s.at, { status: "confirmed" })) as { data: { items: unknown[] } }).data.items).toHaveLength(1);
  });
  it("a user outside the scope sees no signals and cannot review one", async () => {
    const s = await session("tso999", "tso-pass-3");
    expect(((await listRiskSignals(s.at)) as { data: { items: unknown[] } }).data.items).toEqual([]);
    expect((await riskPost(bff("/api/bff/risk-review", s.cookie, { signal_id: 900, request_uuid: uuid(), action: "dismissed" }))).status).toBe(404);
  });
  it("rejects an invalid action", async () => {
    const s = await session("tso334", "tso-pass-1");
    expect((await riskPost(bff("/api/bff/risk-review", s.cookie, { signal_id: 900, request_uuid: uuid(), action: "delete" }))).status).toBe(400);
  });
});

describe("tutorials (F-WEB-035)", () => {
  it("lists the four manuals and shows a video added in the portal on the next load", async () => {
    const s = await session("tso334", "tso-pass-1");
    const before = await listTutorials(s.at);
    if (!before.ok) throw new Error("no tutorials");
    expect(before.data.items.filter((i) => i.kind === "manual")).toHaveLength(4);
    await fetch(`${base}/__mock/tutorial`, { method: "POST", body: JSON.stringify({ tutorial_id: 9, kind: "video", title_en: "How to final submit", title_bn: "ফাইনাল সাবমিট", url: "https://files.example/v.mp4", sort: 9 }) });
    const after = await listTutorials(s.at);
    if (!after.ok) throw new Error("no tutorials");
    expect(after.data.items.find((i) => i.kind === "video")?.title_en).toBe("How to final submit");
  });
});

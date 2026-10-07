// F-WEB-038 / F-WEB-039 / F-WEB-028: buckets, the exception bucket, the 17:00 Dhaka rule and the take-action BFF against the mock.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as actionPost } from "@/app/api/bff/tracking-action/route";
import { SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import { BUCKETS, canTakeAction, countBuckets } from "@/lib/dash/buckets";
import { getDailyTracking, previousDate } from "@/lib/dash/server";
import { createMock } from "../mock/server";
import { ROUTES } from "../mock/seed";

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
const setNow = (now: string | null) => fetch(`${base}/__mock/now`, { method: "POST", body: JSON.stringify({ now }) });
const post = (cookie: string, body: unknown) => actionPost(new NextRequest("http://localhost:3000/api/bff/tracking-action", { method: "POST", body: JSON.stringify(body), headers: { cookie, host: "localhost:3000", "content-type": "application/json" } }));
const uuid = () => crypto.randomUUID();

describe("buckets", () => {
  it("keeps the exception bucket distinct from not logged in", async () => {
    const s = await session("tso335", "tso-pass-2");
    const r = await getDailyTracking(s.at, "2026-10-07");
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    const c = countBuckets(r.data.items);
    expect(c.exception).toBe(1); // Gulshan West has a day exception
    expect(c.not_logged_in).toBe(0);
    expect(Object.keys(c)).toEqual([...BUCKETS]);
    expect(BUCKETS.indexOf("exception")).not.toBe(BUCKETS.indexOf("not_logged_in"));
  });
  it("the territory's buckets add up to its routes", async () => {
    const s = await session("tso334", "tso-pass-1");
    const r = await getDailyTracking(s.at, "2026-10-07");
    if (!r.ok) throw new Error("no data");
    expect(Object.values(countBuckets(r.data.items)).reduce((a, b) => a + b, 0)).toBe(ROUTES.filter((x) => x.territory_id === 334).length);
  });
  it("previousDate crosses month and year ends", () => {
    expect(previousDate("2026-10-01")).toBe("2026-09-30");
    expect(previousDate("2027-01-01")).toBe("2026-12-31");
    expect(previousDate("2028-03-01")).toBe("2028-02-29");
  });
});

describe("take action (17:00 Dhaka rule)", () => {
  it("is offered from 17:00 Dhaka of the date, and for earlier dates", () => {
    const at = (iso: string) => new Date(iso);
    expect(canTakeAction("2026-10-07", "2026-10-07", at("2026-10-07T10:59:00Z"))).toBe(false); // 16:59 Dhaka
    expect(canTakeAction("2026-10-07", "2026-10-07", at("2026-10-07T11:00:00Z"))).toBe(true); // 17:00 Dhaka
    expect(canTakeAction("2026-10-06", "2026-10-07", at("2026-10-07T04:00:00Z"))).toBe(true);
    expect(canTakeAction("2026-10-08", "2026-10-07", at("2026-10-07T20:00:00Z"))).toBe(false);
  });
  it("before 17:00 the server refuses (409) and nothing is stored", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T10:00:00Z"); // 16:00 Dhaka
    const res = await post(s.cookie, { action_uuid: uuid(), route_id: 10233, business_date: "2026-10-07", note: "Please push the route" });
    expect(res.status).toBe(409);
    expect(((await (await fetch(`${base}/__mock/state`)).json()) as { actions: unknown[] }).actions).toHaveLength(0);
  });
  it("after 17:00 it stores one note and notifies the route's SR and the TSO, without reassigning; a retry stores nothing new", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T12:00:00Z"); // 18:00 Dhaka
    const body = { action_uuid: uuid(), route_id: 10233, business_date: "2026-10-07", note: "Please push the route" };
    const a = await post(s.cookie, body);
    expect(a.status).toBe(201);
    const stored = (await a.json()) as { notified_user_ids: number[]; route_id: number };
    expect(stored.notified_user_ids).toEqual(expect.arrayContaining([1003, 2001])); // route 10233's SR and the TSO
    expect((await post(s.cookie, body)).status).toBe(201);
    const state = (await (await fetch(`${base}/__mock/state`)).json()) as { actions: unknown[] };
    expect(state.actions).toHaveLength(1);
  });
  it("rejects a route outside the scope, an empty note and a malformed body", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T12:00:00Z");
    expect((await post(s.cookie, { action_uuid: uuid(), route_id: 10351, business_date: "2026-10-07", note: "xyz note" })).status).toBe(403);
    expect((await post(s.cookie, { action_uuid: uuid(), route_id: 10233, business_date: "2026-10-07", note: "   " })).status).toBe(400);
    expect((await post(s.cookie, { route_id: "x" })).status).toBe(400);
  });
});

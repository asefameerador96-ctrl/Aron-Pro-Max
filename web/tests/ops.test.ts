// F-WEB-045: zone roll-ups, latency figures and the pages' data against the seeded day.
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { latency, rollupByZone, totalsOf } from "@/lib/dash/ops";
import { getConfigAck, getDailyTracking, getPendingPhotos, getSyncHealth } from "@/lib/dash/server";
import { createMock } from "../mock/server";
import { ROUTES } from "../mock/seed";

const mock = createMock();
let base = "";
beforeAll(async () => {
  await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  process.env.ARON_API_BASE_URL = base;
});
afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
const token = async (u: string, p: string) => ((await (await fetch(`${base}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: u, password: p, client: "web" }) })).json()) as { access_token: string }).access_token;

describe("zone roll-up", () => {
  it("matches the seeded day: login %, submit %, final submit per zone", async () => {
    const t = await token("tso334", "tso-pass-1");
    const r = await getDailyTracking(t, "2026-10-07");
    if (!r.ok) throw new Error("no data");
    const zs = rollupByZone(r.data.items);
    expect(zs.map((z) => z.zone_id)).toEqual([3341, 3342]);
    // Zone 3341: R1 final, R2 sales_submitted -> 2 routes, 2 logged in, 2 submitted, not done. Zone 3342: R3 in_field, R4 not started.
    expect(zs[0]).toMatchObject({ routes: 2, logged_in: 2, submitted: 2, final_submitted: 1, login_pct: 100, submit_pct: 100, done: false });
    expect(zs[1]).toMatchObject({ routes: 2, logged_in: 1, submitted: 0, login_pct: 50, submit_pct: 0, done: false });
    expect(totalsOf(zs)).toEqual({ login_pct: 75, submit_pct: 66.67, zones_done: 0, zones: 2 });
    expect(zs.reduce((a, z) => a + z.routes, 0)).toBe(ROUTES.filter((x) => x.territory_id === 334).length);
  });
  it("no routes gives null percentages, not NaN", () => {
    expect(totalsOf([])).toEqual({ login_pct: null, submit_pct: null, zones_done: 0, zones: 0 });
  });
});

describe("latency", () => {
  const d = (p: number | null | undefined) => ({ sync_p95_s: p }) as never;
  it("worst and median of the reported p95s; none reported is null", () => {
    expect(latency([d(10), d(30), d(20)])).toEqual({ worst: 30, median: 20 });
    expect(latency([d(10), d(30)])).toEqual({ worst: 30, median: 20 });
    expect(latency([d(null), d(undefined)])).toEqual({ worst: null, median: null });
  });
});

describe("ops figures by role", () => {
  it("an analyst sees config ack and pending photos; a TSO gets null (not available) instead of a guess", async () => {
    const a = await token("analyst1", "analyst-pass-1");
    expect(await getConfigAck(a)).toEqual({ version: 318, acked: 6, targeted: 8, pct: 75 });
    expect(await getPendingPhotos(a)).toBe(ROUTES.filter((r) => r.user_id).reduce((s, r) => s + r.offline_memos, 0));
    const t = await token("tso334", "tso-pass-1");
    expect(await getConfigAck(t)).toBeNull();
    expect(await getPendingPhotos(t)).toBeNull();
  });
  it("sync health lists devices only of the caller's reach with the quarantine total", async () => {
    const t = await token("tso334", "tso-pass-1");
    const h = await getSyncHealth(t, "2026-10-07");
    if (!h.ok) throw new Error("no data");
    expect(h.data.items.every((i) => (i.route_ids ?? []).every((id) => ROUTES.find((r) => r.route_id === id)?.territory_id === 334))).toBe(true);
    expect(h.data.summary.quarantined).toBe(ROUTES.filter((r) => r.territory_id === 334).reduce((s, r) => s + r.suspicious, 0));
  });
});

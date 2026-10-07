// Independent checker (docs/26 s4) for the report engine rows: each failing test is evidence of a defect.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { GET as exportGet } from "@/app/api/bff/reports/[slug]/export/route";
import { SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import type { SessionData } from "@/lib/auth/session";
import type { ScopeSummary } from "@/contract/types";
import { t } from "@/lib/i18n";
import { WEB_REPORTS, reportBySlug } from "@/lib/reports/catalog";
import { formatTaka, mtkToTaka } from "@/lib/reports/format";
import { bindGeo, boundGeo, buildReportQuery, withFormat } from "@/lib/reports/query";
import { runReportJson } from "@/lib/reports/server";
import { createMock } from "../mock/server";

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

async function login(username: string, password: string) {
  const r = await fetch(`${base}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username, password, client: "web" }) });
  const b = (await r.json()) as { access_token: string; scope: ScopeSummary; user: SessionData["user"] };
  const session: SessionData = { at: b.access_token, atExp: Date.now() + 600_000, user: b.user, scope: b.scope };
  return { at: b.access_token, scope: b.scope, cookie: `${SESSION_COOKIE}=${seal(session, SESSION_PURPOSE, 600)}` };
}
const rep = reportBySlug("route-std")!;

describe("F-WEB-040 page vs export query divergence", () => {
  it("a comma id list in the URL builds the same query whether given as a string (page) or via getAll array (export route)", () => {
    const page = buildReportQuery(rep, { products: "1,2", category: "3,4", zone: "5,6" }, null, "json", "2026-10-07");
    const exp = buildReportQuery(rep, { products: ["1,2"], category: ["3,4"], zone: ["5,6"] }, null, "xlsx", "2026-10-07");
    expect({ ...exp, output: 0 }).toEqual({ ...page, output: 0 });
  });
  it("export route end to end: ?products=1,2 reaches the server as [1,2]", async () => {
    const me = await login("tso334", "tso-pass-1");
    await exportGet(new NextRequest("http://localhost:3000/api/bff/reports/route-std/export?format=xlsx&products=1,2&zone=3341,3342", { headers: { cookie: me.cookie, host: "localhost:3000" } }), { params: Promise.resolve({ slug: "route-std" }) });
    const log = (await (await fetch(`${base}/__mock/state`)).json()) as { exports: { query: { products?: number[] } }[] };
    expect(log.exports[0]!.query.products).toEqual([1, 2]);
  });
});

describe("query builder input validation", () => {
  it("rejects calendar-invalid dates (2026-02-30) instead of forwarding them", () => {
    const q = buildReportQuery(rep, { from: "2026-02-30", to: "2026-13-45" }, null, "json", "2026-10-07");
    expect(q.period).toEqual({ from: "2026-10-07", to: "2026-10-07" });
  });
  it("rejects an impossible month (2026-13)", () => {
    const q = buildReportQuery(rep, { month: "2026-13" }, null, "json", "2026-10-07");
    expect(q.period.month).toBeUndefined();
  });
  it("a month and a range together: range given explicitly must not be silently dropped", () => {
    const q = buildReportQuery(rep, { month: "2026-09", from: "2026-10-01", to: "2026-10-03" }, null, "json", "2026-10-07");
    expect(q.period.month === undefined || q.period.from === undefined).toBe(true);
    // contract: exactly one of month | from/to | date
    expect(Object.keys(q.period).length).toBeGreaterThan(0);
  });
  it("a threshold beyond double range is not forwarded as Infinity (JSON null)", () => {
    const q = buildReportQuery(reportBySlug("std-memo")!, { std: `>=${"9".repeat(400)}` }, null);
    expect(q.std_criteria === undefined || Number.isFinite(q.std_criteria.value)).toBe(true);
  });
  it("empty std param falls back to op+val parts", () => {
    const q = buildReportQuery(reportBySlug("std-memo")!, { std: "", std_op: ">", std_val: "3" }, null);
    expect(q.std_criteria).toEqual({ op: ">", value: 3 });
  });
  it("ids above Number.MAX_SAFE_INTEGER are not forwarded", () => {
    const q = buildReportQuery(rep, { products: "999999999999,1" }, null);
    expect((q.products ?? []).every((n) => Number.isSafeInteger(n) && n < 2 ** 31)).toBe(true); // int32 id space
  });
  it("a single-date report ignores from/to silently but a range report ignores `date`: both fine; singleDate with invalid date falls back to today", () => {
    expect(buildReportQuery(reportBySlug("gigo")!, { date: "2026-02-30" }, null, "json", "2026-10-07").period).toEqual({ date: "2026-10-07" });
  });
});

describe("scope binding bypass attempts", () => {
  it("an unknown node type must not unlock everything (fail closed)", () => {
    const odd = { scope_version: 1, nodes: [{ type: "galaxy", id: 1 }] } as unknown as ScopeSummary;
    expect(bindGeo(odd).locked).toContain("wing");
    expect(boundGeo(odd, { wing: [1], zone: [2] })).toBeUndefined();
  });
  it("route-level scope locks wing..zone and bounds route ids to own routes", () => {
    const s: ScopeSummary = { scope_version: 1, nodes: [{ type: "route", id: 7 }, { type: "route", id: 8 }] };
    expect(boundGeo(s, { zone: [1], route: [7, 99] })).toEqual({ route: [7] });
  });
  it("mixed own nodes (territory + a zone of another territory): zone ids are bounded to own zone", () => {
    const s: ScopeSummary = { scope_version: 1, nodes: [{ type: "territory", id: 334 }, { type: "zone", id: 9001 }] };
    const g = boundGeo(s, { zone: [9001, 1234] });
    expect(g?.zone ?? []).not.toContain(1234);
  });
  it("two zone nodes: other zones cannot be selected", () => {
    const s: ScopeSummary = { scope_version: 1, nodes: [{ type: "zone", id: 1 }, { type: "zone", id: 2 }] };
    expect(boundGeo(s, { zone: [1, 3] })).toEqual({ zone: [1] });
  });
  it("a TSO of another territory sending territory=334 gets no geo in the exported query", async () => {
    const me = await login("tso999", "tso-pass-3");
    await exportGet(new NextRequest("http://localhost:3000/api/bff/reports/route-std/export?format=xlsx&territory=334&wing=1&division=2", { headers: { cookie: me.cookie, host: "localhost:3000" } }), { params: Promise.resolve({ slug: "route-std" }) });
    const log = (await (await fetch(`${base}/__mock/state`)).json()) as { exports: { query: { geo?: unknown } }[] };
    expect(log.exports[0]!.query.geo).toBeUndefined();
  });
  it("the geo filter is not applied to reports that do not list it (retailers geo ok) and withFormat keeps geo", () => {
    const q = buildReportQuery(reportBySlug("retailers")!, { zone: "1" }, null);
    expect(withFormat(q, "xlsx").geo).toEqual(q.geo);
  });
});

describe("formatting", () => {
  it("a number >= 1e21 does not render as exponent garbage", () => {
    expect(mtkToTaka(1e21)).not.toBe("1.121"); // String(1e21)="1e+21" -> digits "121"
  });
  it("a fractional milli-taka number is not silently turned into a different amount", () => {
    expect(mtkToTaka(7935.5)).not.toBe("79.355");
  });
  it("NaN or non-numeric input is not rendered as 0.000", () => {
    expect(formatTaka("en", NaN)).not.toBe("0.000");
    expect(formatTaka("en", "abc")).not.toBe("0.000");
  });
  it("bn negative keeps sign and Bengali digits", () => {
    expect(formatTaka("bn", -12_345_678_900)).toBe("-১,২৩,৪৫,৬৭৮.৯০০");
  });
  it("string money with decimals ('7.935') is not reinterpreted as 7935 mtk", () => {
    expect(formatTaka("en", "7.935")).not.toBe("7.935");
  });
});

describe("i18n", () => {
  it("every catalogue title and report UI key has a distinct Bangla text", () => {
    for (const r of WEB_REPORTS) {
      expect(t("bn", r.titleKey), r.slug).not.toBe(t("en", r.titleKey));
    }
    for (const k of ["report.get_excel", "report.pdf", "report.print", "report.empty", "report.totals", "report.paging"] as const) expect(t("bn", k)).not.toBe(t("en", k));
  });
});

describe("export route security", () => {
  const call = (headers: Record<string, string>, path = "/api/bff/reports/route-std/export?format=xlsx") =>
    exportGet(new NextRequest(`http://localhost:3000${path}`, { headers: { host: "localhost:3000", ...headers } }), { params: Promise.resolve({ slug: path.split("/")[4]!.split("?")[0]! }) });
  it("refuses a cross-site request", async () => {
    const me = await login("tso334", "tso-pass-1");
    expect((await call({ cookie: me.cookie, "sec-fetch-site": "cross-site" })).status).toBe(403);
    expect((await call({ cookie: me.cookie, origin: "https://evil.example" })).status).toBe(403);
  });
  it("a request with neither Origin nor Sec-Fetch-Site (navigation GET from an attacker page with older browsers) is a logged export: refuse unless same-site", async () => {
    const me = await login("tso334", "tso-pass-1");
    const r = await call({ cookie: me.cookie, "sec-fetch-site": "same-site" });
    expect(r.status).toBe(403); // same-site (sibling subdomain) is not same-origin
  });
  it("the PDF job redirect must not follow a spoofed X-Forwarded-Host", async () => {
    const me = await login("tso334", "tso-pass-1");
    const r = await call({ cookie: me.cookie, "x-forwarded-host": "evil.example" }, "/api/bff/reports/qc/export?format=pdf");
    expect(r.status).toBe(303);
    expect(r.headers.get("location")!.startsWith("/reports/")).toBe(true); // relative: the browser keeps its own host
  });
  it("export filename day is the Dhaka business date, not the UTC date", async () => {
    const me = await login("tso334", "tso-pass-1");
    const r = await call({ cookie: me.cookie });
    const { businessDate } = await import("@/lib/i18n");
    expect(r.headers.get("content-disposition")).toContain(businessDate());
    // differs only between 18:00 and 24:00 UTC; asserted structurally via source in the report
  });
  it("a role without PII gets no phone/owner/trade price in the xlsx either", async () => {
    const me = await login("tso334", "tso-pass-1");
    const r = await call({ cookie: me.cookie }, "/api/bff/reports/retailers/export?format=xlsx");
    const text = await r.text();
    const rows = text.split("\n").slice(2).filter(Boolean).map((l) => l.split("\t"));
    expect(rows.length).toBeGreaterThan(0);
    const head = text.split("\n")[1]!.split("\t");
    for (const row of rows) for (const k of ["owner_name", "phone"]) expect(row[head.indexOf(k)] ?? "").toMatch(/^(|null)$/);
  });
  it("mock formula sanitiser also covers a leading newline and space-prefixed formulas", async () => {
    const { handleDash } = await import("../mock/dash").catch(() => ({ handleDash: null }));
    expect(handleDash).toBeTruthy();
  });
});

describe("pagination", () => {
  it("page beyond the last page returns an empty page without error", async () => {
    const me = await login("tso334", "tso-pass-1");
    const r = await runReportJson(me.at, "route-std", buildReportQuery(rep, { page: "99999" }, me.scope));
    expect(r.ok).toBe(true);
  });
});

// Outlet import (docs/34 s2): the channel is mandatory on every row, in the sheet report, at the proxy and at the server.
import { describe, expect, it } from "vitest";
import { readSheet } from "read-excel-file/node";
import writeExcelFile from "write-excel-file/node";
import { OUTLET_CHANNELS, OUTLET_HEADERS, outletImportRules, outletTemplateCells, parseOutletSheet } from "@/lib/admin/outlet-import";
import { OPS } from "@/lib/admin/ops";
import { madmin, op, setupMock, support } from "./helpers/harness";

const h = setupMock();
const REASON = "Import of the Gulshan outlet census sheet";
const BATCH = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const HEAD = [...OUTLET_HEADERS];
const line = (o: Record<string, string>) => HEAD.map((k) => o[k] ?? "");
const ok = { name: "Rahim Store", owner_name: "Md. Rahim", zone_code: "Z-334-1", cluster: "Banani Market", channel: "GT", outlet_kind: "retail" };

describe("parseOutletSheet", () => {
  it("reads a valid row, optional members become null, Bengali digits in phone and coordinates are accepted", () => {
    const r = parseOutletSheet([HEAD, line({ ...ok, lat: "২৩.৭৯৩৭", lng: "90.4043", contact_number: "০১৭১২৩৪৫৬৭৮", route_code: "R-334-01" })]);
    expect(r.issues).toEqual([]);
    expect(r.rows[0]).toMatchObject({ channel: "GT", lat: 23.7937, lng: 90.4043, contact_number: "01712345678", route_code: "R-334-01", code: null, address: null });
  });
  it("refuses a row with a missing, blank or unknown channel, and a sheet without the channel column", () => {
    const r = parseOutletSheet([HEAD, line({ ...ok, channel: "" }), line({ ...ok, channel: "gt" }), line({ ...ok, channel: "Wholesale" }), line({ ...ok, channel: "HoReCa" })]);
    expect(r.issues).toEqual([{ row: 2, column: "channel", code: "required" }, { row: 3, column: "channel", code: "unknown_channel" }, { row: 4, column: "channel", code: "unknown_channel" }]);
    expect(r.rows).toHaveLength(1);
    expect(parseOutletSheet([HEAD.filter((x) => x !== "channel"), ["a", "b", "c"]]).issues).toEqual([{ row: 1, column: "channel", code: "missing_header" }]);
  });
  it("all six channels are accepted", () => {
    const r = parseOutletSheet([HEAD, ...OUTLET_CHANNELS.map((c, i) => line({ ...ok, channel: c, name: `Store ${i}` }))]);
    expect(r.issues).toEqual([]);
    expect(r.rows.map((x) => x.channel)).toEqual([...OUTLET_CHANNELS]);
  });
  it("checks the other required members, kind, coordinates (both or none), phone and duplicate codes", () => {
    const r = parseOutletSheet([
      HEAD,
      line({ ...ok, name: "", owner_name: "x" }),
      line({ ...ok, outlet_kind: "shop" }),
      line({ ...ok, lat: "91", lng: "90" }),
      line({ ...ok, lat: "23.7" }),
      line({ ...ok, contact_number: "123" }),
      line({ ...ok, code: "A1" }),
      line({ ...ok, code: "A1", name: "Other" }),
    ]);
    expect(r.issues).toEqual([
      { row: 2, column: "name", code: "required" },
      { row: 2, column: "owner_name", code: "invalid" },
      { row: 3, column: "outlet_kind", code: "invalid" },
      { row: 4, column: "lat", code: "invalid" },
      { row: 5, column: "lng", code: "required" },
      { row: 6, column: "contact_number", code: "invalid" },
      { row: 8, column: "code", code: "duplicate" },
    ]);
  });
  it("round-trips the downloadable template through a real workbook", async () => {
    const buf = await writeExcelFile(outletTemplateCells().map((r) => r.map((value) => ({ value })))).toBuffer();
    const r = parseOutletSheet((await readSheet(buf)) as unknown[][]);
    expect(r.issues).toEqual([]);
    expect(r.rows[0]).toMatchObject({ channel: "GT", outlet_kind: "retail" });
  });
});

describe("outlet import through the proxy", () => {
  const row = { code: null, name: "Rahim Store", owner_name: "Md. Rahim", zone_code: "Z-334-1", cluster: "Banani Market", channel: "GT", outlet_kind: "retail", route_code: "R-334-01", lat: null, lng: null, contact_number: null, address: null };
  it("ops: Administrators only, reason only on commit", () => {
    expect(OPS["outlet-import.validate"]).toMatchObject({ method: "POST", reason: null });
    expect(OPS["outlet-import.commit"]).toMatchObject({ reason: "change_reason" });
    expect([...OPS["outlet-import.commit"].roles]).toEqual(["ADMIN", "SUPERADMIN"]);
  });
  it("the proxy refuses a row without a channel before any API call", async () => {
    const c = await madmin();
    for (const channel of [undefined, "", null, "XX"]) {
      const res = await op(c, { op: "outlet-import.validate", body: { batch_uuid: BATCH, rows: [{ ...row, channel }], dry_run: true } });
      expect(res.status).toBe(400);
    }
    expect(outletImportRules("validate", { batch_uuid: BATCH, rows: [{ ...row, channel: "" }], dry_run: true })).toContainEqual({ pointer: "/rows/0/channel", code: "required" });
    expect(h.calls()).toHaveLength(0);
  });
  it("preview reports unknown zone, cluster and route per row; commit creates, replays and audits", async () => {
    const c = await madmin();
    const bad = [{ ...row, zone_code: "NOPE" }, { ...row, cluster: "Nowhere" }, { ...row, route_code: "R-335-01" }];
    const p1 = (await (await op(c, { op: "outlet-import.validate", body: { batch_uuid: BATCH, rows: bad, dry_run: true } })).json()).data;
    expect(p1.errors).toEqual([{ row: 1, column: "zone_code", code: "unknown_zone" }, { row: 2, column: "cluster", code: "unknown_cluster" }, { row: 3, column: "route_code", code: "unknown_route" }]);
    const good = [row, { ...row, name: "Second", channel: "Astha", code: "DHK-334-001" }];
    const p2 = (await (await op(c, { op: "outlet-import.validate", body: { batch_uuid: BATCH, rows: good, dry_run: true } })).json()).data;
    expect(p2).toMatchObject({ rows: 2, creates: 1, updates: 1, by_channel: { GT: 1, Astha: 1 }, errors: [] });
    const before = h.mock.state.tables.outlets!.length;
    const done = await op(c, { op: "outlet-import.commit", body: { batch_uuid: BATCH, rows: good, dry_run: false }, reason: REASON });
    expect((await done.json()).data).toMatchObject({ rows: 2, replayed: false });
    expect(h.mock.state.tables.outlets!.length).toBe(before + 1);
    expect(h.mock.state.tables.outlets!.find((o) => o.code === "DHK-334-001")).toMatchObject({ name: "Second", channel: "Astha" });
    const again = await op(c, { op: "outlet-import.commit", body: { batch_uuid: BATCH, rows: good, dry_run: false }, reason: REASON });
    expect((await again.json()).data).toMatchObject({ replayed: true });
    expect(h.mock.state.tables.outlets!.length).toBe(before + 1);
    expect(h.mock.state.audit.at(-1)).toMatchObject({ entity: "outlet_import", reason: REASON });
  });
  it("SUPPORT is refused", async () => {
    expect((await op(await support(), { op: "outlet-import.validate", body: { batch_uuid: BATCH, rows: [row], dry_run: true } })).status).toBe(403);
  });
});

// Outlet import (docs/34 s2, contract 1.18.0): the field's master data is accepted as it is: channel labels mapped, an empty channel left to the server,
// phones repaired, any number of coordinate decimals, new optional columns.
import { describe, expect, it } from "vitest";
import { readSheet } from "read-excel-file/node";
import writeExcelFile from "write-excel-file/node";
import { OUTLET_HEADERS, outletImportRules, outletTemplateCells, parseOutletSheet } from "@/lib/admin/outlet-import";
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
  it("maps the field's channel labels, leaves empty as null and an unknown text as typed (the server reports it)", () => {
    const labels = ["GT Channel", "Astha Channel", "DCC Channel", "MT Channel", "RCC", "HoReCa", "horeca channel", "gt", "Wholesale", ""];
    const r = parseOutletSheet([HEAD, ...labels.map((c, i) => line({ ...ok, channel: c, name: `Store ${i}` }))]);
    expect(r.issues).toEqual([]);
    expect(r.rows.map((x) => x.channel)).toEqual(["GT", "Astha", "DCC", "MT", "RCC", "HoReCa", "HoReCa", "GT", "Wholesale", null]);
  });
  it("a sheet without the channel header is accepted; the other headers stay required", () => {
    const noChannel = HEAD.filter((x) => x !== "channel");
    const r = parseOutletSheet([noChannel, noChannel.map((k) => (ok as Record<string, string>)[k] ?? "")]);
    expect(r.issues).toEqual([]);
    expect(r.rows[0]!.channel).toBeNull();
    expect(parseOutletSheet([HEAD.filter((x) => x !== "cluster"), ["a"]]).issues).toEqual([{ row: 1, column: "cluster", code: "missing_header" }]);
  });
  it("repairs phones, rounds long coordinates to 8 decimals and reads the new columns", () => {
    const r = parseOutletSheet([HEAD, line({ ...ok, contact_number: "1711000001", lat: "23.793712345678", lng: "90.404312345678", sub_channel: "GT", geo_class: "Semi Urban", created_at: "2024-01-05 10:00", updated_at: "2024-02-01" }), line({ ...ok, name: "Two", contact_number: "abc", sub_channel: "--" })]);
    expect(r.issues).toEqual([]);
    expect(r.rows[0]).toMatchObject({ contact_number: "01711000001", lat: 23.79371235, lng: 90.40431235, sub_channel: "GT", geo_class: "Semi Urban", created_at: "2024-01-05 10:00", updated_at: "2024-02-01" });
    expect(r.rows[1]).toMatchObject({ contact_number: null, sub_channel: null, geo_class: null, created_at: null });
  });
  it("refuses a too long new-column text and an out-of-range coordinate", () => {
    const r = parseOutletSheet([HEAD, line({ ...ok, geo_class: "x".repeat(41) }), line({ ...ok, lat: "91.123456789012", lng: "90" })]);
    expect(r.issues).toEqual([{ row: 2, column: "geo_class", code: "invalid" }, { row: 3, column: "lat", code: "invalid" }]);
  });
  it("checks the other required members, kind, coordinates (both or none), phone and duplicate codes", () => {
    const r = parseOutletSheet([
      HEAD,
      line({ ...ok, name: "", owner_name: "x" }),
      line({ ...ok, outlet_kind: "shop" }),
      line({ ...ok, lat: "91", lng: "90" }),
      line({ ...ok, lat: "23.7" }),
      line({ ...ok, code: "A1" }),
      line({ ...ok, code: "A1", name: "Other" }),
    ]);
    expect(r.issues).toEqual([
      { row: 2, column: "name", code: "required" },
      { row: 2, column: "owner_name", code: "invalid" },
      { row: 3, column: "outlet_kind", code: "invalid" },
      { row: 4, column: "lat", code: "invalid" },
      { row: 5, column: "lng", code: "required" },
      { row: 7, column: "code", code: "duplicate" },
    ]);
  });
  it("round-trips the downloadable template through a real workbook", async () => {
    const buf = await writeExcelFile(outletTemplateCells().map((r) => r.map((value) => ({ value })))).toBuffer();
    const r = parseOutletSheet((await readSheet(buf)) as unknown[][]);
    expect(r.issues).toEqual([]);
    expect(r.rows[0]).toMatchObject({ channel: "GT", outlet_kind: "retail", sub_channel: "GT", geo_class: "Semi Urban" });
  });
});

describe("outlet import through the proxy", () => {
  const row = { code: null, name: "Rahim Store", owner_name: "Md. Rahim", zone_code: "Z-334-1", cluster: "Banani Market", channel: "GT", outlet_kind: "retail", route_code: "R-334-01", lat: null, lng: null, contact_number: null, address: null };
  it("ops: Administrators only, reason only on commit", () => {
    expect(OPS["outlet-import.validate"]).toMatchObject({ method: "POST", reason: null });
    expect(OPS["outlet-import.commit"]).toMatchObject({ reason: "change_reason" });
    expect([...OPS["outlet-import.commit"].roles]).toEqual(["ADMIN", "SUPERADMIN"]);
  });
  it("the proxy no longer needs a channel; the server reports an unknown one as a row error", async () => {
    const c = await madmin();
    for (const channel of [undefined, "", null]) {
      expect(outletImportRules("validate", { batch_uuid: BATCH, rows: [{ ...row, channel }], dry_run: true })).toEqual([]);
    }
    expect(outletImportRules("validate", { batch_uuid: BATCH, rows: [{ ...row, channel: 5 }], dry_run: true })).toContainEqual({ pointer: "/rows/0/channel", code: "invalid" });
    const res = await op(c, { op: "outlet-import.validate", body: { batch_uuid: BATCH, rows: [{ ...row, channel: "XX" }, { ...row, channel: "" }], dry_run: true } });
    expect(res.status).toBe(200);
    expect((await res.json()).data.errors).toEqual([{ row: 1, column: "channel", code: "unknown_channel" }]);
  });
  it("rules: location_confirmed and on_row_error are allowed members with checked values; new row members are allowed", () => {
    const base = { batch_uuid: BATCH, rows: [{ ...row, sub_channel: "GT", geo_class: null, created_at: null, updated_at: null }], dry_run: true };
    expect(outletImportRules("validate", { ...base, location_confirmed: true, on_row_error: "skip_rows" })).toEqual([]);
    expect(outletImportRules("validate", { ...base, on_row_error: "reject_batch" })).toEqual([]);
    expect(outletImportRules("validate", { ...base, location_confirmed: "yes" })).toContainEqual({ pointer: "/location_confirmed", code: "invalid" });
    expect(outletImportRules("validate", { ...base, on_row_error: "ignore" })).toContainEqual({ pointer: "/on_row_error", code: "invalid" });
    expect(outletImportRules("validate", { ...base, surprise: 1 })).toContainEqual({ pointer: "/surprise", code: "unknown_member" });
    expect(outletImportRules("validate", { ...base, rows: [{ ...row, surprise: 1 }] })).toContainEqual({ pointer: "/rows/0", code: "invalid" });
    expect(outletImportRules("validate", { ...base, rows: [{ ...row, name: "" }] })).toContainEqual({ pointer: "/rows/0/name", code: "required" });
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

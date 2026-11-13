// Outlet import from Excel (docs/34 s2), accepting the field's master data as the field has it (backend contract 1.18.0):
// - the channel is OPTIONAL and free text: the field's labels ("GT Channel", "Astha Channel", "RCC", any case, with or without the
//   word Channel) are mapped to the six codes here; an empty channel stays empty (the server derives it from sub_channel, else GT
//   with a warning); a text that maps to no code is sent as is and the server reports unknown_channel as a ROW error (the sheet is
//   not blocked); the channel header may be absent altogether;
// - phones are sent as the sheet has them (B1, push 1b): the SERVER alone repairs, warns (phone_unusable) and keeps an unusable text in
//   the outlet's source column, so nothing vanishes without a trace; a phone never invalidates the row here;
// - Excel date cells are the sheet's wall clock (Asia/Dhaka): sent as "yyyy-MM-dd HH:mm:ss" without an offset, never shifted;
// - coordinates may have any number of decimals (range checked, rounded to 8 before sending);
// - new optional columns sub_channel ("--" or blank = none), geo_class, created_at, updated_at (text, max 40, sent as is).
// The sheet is read in the browser; codes are checked by the server. Wire types come from the contract (importOutlets).
import type { Schemas } from "@/contract/types";
import { latinDigits } from "./taka";

export const OUTLET_CHANNELS = ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"] as const;
export const OUTLET_KINDS = ["retail", "wholesale"] as const;
export const MAX_OUTLET_ROWS = 5000;

export interface OutletImportRow {
  code: string | null;
  name: string;
  owner_name: string;
  zone_code: string;
  cluster: string;
  channel?: string | null;
  outlet_kind: (typeof OUTLET_KINDS)[number];
  route_code: string | null;
  lat: number | null;
  lng: number | null;
  contact_number: string | null;
  address: string | null;
  sub_channel?: string | null;
  geo_class?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
}

export type OutletColumn = "code" | "name" | "owner_name" | "zone_code" | "cluster" | "channel" | "outlet_kind" | "route_code" | "lat" | "lng" | "contact_number" | "address" | "sub_channel" | "geo_class" | "created_at" | "updated_at" | "sheet";
export interface OutletIssue {
  row: number;
  column: OutletColumn;
  code: "missing_header" | "required" | "invalid" | "unknown_channel" | "duplicate" | "too_many_rows" | "no_rows" | "coordinates_invalid";
}

export const OUTLET_HEADERS: readonly OutletColumn[] = ["name", "owner_name", "zone_code", "cluster", "channel", "outlet_kind", "route_code", "lat", "lng", "contact_number", "address", "code", "sub_channel", "geo_class", "created_at", "updated_at"];
const REQUIRED: readonly OutletColumn[] = ["name", "owner_name", "zone_code", "cluster", "outlet_kind"];
const norm = (v: unknown) => String(v ?? "").trim().toLowerCase().replace(/[\s-]+/g, "_");
const CODE = /^[0-9A-Za-z][0-9A-Za-z._/-]{0,39}$/;
const OPTIONAL_TEXT_MAX = 40;

/** The field's channel label to a channel code ("GT Channel", "astha channel", "Ho-ReCa" -> GT, Astha, HoReCa); anything else stays as typed. */
export function channelCode(label: string): string {
  const key = label.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "").replace(/channel$/, "");
  return OUTLET_CHANNELS.find((c) => c.toLowerCase() === key) ?? label;
}
/** An Excel date cell (read-excel-file gives the sheet's wall clock in the Date's UTC fields) as local text: the server reads it as Asia/Dhaka. */
export function excelWallClock(d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`;
}
const cell = (v: unknown): string => (v === null || v === undefined ? "" : v instanceof Date ? (Number.isNaN(v.getTime()) ? "" : excelWallClock(v)) : typeof v === "string" ? v.trim() : String(v).trim());

export interface ParsedOutlets {
  rows: OutletImportRow[];
  sheetRows: number[];
  issues: OutletIssue[];
  /** Non-blocking notes (push 1b): an unusable pin is left out of the row, which still loads (the server repeats the warning). */
  notes: OutletIssue[];
}

export function parseOutletSheet(cells: readonly (readonly unknown[])[]): ParsedOutlets {
  const issues: OutletIssue[] = [];
  const header = (cells[0] ?? []).map(norm);
  const col = {} as Record<OutletColumn, number>;
  for (const h of OUTLET_HEADERS) {
    col[h] = header.indexOf(h);
    if (col[h] < 0 && REQUIRED.includes(h)) issues.push({ row: 1, column: h, code: "missing_header" });
  }
  if (issues.length) return { rows: [], sheetRows: [], issues, notes: [] };
  const notes: OutletIssue[] = [];
  const rows: OutletImportRow[] = [];
  const sheetRows: number[] = [];
  const seenCodes = new Set<string>();
  let used = 0;
  cells.slice(1).forEach((r, i) => {
    const row = i + 2;
    if (!r || r.every((c) => cell(c) === "")) return;
    if (++used > MAX_OUTLET_ROWS) {
      if (used === MAX_OUTLET_ROWS + 1) issues.push({ row, column: "sheet", code: "too_many_rows" });
      return;
    }
    const get = (h: OutletColumn) => (col[h] >= 0 ? cell(r[col[h]]) : "");
    let bad = false;
    const add = (column: OutletColumn, code: OutletIssue["code"]) => {
      issues.push({ row, column, code });
      bad = true;
    };
    for (const h of REQUIRED) if (get(h) === "") add(h, "required");
    const name = get("name");
    if (name !== "" && (name.length < 2 || name.length > 120)) add("name", "invalid");
    const owner = get("owner_name");
    if (owner !== "" && (owner.length < 2 || owner.length > 120)) add("owner_name", "invalid");
    if (get("zone_code") !== "" && !CODE.test(get("zone_code"))) add("zone_code", "invalid");
    const channel = channelCode(get("channel")); // empty stays empty; an unmappable text goes to the server as is
    const kind = get("outlet_kind").toLowerCase();
    if (kind !== "" && !(OUTLET_KINDS as readonly string[]).includes(kind)) add("outlet_kind", "invalid");
    const route = get("route_code");
    if (route !== "" && !CODE.test(route)) add("route_code", "invalid");
    const code = get("code");
    if (code !== "" && !CODE.test(code.slice(0, 32))) add("code", "invalid");
    else if (code.length > 32) add("code", "invalid");
    // (6) push 1b: a pin we cannot use never costs the row; it is left out with a note (a half pin goes to the server, which warns).
    let pinBad = false;
    const num = (h: "lat" | "lng", min: number, max: number): number | null => {
      const raw = latinDigits(get(h));
      if (raw === "") return null;
      const n = Number(raw);
      if (!/^-?\d{1,3}(\.\d+)?$/.test(raw) || !Number.isFinite(n) || n < min || n > max) {
        if (!pinBad) notes.push({ row, column: h, code: "coordinates_invalid" });
        pinBad = true;
        return null;
      }
      return Math.round(n * 1e8) / 1e8;
    };
    const rawLat = num("lat", -90, 90);
    const rawLng = num("lng", -180, 180);
    const lat = pinBad ? null : rawLat;
    const lng = pinBad ? null : rawLng;
    const phone = get("contact_number"); // the text as it is: the server repairs, warns and keeps it
    const optText = (h: "sub_channel" | "geo_class" | "created_at" | "updated_at"): string | null => {
      const v = get(h);
      if (v === "" || (h === "sub_channel" && v === "--")) return null;
      if (Array.from(v).length > OPTIONAL_TEXT_MAX) add(h, "invalid");
      return v;
    };
    const subChannel = optText("sub_channel");
    const geoClass = optText("geo_class");
    const createdAt = optText("created_at");
    const updatedAt = optText("updated_at");
    const address = get("address");
    if (address.length > 300) add("address", "invalid");
    if (code !== "") {
      if (seenCodes.has(code)) add("code", "duplicate");
      seenCodes.add(code);
    }
    if (bad) return;
    rows.push({ code: code || null, name, owner_name: owner, zone_code: get("zone_code"), cluster: get("cluster"), channel: channel || null, outlet_kind: kind as OutletImportRow["outlet_kind"], route_code: route || null, lat, lng, contact_number: phone || null, address: address || null, sub_channel: subChannel, geo_class: geoClass, created_at: createdAt, updated_at: updatedAt });
    sheetRows.push(row);
  });
  if (rows.length === 0 && issues.length === 0) issues.push({ row: 1, column: "sheet", code: "no_rows" });
  return { rows, sheetRows, issues, notes };
}

export function outletTemplateCells(): string[][] {
  return [[...OUTLET_HEADERS], ["Rahim Store", "Md. Rahim", "Z-334-1", "Banani Cluster 1", "GT Channel", "retail", "R-334-01", "23.7937", "90.4043", "01712345678", "Road 11, Banani", "", "GT", "Semi Urban", "", ""]];
}

/** Shape rules of the proxy (the API validates codes, channel and states again; the channel is optional free text). */
export function outletImportRules(op: "validate" | "commit", body: Record<string, unknown>): { pointer: string; code: string }[] {
  const out: { pointer: string; code: string }[] = [];
  for (const k of Object.keys(body)) if (!["batch_uuid", "rows", "dry_run", "change_reason", "location_confirmed", "on_row_error"].includes(k)) out.push({ pointer: `/${k}`, code: "unknown_member" });
  if (typeof body.batch_uuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.batch_uuid)) out.push({ pointer: "/batch_uuid", code: "invalid" });
  if (body.dry_run !== (op === "validate")) out.push({ pointer: "/dry_run", code: "invalid" });
  if (body.location_confirmed !== undefined && typeof body.location_confirmed !== "boolean") out.push({ pointer: "/location_confirmed", code: "invalid" });
  if (body.on_row_error !== undefined && body.on_row_error !== "reject_batch" && body.on_row_error !== "skip_rows") out.push({ pointer: "/on_row_error", code: "invalid" });
  const rows = body.rows;
  if (!Array.isArray(rows) || rows.length < 1 || rows.length > MAX_OUTLET_ROWS) return [...out, { pointer: "/rows", code: "invalid" }];
  const members = new Set<string>(OUTLET_HEADERS);
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i] as Record<string, unknown> | null;
    if (typeof r !== "object" || r === null || Object.keys(r).some((k) => !members.has(k))) return [...out, { pointer: `/rows/${i}`, code: "invalid" }];
    if (r.channel !== undefined && r.channel !== null && typeof r.channel !== "string") return [...out, { pointer: `/rows/${i}/channel`, code: "invalid" }];
    for (const k of ["name", "owner_name", "zone_code", "cluster", "outlet_kind"]) if (typeof r[k] !== "string" || r[k] === "") return [...out, { pointer: `/rows/${i}/${k}`, code: "required" }];
  }
  return out;
}

/** Preview answer of a dry run (contract `OutletImportPreview`). */
export type OutletPreview = Schemas["OutletImportPreview"];

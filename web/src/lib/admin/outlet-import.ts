// Outlet import from Excel (docs/34 s2): the channel (GT, DCC, Astha, RCC, MT, HoReCa) is MANDATORY on every row: a row without a
// known channel is refused in the validation report and again by the server. The sheet is read in the browser; codes are checked
// by the server. The API is PROVISIONAL: docs/requests/backend-admin-outlet-import.md, served by mock/custom.ts.
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
  channel: (typeof OUTLET_CHANNELS)[number];
  outlet_kind: (typeof OUTLET_KINDS)[number];
  route_code: string | null;
  lat: number | null;
  lng: number | null;
  contact_number: string | null;
  address: string | null;
}

export type OutletColumn = "code" | "name" | "owner_name" | "zone_code" | "cluster" | "channel" | "outlet_kind" | "route_code" | "lat" | "lng" | "contact_number" | "address" | "sheet";
export interface OutletIssue {
  row: number;
  column: OutletColumn;
  code: "missing_header" | "required" | "invalid" | "unknown_channel" | "duplicate" | "too_many_rows" | "no_rows";
}

export const OUTLET_HEADERS: readonly OutletColumn[] = ["name", "owner_name", "zone_code", "cluster", "channel", "outlet_kind", "route_code", "lat", "lng", "contact_number", "address", "code"];
const REQUIRED: readonly OutletColumn[] = ["name", "owner_name", "zone_code", "cluster", "channel", "outlet_kind"];
const norm = (v: unknown) => String(v ?? "").trim().toLowerCase().replace(/[\s-]+/g, "_");
const CODE = /^[0-9A-Za-z][0-9A-Za-z._/-]{0,39}$/;
const cell = (v: unknown): string => (v === null || v === undefined ? "" : typeof v === "string" ? v.trim() : String(v).trim());

export interface ParsedOutlets {
  rows: OutletImportRow[];
  sheetRows: number[];
  issues: OutletIssue[];
}

export function parseOutletSheet(cells: readonly (readonly unknown[])[]): ParsedOutlets {
  const issues: OutletIssue[] = [];
  const header = (cells[0] ?? []).map(norm);
  const col = {} as Record<OutletColumn, number>;
  for (const h of OUTLET_HEADERS) {
    col[h] = header.indexOf(h);
    if (col[h] < 0 && REQUIRED.includes(h)) issues.push({ row: 1, column: h, code: "missing_header" });
  }
  if (issues.length) return { rows: [], sheetRows: [], issues };
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
    const channel = get("channel");
    if (channel !== "" && !(OUTLET_CHANNELS as readonly string[]).includes(channel)) add("channel", "unknown_channel");
    const kind = get("outlet_kind").toLowerCase();
    if (kind !== "" && !(OUTLET_KINDS as readonly string[]).includes(kind)) add("outlet_kind", "invalid");
    const route = get("route_code");
    if (route !== "" && !CODE.test(route)) add("route_code", "invalid");
    const code = get("code");
    if (code !== "" && !CODE.test(code.slice(0, 32))) add("code", "invalid");
    else if (code.length > 32) add("code", "invalid");
    const num = (h: "lat" | "lng", min: number, max: number): number | null => {
      const raw = latinDigits(get(h));
      if (raw === "") return null;
      const n = Number(raw);
      if (!/^-?\d{1,3}(\.\d{1,8})?$/.test(raw) || !Number.isFinite(n) || n < min || n > max) {
        add(h, "invalid");
        return null;
      }
      return n;
    };
    const lat = num("lat", -90, 90);
    const lng = num("lng", -180, 180);
    if ((get("lat") === "") !== (get("lng") === "")) add(get("lat") === "" ? "lat" : "lng", "required"); // a location needs both
    const phone = latinDigits(get("contact_number"));
    if (phone !== "" && !/^01[3-9]\d{8}$/.test(phone)) add("contact_number", "invalid");
    const address = get("address");
    if (address.length > 300) add("address", "invalid");
    if (code !== "") {
      if (seenCodes.has(code)) add("code", "duplicate");
      seenCodes.add(code);
    }
    if (bad) return;
    rows.push({ code: code || null, name, owner_name: owner, zone_code: get("zone_code"), cluster: get("cluster"), channel: channel as OutletImportRow["channel"], outlet_kind: kind as OutletImportRow["outlet_kind"], route_code: route || null, lat, lng, contact_number: phone || null, address: address || null });
    sheetRows.push(row);
  });
  if (rows.length === 0 && issues.length === 0) issues.push({ row: 1, column: "sheet", code: "no_rows" });
  return { rows, sheetRows, issues };
}

export function outletTemplateCells(): string[][] {
  return [[...OUTLET_HEADERS], ["Rahim Store", "Md. Rahim", "Z-334-1", "Banani Cluster 1", "GT", "retail", "R-334-01", "23.7937", "90.4043", "01712345678", "Road 11, Banani", ""]];
}

/** Shape rules of the proxy (the API validates codes and states again); the channel check repeats here so no client can skip it. */
export function outletImportRules(op: "validate" | "commit", body: Record<string, unknown>): { pointer: string; code: string }[] {
  const out: { pointer: string; code: string }[] = [];
  for (const k of Object.keys(body)) if (!["batch_uuid", "rows", "dry_run", "change_reason"].includes(k)) out.push({ pointer: `/${k}`, code: "unknown_member" });
  if (typeof body.batch_uuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.batch_uuid)) out.push({ pointer: "/batch_uuid", code: "invalid" });
  if (body.dry_run !== (op === "validate")) out.push({ pointer: "/dry_run", code: "invalid" });
  const rows = body.rows;
  if (!Array.isArray(rows) || rows.length < 1 || rows.length > MAX_OUTLET_ROWS) return [...out, { pointer: "/rows", code: "invalid" }];
  const members = new Set<string>(OUTLET_HEADERS);
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i] as Record<string, unknown> | null;
    if (typeof r !== "object" || r === null || Object.keys(r).some((k) => !members.has(k))) return [...out, { pointer: `/rows/${i}`, code: "invalid" }];
    if (typeof r.channel !== "string" || !(OUTLET_CHANNELS as readonly string[]).includes(r.channel)) return [...out, { pointer: `/rows/${i}/channel`, code: r.channel === undefined || r.channel === null || r.channel === "" ? "required" : "invalid" }];
    for (const k of ["name", "owner_name", "zone_code", "cluster", "outlet_kind"]) if (typeof r[k] !== "string" || r[k] === "") return [...out, { pointer: `/rows/${i}/${k}`, code: "required" }];
  }
  return out;
}

/** Wire shape of the preview answer (REQUEST: backend-admin-outlet-import). */
export interface OutletPreview {
  batch_uuid: string;
  rows: number;
  creates: number;
  updates: number;
  by_channel: Record<string, number>;
  errors: { row: number; column: string; code: string }[];
}

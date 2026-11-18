// Points adjustment (contract 1.23.0, D-LOY-API-35): pure helpers of the portal form. One outlet at a time (postProgrammeAdjustment) or a CSV file
// (putProgrammeAdjustments: a dry-run preview, then the apply). No earning rule lives here: the amount is whatever the admin enters, with a reason.
// The API stays the authority on scope, programme state, overdraw and replay; this only spares a round trip and places the errors.
export const ADJ_POINTS_MAX = 1_000_000;
export const ADJ_MAX_ROWS = 20_000;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const realDay = (s: string): boolean => DAY.test(s) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);
const INT = /^[+-]?\d{1,7}$/;
const ID = /^[1-9]\d{0,14}$/;

export type AdjustForm = { outlet_id: string; points: string; expires_on: string; reason: string; allow_overdraw: boolean };
export const emptyAdjustForm = (): AdjustForm => ({ outlet_id: "", points: "", expires_on: "", reason: "", allow_overdraw: false });
export type AdjustRow = { outlet_id: number; points: number; expires_on?: string };
export type AdjustCode = "required" | "not_an_integer" | "out_of_range" | "zero" | "not_a_date" | "in_the_past" | "reason_short" | "reason_long" | "duplicate";
export type AdjustError = { field: string; code: AdjustCode };

/** The signed amount typed by the admin: whole points, a leading + or - allowed; null when it is not one. */
export function parsePoints(text: string): number | null {
  const v = text.trim();
  if (!INT.test(v)) return null;
  const n = Number(v);
  return Number.isSafeInteger(n) ? n : null;
}

function rowErrors(outlet: string, points: string, expires: string, today: string, prefix: string): { row: AdjustRow | null; errors: AdjustError[] } {
  const errors: AdjustError[] = [];
  const o = outlet.trim();
  if (!o) errors.push({ field: `${prefix}outlet_id`, code: "required" });
  else if (!ID.test(o)) errors.push({ field: `${prefix}outlet_id`, code: "not_an_integer" });
  const p = points.trim();
  const n = parsePoints(p);
  if (!p) errors.push({ field: `${prefix}points`, code: "required" });
  else if (n === null) errors.push({ field: `${prefix}points`, code: "not_an_integer" });
  else if (n === 0) errors.push({ field: `${prefix}points`, code: "zero" });
  else if (Math.abs(n) > ADJ_POINTS_MAX) errors.push({ field: `${prefix}points`, code: "out_of_range" });
  const e = expires.trim();
  if (e && !realDay(e)) errors.push({ field: `${prefix}expires_on`, code: "not_a_date" });
  else if (e && e < today) errors.push({ field: `${prefix}expires_on`, code: "in_the_past" });
  if (errors.length > 0 || n === null) return { row: null, errors };
  return { row: { outlet_id: Number(o), points: n, ...(e ? { expires_on: e } : {}) }, errors };
}

/** The single form: the body of postProgrammeAdjustment (without request_uuid, which the component keeps per attempt) or the field errors. */
export function validateAdjust(f: AdjustForm, today: string): { errors: AdjustError[]; body: (AdjustRow & { reason: string; allow_overdraw?: true }) | null } {
  const { row, errors } = rowErrors(f.outlet_id, f.points, f.expires_on, today, "");
  const reason = f.reason.trim();
  const len = Array.from(reason).length;
  if (len < 10) errors.push({ field: "reason", code: "reason_short" });
  else if (len > 500) errors.push({ field: "reason", code: "reason_long" });
  if (errors.length > 0 || !row) return { errors, body: null };
  return { errors, body: { ...row, reason, ...(f.allow_overdraw ? { allow_overdraw: true as const } : {}) } };
}

export type ParsedFile = { rows: AdjustRow[]; errors: { line: number; code: AdjustCode; column: "outlet_id" | "points" | "expires_on" | "row" }[]; tooMany: boolean };

/**
 * A CSV file of `outlet_id,points[,expires_on]` (a header line and blank lines are skipped; `;` or a tab also separate). Every bad line is reported with its
 * 1-based line number; an outlet may appear once. The server judges again.
 */
export function parseAdjustFile(text: string, today: string): ParsedFile {
  const rows: AdjustRow[] = [];
  const errors: ParsedFile["errors"] = [];
  const seen = new Set<number>();
  const lines = text.replace(/^﻿/, "").split(/\r?\n/);
  lines.forEach((raw, i) => {
    const line = raw.trim();
    if (!line) return;
    const cells = line.split(/[,;\t]/).map((c) => c.trim());
    if (i === 0 || rows.length + errors.length === 0) if (/^outlet/i.test(cells[0] ?? "")) return; // the header
    const { row, errors: es } = rowErrors(cells[0] ?? "", cells[1] ?? "", cells[2] ?? "", today, "");
    if (cells.length > 3) errors.push({ line: i + 1, code: "not_an_integer", column: "row" });
    for (const e of es) errors.push({ line: i + 1, code: e.code, column: e.field as "outlet_id" | "points" | "expires_on" });
    if (row) {
      if (seen.has(row.outlet_id)) errors.push({ line: i + 1, code: "duplicate", column: "outlet_id" });
      else { seen.add(row.outlet_id); rows.push(row); }
    }
  });
  return { rows, errors, tooMany: rows.length > ADJ_MAX_ROWS };
}

export type AdjustFailure = "loy.adj.refused_state" | "loy.adj.refused_scope" | "loy.adj.overdraw" | "loy.adj.reused" | "loy.adj.refused_role" | null;

/** The banner for a refused call (the field errors are placed separately); null = a generic problem text. */
export function adjustFailureKey(status: number, code: string | undefined, errors: readonly { pointer: string; code: string }[] | undefined): AdjustFailure {
  if (status === 403) return (errors ?? []).some((e) => e.code === "out_of_scope") ? "loy.adj.refused_scope" : "loy.adj.refused_role";
  if (status === 409 && (errors ?? []).some((e) => e.code === "would_overdraw")) return "loy.adj.overdraw";
  if (status === 409 && code === "ERR_REQUEST_STATE") return "loy.adj.refused_state";
  if (status === 409 && code === "ERR_CONFLICT") return "loy.adj.reused";
  return null;
}

/** The ledger row sign for display: always shows + or - (never a bare number). */
export const signed = (n: number): string => (n > 0 ? `+${n}` : String(n));

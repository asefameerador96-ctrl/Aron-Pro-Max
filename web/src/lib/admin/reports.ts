// Day control reads the report registry (POST /v1/reports/{key}/query): the final-submit log lists late rows after a final,
// the attendance report lists check-outs. The report columns are matched by key pattern (see docs/requests/web-config-day-control-columns.md).
import type { ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";
import type { ReportResult } from "./types";

export function runReport(token: string, key: "final-submit-log" | "attendance", date: string, zone?: string): Promise<ApiOutcome<ReportResult>> {
  const body = { output: { format: "json", page_size: 100 }, period: { date }, ...(zone ? { geo: { zone: [Number(zone)] } } : {}) };
  return rawRequest<ReportResult>({ method: "POST", path: `/v1/reports/${key}/query`, token, body });
}

const truthy = (v: unknown) => v === true || (typeof v === "number" && v > 0) || (typeof v === "string" && !["", "0", "false", "no", "n", "none"].includes(v.trim().toLowerCase()));

/** Rows of the final-submit log that were captured after the final (a `late*` column that is true or above zero). */
export function lateRows(r: ReportResult): { rows: ReportResult["rows"]; known: boolean } {
  const cols = r.columns.filter((c) => /(^|_)late(_|$)|after_final/i.test(c.key) && c.type !== "timestamp" && c.type !== "date" && c.type !== "string").map((c) => c.key);
  if (cols.length === 0) return { rows: [], known: false };
  return { rows: r.rows.filter((row) => cols.some((k) => truthy(row[k]))), known: true };
}

/** Attendance rows with a check-in but no check-out. */
export function missingCheckoutRows(r: ReportResult): { rows: ReportResult["rows"]; known: boolean } {
  const out = r.columns.filter((c) => /check_?out/i.test(c.key) && c.type !== "bool").map((c) => c.key);
  const inn = r.columns.filter((c) => /check_?in/i.test(c.key) && c.type !== "bool").map((c) => c.key);
  if (out.length === 0) return { rows: [], known: false };
  return { rows: r.rows.filter((row) => out.every((k) => row[k] === null || row[k] === undefined || row[k] === "") && (inn.length === 0 || inn.some((k) => row[k]))), known: true };
}

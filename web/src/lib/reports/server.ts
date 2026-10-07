// Server-side data access for the dashboard pages. Tokens stay here; the browser only sees rendered HTML or BFF streams.
import type { Me, Problem, Schemas } from "@/contract/types";
import { apiBase, transportProblem, type ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";
import type { ReportKey } from "./catalog";
import type { GeoLevel, ReportQuery } from "./query";

export type ReportResult = Schemas["ReportResult"];
export type ExportJob = Schemas["ExportJob"];

export const getMe = (token: string): Promise<ApiOutcome<Me>> => rawRequest<Me>({ method: "GET", path: "/v1/me", token });

export const runReportJson = (token: string, key: ReportKey, q: ReportQuery): Promise<ApiOutcome<ReportResult>> =>
  rawRequest<ReportResult>({ method: "POST", path: `/v1/reports/${key}/query`, token, body: q });

export const getExportJob = (token: string, id: string): Promise<ApiOutcome<ExportJob>> => rawRequest<ExportJob>({ method: "GET", path: `/v1/report-exports/${encodeURIComponent(id)}`, token });

export interface GeoOption {
  id: number;
  label: string;
}

/** Options for one level of the five-level scope filter. The server returns only nodes in the caller's reach. */
export async function geoOptions(token: string, level: GeoLevel, parentId?: number): Promise<ApiOutcome<GeoOption[]>> {
  if (level === "route") {
    if (!parentId) return { ok: true, status: 200, data: [], response: new Response(null) };
    const r = await rawRequest<{ items: Schemas["Route"][] }>({ method: "GET", path: "/v1/admin/routes", token, query: { zone_id: parentId, status: "active", limit: 500 } });
    return r.ok ? { ...r, data: r.data.items.map((x) => ({ id: x.id, label: x.display_label ?? x.name })) } : r;
  }
  const r = await rawRequest<{ items: Schemas["GeoNode"][] }>({ method: "GET", path: `/v1/admin/geo/${level}`, token, query: { parent_id: parentId, status: "active", limit: 500 } });
  return r.ok ? { ...r, data: r.data.items.map((x) => ({ id: x.id, label: x.name })) } : r;
}

/** Binary or HTML output (xlsx workbook, print view). Same query as the screen; the server logs the export and watermarks it. */
export async function runReportRaw(token: string, key: ReportKey, q: ReportQuery): Promise<{ ok: true; response: Response } | { ok: false; status: number; problem: Problem }> {
  let response: Response;
  try {
    response = await fetch(`${apiBase()}/v1/reports/${key}/query`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", Accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, text/html, application/json", "X-Request-Id": crypto.randomUUID() },
      body: JSON.stringify(q),
      cache: "no-store",
    });
  } catch {
    return { ok: false, status: 503, problem: transportProblem(503) };
  }
  if (response.headers.get("x-aron-api") !== "1") return { ok: false, status: response.status, problem: transportProblem(response.status) };
  if (response.ok) return { ok: true, response };
  const p = (await response.json().catch(() => null)) as Partial<Problem> | null;
  return { ok: false, status: response.status, problem: p && typeof p.code === "string" ? (p as Problem) : { ...transportProblem(response.status), code: "ERR_INTERNAL" } };
}

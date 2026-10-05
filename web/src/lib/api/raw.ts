// Path-driven request used by the generic CRUD engine, where the path comes from entity metadata (typed as `keyof paths`
// there) instead of a literal at the call site. Same marker check, same problem handling as client.ts.
import type { Problem } from "@/contract/types";
import { apiBase, transportProblem, type ApiOutcome } from "./client";

export interface RawRequest {
  method: "GET" | "POST" | "PATCH" | "PUT";
  path: string; // already filled, e.g. /v1/admin/clusters/12
  token: string;
  query?: Record<string, string | number | undefined>;
  body?: unknown;
  ifMatch?: string;
}

export async function rawRequest<T>(req: RawRequest): Promise<ApiOutcome<T>> {
  const url = new URL(apiBase() + req.path);
  for (const [k, v] of Object.entries(req.query ?? {})) if (v !== undefined && v !== "") url.searchParams.set(k, String(v));
  const headers: Record<string, string> = { Authorization: `Bearer ${req.token}`, Accept: "application/json", "X-Request-Id": crypto.randomUUID() };
  if (req.body !== undefined) headers["Content-Type"] = "application/json";
  if (req.ifMatch) headers["If-Match"] = req.ifMatch;
  let response: Response;
  try {
    response = await fetch(url, { method: req.method, headers, body: req.body === undefined ? undefined : JSON.stringify(req.body), cache: "no-store" });
  } catch {
    return { ok: false, status: 503, problem: transportProblem(503) };
  }
  if (response.headers.get("x-aron-api") !== "1") return { ok: false, status: response.status, problem: transportProblem(response.status), response };
  const text = await response.text();
  let json: unknown = undefined;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  if (response.ok) return { ok: true, status: response.status, data: json as T, response };
  const p = json as Partial<Problem> | undefined;
  const problem: Problem = p && typeof p.code === "string" ? (p as Problem) : { ...transportProblem(response.status), code: "ERR_INTERNAL" };
  return { ok: false, status: response.status, problem, response };
}

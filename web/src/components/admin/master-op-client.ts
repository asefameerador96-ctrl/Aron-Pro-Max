// Browser side of POST /api/bff/master-op. The browser sends an operation key, path parameters, a body and the reason; the
// server decides method, path and roles. Returns the problem on failure so forms can show field errors.
import type { Problem } from "@/contract/types";
import type { MasterOpKey } from "@/lib/admin/master-ops";

export type OpResult<T> = { ok: true; data: T } | { ok: false; status: number; problem: Partial<Problem> };

export async function callMasterOp<T = unknown>(op: MasterOpKey, args: { params?: Record<string, string | number>; body?: Record<string, unknown>; reason: string }): Promise<OpResult<T>> {
  try {
    const res = await fetch("/api/bff/master-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op, params: args.params ?? {}, body: args.body ?? {}, reason: args.reason }) });
    const json = (await res.json().catch(() => ({}))) as { data?: T } & Partial<Problem>;
    if (res.ok) return { ok: true, data: json.data as T };
    return { ok: false, status: res.status, problem: json };
  } catch {
    return { ok: false, status: 0, problem: { code: "ERR_SERVICE_UNAVAILABLE" } };
  }
}

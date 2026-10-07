// BFF handler of MASTER_OPS (POST /api/bff/master-op). Server only: the access token never leaves it.
import { NextResponse, type NextRequest } from "next/server";
import type { Problem } from "@/contract/types";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";
import { fillPath } from "@/lib/admin/ops";
import { isMasterOp, MASTER_OPS, type MasterOpKey } from "@/lib/admin/master-ops";
import { masterOpRules } from "./master-op-rules";
import { codePoints, REASON_MIN } from "./crud/validation";

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

export async function handleMasterOp(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const input = (await req.json().catch(() => null)) as Record<string, unknown> | null;
  if (!isObj(input) || !isMasterOp(input.op)) return auth.finish(problemResponse(400, "ERR_MALFORMED_JSON"));
  const key = input.op as MasterOpKey;
  const def = MASTER_OPS[key];
  const role = auth.session.user.role;
  if (!(def.roles as readonly string[]).includes(role)) return auth.finish(problemResponse(403, "ERR_FORBIDDEN"));

  const path = fillPath(def.path, isObj(input.params) ? input.params : undefined);
  if (path === null) return auth.finish(problemResponse(400, "ERR_VALIDATION", { errors: [{ pointer: "/params", code: "invalid" }] }));

  const body: Record<string, unknown> = isObj(input.body) ? { ...input.body } : {};
  const errors: { pointer: string; code: string }[] = [];
  const reason = typeof input.reason === "string" ? input.reason.replace(/[\u200B-\u200D\u2060\uFEFF]/g, "").trim() : ""; // zero-width characters do not count as a reason
  const n = codePoints(reason);
  const max = "reasonMax" in def ? def.reasonMax : 500;
  if (n < REASON_MIN) errors.push({ pointer: "/reason", code: "too_short" });
  else if (n > max) errors.push({ pointer: "/reason", code: "too_long" });
  else body[def.reason] = reason;
  errors.push(...(await masterOpRules(key, body, isObj(input.params) ? input.params : {}, auth.session.at, role, auth.session.scope?.nodes ?? [])));
  if (errors.some((e) => e.code === "forbidden_scope")) return auth.finish(problemResponse(403, "ERR_FORBIDDEN"));
  if (errors.some((e) => e.code === "unavailable")) return auth.finish(problemResponse(503, "ERR_SERVICE_UNAVAILABLE", { retryable: true }));
  if (errors.some((e) => e.code === "not_found")) return auth.finish(problemResponse(404, "ERR_NOT_FOUND"));
  if (errors.length) return auth.finish(problemResponse(400, "ERR_VALIDATION", { errors }));

  const r = await rawRequest<unknown>({ method: def.method, path, token: auth.session.at, body });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem as Problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json({ data: r.data ?? null }, { status: r.status === 204 ? 200 : r.status }));
}

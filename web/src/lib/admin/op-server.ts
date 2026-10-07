// BFF handler of the whitelisted admin operations (see ops.ts). Server only: the access token never leaves it.
import { NextResponse, type NextRequest } from "next/server";
import type { Problem } from "@/contract/types";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";
import { codePoints, REASON_MAX, REASON_MIN } from "@/components/admin/crud/validation";
import { opRules } from "./op-rules";
import { fillPath, isOpKey, isTeamOpKey, OPS, TEAM_OPS, type OpDef, type OpKey } from "./ops";

interface OpRequest {
  op?: unknown;
  params?: unknown;
  body?: unknown;
  reason?: unknown;
  version?: unknown;
}

const isPlainObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** POST /api/bff/admin-op: one audited write through a whitelisted contract operation. */
export async function handleOp(req: NextRequest, table: "admin" | "team" = "admin"): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const input = (await req.json().catch(() => null)) as OpRequest | null;
  const known = table === "admin" ? isOpKey(input?.op) : isTeamOpKey(input?.op);
  if (!isPlainObject(input) || !known) return auth.finish(problemResponse(400, "ERR_MALFORMED_JSON"));
  const def: OpDef = table === "admin" ? OPS[input.op as OpKey] : TEAM_OPS[input.op as keyof typeof TEAM_OPS];
  if (!def.roles.includes(auth.session.user.role)) return auth.finish(problemResponse(403, "ERR_FORBIDDEN"));

  const path = fillPath(def.path, isPlainObject(input.params) ? input.params : undefined);
  if (path === null) return auth.finish(problemResponse(400, "ERR_VALIDATION", { errors: [{ pointer: "/params", code: "invalid" }] }));

  const body: Record<string, unknown> = isPlainObject(input.body) ? { ...input.body } : {};
  if (def.forbid && typeof body[def.forbid.member] === "string" && def.forbid.values.includes(body[def.forbid.member] as string)) return auth.finish(problemResponse(403, "ERR_FORBIDDEN"));
  const errors: { pointer: string; code: string }[] = [];
  const given = typeof input.reason === "string" && input.reason.trim() !== "";
  if (def.reason !== null && (!def.reasonOptional || given)) {
    const reason = typeof input.reason === "string" ? input.reason.trim() : "";
    const n = codePoints(reason);
    if (n < REASON_MIN) errors.push({ pointer: "/reason", code: "too_short" });
    else if (n > REASON_MAX) errors.push({ pointer: "/reason", code: "too_long" });
    else body[def.reason] = reason;
  }
  let ifMatch: string | undefined;
  if (def.ifMatch) {
    const v = input.version;
    // If-Match must match ^"[0-9]{1,10}"$ (contract): an integer of at most 10 digits, at least 1.
    if (typeof v !== "number" || !Number.isInteger(v) || v < 1 || v > 9_999_999_999) errors.push({ pointer: "/version", code: v === undefined ? "required" : "invalid" });
    else ifMatch = `"${v}"`;
  }
  errors.push(...opRules(input.op as string, body));
  if (errors.length) return auth.finish(problemResponse(400, "ERR_VALIDATION", { errors }));

  const r = await rawRequest<unknown>({ method: def.method, path, token: auth.session.at, body: def.method === "DELETE" || def.noBody ? undefined : body, ifMatch });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem as Problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json({ data: r.data ?? null }, { status: r.status === 204 ? 200 : r.status }));
}

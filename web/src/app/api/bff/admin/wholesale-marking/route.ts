import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { Problem } from "@/contract/types";
import { apiClient, outcome } from "@/lib/api/client";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { reasonSchema, toFieldErrors } from "@/components/admin/crud/validation";

const Body = z
  .object({
    batch_uuid: z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/),
    outlet_kind: z.enum(["retail", "wholesale"]),
    outlet_ids: z.array(z.number().int().min(1)).min(1).max(5000),
    reason: z.unknown(),
  })
  .strict();

// Bulk outlet-kind change (F-ADM-056). Idempotent by the client's batch_uuid: a repeat replays the first result.
export async function POST(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  if (!["ADMIN", "SUPERADMIN"].includes(auth.session.user.role)) return problemResponse(403, "ERR_FORBIDDEN");
  const raw = await req.json().catch(() => null);
  const body = Body.safeParse(raw);
  const reason = reasonSchema.safeParse((raw as { reason?: unknown } | null)?.reason);
  if (!body.success || !reason.success) {
    return problemResponse(400, "ERR_VALIDATION", { errors: [...(body.success ? [] : toFieldErrors(body.error, "")), ...(reason.success ? [] : toFieldErrors(reason.error, "/reason"))] });
  }
  if (new Set(body.data.outlet_ids).size !== body.data.outlet_ids.length) return problemResponse(400, "ERR_VALIDATION", { errors: [{ pointer: "/outlet_ids", code: "duplicate" }] });
  const r = await outcome(apiClient(auth.session.at).POST("/v1/admin/outlets/outlet-kind", { body: { batch_uuid: body.data.batch_uuid, outlet_kind: body.data.outlet_kind, outlet_ids: body.data.outlet_ids, reason: reason.data } }));
  if (!r.ok) return auth.finish(NextResponse.json(r.problem as Problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json(r.data));
}

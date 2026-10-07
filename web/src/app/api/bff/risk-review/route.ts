import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { Schemas } from "@/contract/types";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";

const body = z.object({ signal_id: z.number().int().positive(), request_uuid: z.string().uuid(), action: z.enum(["reviewed", "dismissed", "confirmed"]), note: z.string().trim().max(500).optional() });

/** POST /api/bff/risk-review: review, dismiss or confirm a risk signal (F-WEB-057). Idempotent by the review uuid. */
export async function POST(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const p = body.safeParse(await req.json().catch(() => null));
  if (!p.success) return auth.finish(problemResponse(400, "ERR_VALIDATION"));
  const r = await rawRequest<Schemas["RiskSignal"]>({ method: "POST", path: `/v1/risk-signals/${p.data.signal_id}/review`, token: auth.session.at, body: { review_uuid: p.data.request_uuid, action: p.data.action, note: p.data.note ?? null } });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json(r.data));
}

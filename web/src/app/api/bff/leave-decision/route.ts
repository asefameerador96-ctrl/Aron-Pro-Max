import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { Schemas } from "@/contract/types";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";

const body = z.object({ leave_uuid: z.string().uuid(), decision: z.enum(["approve", "reject"]), note: z.string().trim().max(300).optional() });

/** POST /api/bff/leave-decision: a DMO approves or rejects a TSO's leave (F-WEB-046). The server checks the role and the TSO's reach. */
export async function POST(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const p = body.safeParse(await req.json().catch(() => null));
  if (!p.success) return auth.finish(problemResponse(400, "ERR_VALIDATION"));
  const r = await rawRequest<Schemas["LeaveApplication"]>({ method: "POST", path: `/v1/leave/${p.data.leave_uuid}/decision`, token: auth.session.at, body: { decision: p.data.decision, note: p.data.note ?? null } });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json(r.data));
}

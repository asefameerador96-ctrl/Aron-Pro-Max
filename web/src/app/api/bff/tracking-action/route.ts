import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { Schemas } from "@/contract/types";
import { isCalendarDate } from "@/lib/dates";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";

const body = z.object({
  action_uuid: z.string().uuid(),
  route_id: z.number().int().positive(),
  business_date: z.string().refine(isCalendarDate),
  note: z.string().trim().min(3).max(500),
});

/** POST /api/bff/tracking-action: the Daily Tracking "take action" note (F-WEB-039). The server enforces the 17:00 rule, scope and notifications. */
export async function POST(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const parsed = body.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return auth.finish(problemResponse(400, "ERR_VALIDATION"));
  const r = await rawRequest<Schemas["TrackingAction"]>({ method: "POST", path: "/v1/dashboards/daily-tracking/actions", token: auth.session.at, body: parsed.data });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  return auth.finish(NextResponse.json(r.data, { status: r.status }));
}

import { NextResponse, type NextRequest } from "next/server";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { isCalendarDate } from "@/lib/dates";
import { businessDate } from "@/lib/i18n";
import { getFfPopup } from "@/lib/national/server";

// GET /api/bff/national/ff-popup?route_id=&date=: the pop-up of one FF Geo pin, read when the pin is clicked. The server decides whether the
// caller sees the SR's name and phone; the page shows what it gets.
export async function GET(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const id = Number(req.nextUrl.searchParams.get("route_id"));
  const d = req.nextUrl.searchParams.get("date");
  if (!Number.isSafeInteger(id) || id <= 0) return auth.finish(problemResponse(400, "ERR_VALIDATION"));
  const r = await getFfPopup(auth.session.at, id, isCalendarDate(d) ? d : businessDate());
  const headers = { "Cache-Control": "no-store", ...(r.ok ? {} : { "Content-Type": "application/problem+json" }) };
  return auth.finish(NextResponse.json(r.ok ? r.data : r.problem, { status: r.ok ? 200 : r.status, headers }));
}

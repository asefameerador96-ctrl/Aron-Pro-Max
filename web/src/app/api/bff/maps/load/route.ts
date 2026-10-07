import { NextResponse, type NextRequest } from "next/server";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { mapsKey, sharedGuard } from "@/lib/maps/guard";

/** POST /api/bff/maps/load: permission (and the key) to load the Maps script, counted against the daily cap. */
export async function POST(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const key = mapsKey();
  if (!key) return auth.finish(NextResponse.json({ enabled: false, reason: "no_key" }, { status: 200, headers: { "Cache-Control": "no-store" } }));
  const r = sharedGuard().take();
  if (!r.allowed) return auth.finish(problemResponse(429, "ERR_RATE_LIMITED", { detail: "maps_daily_cap" }));
  return auth.finish(NextResponse.json({ enabled: true, key }, { headers: { "Cache-Control": "no-store" } }));
}

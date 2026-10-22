import { NextResponse, type NextRequest } from "next/server";
import { authenticate } from "@/lib/api/guard";
import { businessDate } from "@/lib/i18n";
import { parseQuery } from "@/lib/national/query";
import { getFfGeo } from "@/lib/national/server";

// GET /api/bff/national/ff-geo?<same filter as the page>: the route pins of the FF Geo card. The page loads them from the browser, after
// the cards, so the 6,933 pins of a national selection never sit in the HTML of the dashboard. The filter is re-parsed here (only known
// members pass) and the server re-checks scope from the token.
export async function GET(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const sp: Record<string, string> = {};
  for (const [k, v] of req.nextUrl.searchParams) sp[k] = v;
  const r = await getFfGeo(auth.session.at, parseQuery(sp, businessDate()));
  const headers = { "Cache-Control": "no-store", ...(r.ok ? {} : { "Content-Type": "application/problem+json" }) };
  return auth.finish(NextResponse.json(r.ok ? r.data : r.problem, { status: r.ok ? 200 : r.status, headers }));
}

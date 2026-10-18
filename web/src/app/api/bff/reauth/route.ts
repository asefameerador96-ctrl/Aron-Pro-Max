import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import type { ReauthResponse } from "@/contract/types";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { clientIpHeaders } from "@/lib/api/client-ip";
import { rawRequest } from "@/lib/api/raw";
import { ADMIN_ABSOLUTE_DAYS } from "@/lib/auth/limits";
import { setSessionCookie } from "@/lib/auth/service";

const body = z.object({ password: z.string().min(1).max(128), code: z.string().regex(/^[0-9]{6}$/).nullable().optional() }).strict();

/**
 * POST /api/bff/reauth: the PII reveal step-up (POST /v1/auth/reauth). The new access token replaces the one in the sealed
 * session cookie; the refresh cookie stays, and a later refresh drops the reveal (the server decides, the web only shows pii_until).
 * The password and code are forwarded, never logged or echoed.
 */
export async function POST(req: NextRequest): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const p = body.safeParse(await req.json().catch(() => null));
  if (!p.success) return auth.finish(problemResponse(400, "ERR_VALIDATION"));
  const r = await rawRequest<ReauthResponse>({ method: "POST", path: "/v1/auth/reauth", token: auth.session.at, body: { password: p.data.password, ...(p.data.code ? { code: p.data.code } : {}) }, headers: clientIpHeaders((n) => req.headers.get(n), crypto.randomUUID()) });
  if (!r.ok) return auth.finish(NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } }));
  const res = auth.finish(NextResponse.json({ pii_until: r.data.pii_until }, { headers: { "Cache-Control": "no-store" } }));
  setSessionCookie(res, { ...auth.session, at: r.data.access_token, atExp: Date.parse(r.data.access_expires_at) }, ADMIN_ABSOLUTE_DAYS);
  return res;
}

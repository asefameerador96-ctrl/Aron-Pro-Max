import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { apiClient, outcome } from "@/lib/api/client";
import { problemResponse, sameOrigin } from "@/lib/api/guard";
import { PWC_COOKIE } from "@/lib/auth/cookies";
import { completeLogin } from "@/lib/auth/complete-login";
import { MAX_LENGTH, checkPasswords, isValid } from "@/lib/auth/password-policy";
import { readPasswordChange } from "@/lib/auth/service";

const Body = z.object({ current_password: z.string().min(1).max(MAX_LENGTH), new_password: z.string().min(1).max(MAX_LENGTH) }).strict();

/** POST /api/bff/login/change-password: the forced change after a web login answered `password_change_required`. The 10-minute token
 *  stays in a sealed cookie and goes to the API as Bearer; the 200 answer continues the login (session, or the TOTP step for MFA roles). */
export async function POST(req: NextRequest): Promise<NextResponse> {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  const p = Body.safeParse(await req.json().catch(() => null));
  if (!p.success) return problemResponse(400, "ERR_VALIDATION");
  const pc = readPasswordChange(req.cookies.get(PWC_COOKIE)?.value);
  if (!pc) return problemResponse(401, "ERR_UNAUTHENTICATED");
  if (!isValid(checkPasswords(p.data.current_password, p.data.new_password, p.data.new_password))) return problemResponse(400, "ERR_AUTH_PASSWORD_POLICY");

  const r = await outcome(apiClient(pc.token).POST("/v1/auth/change-password", { body: p.data }));
  if (!r.ok) {
    const res = NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });
    if (r.status === 401) res.cookies.delete(PWC_COOKIE); // token expired or used: sign in again
    return res;
  }
  // 204 means the server treated the token as an access token; there is no login to continue, so ask for a fresh sign-in.
  if (r.status === 204 || !r.data) {
    const res = NextResponse.json({ status: "signin_again" });
    res.cookies.delete(PWC_COOKIE);
    return res;
  }
  const res = completeLogin(r.response, r.data, pc.remember);
  res.cookies.delete(PWC_COOKIE);
  return res;
}

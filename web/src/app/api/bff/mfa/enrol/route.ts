import { NextResponse, type NextRequest } from "next/server";
import { apiClient, outcome } from "@/lib/api/client";
import { problemResponse, sameOrigin } from "@/lib/api/guard";
import { MFA_COOKIE } from "@/lib/auth/cookies";
import { readMfaToken } from "@/lib/auth/service";

/**
 * First sign-in of an administrator with no authenticator yet: POST /v1/auth/mfa/enrol with the login's mfa_token (kept in the
 * sealed cookie, never in the browser). The answer carries the TOTP secret URI and the ten recovery codes, shown once; the first
 * good code at the next step confirms the enrolment. A second call is a 409 (an administrator resets it).
 */
export async function POST(req: NextRequest) {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  const mfaToken = readMfaToken(req.cookies.get(MFA_COOKIE)?.value);
  if (!mfaToken) return problemResponse(401, "ERR_AUTH_MFA_INVALID");
  const r = await outcome(apiClient(mfaToken).POST("/v1/auth/mfa/enrol"));
  if (!r.ok) return NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });
  return NextResponse.json({ otpauth_uri: r.data.otpauth_uri, recovery_codes: r.data.recovery_codes }, { headers: { "Cache-Control": "no-store" } });
}

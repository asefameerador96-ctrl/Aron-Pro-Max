import { NextResponse, type NextRequest } from "next/server";
import { apiClient, outcome } from "@/lib/api/client";
import { problemResponse, sameOrigin } from "@/lib/api/guard";
import { MFA_COOKIE } from "@/lib/auth/cookies";
import { readMfaToken } from "@/lib/auth/service";

/**
 * First sign-in of an administrator with no authenticator yet: POST /v1/auth/mfa/enrol with the login's mfa_token (kept in the
 * sealed cookie, never in the browser). The answer carries the TOTP secret URI and the ten recovery codes, shown once; the first
 * good code at the next step confirms the enrolment. Until then a new call replaces the unconfirmed secret; once confirmed it is a
 * 409 (an administrator resets it).
 */
export async function POST(req: NextRequest) {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  const mfaToken = readMfaToken(req.cookies.get(MFA_COOKIE)?.value);
  if (!mfaToken) return problemResponse(401, "ERR_TOKEN_EXPIRED"); // the 5-minute second-step window ran out: sign in again, not "wrong code"
  const r = await outcome(apiClient(mfaToken).POST("/v1/auth/mfa/enrol"));
  if (!r.ok) return NextResponse.json(r.problem, { status: r.status >= 400 && r.status <= 599 ? r.status : 503, headers: { "Content-Type": "application/problem+json" } });
  if (!r.data?.otpauth_uri || !Array.isArray(r.data.recovery_codes)) return problemResponse(503, "ERR_SERVICE_UNAVAILABLE", { retryable: true }); // a 204 or an empty body is not an enrolment
  return NextResponse.json({ otpauth_uri: r.data.otpauth_uri, recovery_codes: r.data.recovery_codes }, { headers: { "Cache-Control": "no-store" } });
}

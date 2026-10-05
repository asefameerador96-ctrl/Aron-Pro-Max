import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { apiClient, outcome } from "@/lib/api/client";
import { problemResponse, sameOrigin } from "@/lib/api/guard";
import { MFA_COOKIE } from "@/lib/auth/cookies";
import { WEB_ROLES, hasRole } from "@/lib/auth/roles";
import { extractRefreshToken, readMfaToken, sessionFromLogin, setAuthCookies } from "@/lib/auth/service";
import { LOCALE_COOKIE } from "@/lib/i18n/types";

// Matches MfaVerifyRequest.code in the contract: 6 digits (TOTP) or XXXX-XXXX (recovery code).
const Body = z.object({ code: z.string().regex(/^([0-9]{6}|[A-Z0-9]{4}-[A-Z0-9]{4})$/) }).strict();

export async function POST(req: NextRequest) {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  const parsed = Body.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return problemResponse(400, "ERR_VALIDATION");
  const mfaToken = readMfaToken(req.cookies.get(MFA_COOKIE)?.value);
  if (!mfaToken) return problemResponse(401, "ERR_AUTH_MFA_INVALID");

  const r = await outcome(apiClient().POST("/v1/auth/mfa/verify", { body: { mfa_token: mfaToken, code: parsed.data.code } }));
  if (!r.ok) return NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });
  const session = sessionFromLogin(r.data);
  if (r.data.status !== "ok" || !session) return problemResponse(401, "ERR_AUTH_MFA_INVALID");
  if (!hasRole(session.user.role, WEB_ROLES)) return NextResponse.json({ status: "no_web_access" });

  const res = NextResponse.json({ status: "ok", user: session.user, scope: session.scope });
  setAuthCookies(res, { session, refreshToken: extractRefreshToken(r.response, r.data.refresh_token), refreshExpiresAt: r.data.refresh_expires_at ?? null });
  res.cookies.set(LOCALE_COOKIE, session.user.locale, { path: "/", sameSite: "lax", maxAge: 365 * 86_400 });
  return res;
}

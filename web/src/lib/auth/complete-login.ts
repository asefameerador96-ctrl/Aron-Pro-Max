// Turns a LoginResponse (from the password step or from the password change that follows `password_change_required`) into the BFF reply
// and cookies. One place, so the two entry points cannot drift: MFA roles never get a session from the password step alone (docs/24 s6.5).
import { NextResponse } from "next/server";
import type { LoginResponse } from "@/contract/types";
import { problemResponse } from "@/lib/api/guard";
import { LOCALE_COOKIE } from "@/lib/i18n/types";
import { MFA_ROLES, WEB_ROLES, hasRole } from "./roles";
import { extractRefreshToken, sessionFromLogin, setAuthCookies, setMfaCookie, setPasswordChangeCookie } from "./service";

export function completeLogin(response: Response, body: LoginResponse, remember: boolean): NextResponse {
  if (body.status === "mfa_required" && body.mfa_token) {
    const res = NextResponse.json({ status: "mfa_required" });
    setMfaCookie(res, body.mfa_token);
    return res;
  }
  if (body.status === "password_change_required") {
    // Web login carries a 10-minute token for the change; without one the account needs a support reset.
    if (!body.password_change_token) return NextResponse.json({ status: "password_reset_by_support" });
    const res = NextResponse.json({ status: "password_change_required" });
    setPasswordChangeCookie(res, body.password_change_token, remember);
    return res;
  }
  const session = sessionFromLogin(body, remember);
  if (body.status !== "ok" || !session) return problemResponse(401, "ERR_AUTH_INVALID_CREDENTIALS");
  if (hasRole(session.user.role, MFA_ROLES)) return problemResponse(401, "ERR_AUTH_MFA_INVALID");
  if (!hasRole(session.user.role, WEB_ROLES)) return NextResponse.json({ status: "no_web_access" });
  const res = NextResponse.json({ status: "ok", user: session.user, scope: session.scope });
  setAuthCookies(res, { session, refreshToken: extractRefreshToken(response, body.refresh_token), refreshExpiresAt: body.refresh_expires_at ?? null });
  res.cookies.set(LOCALE_COOKIE, session.user.locale, { path: "/", sameSite: "lax", maxAge: 365 * 86_400 });
  return res;
}

import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { apiClient, outcome } from "@/lib/api/client";
import { problemResponse, sameOrigin } from "@/lib/api/guard";
import { MFA_ROLES, WEB_ROLES, hasRole } from "@/lib/auth/roles";
import { extractRefreshToken, sessionFromLogin, setAuthCookies, setMfaCookie } from "@/lib/auth/service";
import { LOCALE_COOKIE } from "@/lib/i18n/types";

const Body = z.object({ username: z.string().min(1).max(40), password: z.string().min(1).max(128), remember: z.boolean().optional() }).strict();

export async function POST(req: NextRequest) {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  const parsed = Body.safeParse(await req.json().catch(() => null));
  if (!parsed.success) return problemResponse(400, "ERR_VALIDATION");

  const r = await outcome(apiClient().POST("/v1/auth/login", { body: { username: parsed.data.username.trim().toLowerCase(), password: parsed.data.password, client: "web", device_uuid: null } }));
  if (!r.ok) return NextResponse.json(r.problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });
  const body = r.data;

  if (body.status === "mfa_required" && body.mfa_token) {
    const res = NextResponse.json({ status: "mfa_required" });
    setMfaCookie(res, body.mfa_token);
    return res;
  }
  if (body.status === "password_change_required") return NextResponse.json({ status: "password_change_required" });
  const session = sessionFromLogin(body, parsed.data.remember === true);
  if (body.status !== "ok" || !session) return problemResponse(401, "ERR_AUTH_INVALID_CREDENTIALS");

  // MFA roles must never get a session from the password step alone (docs/24 s6.5): refuse if the server skipped it.
  if (hasRole(session.user.role, MFA_ROLES)) return problemResponse(401, "ERR_AUTH_MFA_INVALID");
  if (!hasRole(session.user.role, WEB_ROLES)) return NextResponse.json({ status: "no_web_access" });

  const res = NextResponse.json({ status: "ok", user: session.user, scope: session.scope });
  setAuthCookies(res, { session, refreshToken: extractRefreshToken(r.response, body.refresh_token), refreshExpiresAt: body.refresh_expires_at ?? null });
  res.cookies.set(LOCALE_COOKIE, session.user.locale, { path: "/", sameSite: "lax", maxAge: 365 * 86_400 });
  return res;
}

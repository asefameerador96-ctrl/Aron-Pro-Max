// BFF auth service: talks to /v1/auth/* with the contract client and turns results into sealed cookies.
// The browser only ever receives HttpOnly cookies and non-secret JSON (docs/24 s6.5).
import { cookies } from "next/headers";
import type { NextResponse } from "next/server";
import type { LoginResponse, Problem, ScopeSummary, UserSummary } from "@/contract/types";
import { apiClient, outcome, transportProblem, type ApiOutcome } from "@/lib/api/client";
import { DEFAULT_LOCALE, LOCALE_COOKIE, isLocale, type Locale } from "@/lib/i18n/types";
import { MFA_ROLES, hasRole } from "./roles";
import { MFA_COOKIE, MFA_PURPOSE, MFA_TTL_S, REFRESH_SKEW_MS, RT_COOKIE, SESSION_COOKIE, SESSION_PURPOSE, cookieOptions } from "./cookies";
import { open, seal } from "./seal";
import { readSession, type SessionData } from "./session";

/** Web access tokens live 15 minutes (docs/24 s8.1); used when the response lacks an expiry. */
const FALLBACK_ACCESS_MS = 15 * 60_000;

export async function getSession(): Promise<SessionData | null> {
  const jar = await cookies();
  return readSession(jar.get(SESSION_COOKIE)?.value);
}

export async function getLocale(): Promise<Locale> {
  const jar = await cookies();
  const v = jar.get(LOCALE_COOKIE)?.value;
  return isLocale(v) ? v : DEFAULT_LOCALE;
}

export function needsRefresh(s: SessionData, now = Date.now()): boolean {
  return s.atExp - now < REFRESH_SKEW_MS;
}

/** The refresh token: the API sets it as the `aron_rt` Set-Cookie on the web; a body value is accepted as a fallback. */
export function extractRefreshToken(response: Response, bodyToken: string | null | undefined): string | null {
  for (const line of response.headers.getSetCookie?.() ?? []) {
    const m = /^aron_rt=([^;]+)/.exec(line);
    if (m?.[1]) return m[1];
  }
  return bodyToken ?? null;
}

export interface AuthCookies {
  session: SessionData;
  refreshToken: string | null;
  refreshExpiresAt: string | null;
}

export function sessionFromLogin(body: LoginResponse, rem = false): SessionData | null {
  if (!body.access_token) return null;
  return {
    at: body.access_token,
    atExp: body.access_expires_at ? Date.parse(body.access_expires_at) : Date.now() + FALLBACK_ACCESS_MS,
    user: body.user,
    scope: body.scope ?? null,
    ...(rem ? { rem: true } : {}),
  };
}

/** Remember me lasts at most 30 days (docs/21: cfg.auth.web_remember_me_days 0..30). */
export const REMEMBER_MAX_DAYS = 30;

function ttlSeconds(refreshExpiresAt: string | null): number {
  const t = refreshExpiresAt ? Date.parse(refreshExpiresAt) : NaN;
  return Number.isFinite(t) ? Math.max(60, Math.min(REMEMBER_MAX_DAYS * 86_400, (t - Date.now()) / 1000)) : 8 * 3600;
}

/** Write the session cookies on a response. */
export function setAuthCookies(res: NextResponse, a: AuthCookies): void {
  const ttl = ttlSeconds(a.refreshExpiresAt);
  const persistent = a.session.rem === true;
  res.cookies.set(SESSION_COOKIE, seal(a.session, SESSION_PURPOSE, ttl), cookieOptions(ttl, persistent));
  if (a.refreshToken) res.cookies.set(RT_COOKIE, a.refreshToken, cookieOptions(ttl, persistent));
  res.cookies.delete(MFA_COOKIE);
}

export function setMfaCookie(res: NextResponse, mfaToken: string): void {
  res.cookies.set(MFA_COOKIE, seal({ mfaToken }, MFA_PURPOSE, MFA_TTL_S), cookieOptions(MFA_TTL_S));
}

export function readMfaToken(value: string | undefined): string | null {
  return open<{ mfaToken: string }>(value, MFA_PURPOSE)?.mfaToken ?? null;
}

export function clearAuthCookies(res: NextResponse): void {
  for (const n of [SESSION_COOKIE, RT_COOKIE, MFA_COOKIE]) res.cookies.delete(n);
}

/** Rotate the refresh cookie and mint a new access token, then re-read the principal (role and scope may have changed). */
export async function refreshSession(rt: string, rem = false, prevRole?: string): Promise<ApiOutcome<AuthCookies>> {
  const client = apiClient(undefined, { Cookie: `aron_rt=${rt}` });
  const r = await outcome(client.POST("/v1/auth/refresh", { body: { grant: "full", refresh_token: null } }));
  if (!r.ok) return r;
  const pair = r.data;
  const newRt = extractRefreshToken(r.response, pair.refresh_token) ?? rt;
  const me = await outcome(apiClient(pair.access_token).GET("/v1/me"));
  if (!me.ok) return me;
  // A role that became an MFA role since sign-in must sign in again with the second step; admin sessions are never persistent.
  const mfa = hasRole(me.data.user.role, MFA_ROLES);
  if (mfa && prevRole !== undefined && !hasRole(prevRole as never, MFA_ROLES)) return { ok: false, status: 401, problem: { ...transportProblem(401), code: "ERR_AUTH_MFA_INVALID" } };
  if (mfa) rem = false;
  return {
    ok: true,
    status: 200,
    response: r.response,
    data: {
      session: { at: pair.access_token, atExp: Date.parse(pair.access_expires_at), user: me.data.user, scope: me.data.scope, ...(rem ? { rem: true } : {}) },
      refreshToken: newRt,
      refreshExpiresAt: pair.refresh_expires_at,
    },
  };
}

export type { LoginResponse, Problem, ScopeSummary, UserSummary };

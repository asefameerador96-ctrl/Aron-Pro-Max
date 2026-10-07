// Request guards for BFF route handlers.
import { NextResponse, type NextRequest } from "next/server";
import type { Problem, ProblemCode } from "@/contract/types";
import { readSession, type SessionData } from "@/lib/auth/session";
import { SESSION_COOKIE, RT_COOKIE } from "@/lib/auth/cookies";
import { accessFor } from "@/lib/auth/roles";
import { needsRefresh, refreshSession, setAuthCookies, clearAuthCookies } from "@/lib/auth/service";

export function problemResponse(status: number, code: ProblemCode, extra?: Partial<Problem>): NextResponse {
  const body: Problem = {
    type: `urn:aron:problem:${code.toLowerCase()}`,
    title: code,
    status,
    code,
    request_id: crypto.randomUUID(),
    ...extra,
  };
  return NextResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });
}

/** CSRF defence in depth on top of SameSite=Strict: a browser POST from another site is refused. */
export function sameOrigin(req: NextRequest): boolean {
  const site = req.headers.get("sec-fetch-site");
  if (site && site !== "same-origin" && site !== "none") return false; // cross-site and same-site (a sibling subdomain) are refused
  const origin = req.headers.get("origin");
  if (!origin) return true; // non-browser caller; the cookie is SameSite=Strict, so a browser cannot attach it cross-site anyway
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

export interface Authed {
  session: SessionData;
  /** Apply to the response when the access token was refreshed during this call. */
  finish: (res: NextResponse) => NextResponse;
}

/** Session for a BFF route handler: role-gated like the pages, refreshes an expiring token, never exposes it. */
export async function authenticate(req: NextRequest, pathname: string): Promise<Authed | NextResponse> {
  if (!sameOrigin(req)) return problemResponse(403, "ERR_FORBIDDEN");
  let session = readSession(req.cookies.get(SESSION_COOKIE)?.value);
  if (!session) return problemResponse(401, "ERR_UNAUTHENTICATED");
  let refreshed: Awaited<ReturnType<typeof refreshSession>> | null = null;
  const rt = req.cookies.get(RT_COOKIE)?.value;
  if (needsRefresh(session)) {
    if (!rt) return problemResponse(401, "ERR_TOKEN_EXPIRED");
    refreshed = await refreshSession(rt, session.rem === true, session.user.role);
    if (!refreshed.ok && refreshed.status !== 401 && refreshed.status !== 403) {
      // API outage or edge error: keep the cookies so the user is not logged out by a blip; the caller may retry.
      return problemResponse(503, "ERR_SERVICE_UNAVAILABLE", { retryable: true });
    }
    if (!refreshed.ok) {
      const res = problemResponse(401, "ERR_TOKEN_EXPIRED");
      clearAuthCookies(res);
      return res;
    }
    session = refreshed.data.session;
  }
  const access = accessFor(session.user.role, pathname);
  if (access !== "ok") return problemResponse(403, "ERR_FORBIDDEN");
  const fresh = refreshed && refreshed.ok ? refreshed.data : null;
  return { session, finish: (res) => { if (fresh) setAuthCookies(res, fresh); return res; } };
}

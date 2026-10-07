// Optimistic route gate (Next.js 16 "proxy"). Pages and route handlers re-check the same policy (accessFor), so this is
// defence in depth and the place where the status codes come from: no session -> /login (pages) or 401 (BFF);
// wrong role -> a real HTTP 403 with a small localised page (a rewrite cannot set the status, and a redirect would hide the refusal).
import { NextResponse, type NextRequest } from "next/server";
import type { Problem } from "@/contract/types";
import { publicUrl } from "@/lib/api/origin";
import { SESSION_COOKIE, SESSION_PURPOSE, cookieOptions } from "@/lib/auth/cookies";
import { sessionState, slid } from "@/lib/auth/limits";
import { seal } from "@/lib/auth/seal";
import { clearAuthCookies } from "@/lib/auth/service";
import { csp, newNonce, securityHeaders } from "@/lib/security-headers";
import { forbiddenPage } from "@/lib/forbidden-page";
import { LOCALE_COOKIE, isLocale, DEFAULT_LOCALE } from "@/lib/i18n/types";
import { entityBySlug } from "@/app/admin/_entities/registry";
import { accessFor } from "@/lib/auth/roles";
import { readSession } from "@/lib/auth/session";

function problem(status: 401 | 403): NextResponse {
  const code = status === 401 ? "ERR_UNAUTHENTICATED" : "ERR_FORBIDDEN";
  const body: Problem = { type: `urn:aron:problem:${code.toLowerCase()}`, title: code, status, code, request_id: crypto.randomUUID() };
  return NextResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });
}

/** Every response of the proxy carries the security headers (AUD-SEC-04); the nonce reaches Next through the request's CSP header. */
export function proxy(req: NextRequest) {
  const nonce = newNonce();
  const raw = readSession(req.cookies.get(SESSION_COOKIE)?.value);
  const state = raw ? sessionState(raw) : "ok";
  const session = state === "ok" ? raw : null; // an idle or too-old session is no session
  const res = route(req, session, nonce);
  // A handler may set a stricter policy of its own (the sandboxed print view): never overwrite one.
  for (const [k, v] of Object.entries(securityHeaders(nonce))) {
    // Middleware headers win over a route handler's, so a pass-through API call keeps the handler's own CSP (the sandboxed print view).
    if (k === "Content-Security-Policy" && req.nextUrl.pathname.startsWith("/api/") && res.headers.has("x-middleware-next")) continue;
    if (!res.headers.has(k)) res.headers.set(k, v);
  }
  if (raw && state !== "ok") clearAuthCookies(res);
  else if (raw) {
    const next = slid(raw);
    if (next && !res.headers.get("location")?.includes("/login")) res.cookies.set(SESSION_COOKIE, seal(next, SESSION_PURPOSE, 30 * 86_400), cookieOptions(30 * 86_400, next.rem === true));
  }
  return res;
}

function route(req: NextRequest, session: ReturnType<typeof readSession>, nonce: string) {
  const { pathname, search } = req.nextUrl;
  const access = accessFor(session?.user.role, pathname);
  const isApi = pathname.startsWith("/api/");

  // Write pages of an entity (create, edit) need its write roles: a read-only portal role gets a real 403 there too.
  // Match the decoded path: /admin/clusters/%31 is /admin/clusters/1.
  let decoded = pathname;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    /* an undecodable path keeps the raw form */
  }
  const writePage = /^\/admin\/([^/]+)\/(new|[0-9]+|[0-9a-f]{8}-[0-9a-f-]{27})(?:\/([^/]+))?\/?$/.exec(decoded);
  const entity = writePage?.[1] ? entityBySlug(writePage[1]) : undefined;
  const actionRoles = writePage?.[3] ? entity?.actions?.find((a) => a.key === writePage[3])?.writeRoles : undefined;
  const noCreate = writePage?.[2] === "new" && entity?.canCreate === false;
  if (access === "ok" && session && entity && (noCreate || !(actionRoles ?? entity.writeRoles).includes(session.user.role))) {
    const l = req.cookies.get(LOCALE_COOKIE)?.value;
    return forbiddenPage(isLocale(l) ? l : DEFAULT_LOCALE);
  }

  if (access === "ok") {
    if (pathname === "/login" && session) return NextResponse.redirect(publicUrl(req, "/"));
    // Server components need the requested path (layouts cannot read it) to send an expiring session through the refresh route.
    const headers = new Headers(req.headers);
    headers.set("x-aron-path", pathname + search);
    headers.set("content-security-policy", csp(nonce));
    headers.set("x-nonce", nonce);
    return NextResponse.next({ request: { headers } });
  }
  if (access === "unauthenticated") {
    if (isApi) return problem(401);
    return NextResponse.redirect(publicUrl(req, `/login?next=${encodeURIComponent(pathname + search)}`));
  }
  if (isApi) return problem(403);
  const l = req.cookies.get(LOCALE_COOKIE)?.value;
  return forbiddenPage(isLocale(l) ? l : DEFAULT_LOCALE);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|robots.txt).*)"],
};

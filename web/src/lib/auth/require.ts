// Guards for server components and layouts. They repeat the proxy's policy so a page can never render for the wrong role
// even if the proxy matcher is changed (defence in depth).
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import type { RoleList } from "./roles";
import { accessFor } from "./roles";
import { getSession, needsRefresh } from "./service";
import type { SessionData } from "./session";

export async function currentPath(): Promise<string> {
  const h = await headers();
  return h.get("x-aron-path") ?? "/";
}

/** A live session or a redirect to /login (no session) or to the refresh route (token about to expire). */
export async function requireSession(): Promise<SessionData> {
  const path = await currentPath();
  const s = await getSession();
  if (!s) redirect(`/login?next=${encodeURIComponent(path)}`);
  if (needsRefresh(s)) redirect(`/api/bff/session/refresh?next=${encodeURIComponent(path)}`);
  return s;
}

export type GuardResult = { allowed: true; session: SessionData } | { allowed: false; session: SessionData };

/** Session plus the route-group decision; the caller renders <Forbidden/> when `allowed` is false. */
export async function guard(): Promise<GuardResult> {
  const session = await requireSession();
  const path = (await currentPath()).split("?")[0] ?? "/";
  return { allowed: accessFor(session.user.role, path) === "ok", session };
}

export function roleAllowed(session: SessionData, roles: RoleList): boolean {
  return roles.includes(session.user.role);
}

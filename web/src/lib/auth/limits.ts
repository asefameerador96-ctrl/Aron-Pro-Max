// Web session limits (docs/21 s2: web idle 30 min, absolute 7 days for admin roles; AUD-SEC-04). The access token still lives 15 min
// and is refreshed silently; these limits end the *session* when the person has stopped using it or it is simply too old.
import { MFA_ROLES, hasRole } from "./roles";
import type { SessionData } from "./session";

export const DEFAULT_IDLE_MIN = 30;
export const ADMIN_ABSOLUTE_DAYS = 7;
export const OTHER_ABSOLUTE_DAYS = 30;
/** Slide the idle clock at most this often, so a page does not rewrite the cookie on every request. */
export const SLIDE_AFTER_MS = 60_000;

export function idleMs(env: Record<string, string | undefined> = process.env): number {
  const n = Number(env.ARON_WEB_IDLE_MIN);
  return (Number.isFinite(n) && n >= 1 && n <= 480 ? n : DEFAULT_IDLE_MIN) * 60_000;
}

export type SessionState = "ok" | "idle" | "absolute";

/** A session without the stamps (minted before this limit existed) is not enforced; every mint path now sets them. */
export function sessionState(s: SessionData, now = Date.now(), idle = idleMs()): SessionState {
  const days = hasRole(s.user.role, MFA_ROLES) ? ADMIN_ABSOLUTE_DAYS : OTHER_ABSOLUTE_DAYS;
  if (typeof s.sat === "number" && now - s.sat > days * 86_400_000) return "absolute";
  if (typeof s.act === "number" && now - s.act > idle) return "idle";
  return "ok";
}

/** The session with its idle clock moved to now, or null when it is recent enough already. */
export function slid(s: SessionData, now = Date.now()): SessionData | null {
  return typeof s.act === "number" && now - s.act < SLIDE_AFTER_MS ? null : { ...s, act: now };
}

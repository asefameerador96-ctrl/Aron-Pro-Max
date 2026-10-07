import type { Role, ScopeSummary, UserSummary } from "@/contract/types";
import { open } from "./seal";
import { SESSION_COOKIE, SESSION_PURPOSE } from "./cookies";

/** What the sealed `aron_sess` cookie carries. Never sent to the browser in readable form. */
export interface SessionData {
  at: string; // access token (server side only)
  atExp: number; // epoch ms
  user: UserSummary;
  scope: ScopeSummary | null;
  /** "Remember me" was ticked at sign-in: the cookies outlive the browser session. Off by default, never for admin roles. */
  rem?: boolean;
  /** Signed in at, and last activity (epoch ms): the absolute and idle limits of lib/auth/limits.ts. */
  sat?: number;
  act?: number;
}

export function readSession(cookieValue: string | undefined): SessionData | null {
  return open<SessionData>(cookieValue, SESSION_PURPOSE);
}

export type { Role };
export { SESSION_COOKIE };

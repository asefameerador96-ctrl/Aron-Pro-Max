// Cookie names and attributes of the web BFF (docs/24 s3.2, s6.5).
//   aron_rt    refresh token, HttpOnly + Secure + SameSite=Strict, opaque to the browser
//   aron_sess  sealed session (access token, expiry, user, scope), HttpOnly
//   aron_mfa   sealed mfa_token for the second login step (5 min), HttpOnly
//   aron_locale  the chosen language (not a secret; readable by scripts is not needed, but harmless)
export const RT_COOKIE = "aron_rt";
export const SESSION_COOKIE = "aron_sess";
export const MFA_COOKIE = "aron_mfa";
/** Sealed `password_change_token` (10 min) between the login step that demanded a change and the change itself; never readable by the browser. */
export const PWC_COOKIE = "aron_pwc";

export const SESSION_PURPOSE = "session";
export const MFA_PURPOSE = "mfa";
export const PWC_PURPOSE = "pwchange";

/** `ARON_COOKIE_INSECURE=1` drops `Secure` for plain-http localhost (dev and the Playwright run); never set in production. */
export function secureCookies(): boolean {
  return process.env.ARON_COOKIE_INSECURE !== "1";
}

export interface CookieOptions {
  httpOnly: boolean;
  secure: boolean;
  sameSite: "strict";
  path: string;
  /** Absent for a session cookie: it ends with the browser session (Remember me off, the default). */
  maxAge?: number;
}

export function cookieOptions(maxAgeSeconds: number, persistent = true): CookieOptions {
  const base = { httpOnly: true, secure: secureCookies(), sameSite: "strict", path: "/" } as const;
  return persistent ? { ...base, maxAge: Math.max(0, Math.floor(maxAgeSeconds)) } : base;
}

/** Access tokens of the web live 15 minutes (docs/24 s8.1); refresh a little early. */
export const REFRESH_SKEW_MS = 60_000;
export const MFA_TTL_S = 5 * 60;
export const PWC_TTL_S = 10 * 60;

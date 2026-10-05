// Cookie names and attributes of the web BFF (docs/24 s3.2, s6.5).
//   aron_rt    refresh token, HttpOnly + Secure + SameSite=Strict, opaque to the browser
//   aron_sess  sealed session (access token, expiry, user, scope), HttpOnly
//   aron_mfa   sealed mfa_token for the second login step (5 min), HttpOnly
//   aron_locale  the chosen language (not a secret; readable by scripts is not needed, but harmless)
export const RT_COOKIE = "aron_rt";
export const SESSION_COOKIE = "aron_sess";
export const MFA_COOKIE = "aron_mfa";

export const SESSION_PURPOSE = "session";
export const MFA_PURPOSE = "mfa";

/** `ARON_COOKIE_INSECURE=1` drops `Secure` for plain-http localhost (dev and the Playwright run); never set in production. */
export function secureCookies(): boolean {
  return process.env.ARON_COOKIE_INSECURE !== "1";
}

export interface CookieOptions {
  httpOnly: boolean;
  secure: boolean;
  sameSite: "strict";
  path: string;
  maxAge: number;
}

export function cookieOptions(maxAgeSeconds: number): CookieOptions {
  return { httpOnly: true, secure: secureCookies(), sameSite: "strict", path: "/", maxAge: Math.max(0, Math.floor(maxAgeSeconds)) };
}

/** Access tokens of the web live 15 minutes (docs/24 s8.1); refresh a little early. */
export const REFRESH_SKEW_MS = 60_000;
export const MFA_TTL_S = 5 * 60;

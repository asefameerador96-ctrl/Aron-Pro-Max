import type { NextRequest } from "next/server";

/** The browser-facing origin, honouring the Azure ingress headers (the Node server only sees the internal host). */
export function publicUrl(req: NextRequest, path: string): URL {
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  const proto = req.headers.get("x-forwarded-proto") ?? req.nextUrl.protocol.replace(":", "");
  return new URL(path, host ? `${proto}://${host}` : req.nextUrl.origin);
}

/** Only same-site relative paths are accepted as a post-login or post-switch destination (no open redirect). */
export function safeNext(raw: string | null | undefined, fallback = "/"): string {
  if (!raw || !raw.startsWith("/")) return fallback;
  // URL parsing drops TAB/LF/CR and treats "\\" as "/", so "/\t/evil.com" would become "//evil.com": refuse control characters
  // and backslashes outright, then confirm the parsed result stays on our own origin.
  if (/[\u0000-\u001f\u007f\\]/.test(raw)) return fallback;
  try {
    const u = new URL(raw, "http://self.invalid");
    if (u.origin !== "http://self.invalid") return fallback;
    return u.pathname + u.search + u.hash;
  } catch {
    return fallback;
  }
}

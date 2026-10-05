import type { NextRequest } from "next/server";

/** The browser-facing origin, honouring the Azure ingress headers (the Node server only sees the internal host). */
export function publicUrl(req: NextRequest, path: string): URL {
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  const proto = req.headers.get("x-forwarded-proto") ?? req.nextUrl.protocol.replace(":", "");
  return new URL(path, host ? `${proto}://${host}` : req.nextUrl.origin);
}

/** Only same-site relative paths are accepted as a post-login or post-switch destination (no open redirect). */
export function safeNext(raw: string | null | undefined, fallback = "/"): string {
  return raw && raw.startsWith("/") && !raw.startsWith("//") && !raw.startsWith("/\\") ? raw : fallback;
}

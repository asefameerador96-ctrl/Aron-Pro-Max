// Security headers for every response (docs/21 s2.8, AUD-SEC-04): CSP with a per-request nonce, HSTS, no-store, nosniff, framing off.
// One list, applied by the proxy (all routes) and by next.config (static assets), and asserted by tests/security-headers.test.ts.
/** ARON_BLOB_ORIGIN: one origin (https, or http for the local mock), never a path or a wildcard. */
export function blobOrigin(raw: string | undefined = process.env.ARON_BLOB_ORIGIN): string | null {
  if (!raw) return null;
  try {
    const u = new URL(raw);
    const ok = u.protocol === "https:" || (u.protocol === "http:" && (u.hostname === "127.0.0.1" || u.hostname === "localhost"));
    return ok && u.origin === raw.replace(/\/$/, "") && /^[a-z0-9.-]+(:[0-9]{1,5})?$/.test(u.host) ? u.origin : null;
  } catch {
    return null;
  }
}

export function csp(nonce: string): string {
  const maps = "https://maps.googleapis.com https://maps.gstatic.com";
  const blob = blobOrigin(); // the admin portal PUTs tutorial files straight to the write-only SAS URL of the storage account
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic' ${maps}`,
    "style-src 'self' 'unsafe-inline'", // Tailwind and the Maps widget set inline styles; scripts stay nonce-only
    `img-src 'self' data: blob: ${maps} https://*.googleapis.com https://*.ggpht.com`,
    `connect-src 'self' ${maps} https://*.googleapis.com${blob ? ` ${blob}` : ""}`,
    "font-src 'self' data:",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "object-src 'none'",
  ].join("; ");
}

export function securityHeaders(nonce: string): Record<string, string> {
  return {
    "Content-Security-Policy": csp(nonce),
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Permissions-Policy": "geolocation=(), camera=(), microphone=()",
    "Cache-Control": "no-store",
  };
}

export const REQUIRED_HEADERS = ["Content-Security-Policy", "Strict-Transport-Security", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Cache-Control"] as const;

export function newNonce(): string {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
}

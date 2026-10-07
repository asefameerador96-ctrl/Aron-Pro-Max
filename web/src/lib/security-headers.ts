// Security headers for every response (docs/21 s2.8, AUD-SEC-04): CSP with a per-request nonce, HSTS, no-store, nosniff, framing off.
// One list, applied by the proxy (all routes) and by next.config (static assets), and asserted by tests/security-headers.test.ts.
export function csp(nonce: string): string {
  const maps = "https://maps.googleapis.com https://maps.gstatic.com";
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic' ${maps}`,
    "style-src 'self' 'unsafe-inline'", // Tailwind and the Maps widget set inline styles; scripts stay nonce-only
    `img-src 'self' data: blob: ${maps} https://*.googleapis.com https://*.ggpht.com`,
    `connect-src 'self' ${maps} https://*.googleapis.com`,
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

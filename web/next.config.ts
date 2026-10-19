import type { NextConfig } from "next";

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
  { key: "Permissions-Policy", value: "geolocation=(), camera=(), microphone=()" },
  { key: "Cache-Control", value: "no-store" },
];

// The Aron logo files and the browser icons are public (src/proxy.ts keeps them out of the sign-in gate), change rarely and are
// NOT named by content hash, so they get a day in the browser and a week of stale-while-revalidate, never "immutable".
// Everything else keeps no-store. Exactly the paths the proxy matcher lets through, nothing wider (a missing /brand/x.png must
// not get a cacheable 404); tests/brand-assets.test.ts ties the two lists together.
const PUBLIC_STATIC_CACHE = "public, max-age=86400, stale-while-revalidate=604800";
const PUBLIC_STATIC_SOURCES = ["/brand/aron-mark-64.png", "/brand/aron-mark-128.png", "/brand/aron-mark-256.png", "/icon.png", "/apple-icon.png", "/favicon.ico"] as const;

const nextConfig: NextConfig = {
  // Standalone Node server for Azure Container Apps (docs/24 s6.5).
  output: "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
  async headers() {
    // When two rules set the same header for one path the LATER rule wins (Next docs, headers): the catch-all stays first.
    return [
      { source: "/:path*", headers: securityHeaders },
      ...PUBLIC_STATIC_SOURCES.map((source) => ({ source, headers: [{ key: "Cache-Control", value: PUBLIC_STATIC_CACHE }] })),
    ];
  },
};

export default nextConfig;

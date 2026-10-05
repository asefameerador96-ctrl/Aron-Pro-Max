// A self-contained 403 response for the proxy (no React, no session). Same texts as <Forbidden/>, from the catalogue.
import { NextResponse } from "next/server";
import { t, type Locale } from "@/lib/i18n";

export function forbiddenPage(locale: Locale): NextResponse {
  const title = t(locale, "admin.forbidden.title");
  const html = `<!doctype html><html lang="${locale}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>${title}</title></head><body style="font-family:'Noto Sans Bengali',system-ui,sans-serif;background:#f8fafc;margin:0;display:grid;place-items:center;min-height:100vh"><main data-testid="forbidden" role="alert" style="background:#fff;border:1px solid #fecaca;border-radius:8px;padding:24px;max-width:28rem;text-align:center"><h1 style="color:#991b1b;font-size:1.25rem;margin:0 0 .5rem">${title}</h1><p style="color:#334155;margin:0 0 1rem">${t(locale, "admin.forbidden.body")}</p><a href="/" style="background:#1d5fd1;color:#fff;padding:.5rem 1rem;border-radius:4px;text-decoration:none">${t(locale, "menu.dashboard")}</a></main></body></html>`;
  return new NextResponse(html, { status: 403, headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}

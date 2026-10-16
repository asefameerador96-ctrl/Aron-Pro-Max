import type { Metadata } from "next";
import { headers } from "next/headers";
import type { ReactNode } from "react";
import { I18nProvider } from "@/components/i18n-provider";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";
import { THEME_SCRIPT } from "@/lib/ui/theme";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getLocale();
  return { title: `${t(locale, "app.name")} · ${t(locale, "app.tagline")}`, robots: { index: false, follow: false } };
}

export default async function RootLayout({ children }: { children: ReactNode }) {
  const [locale, h] = await Promise.all([getLocale(), headers()]);
  // The proxy mints one nonce per request (src/proxy.ts) and the CSP allows only scripts that carry it.
  const nonce = h.get("x-nonce") ?? undefined;
  return (
    // The pre-paint script stamps data-theme, data-glass and data-density (web-glass s2.6); React must not undo them on hydration.
    <html lang={locale} suppressHydrationWarning>
      <head>
        <script nonce={nonce} dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body>
        {/* One fixed full-viewport gradient layer with the two glows, so scrolling never repaints it (web-glass s2.2). */}
        <div className="bg-page fixed inset-0 -z-10" aria-hidden="true" />
        <I18nProvider locale={locale}>{children}</I18nProvider>
      </body>
    </html>
  );
}

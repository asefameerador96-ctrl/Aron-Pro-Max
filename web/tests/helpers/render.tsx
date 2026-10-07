import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { I18nProvider } from "@/components/i18n-provider";
import type { Locale } from "@/lib/i18n";

/** Static HTML of a (server or client) component tree under the i18n provider. Needs vi.mock("next/navigation") in the test file. */
export function html(el: ReactElement, locale: Locale = "en"): string {
  return renderToStaticMarkup(<I18nProvider locale={locale}>{el}</I18nProvider>);
}
export const text = (markup: string) => markup.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();

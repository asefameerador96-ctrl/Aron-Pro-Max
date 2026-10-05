import Link from "next/link";
import { t, type Locale } from "@/lib/i18n";

export function Forbidden({ locale }: { locale: Locale }) {
  return (
    <section role="alert" data-testid="forbidden" className="mx-auto mt-16 max-w-md rounded-lg border border-red-200 bg-white p-6 text-center shadow-sm">
      <h1 className="text-xl font-semibold text-red-800">{t(locale, "admin.forbidden.title")}</h1>
      <p className="mt-2 text-slate-700">{t(locale, "admin.forbidden.body")}</p>
      <Link href="/" className="mt-4 inline-block rounded bg-brand-600 px-4 py-2 text-white hover:bg-brand-700">
        {t(locale, "menu.dashboard")}
      </Link>
    </section>
  );
}

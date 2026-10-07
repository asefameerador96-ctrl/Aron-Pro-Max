import Link from "next/link";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

export default async function NotFound() {
  const locale = await getLocale();
  return (
    <section data-testid="page-not-found" className="rounded-lg border border-slate-200 bg-white p-6 text-center">
      <h1 className="text-lg font-semibold">{t(locale, "error.ERR_NOT_FOUND")}</h1>
      <Link href="/" className="mt-3 inline-block text-brand-700 underline">
        {t(locale, "menu.dashboard")}
      </Link>
    </section>
  );
}

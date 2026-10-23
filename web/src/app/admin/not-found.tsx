// Not-found state of the admin segment (a bad id, or a 404 answer): plain words in the user's language and a way back, inside the admin shell.
import Link from "next/link";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

export default async function AdminNotFound() {
  const locale = await getLocale();
  return (
    <section data-testid="page-not-found" className="glass-card space-y-3 p-6 text-center">
      <h1 className="text-lg font-semibold">{t(locale, "error.ERR_NOT_FOUND")}</h1>
      <Link href="/admin" className="inline-block text-brand-700 underline">
        {t(locale, "menu.admin.home")}
      </Link>
    </section>
  );
}

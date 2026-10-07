import { ChangePasswordForm } from "@/components/change-password-form";
import { getLocale } from "@/lib/auth/service";
import { WEB_MIN_LENGTH } from "@/lib/auth/password-policy";
import { n } from "@/components/dash/tiles";
import { t } from "@/lib/i18n";

export default async function CredentialsPage() {
  const locale = await getLocale();
  return (
    <div className="space-y-4" data-testid="credentials">
      <h1 className="text-2xl font-bold">{t(locale, "menu.credentials")}</h1>
      <section className="max-w-md rounded-lg border border-slate-200 bg-white p-4 text-sm" data-testid="password-guideline">
        <h2 className="font-semibold">{t(locale, "credentials.guideline")}</h2>
        <ul className="mt-1 list-disc space-y-0.5 pl-5">
          <li>{t(locale, "credentials.rule.length", { n: n(locale, WEB_MIN_LENGTH) })}</li>
          <li>{t(locale, "credentials.rule.mixed")}</li>
          <li>{t(locale, "credentials.rule.history")}</li>
        </ul>
      </section>
      <ChangePasswordForm />
    </div>
  );
}

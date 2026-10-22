import { LocaleSwitch } from "@/components/locale-switch";
import { LoginForm } from "@/components/login-form";
import { BrandMark } from "@/components/brand-mark";
import { safeNext } from "@/lib/api/origin";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";
import { Suspense } from "react";

/** Login (web-glass s7.2): one centred glass dialog (420 wide) over the gradient, the Aron logo, the form, language switch
 *  top right and the AKTCL footer. The MFA step lives inside the form. */
export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string }> }) {
  const locale = await getLocale();
  const { next } = await searchParams;
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-[420px] flex-col justify-center gap-4 px-4 py-8">
      <div className="flex items-center justify-end">
        <Suspense>
          <LocaleSwitch />
        </Suspense>
      </div>
      <section className="glass-dialog page-in flex flex-col gap-5 p-6 text-primary sm:p-8" aria-labelledby="login-title">
        <div className="flex items-center gap-3">
          <BrandMark size={44} priority />
          <div className="min-w-0">
            <h1 id="login-title" className="text-title text-primary">
              {t(locale, "app.name")}
            </h1>
            <p className="text-caption text-secondary">{t(locale, "app.tagline")}</p>
          </div>
        </div>
        <h2 className="text-heading text-primary">{t(locale, "auth.title")}</h2>
        <LoginForm next={safeNext(next)} />
      </section>
      <p className="text-center text-caption text-secondary">{t(locale, "app.copyright")}</p>
    </main>
  );
}

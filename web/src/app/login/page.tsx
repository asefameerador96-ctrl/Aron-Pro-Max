import { LocaleSwitch } from "@/components/locale-switch";
import { LoginForm } from "@/components/login-form";
import { safeNext } from "@/lib/api/origin";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";
import { Suspense } from "react";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string }> }) {
  const locale = await getLocale();
  const { next } = await searchParams;
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center gap-6 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-brand-700">{t(locale, "app.name")}</h1>
          <p className="text-sm text-slate-500">{t(locale, "app.tagline")}</p>
        </div>
        <Suspense>
          <LocaleSwitch />
        </Suspense>
      </div>
      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 text-xl font-semibold">{t(locale, "auth.title")}</h2>
        <LoginForm next={safeNext(next)} />
      </section>
    </main>
  );
}

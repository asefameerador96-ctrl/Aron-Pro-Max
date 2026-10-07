import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t, type MessageKey } from "@/lib/i18n";

// SR lifecycle (F-ADM-076): one page that walks the four steps over the pages that already do the work. A disabled user's
// phone still uploads what it holds (server side), so disabling is safe at any time of day.
const STEPS: readonly { id: string; title: MessageKey; hint: MessageKey; href: string; cta: MessageKey }[] = [
  { id: "create", title: "lifecycle.create.title", hint: "lifecycle.create.hint", href: "/admin/users/new", cta: "lifecycle.create.cta" },
  { id: "bind", title: "lifecycle.bind.title", hint: "lifecycle.bind.hint", href: "/admin/route-assignments/new", cta: "lifecycle.bind.cta" },
  { id: "reassign", title: "lifecycle.reassign.title", hint: "lifecycle.reassign.hint", href: "/admin/sr-transfer", cta: "lifecycle.reassign.cta" },
  { id: "disable", title: "lifecycle.disable.title", hint: "lifecycle.disable.hint", href: "/admin/users?role=SR", cta: "lifecycle.disable.cta" },
];

export default async function SrLifecyclePage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t(locale, "menu.admin.sr_lifecycle")}</h1>
      <ol className="grid gap-3 sm:grid-cols-2" data-testid="lifecycle-steps">
        {STEPS.map((s, i) => (
          <li key={s.id} className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid={`step-${s.id}`}>
            <h2 className="font-semibold text-slate-800">
              {(i + 1).toLocaleString(locale === "bn" ? "bn-BD" : "en-US")}. {t(locale, s.title)}
            </h2>
            <p className="my-2 text-sm text-slate-600">{t(locale, s.hint)}</p>
            <Link href={s.href} className="text-sm font-medium text-brand-600 underline">
              {t(locale, s.cta)}
            </Link>
          </li>
        ))}
      </ol>
    </div>
  );
}

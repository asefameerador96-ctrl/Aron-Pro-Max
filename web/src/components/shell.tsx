import type { ReactNode } from "react";
import type { SessionData } from "@/lib/auth/session";
import { t, type Locale } from "@/lib/i18n";
import { menuFor } from "@/lib/menu/menu";
import { LocaleSwitch } from "./locale-switch";
import { LogoutButton } from "./logout-button";
import { NavLink } from "./nav-link";
import { ScopeBadge } from "./scope-badge";

/** Header (identity, scope, language), role-driven menu and content area, shared by the dashboards and the admin portal. */
export function Shell({ session, locale, children }: { session: SessionData; locale: Locale; children: ReactNode }) {
  const sections = menuFor(session.user.role);
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-white px-4 py-3">
        <div className="flex items-baseline gap-3">
          <span className="text-lg font-bold text-brand-700">{t(locale, "app.name")}</span>
          <span className="hidden text-sm text-slate-500 sm:inline">{t(locale, "app.tagline")}</span>
        </div>
        <ScopeBadge scope={session.scope} locale={locale} />
        <div className="flex items-center gap-3">
          <LocaleSwitch />
          <span className="text-sm" data-testid="who">
            {session.user.full_name} · {t(locale, `role.${session.user.role}`)}
          </span>
          <LogoutButton />
        </div>
      </header>
      <div className="mx-auto flex max-w-7xl flex-col gap-4 p-4 md:flex-row">
        <nav aria-label={t(locale, "menu.group.main")} className="w-full shrink-0 md:w-56">
          {sections.map((s) => (
            <div key={s.group} className="mb-4">
              <h2 className="mb-1 px-3 text-xs font-semibold uppercase tracking-wide text-slate-500">{t(locale, s.labelKey)}</h2>
              <ul>
                {s.items.map((i) => (
                  <li key={i.id}>
                    <NavLink href={i.href}>{t(locale, i.labelKey)}</NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
        <main className="min-w-0 flex-1">{children}</main>
      </div>
    </div>
  );
}

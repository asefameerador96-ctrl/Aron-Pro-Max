import type { ReactNode } from "react";
import type { SessionData } from "@/lib/auth/session";
import { t, type Locale } from "@/lib/i18n";
import { menuFor } from "@/lib/menu/menu";
import { ConnectionChip } from "./connection-chip";
import { Icon } from "./kit/icons";
import { LocaleSwitch } from "./locale-switch";
import { LogoutButton } from "./logout-button";
import { ScopeBadge } from "./scope-badge";
import { SearchField } from "./search-field";
import { NavDrawer, Sidebar, type NavSection } from "./sidebar";
import { ThemeMenu } from "./theme-menu";
import { TopBarSentinel } from "./top-bar-sentinel";
import { TopBarTitle } from "./top-bar-title";

/** The Aron shell (PARITY-SPEC s4, owner directive 2026-10-18): crimson-purple gradient sidebar with the red Logout at its foot, a solid white top bar
 *  (page title in blue, scope, search, connection, language, theme, avatar and name), white cards on a light grey page. Shared by the dashboards and the admin portal.
 *  Below md the scope badge wraps to a second line of the same bar (one node, so tests and screen readers see it once). */
export function Shell({ session, locale, children, allowedMenus = null }: { session: SessionData; locale: Locale; children: ReactNode; allowedMenus?: ReadonlySet<string> | null }) {
  const sections: NavSection[] = menuFor(session.user.role, undefined, allowedMenus).map((s) => ({ id: s.group, label: t(locale, s.labelKey), items: s.items.map((i) => ({ id: i.id, href: i.href, label: t(locale, i.labelKey) })) }));
  const appName = t(locale, "app.name");
  const who = `${session.user.full_name} · ${t(locale, `role.${session.user.role}`)}`;
  return (
    <div className="min-h-dvh text-primary">
      <a href="#content" className="sr-only z-50 rounded-chip bg-accent-container px-3 py-2 text-body-strong text-accent-on-container focus:not-sr-only focus:fixed focus:start-3 focus:top-3">
        {t(locale, "nav.skip")}
      </a>
      <Sidebar sections={sections} appName={appName} tagline={t(locale, "app.tagline")} />
      <div className="shell-main flex min-h-dvh flex-col">
        <header className="glass-bar sticky top-0 z-30 flex min-h-(--topbar-h) flex-wrap items-center gap-x-2 gap-y-1 border-b border-transparent px-3 py-1 data-[scrolled]:border-divider data-[scrolled]:shadow-card sm:gap-x-3 sm:px-4" data-testid="top-bar">
          <NavDrawer sections={sections} appName={appName} who={who} />
          <span className="flex items-center gap-2 md:hidden">
            <Icon name="dashboard" className="text-accent-graphic" />
            <span className="text-heading text-primary">{appName}</span>
          </span>
          <TopBarTitle sections={sections} />
          <div className="order-last min-w-0 basis-full pb-1 md:order-none md:basis-auto md:pb-0">
            <ScopeBadge scope={session.scope} locale={locale} />
          </div>
          <SearchField />
          <div className="ms-auto flex items-center gap-1 sm:gap-2">
            <ConnectionChip />
            <div className="hidden sm:block">
              <LocaleSwitch />
            </div>
            <ThemeMenu />
            <span className="flex items-center gap-2">
              <span className="inline-flex size-9 shrink-0 items-center justify-center rounded-full bg-accent-container text-body-strong text-accent-on-container" aria-hidden="true">
                {Array.from(session.user.full_name.trim())[0]?.toUpperCase() ?? "?"}
              </span>
              <span className="hidden max-w-56 truncate text-caption text-primary lg:inline" data-testid="who" title={who}>
                {who}
              </span>
            </span>
            <div className="md:hidden">
              <LogoutButton />
            </div>
          </div>
        </header>
        <TopBarSentinel />
        <main id="content" tabIndex={-1} className="page-in mx-auto w-full max-w-[1600px] flex-1 px-4 py-6 md:px-6 xl:px-8">
          {children}
        </main>
      </div>
    </div>
  );
}

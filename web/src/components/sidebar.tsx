"use client";
// Glass sidebar (web-glass s5, s6): 264 px on lg and up, collapsible to a 72 px icon rail (remembered per browser, stamped as
// html[data-nav="rail"] before paint), the rail alone on md, and a drawer sheet below md. The role-driven menu is the only
// source of items; this file only lays it out.
import { useRef, useSyncExternalStore } from "react";
import { NAV_KEY } from "@/lib/ui/theme";
import { BrandMark } from "./brand-mark";
import { useI18n } from "./i18n-provider";
import { Icon } from "./kit/icons";
import { navIconFor } from "./nav-icons";
import { IconButton } from "./kit/button";
import { LocaleSwitch } from "./locale-switch";
import { LogoutButton } from "./logout-button";
import { NavLink } from "./nav-link";

export interface NavSection {
  id: string;
  /** null = a single entry shown as a plain link, not a collapsible parent. */
  label: string | null;
  items: { id: string; href: string; label: string }[];
}

function subscribeNav(cb: () => void): () => void {
  const mo = new MutationObserver(cb);
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-nav"] });
  return () => mo.disconnect();
}

function Groups({ sections, onNavigate }: { sections: NavSection[]; onNavigate?: () => void }) {
  return (
    <>
      {sections.map((s) =>
        s.label === null ? (
          <div key={s.id} className="mb-1">
            <NavLink href={s.items[0]!.href} icon={navIconFor(s.items[0]!.id)} label={s.items[0]!.label} onNavigate={onNavigate} />
          </div>
        ) : (
        <details key={s.id} open className="group/nav mb-2">
          <summary className="rail-group flex min-h-8 cursor-pointer list-none items-center justify-between rounded-chip px-3 text-label text-on-rail/85 hover:bg-on-rail/15 [&::-webkit-details-marker]:hidden">
            <span>{s.label}</span>
            <Icon name="chevron-down" size={16} className="transition-transform duration-fast group-open/nav:rotate-180" />
          </summary>
          <ul className="space-y-0.5">
            {s.items.map((i) => (
              <li key={i.id}>
                <NavLink href={i.href} icon={navIconFor(i.id)} label={i.label} onNavigate={onNavigate} />
              </li>
            ))}
          </ul>
        </details>
        ),
      )}
    </>
  );
}

export function Sidebar({ sections, appName, tagline }: { sections: NavSection[]; appName: string; tagline: string }) {
  const { t } = useI18n();
  // The rail state lives on <html data-nav> (stamped before paint); React only mirrors it, so there is no hydration mismatch.
  const rail = useSyncExternalStore(subscribeNav, () => document.documentElement.getAttribute("data-nav") === "rail", () => false);
  function toggle() {
    const next = !rail;
    const d = document.documentElement;
    if (next) d.setAttribute("data-nav", "rail");
    else d.removeAttribute("data-nav");
    try {
      localStorage.setItem(NAV_KEY, next ? "rail" : "full");
    } catch {
      /* preference only */
    }
  }
  return (
    <div className="shell-rail aron-rail fixed inset-y-0 start-0 z-30 hidden flex-col md:flex" data-testid="sidebar">
      <div className="rail-head flex min-h-16 shrink-0 items-center gap-3 px-4">
        <BrandMark />
        <div className="rail-wordmark min-w-0 flex-1">
          <p className="truncate text-heading text-on-rail">{appName}</p>
          <p className="truncate text-caption text-on-rail/85">{tagline}</p>
        </div>
        <IconButton icon={rail ? "chevrons-right" : "chevrons-left"} label={t(rail ? "nav.expand" : "nav.collapse")} aria-expanded={!rail} onClick={toggle} data-testid="nav-collapse" className="hidden !text-on-rail hover:!bg-on-rail/15 lg:inline-flex" />
      </div>
      <nav aria-label={t("nav.sidebar")} className="min-h-0 flex-1 overflow-y-auto px-3 py-2 [scrollbar-width:thin]">
        <Groups sections={sections} />
      </nav>
      <div className="shrink-0 p-3">
        <LogoutButton variant="rail" />
      </div>
    </div>
  );
}

/** Below md: the hamburger in the top bar opens the same menu as a start-edge sheet (<dialog>, scrim, Esc closes, focus returns). */
export function NavDrawer({ sections, appName, who }: { sections: NavSection[]; appName: string; who: string }) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);
  const open = () => {
    ref.current?.showModal();
    document.documentElement.setAttribute("data-overlay", "");
  };
  const close = () => {
    ref.current?.close();
  };
  return (
    <>
      <IconButton icon="menu" label={t("nav.menu")} onClick={open} className="md:hidden" data-testid="nav-open" />
      <dialog ref={ref} onClose={() => document.documentElement.removeAttribute("data-overlay")} className="sheet-start aron-rail m-0 me-auto h-dvh max-h-dvh w-[min(320px,86vw)] max-w-none rounded-none rounded-e-sheet p-0 text-on-rail backdrop:bg-scrim" aria-label={t("nav.menu")} data-testid="nav-drawer">
        <div className="flex h-14 items-center justify-between gap-3 px-4">
          <div className="flex items-center gap-3">
            <BrandMark />
            <span className="text-heading text-on-rail">{appName}</span>
          </div>
          <IconButton icon="close" label={t("nav.close")} onClick={close} className="!text-on-rail hover:!bg-on-rail/15" />
        </div>
        <nav aria-label={t("nav.main")} className="h-[calc(100dvh-3.5rem-4.5rem)] overflow-y-auto px-3 py-2">
          <Groups sections={sections} onNavigate={close} />
        </nav>
        <div className="flex h-18 flex-wrap items-center justify-between gap-2 border-t border-on-rail/25 px-4">
          <span className="min-w-0 truncate text-caption text-on-rail/90" title={who}>
            {who}
          </span>
          <div className="sm:hidden">
            <LocaleSwitch />
          </div>
        </div>
      </dialog>
    </>
  );
}

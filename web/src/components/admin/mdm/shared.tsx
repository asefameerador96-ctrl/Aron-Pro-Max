// Pieces shared by the MDM pages (D-MDM-01): the session gate, the section tabs and the small badges.
import Link from "next/link";
import type { ReactNode } from "react";
import { Forbidden } from "@/components/forbidden";
import { mdmLevelOf, type MdmComplianceFlag, type MdmLevel } from "@/lib/admin/mdm";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import type { SessionData } from "@/lib/auth/session";
import { t, type Locale, type MessageKey } from "@/lib/i18n";

export interface MdmCtx {
  session: SessionData;
  locale: Locale;
  level: MdmLevel;
}

/** Session, locale and the caller's MDM level; `forbidden` is the page to return when the caller has no MDM at all. */
export async function mdmContext(): Promise<{ ctx: MdmCtx; forbidden: ReactNode | null }> {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  const level = mdmLevelOf(session.user as SessionData["user"] & { modules?: Record<string, string> | null });
  return { ctx: { session, locale, level }, forbidden: level === "none" ? <Forbidden locale={locale} /> : null };
}

const TABS: readonly { id: string; href: string; key: MessageKey }[] = [
  { id: "devices", href: "/admin/mdm", key: "mdm.tab.devices" },
  { id: "policies", href: "/admin/mdm/policies", key: "mdm.tab.policies" },
  { id: "enrolment", href: "/admin/mdm/enrolment", key: "mdm.tab.enrolment" },
  { id: "alerts", href: "/admin/mdm/alerts", key: "mdm.tab.alerts" },
  { id: "activity", href: "/admin/mdm/activity", key: "mdm.tab.activity" },
];

export function MdmTabs({ locale, active }: { locale: Locale; active: string }) {
  return (
    <nav aria-label={t(locale, "menu.admin.mdm")} className="flex flex-wrap gap-1 border-b border-slate-200" data-testid="mdm-tabs">
      {TABS.map((tab) => (
        <Link
          key={tab.id}
          href={tab.href}
          aria-current={tab.id === active ? "page" : undefined}
          className={`-mb-px rounded-t border border-b-0 px-3 py-2 text-sm ${tab.id === active ? "border-slate-200 bg-white font-semibold text-brand-700" : "border-transparent text-slate-600 hover:text-slate-900"}`}
        >
          {t(locale, tab.key)}
        </Link>
      ))}
    </nav>
  );
}

const FLAG_SEVERE: readonly MdmComplianceFlag[] = ["rooted", "mock_location", "not_device_owner", "policy_drift"];

export function FlagChip({ locale, flag }: { locale: Locale; flag: MdmComplianceFlag }) {
  const severe = FLAG_SEVERE.includes(flag);
  return (
    <span
      data-testid={`flag-${flag}`}
      className={`inline-block rounded-full border px-2 py-0.5 text-xs font-medium ${severe ? "border-[var(--danger)] text-[var(--danger)]" : "border-[var(--warning)] text-slate-800"}`}
    >
      {t(locale, `mdm.flag.${flag}` as MessageKey)}
    </span>
  );
}

export function FlagChips({ locale, flags }: { locale: Locale; flags: readonly MdmComplianceFlag[] }) {
  if (flags.length === 0) return <span className="text-xs text-[var(--success)]">{t(locale, "mdm.flags.none")}</span>;
  return (
    <span className="flex flex-wrap gap-1">
      {flags.map((f) => (
        <FlagChip key={f} locale={locale} flag={f} />
      ))}
    </span>
  );
}

/** One line "name (@username)" style list of bound users, or a dash. */
export const usersText = (users: readonly string[]): string => (users.length ? users.join(", ") : "—");

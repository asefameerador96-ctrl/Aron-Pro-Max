// F-ADM-024 Data Entry: one place for Web Entry, QC and Final Submit, plus the paper backfill of a dead-phone day.
import Link from "next/link";
import { Card, PageHeading } from "@/components/admin/kit/page";
import { t, type Locale, type MessageKey } from "@/lib/i18n";
import { PaperBackfillForm } from "./paper-backfill-form";

const GROUPS: { href: string; key: MessageKey }[] = [
  { href: "/entry/web", key: "menu.main.web_entry" },
  { href: "/entry/qc", key: "menu.main.qc_entry" },
  { href: "/entry/warehouse-qc", key: "menu.main.wh_qc" },
  { href: "/final-submit/submit", key: "menu.main.final_submit" },
];

export function DataEntryView({ locale, canBackfill }: { locale: Locale; canBackfill: boolean }) {
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "de.title")} intro={t(locale, "de.intro")} />
      <nav className="flex flex-wrap gap-2" aria-label={t(locale, "de.title")} data-testid="entry-groups">
        {GROUPS.map((g) => (
          <Link key={g.href} href={g.href} className="rounded border border-slate-300 bg-white px-4 py-2 text-sm hover:border-brand-600">
            {t(locale, g.key)}
          </Link>
        ))}
      </nav>
      {canBackfill ? (
        <Card title={t(locale, "de.backfill")}>
          <p className="text-sm text-slate-600">{t(locale, "de.backfill.hint")}</p>
          <PaperBackfillForm />
        </Card>
      ) : null}
    </div>
  );
}

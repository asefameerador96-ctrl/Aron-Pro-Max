import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { buttonClass } from "@/components/kit/button";
import { StatusChip, type ChipTone } from "@/components/kit/chip";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar, type FilterControl } from "@/components/admin/kit/filter-bar";
import { PageHeading, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { loadGrants } from "@/lib/admin/modules";
import { canReadLoyalty, canWriteLoyalty, PROGRAMME_KINDS, PROGRAMME_STATUSES, type Programme } from "@/lib/admin/loyalty";
import { loadProgrammes } from "@/lib/admin/loyalty-load";
import { kindLabel, nameOf, programmePeriod, statusLabel } from "@/lib/admin/loyalty-view";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { formatNumber, t } from "@/lib/i18n";

const TONE: Record<Programme["status"], ChipTone> = { draft: "warning", active: "success", inactive: "quiet" };

// Loyalty programmes list (portal-loyalty-web, brief s8). The list operation has no text search or paging: filters run on the loaded rows.
export default async function LoyaltyPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canReadLoyalty(session.user.role, grants)) return <Forbidden locale={locale} />;
  const canWrite = canWriteLoyalty(session.user.role, grants);
  const status = (PROGRAMME_STATUSES as readonly string[]).includes(one(sp.status) ?? "") ? (one(sp.status) as Programme["status"]) : undefined;
  const kind = (PROGRAMME_KINDS as readonly string[]).includes(one(sp.kind) ?? "") ? (one(sp.kind) as Programme["kind"]) : undefined;
  const q = (one(sp.q) ?? "").trim();

  const r = await loadProgrammes(session.at);
  const needle = q.toLowerCase();
  const items = r.ok ? r.data.filter((p) => (!status || p.status === status) && (!kind || p.kind === kind) && (!needle || p.code.toLowerCase().includes(needle) || p.name_en.toLowerCase().includes(needle) || (p.name_bn ?? "").toLowerCase().includes(needle))) : [];

  const controls: FilterControl[] = [
    { param: "q", label: t(locale, "loy.filter.search"), kind: "search", value: q },
    { param: "kind", label: t(locale, "loy.filter.kind"), kind: "enum", value: kind ?? "", options: PROGRAMME_KINDS.map((k) => ({ value: k, label: kindLabel(locale, k) })) },
    { param: "status", label: t(locale, "loy.filter.status"), kind: "enum", value: status ?? "", options: PROGRAMME_STATUSES.map((s) => ({ value: s, label: statusLabel(locale, s) })) },
  ];
  const columns: Column<Programme>[] = [
    { key: "code", header: t(locale, "loy.col.code"), render: (p) => <Link href={`/admin/loyalty/${p.programme_id}`} className="text-body-strong text-accent hover:underline" data-testid={`programme-link-${p.code}`}>{p.code}</Link> },
    { key: "name", header: t(locale, "loy.col.name"), render: (p) => nameOf(locale, p) },
    { key: "kind", header: t(locale, "loy.col.kind"), render: (p) => kindLabel(locale, p.kind) },
    { key: "period", header: t(locale, "loy.col.period"), render: (p) => programmePeriod(p) },
    { key: "tiers", header: t(locale, "loy.col.tiers"), align: "right", render: (p) => formatNumber(locale, (p.tiers ?? []).length) },
    { key: "status", header: t(locale, "loy.col.status"), render: (p) => <StatusChip tone={TONE[p.status]} testId={`programme-status-${p.code}`}>{statusLabel(locale, p.status)}</StatusChip> },
    { key: "version", header: t(locale, "loy.col.version"), align: "right", render: (p) => formatNumber(locale, p.version_no ?? 1) },
  ];
  const filtered = status !== undefined || kind !== undefined || q.length > 0;
  return (
    <div className="space-y-4" data-testid="loyalty-page">
      <PageHeading
        title={t(locale, "loy.title")}
        intro={t(locale, "loy.intro")}
        actions={<><Link href="/admin/loyalty/gifts" className={buttonClass("secondary")} data-testid="loyalty-gifts-link">{t(locale, "loy.gifts_link")}</Link>{canWrite ? <Link href="/admin/loyalty/new" className={buttonClass("primary")} data-testid="programme-new">{t(locale, "loy.new")}</Link> : null}</>}
      />
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "loy.filter.all")} clearHref="/admin/loyalty" />
      {!r.ok ? (
        <Banner tone={r.offline ? "offline" : "danger"} role="alert" testId="loyalty-error" action={<Link href={`/admin/loyalty${qs({ status, kind, q })}`} className="text-body-strong underline">{t(locale, "loy.reload")}</Link>}>
          {t(locale, r.offline ? "loy.offline" : "loy.error_load")}
        </Banner>
      ) : (
        <DataTable columns={columns} rows={items} rowKey={(p) => String(p.programme_id)} empty={t(locale, filtered ? "loy.empty_filtered" : "loy.empty")} caption={t(locale, "loy.title")} />
      )}
    </div>
  );
}

import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { Banner } from "@/components/kit/banner";
import { buttonClass } from "@/components/kit/button";
import { StatusChip, type ChipTone } from "@/components/kit/chip";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar, type FilterControl } from "@/components/admin/kit/filter-bar";
import { NextLink, PageHeading, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { apiClient, outcome } from "@/lib/api/client";
import { loadGrants } from "@/lib/admin/modules";
import { canReadOffers, canWriteOffers, OFFER_STATUSES, type Offer } from "@/lib/admin/offers";
import { statusLabel, titleOf, typeLabel, valueText } from "@/lib/admin/offers-view";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { formatBusinessDate, t } from "@/lib/i18n";

const PAGE = 25;
const SEARCH_PAGES = 10; // a search reads at most this many pages of 100 (pilot size: far fewer programmes than that)
const TONE: Record<Offer["status"], ChipTone> = { draft: "warning", active: "success", inactive: "quiet" };

// Discount programmes list (portal-discount-web, brief section 4). listOffers has no text search: `q` filters the loaded pages here.
export default async function DiscountsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  const grants = await loadGrants(session.at, session.user.role);
  if (!canReadOffers(session.user.role, grants)) return <Forbidden locale={locale} />;
  const canWrite = canWriteOffers(session.user.role, grants);
  const status = (OFFER_STATUSES as readonly string[]).includes(one(sp.status) ?? "") ? (one(sp.status) as Offer["status"]) : undefined;
  const q = (one(sp.q) ?? "").trim();
  const cursor = one(sp.cursor, 200);

  let items: Offer[] = [];
  let next: string | null = null;
  let failed: { offline: boolean } | null = null;
  const api = apiClient(session.at);
  if (q.length === 0) {
    const r = await outcome(api.GET("/v1/admin/offers", { params: { query: { status, limit: PAGE, cursor } } }));
    if (r.ok) ({ items, next_cursor: next } = r.data);
    else failed = { offline: r.status >= 500 || r.status === 0 };
  } else {
    let c: string | undefined;
    for (let i = 0; i < SEARCH_PAGES; i++) {
      const r = await outcome(api.GET("/v1/admin/offers", { params: { query: { status, limit: 100, cursor: c } } }));
      if (!r.ok) {
        failed = { offline: r.status >= 500 || r.status === 0 };
        break;
      }
      items.push(...r.data.items);
      if (!r.data.next_cursor) break;
      c = r.data.next_cursor;
    }
    const needle = q.toLowerCase();
    items = items.filter((o) => o.code.toLowerCase().includes(needle) || o.title_en.toLowerCase().includes(needle) || (o.title_bn ?? "").toLowerCase().includes(needle));
  }

  const controls: FilterControl[] = [
    { param: "q", label: t(locale, "disc.filter.search"), kind: "search", value: q },
    { param: "status", label: t(locale, "disc.filter.status"), kind: "enum", value: status ?? "", options: OFFER_STATUSES.map((s) => ({ value: s, label: statusLabel(locale, s) })) },
  ];
  const columns: Column<Offer>[] = [
    { key: "code", header: t(locale, "disc.col.code"), render: (o) => <Link href={`/admin/discounts/${o.id}`} className="text-body-strong text-accent hover:underline" data-testid={`offer-link-${o.code}`}>{o.code}</Link> },
    { key: "title", header: t(locale, "disc.col.title"), render: (o) => titleOf(locale, o) },
    { key: "type", header: t(locale, "disc.col.type"), render: (o) => typeLabel(locale, o.offer_type) },
    { key: "value", header: t(locale, "disc.col.value"), render: (o) => valueText(locale, o) },
    { key: "valid", header: t(locale, "disc.col.valid"), render: (o) => `${formatBusinessDate(locale, o.valid_from)} – ${formatBusinessDate(locale, o.valid_to)}` },
    { key: "status", header: t(locale, "disc.col.status"), render: (o) => <StatusChip tone={TONE[o.status]} testId={`offer-status-${o.code}`}>{statusLabel(locale, o.status)}</StatusChip> },
    { key: "version", header: t(locale, "disc.col.version"), align: "right", render: (o) => String(o.version_no ?? 1) },
  ];
  const filtered = status !== undefined || q.length > 0;
  return (
    <div className="space-y-4" data-testid="discounts-page">
      <PageHeading title={t(locale, "disc.title")} intro={t(locale, "disc.intro")} actions={canWrite ? <Link href="/admin/discounts/new" className={buttonClass("primary")} data-testid="offer-new">{t(locale, "disc.new")}</Link> : undefined} />
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "disc.filter.all")} clearHref="/admin/discounts" />
      {failed ? (
        <Banner tone={failed.offline ? "offline" : "danger"} role="alert" testId="discounts-error" action={<Link href={`/admin/discounts${qs({ status, q, cursor })}`} className="text-body-strong underline">{t(locale, "disc.reload")}</Link>}>
          {t(locale, failed.offline ? "disc.offline" : "disc.error_load")}
        </Banner>
      ) : (
        <>
          <DataTable columns={columns} rows={items} rowKey={(o) => String(o.id)} empty={t(locale, filtered ? "disc.empty_filtered" : "disc.empty")} caption={t(locale, "disc.title")} />
          {q.length === 0 ? <NextLink href={next ? `/admin/discounts${qs({ status, cursor: next })}` : null} label={t(locale, "disc.next_page")} /> : null}
        </>
      )}
    </div>
  );
}

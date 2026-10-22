import { RouteTargetsUpload } from "@/components/admin/route-targets-upload";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { apiGet, Card, NextLink, one, PageHeading, qs, type SearchParams } from "@/components/admin/kit/page";
import { onApiFailure } from "@/components/admin/crud/pages";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { canSee, canWriteModule, loadGrants } from "@/lib/admin/modules";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, formatNumber, t } from "@/lib/i18n";

interface TargetItem {
  route_code: string;
  route_name: string;
  sku_code: string;
  month: string;
  qty_base: number;
}

// Route monthly targets (D-TARGET-MIN-01, docs/34 s2). Read shows the targets; upload and commit need the targets module at w.
export default async function RouteTargetsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const grants = await loadGrants(session.at, session.user.role);
  if (!canSee(grants, "targets")) return <Forbidden locale={locale} />;
  const current = businessDate().slice(0, 7);
  const m = one(sp.month, 7);
  const month = m && /^\d{4}-(0[1-9]|1[0-2])$/.test(m) ? m : current;
  const cursor = one(sp.cursor, 200);
  const list = await apiGet<{ items: TargetItem[]; next_cursor: string | null }>("/v1/admin/route-targets", session.at, { month, limit: 100, cursor });
  if (!list.ok) return onApiFailure(list.status, list.problem, locale);
  const columns: Column<TargetItem>[] = [
    { key: "route", header: t(locale, "rt.col.route"), render: (r) => `${r.route_code} · ${r.route_name}` },
    { key: "sku", header: t(locale, "rt.col.sku"), render: (r) => r.sku_code },
    { key: "month", header: t(locale, "rt.col.month"), render: (r) => r.month },
    { key: "qty", header: t(locale, "rt.col.quantity"), align: "right", render: (r) => formatNumber(locale, r.qty_base) },
  ];
  const next = list.data.next_cursor ? `/admin/route-targets${qs({ month, cursor: list.data.next_cursor })}` : null;
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "rt.title")} intro={t(locale, "rt.intro")} />
      {/* upload and commit need targets: w (D-WEB-ADMIN-MENU-01) */}
      {canOp("route-targets.commit", session.user.role) && canWriteModule(grants, "targets") ? (
        <Card>
          <RouteTargetsUpload currentMonth={current} />
        </Card>
      ) : (
        <p className="text-sm text-slate-600">{t(locale, "rt.read_only")}</p>
      )}
      <Card title={t(locale, "rt.list_title")} data>
        <FilterBar controls={[{ param: "month", label: t(locale, "rt.month"), kind: "search", value: month }]} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/route-targets" />
        <DataTable columns={columns} rows={list.data.items} rowKey={(r) => `${r.route_code}|${r.sku_code}|${r.month}`} empty={t(locale, "rt.list_empty")} caption={t(locale, "rt.list_title")} />
        <NextLink href={next} label={t(locale, "common.next")} />
      </Card>
    </div>
  );
}

import { Forbidden } from "@/components/forbidden";
import { WholesaleBasket, type BasketRow } from "@/components/admin/wholesale-basket";
import { FilterBar, type FilterControl } from "@/components/admin/kit/filter-bar";
import { loadAllRows, loadRefOptions } from "@/components/admin/crud/server";
import { outlets } from "../_entities/outlets";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v)?.slice(0, 80) || undefined;
const DISPLAY_MAX = 500;
const ZONES = { path: "/v1/admin/geo/{level}", params: { level: "zone" }, label: ["code", "name"] } as const;

// Wholesale outlets bulk marking (F-ADM-056). The list operation has no outlet_kind filter, so the kind filter is applied
// to the (at most 500) rows of the page; use the zone or search filter to narrow big zones.
export default async function WholesaleMarkingPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!outlets.writeRoles.includes(session.user.role)) return <Forbidden locale={locale} />;
  const kind = one(sp.kind);
  const query = { zone_id: one(sp.zone_id), q: one(sp.q), status: "active" };
  const [all, zones] = await Promise.all([loadAllRows("/v1/admin/outlets", session.at, query), loadRefOptions(ZONES, session.at)]);
  if (all.failed && all.rows.length === 0) {
    return (
      <p role="alert" className="rounded border border-red-200 bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-[var(--danger)]">
        {t(locale, "error.ref_load")}
      </p>
    );
  }
  // The list operation has no outlet_kind filter, so every page is read (up to 10,000 rows) and the kind is filtered here.
  const matching = all.rows.filter((o) => !kind || o.outlet_kind === kind);
  const rows: BasketRow[] = matching
    .slice(0, DISPLAY_MAX)
    .map((o) => ({ id: Number(o.id), code: String(o.code), name: String(o.name), owner: String(o.owner_name ?? ""), kind: o.outlet_kind === "wholesale" ? "wholesale" : "retail", kindLabel: t(locale, o.outlet_kind === "wholesale" ? "outlet.kind.wholesale" : "outlet.kind.retail") }));
  const controls: FilterControl[] = [
    { param: "q", label: t(locale, "common.search"), kind: "search", value: query.q ?? "" },
    { param: "zone_id", label: t(locale, "entity.field.zone_id"), kind: "enum", value: query.zone_id ?? "", options: zones },
    { param: "kind", label: t(locale, "entity.field.outlet_kind"), kind: "enum", value: kind ?? "", options: [{ value: "wholesale", label: t(locale, "outlet.kind.wholesale") }, { value: "retail", label: t(locale, "outlet.kind.retail") }] },
  ];
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "wholesale.title")}</h1>
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/wholesale-marking" />
      {all.failed ? <p role="alert" className="rounded border border-red-200 bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-[var(--danger)]">{t(locale, "error.ref_load")}</p> : null}
      <WholesaleBasket rows={rows} total={matching.length} />
    </div>
  );
}

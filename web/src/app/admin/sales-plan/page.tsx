import { Forbidden } from "@/components/forbidden";
import { SalesPlanEditor, type PlanSku } from "@/components/admin/sales-plan-editor";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { loadAllRows } from "@/components/admin/crud/server";
import { apiClient, outcome } from "@/lib/api/client";
import { notFound } from "next/navigation";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, formatBusinessDate, problemMessage, t, type MessageKey } from "@/lib/i18n";

const CAT_KEY: Record<string, MessageKey> = { cigarette: "sku.cat.cigarette", bidi: "sku.cat.bidi", lighter: "sku.cat.lighter", match: "sku.cat.match" };
// Sales Plan (F-ADM-006): the SKUs enabled for a zone from a date. The zone is chosen here; the user's reach is the server's.
export default async function SalesPlanPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const zoneRaw = Array.isArray(sp.zone_id) ? sp.zone_id[0] : sp.zone_id;
  const zoneId = zoneRaw && /^[0-9]{1,15}$/.test(zoneRaw) ? Number(zoneRaw) : null;

  const [zones, houses, territories] = await Promise.all([loadAllRows("/v1/admin/geo/zone", session.at), loadAllRows("/v1/admin/geo/house", session.at), loadAllRows("/v1/admin/geo/territory", session.at)]);
  const zoneOptions = zones.rows.map((z) => ({ value: String(z.id), label: `${z.code} · ${z.name}` }));
  const controls = [{ param: "zone_id", label: t(locale, "entity.field.zone_id"), kind: "enum" as const, value: zoneId ? String(zoneId) : "", options: zoneOptions }];
  const head = (
    <>
      <h1 className="text-2xl font-bold">{t(locale, "salesplan.title")}</h1>
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/sales-plan" />
      {zones.failed || houses.failed || territories.failed ? <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "error.ref_load")}</p> : null}
    </>
  );
  if (zoneId === null) {
    return (
      <div className="space-y-4">
        {head}
        <p className="text-slate-600">{t(locale, "salesplan.pick_zone")}</p>
      </div>
    );
  }
  const zone = zones.rows.find((z) => z.id === zoneId);
  if (!zone) notFound();

  const client = apiClient(session.at);
  const [plan, skuRows] = await Promise.all([outcome(client.GET("/v1/admin/sales-plans/{zone_id}", { params: { path: { zone_id: zoneId }, query: { valid_on: businessDate() } } })), loadAllRows("/v1/admin/skus", session.at, { status: "active" })]);
  if (!plan.ok) {
    return (
      <div className="space-y-4">
        {head}
        <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{problemMessage(locale, plan.problem.code)}</p>
      </div>
    );
  }
  const skus: PlanSku[] = skuRows.rows.map((s) => ({ id: Number(s.id), code: String(s.code), name: String(s.name), category: String(s.category_code), categoryLabel: t(locale, CAT_KEY[String(s.category_code)] ?? "sku.cat.cigarette") }));
  // zone -> house -> territory: the zones of the same territory (apply to all).
  const houseOf = (zId: unknown) => houses.rows.find((h) => h.id === zId);
  const territoryId = houseOf(zone.parent_id)?.parent_id;
  const territory = territories.rows.find((tr) => tr.id === territoryId);
  const siblings = territory ? zones.rows.filter((z) => houseOf(z.parent_id)?.parent_id === territoryId).map((z) => ({ id: Number(z.id), label: `${z.code} · ${z.name}` })) : [{ id: zoneId, label: `${zone.code} · ${zone.name}` }];
  return (
    <div className="space-y-4">
      {head}
      <p className="text-sm text-slate-600" data-testid="plan-since">
        {t(locale, "salesplan.since", { date: formatBusinessDate(locale, plan.data.valid_from) })}
      </p>
      <SalesPlanEditor zoneId={zoneId} siblingZones={siblings} territoryLabel={territory ? `${territory.code} · ${territory.name}` : null} skus={skus} selected={plan.data.sku_ids} minDate={businessDate()} canWrite={["ADMIN", "SUPERADMIN"].includes(session.user.role)} />
    </div>
  );
}

import { Forbidden } from "@/components/forbidden";
import { PriceGrid, type PriceSku } from "@/components/admin/price-grid";
import { PRICE_TYPES, type PriceType } from "@/lib/admin/price-types";
import { loadAllRows } from "@/components/admin/crud/server";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, t, type MessageKey } from "@/lib/i18n";

const TYPE_KEY: Record<PriceType, MessageKey> = { outlet: "price.type.outlet", cc: "price.type.cc", distributor: "price.type.distributor", reporting: "price.type.reporting", nto: "price.type.nto" };

// Price management (F-ADM-005): five price types per SKU, effective-dated, three decimals, preview before publishing.
export default async function PricesPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const today = businessDate();
  const [skuRows, priceRows] = await Promise.all([loadAllRows("/v1/admin/skus", session.at, { status: "active" }), loadAllRows("/v1/admin/prices", session.at, { valid_on: today })]);
  const skus: PriceSku[] = skuRows.rows.map((s) => {
    const current: Partial<Record<PriceType, number>> = {};
    for (const p of priceRows.rows) if (p.sku_id === s.id && (PRICE_TYPES as readonly string[]).includes(String(p.price_type))) current[p.price_type as PriceType] = Number(p.amount_mtk);
    return { id: Number(s.id), code: String(s.code), name: String(s.name), current };
  });
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "prices.title")}</h1>
      {skuRows.failed || priceRows.failed ? <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "error.ref_load")}</p> : null}
      <PriceGrid skus={skus} minDate={today} canWrite={["ADMIN", "SUPERADMIN"].includes(session.user.role)} typeLabels={Object.fromEntries(PRICE_TYPES.map((ty) => [ty, t(locale, TYPE_KEY[ty])])) as Record<PriceType, string>} />
    </div>
  );
}

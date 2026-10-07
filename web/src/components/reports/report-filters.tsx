import type { ScopeSummary } from "@/contract/types";
import { t, type Locale } from "@/lib/i18n";
import type { WebReport } from "@/lib/reports/catalog";
import { PAGE_SIZES } from "@/lib/reports/query";
import type { GeoOption } from "@/lib/reports/server";
import { ScopeFilter } from "./scope-filter";

type Params = Record<string, string | string[] | undefined>;
const val = (p: Params, k: string): string => {
  const v = p[k];
  return (Array.isArray(v) ? v[0] : v) ?? "";
};
const input = "rounded border border-slate-300 bg-white px-2 py-1 text-sm";

/** The filter bar of a report page: a plain GET form, so a filtered report is a shareable link and works without client JS. */
export async function ReportFilters({ report, locale, params, token, scope, categories, skus, today }: { report: WebReport; locale: Locale; params: Params; token: string; scope: ScopeSummary | null; categories: GeoOption[]; skus: GeoOption[]; today: string }) {
  const has = (f: string) => report.filters.includes(f as never);
  const picked = (k: string) => new Set((Array.isArray(params[k]) ? (params[k] as string[]) : String(params[k] ?? "").split(",")).filter(Boolean));
  return (
    <form method="get" className="space-y-3 rounded-lg border border-slate-200 bg-white p-3 shadow-sm" data-testid="report-filters">
      <div className="flex flex-wrap items-end gap-3">
        {has("period") && report.singleDate ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.date")}</span>
            <input type="date" name="date" defaultValue={val(params, "date") || today} className={input} />
          </label>
        ) : null}
        {has("period") && !report.singleDate ? (
          <>
            <label className="text-sm">
              <span className="block text-xs text-slate-500">{t(locale, "filter.from")}</span>
              <input type="date" name="from" defaultValue={val(params, "from") || today} className={input} />
            </label>
            <label className="text-sm">
              <span className="block text-xs text-slate-500">{t(locale, "filter.to")}</span>
              <input type="date" name="to" defaultValue={val(params, "to") || val(params, "from") || today} className={input} />
            </label>
          </>
        ) : null}
        {has("date_grouping") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.grouping")}</span>
            <select name="group" defaultValue={val(params, "group") || report.grouping || "total"} className={input}>
              {(["total", "day", "week", "month"] as const).map((g) => (
                <option key={g} value={g}>
                  {t(locale, `filter.grouping.${g}`)}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        {has("location") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.location")}</span>
            <select name="location" defaultValue={val(params, "location")} className={input}>
              <option value="">{t(locale, "common.all")}</option>
              {(["wing", "division", "territory", "zone", "route", "outlet", "user"] as const).map((l) => (
                <option key={l} value={l}>
                  {t(locale, `filter.location.${l}`)}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        {has("category") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.category")}</span>
            <select name="category" defaultValue={val(params, "category")} className={input}>
              <option value="">{t(locale, "common.all")}</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        {has("product_type") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.product_type")}</span>
            <select name="product_type" defaultValue={val(params, "product_type")} className={input}>
              <option value="">{t(locale, "common.all")}</option>
              {(["category", "segment", "brand", "variant", "sku"] as const).map((l) => (
                <option key={l} value={l}>
                  {t(locale, `filter.product_type.${l}`)}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        {has("active_status") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.status")}</span>
            <select name="status" defaultValue={val(params, "status")} className={input}>
              <option value="">{t(locale, "common.all")}</option>
              <option value="active">{t(locale, "entity.status.active")}</option>
              <option value="inactive">{t(locale, "entity.status.inactive")}</option>
            </select>
          </label>
        ) : null}
        {has("field_force_type") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.ff")}</span>
            <select name="ff" defaultValue={val(params, "ff")} className={input}>
              <option value="">{t(locale, "common.all")}</option>
              <option value="sr">{t(locale, "filter.ff.sr")}</option>
              <option value="amo">{t(locale, "filter.ff.amo")}</option>
            </select>
          </label>
        ) : null}
        {has("sub_channels") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.sub_channels")}</span>
            <input type="text" name="sub_channels" inputMode="numeric" defaultValue={val(params, "sub_channels")} className={`${input} w-32`} />
          </label>
        ) : null}
        {has("outlet_code") ? (
          <label className="text-sm">
            <span className="block text-xs text-slate-500">{t(locale, "filter.outlet_code")}</span>
            <input type="text" name="outlet_code" maxLength={32} defaultValue={val(params, "outlet_code")} className={`${input} w-32`} />
          </label>
        ) : null}
        {(["std", "memo"] as const).map((k) =>
          has(`${k}_criteria`) ? (
            <div key={k} className="text-sm">
              <span className="block text-xs text-slate-500">{t(locale, k === "std" ? "filter.std_criteria" : "filter.memo_criteria")}</span>
              <span className="flex gap-1">
                <select name={`${k}_op`} defaultValue={val(params, `${k}_op`) || ">="} className={input} aria-label={t(locale, "filter.operator")}>
                  {[">", ">=", "=", "<=", "<"].map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
                <input type="number" step="any" name={`${k}_val`} defaultValue={val(params, `${k}_val`)} className={`${input} w-24`} />
              </span>
            </div>
          ) : null,
        )}
        <label className="text-sm">
          <span className="block text-xs text-slate-500">{t(locale, "filter.page_size")}</span>
          <select name="size" defaultValue={val(params, "size") || "50"} className={input}>
            {PAGE_SIZES.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
      </div>
      {has("products") ? (
        <label className="block text-sm">
          <span className="block text-xs text-slate-500">{t(locale, "filter.products")}</span>
          <select name="products" multiple size={4} defaultValue={[...picked("products")]} className={`${input} w-full max-w-md`} data-testid="filter-products">
            {skus.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {has("geo") ? <ScopeFilter token={token} scope={scope} locale={locale} params={params} /> : null}
      <div className="flex gap-2">
        <button type="submit" className="rounded bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white" data-testid="get-data">
          {t(locale, "report.get_data")}
        </button>
      </div>
    </form>
  );
}

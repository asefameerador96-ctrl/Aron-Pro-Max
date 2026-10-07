import { notFound } from "next/navigation";
import { n } from "@/components/dash/tiles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { listProductNodes } from "@/lib/dash/server";
import { LEVELS, isLevel, levelRows, type Level, type Node } from "@/lib/dash/products";
import { problemMessage, t, type MessageKey } from "@/lib/i18n";

const COLS: Record<Level, readonly ("category" | "segment" | "brand" | "variant")[]> = {
  category: ["category"],
  segment: ["category", "segment"],
  brand: ["category", "segment", "brand"],
  variant: ["category", "segment", "brand", "variant"],
};

// Products: Category, Segment, Brand, Variant (F-WEB-004 to F-WEB-007), read-only lists with ancestor columns, status and sort.
// The tree itself is maintained in the admin portal; this page never writes.
export default async function ProductLevelPage({ params }: { params: Promise<{ level: string }> }) {
  const [{ level }, locale, session] = await Promise.all([params, getLocale(), requireSession()]);
  if (!isLevel(level)) notFound();
  const pages = await Promise.all(LEVELS.map((l) => listProductNodes(session.at, l)));
  const failed = pages.find((p) => !p.ok);
  if (failed && !failed.ok)
    return (
      <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
        {problemMessage(locale, failed.problem.code)}
      </p>
    );
  const all = Object.fromEntries(LEVELS.map((l, i) => [l, (pages[i]!.ok ? (pages[i] as { ok: true; data: { items: Node[] } }).data.items : [])])) as Record<Level, Node[]>;
  const rows = levelRows(level, all);
  const cols = COLS[level];
  return (
    <div className="space-y-4" data-testid={`products-${level}`}>
      <h1 className="text-2xl font-bold">{t(locale, `menu.products.${level}` as MessageKey)}</h1>
      {rows.length === 0 ? (
        <p data-testid="table-empty" className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">
          {t(locale, "report.empty")}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="products-table">
            <thead className="bg-slate-50 text-left">
              <tr>
                {cols.map((c) => (
                  <th key={c} scope="col" className="px-3 py-2 font-semibold text-slate-700">
                    {t(locale, `filter.product_type.${c}` as MessageKey)}
                  </th>
                ))}
                <th scope="col" className="px-3 py-2 font-semibold text-slate-700">{t(locale, "filter.status")}</th>
                <th scope="col" className="px-3 py-2 text-right font-semibold text-slate-700">{t(locale, "products.sort")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <tr key={r.id} data-id={r.id}>
                  {cols.map((c) => (
                    <td key={c} className="px-3 py-2" data-col={c}>
                      {r[c] ?? "—"}
                    </td>
                  ))}
                  <td className="px-3 py-2">{t(locale, r.status === "active" ? "entity.status.active" : "entity.status.inactive")}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{n(locale, r.sort)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="px-3 py-2 text-xs text-slate-500" data-testid="row-count">
            {t(locale, "report.rows", { n: n(locale, rows.length) })}
          </p>
        </div>
      )}
    </div>
  );
}

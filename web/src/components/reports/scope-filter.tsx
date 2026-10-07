import type { ScopeSummary } from "@/contract/types";
import { t, type Locale, type MessageKey } from "@/lib/i18n";
import { GEO_LEVELS, bindGeo, geoFromParams, type GeoLevel } from "@/lib/reports/query";
import { geoOptions } from "@/lib/reports/server";

const LABEL: Record<GeoLevel, MessageKey> = { wing: "scope.wing", division: "scope.division", territory: "scope.territory", zone: "scope.zone", route: "scope.route" };

/** The standard five-level scope filter (F-WEB-041): wing, division, territory, zone, route.
 *  Levels fixed by the caller's token are shown as read-only text and never submitted; the others cascade from the level above.
 *  Options come from the server in reach, so another territory's nodes are not offered; the query builder drops them again. */
export async function ScopeFilter({ token, scope, locale, params }: { token: string; scope: ScopeSummary | null; locale: Locale; params: Record<string, string | string[] | undefined> }) {
  const bound = bindGeo(scope);
  const chosen = geoFromParams(params);
  const own = (scope?.nodes ?? []).map((n) => n.name ?? n.code ?? "").filter(Boolean).join(", ");
  const parts = await Promise.all(
    GEO_LEVELS.map(async (lvl, i) => {
      if (bound.locked.includes(lvl)) return { lvl, locked: true as const, options: [] as { id: number; label: string }[], failed: false };
      const parent = i === 0 ? undefined : chosen[GEO_LEVELS[i - 1]!]?.[0];
      if (i > 0 && parent === undefined && !bound.locked.includes(GEO_LEVELS[i - 1]!)) return { lvl, locked: false as const, options: [], failed: false };
      const parentId = i === 0 || parent === undefined ? scopeParent(scope, lvl) : parent;
      const r = await geoOptions(token, lvl, parentId);
      const ownIds = bound.ownIds[lvl];
      const options = r.ok ? (ownIds ? r.data.filter((o) => ownIds.includes(o.id)) : r.data) : [];
      return { lvl, locked: false as const, options, failed: !r.ok };
    }),
  );
  return (
    <fieldset className="flex flex-wrap gap-3" data-testid="scope-filter">
      <legend className="mb-1 text-sm font-semibold text-slate-700">{t(locale, "filter.scope")}</legend>
      {parts.map((p) =>
        p.locked ? (
          <div key={p.lvl} className="min-w-32" data-testid={`scope-locked-${p.lvl}`}>
            <span className="block text-xs text-slate-500">{t(locale, LABEL[p.lvl])}</span>
            <span className="block rounded bg-slate-100 px-2 py-1 text-sm text-slate-700">{own || t(locale, "filter.scope.fixed")}</span>
          </div>
        ) : (
          <label key={p.lvl} className="min-w-32 text-sm">
            <span className="block text-xs text-slate-500">{t(locale, LABEL[p.lvl])}</span>
            <select name={p.lvl} defaultValue={chosen[p.lvl]?.[0] ?? ""} className="w-full rounded border border-slate-300 bg-white px-2 py-1" data-testid={`scope-select-${p.lvl}`}>
              <option value="">{t(locale, "common.all")}</option>
              {p.options.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        ),
      )}
    </fieldset>
  );
}

/** Parent for the first selectable level: the shallowest own node when the caller's reach starts below national. */
function scopeParent(scope: ScopeSummary | null, lvl: GeoLevel): number | undefined {
  const nodes = scope?.nodes ?? [];
  const idx = GEO_LEVELS.indexOf(lvl);
  if (idx === 0) return undefined;
  const above = GEO_LEVELS[idx - 1]!;
  const n = nodes.length === 1 && nodes[0]!.type === above ? nodes[0]! : undefined;
  return n?.id;
}

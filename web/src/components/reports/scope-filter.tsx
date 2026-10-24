import type { ScopeSummary } from "@/contract/types";
import { t, type Locale, type MessageKey } from "@/lib/i18n";
import { GEO_LEVELS, bindGeo, geoFromParams, type GeoLevel } from "@/lib/reports/query";
import { geoOptions } from "@/lib/reports/server";

const LABEL: Record<GeoLevel, MessageKey> = { wing: "scope.wing", division: "scope.division", territory: "scope.territory", zone: "scope.zone", route: "scope.route" };

/** The standard five-level scope filter (F-WEB-041): wing, division, territory, zone, route.
 *  Levels fixed by the caller's token are shown as read-only text and never submitted; the others cascade from the level above.
 *  Options come from the server in reach, so another territory's nodes are not offered; the query builder drops them again. */
export async function ScopeFilter({ token, scope, locale, params, levels = GEO_LEVELS }: { token: string; scope: ScopeSummary | null; locale: Locale; params: Record<string, string | string[] | undefined>; /** Levels to offer, top down (a page whose endpoint takes no route node leaves `route` out). */ levels?: readonly GeoLevel[] }) {
  const bound = bindGeo(scope);
  const chosen = geoFromParams(params);
  const own = (scope?.nodes ?? []).map((n) => n.name ?? n.code ?? "").filter(Boolean).join(", ");
  const parts = await Promise.all(
    levels.map(async (lvl, i) => {
      if (bound.locked.includes(lvl)) return { lvl, locked: true as const, options: [] as { id: number; label: string }[], failed: false };
      const parent = i === 0 ? undefined : chosen[levels[i - 1]!]?.[0];
      if (i > 0 && parent === undefined && !bound.locked.includes(levels[i - 1]!)) return { lvl, locked: false as const, options: [], failed: false };
      const parentId = i === 0 || parent === undefined ? scopeParent(scope, lvl) : parent;
      const r = await geoOptions(token, lvl, parentId);
      const ownIds = bound.ownIds[lvl];
      const options = r.ok ? (ownIds ? r.data.filter((o) => ownIds.includes(o.id)) : r.data) : [];
      return { lvl, locked: false as const, options, failed: !r.ok };
    }),
  );
  return (
    <fieldset className="flex flex-wrap gap-3" data-testid="scope-filter">
      <legend className="mb-1 text-sm font-semibold text-secondary">{t(locale, "filter.scope")}</legend>
      {parts.map((p) =>
        p.locked ? (
          <div key={p.lvl} className="min-w-32" data-testid={`scope-locked-${p.lvl}`}>
            <span className="block text-caption text-secondary">{t(locale, LABEL[p.lvl])}</span>
            <span className="block rounded-chip bg-raised px-2 py-1 text-body text-primary">{own || t(locale, "filter.scope.fixed")}</span>
          </div>
        ) : (
          <label key={p.lvl} className="min-w-32 text-sm">
            <span className="block text-caption text-secondary">{t(locale, LABEL[p.lvl])}</span>
            <select name={p.lvl} defaultValue={chosen[p.lvl]?.[0] ?? ""} className="min-h-(--ctl-h) w-full rounded-chip border border-input bg-raised px-2 text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" data-testid={`scope-select-${p.lvl}`}>
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

import type { ScopeSummary } from "@/contract/types";
import { t, type Locale } from "@/lib/i18n";
import type { MessageKey } from "@/lib/i18n";

const LEVEL_KEY: Record<string, MessageKey> = {
  national: "scope.national",
  wing: "scope.wing",
  division: "scope.division",
  territory: "scope.territory",
  zone: "scope.zone",
  route: "scope.route",
};

/** Shows the reach the server derived for this user. It is display only: nothing here can change the scope. */
export function ScopeBadge({ scope, locale }: { scope: ScopeSummary | null; locale: Locale }) {
  const nodes = scope?.nodes ?? [];
  return (
    <section aria-label={t(locale, "scope.label")} title={t(locale, "scope.note")} className="flex flex-wrap items-center gap-2 text-sm">
      <span className="text-slate-500">{t(locale, "scope.label")}:</span>
      {nodes.length === 0 ? (
        <span className="rounded bg-amber-100 px-2 py-0.5 text-amber-900">{t(locale, "scope.none")}</span>
      ) : (
        nodes.map((n) => (
          <span key={`${n.type}-${n.id}`} data-testid="scope-node" className="rounded bg-brand-50 px-2 py-0.5 text-brand-900">
            {t(locale, LEVEL_KEY[n.type] ?? "scope.national")}
            {n.name ? ` · ${n.name}` : n.code ? ` · ${n.code}` : ""}
          </span>
        ))
      )}
    </section>
  );
}

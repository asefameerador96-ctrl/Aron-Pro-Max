import type { ScopeSummary } from "@/contract/types";
import { t, type Locale } from "@/lib/i18n";
import type { MessageKey } from "@/lib/i18n";
import { Icon } from "./kit/icons";

const LEVEL_KEY: Record<string, MessageKey> = {
  national: "scope.national",
  wing: "scope.wing",
  division: "scope.division",
  territory: "scope.territory",
  zone: "scope.zone",
  route: "scope.route",
};

/** Shows the reach the server derived for this user (web-glass s6: read-only, never an editor). Nothing here can change the scope. */
export function ScopeBadge({ scope, locale }: { scope: ScopeSummary | null; locale: Locale }) {
  const nodes = scope?.nodes ?? [];
  return (
    <section aria-label={t(locale, "scope.label")} title={t(locale, "scope.note")} className="flex min-w-0 flex-wrap items-center gap-1.5">
      <Icon name="location" size={16} className="text-secondary" />
      <span className="sr-only">{t(locale, "scope.label")}</span>
      {nodes.length === 0 ? (
        <span className="inline-flex min-h-7 items-center rounded-chip bg-warning-container px-2.5 text-label text-warning-on-container">{t(locale, "scope.none")}</span>
      ) : (
        nodes.map((n) => (
          <span key={`${n.type}-${n.id}`} data-testid="scope-node" className="inline-flex min-h-7 max-w-56 items-center truncate rounded-chip bg-accent-container px-2.5 text-label text-accent-on-container">
            {t(locale, LEVEL_KEY[n.type] ?? "scope.national")}
            {n.name ? ` · ${n.name}` : n.code ? ` · ${n.code}` : ""}
          </span>
        ))
      )}
    </section>
  );
}

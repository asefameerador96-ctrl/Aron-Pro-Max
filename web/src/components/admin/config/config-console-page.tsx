import { onApiFailure } from "@/components/admin/crud/pages";
import { one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { latestVersion, listKeys, reachOf, versionDetail } from "@/lib/admin/config-load";
import type { ConfigKey, ResolvedConfigValue } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import type { MessageKey } from "@/lib/i18n";
import { ConfigConsoleView } from "./config-console-view";

/** Shared server body of the console (all keys), P3 (kinds S and T) and P4 (kind O, switches). */
export async function ConfigConsolePage({ searchParams, basePath, titleKey, introKey, kinds, switches }: { searchParams: Promise<SearchParams>; basePath: string; titleKey: MessageKey; introKey?: MessageKey; kinds?: readonly ConfigKey["kind"][]; switches?: boolean }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const all = await listKeys(session.at);
  if (!all.ok) return onApiFailure(all.status, all.problem, locale);
  const scoped = all.data.items.filter((k) => !kinds || kinds.includes(k.kind));
  const areas = [...new Set(scoped.map((k) => k.area))].sort();
  const a = one(sp.area, 32);
  const area = a && areas.includes(a) ? a : undefined;
  const keys = scoped.filter((k) => !area || k.area === area);
  const version = await latestVersion(session.at);
  const v = version.ok ? (version.data?.version ?? null) : null;
  const [detail, reach] = v === null ? [null, null] : await Promise.all([versionDetail(session.at, v), reachOf(session.at, v)]);
  const values: Record<string, ResolvedConfigValue> = {};
  if (detail?.ok) for (const r of detail.data.values) values[r.key] = r;
  return <ConfigConsoleView locale={locale} basePath={basePath} titleKey={titleKey} introKey={introKey} keys={keys} areas={areas} area={area} values={values} reach={reach?.ok ? reach.data : null} canWrite={canOp("config.change", session.user.role)} switches={switches} />;
}

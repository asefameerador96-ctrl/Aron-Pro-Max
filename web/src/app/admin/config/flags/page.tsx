import { FlagsView, type FlagRow } from "@/components/admin/config/flags-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { listKeys } from "@/lib/admin/config-load";
import type { ConfigValuePage } from "@/lib/admin/types";
import { serverNowMs } from "@/lib/admin/time";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function FlagsPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const keys = await listKeys(session.at, "flag");
  if (!keys.ok) return onApiFailure(keys.status, keys.problem, locale);
  const flagKeys = keys.data.items.filter((k) => k.key.startsWith("cfg.flag.")).sort((a, b) => a.key.localeCompare(b.key));
  const flags: FlagRow[] = await Promise.all(
    flagKeys.map(async (key) => {
      const v = await apiGet<ConfigValuePage>("/v1/admin/config/values", session.at, { key: key.key, limit: 200 });
      return { key, values: v.ok ? v.data.items.filter((x) => !x.effective_to || Date.parse(x.effective_to) > serverNowMs()) : [] };
    }),
  );
  return <FlagsView locale={locale} flags={flags} canWrite={canOp("config.change", session.user.role)} />;
}

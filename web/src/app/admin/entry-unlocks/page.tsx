import { EntryUnlocksView } from "@/components/admin/config/entry-unlocks-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { EntryUnlockPage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function EntryUnlocksPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const r = await apiGet<EntryUnlockPage>("/v1/admin/entry-unlocks", session.at, { cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  return <EntryUnlocksView locale={locale} rows={r.data.items} nextHref={r.data.next_cursor ? `/admin/entry-unlocks${qs({ cursor: r.data.next_cursor })}` : null} canWrite={canOp("entry-unlock.create", session.user.role)} />;
}

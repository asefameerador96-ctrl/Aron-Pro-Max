import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { REASON_LISTS } from "@/lib/admin/code-lists";
import type { CodeList, CodeListKey } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate } from "@/lib/i18n";
import { CodeListsView } from "./code-lists-view";

/** `fixed` pins one list (QC fault types); otherwise the reason tables are picked with ?list=. */
export async function CodeListsPageContent({ searchParams, basePath, fixed }: { searchParams: Promise<SearchParams>; basePath: string; fixed?: CodeListKey }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const r = await apiGet<{ lists: CodeList[] }>("/v1/admin/code-lists", session.at);
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  const pick = one(sp.list, 40);
  const selected = fixed ?? ((REASON_LISTS as readonly string[]).includes(pick ?? "") ? (pick as CodeListKey) : null);
  return <CodeListsView locale={locale} lists={r.data.lists} selected={selected} choices={fixed ? null : REASON_LISTS} basePath={basePath} today={businessDate()} canWrite={canOp("code-list.put", session.user.role)} titleKey={fixed ? "qc.title" : "cl.title"} />;
}

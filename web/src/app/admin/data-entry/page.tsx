import { DataEntryView } from "@/components/admin/config/data-entry-view";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function DataEntryPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  return <DataEntryView locale={locale} canBackfill={canOp("paper-backfill.create", session.user.role)} />;
}

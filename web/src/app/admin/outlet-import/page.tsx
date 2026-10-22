import { OutletImport } from "@/components/admin/outlet-import";
import { Card, PageHeading } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Outlet import (docs/34 s2: channel mandatory). PROVISIONAL API: docs/requests/backend-admin-outlet-import.md.
export default async function OutletImportPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "oi.title")} intro={t(locale, "oi.intro")} />
      {canOp("outlet-import.commit", session.user.role) ? (
        <Card>
          <OutletImport />
        </Card>
      ) : (
        <p className="text-sm text-slate-600">{t(locale, "oi.read_only")}</p>
      )}
    </div>
  );
}
